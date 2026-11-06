package art.graphite.xray;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONObject;
import libXray.DialerController;
import libXray.LibXray;
import hev.htproxy.TProxyService;

/** Official Xray + C Hev. Must run inside :xray, never the AWG Go-runtime process. */
public final class XrayTunnelEngine {
 private static final Object LOCK=new Object();
 private static volatile XrayTunnelEngine active;
 private static DialerController processProtector;
 private static boolean controllerRegistered;
 private final Context context; private final VpnService vpnService;
 private ParcelFileDescriptor duplicate; private File hevConfig; private boolean coreStarted;

 public XrayTunnelEngine(Context context,VpnService service){this.context=context.getApplicationContext();vpnService=service;}
 public static final class Snapshot {
  public final long rxBytes,txBytes;
  Snapshot(long rx,long tx){rxBytes=rx;txBytes=tx;}
  @Override public String toString(){return "XrayTrafficSnapshot";}
 }
 public void start(String json,ParcelFileDescriptor tun)throws IOException{start(json,tun,"1.1.1.1:53",1280);}
 public void start(String json,ParcelFileDescriptor tun,String dns)throws IOException{start(json,tun,dns,1280);}
 public void start(String json,ParcelFileDescriptor tun,String dns,int mtu)throws IOException {
  synchronized(LOCK){
   checkProcess();
   if(mtu<1280 || mtu>1500)throw new IOException("XRAY_INVALID_MTU");
   if(active!=null)throw new IOException("XRAY_ALREADY_RUNNING");
   if(json==null || json.length()>262144 || tun==null)throw new IOException("XRAY_INVALID_INPUT");
   active=this;
   String stage="CONFIG";
   try {
    JSONObject config=new JSONObject(json);JSONArray inbounds=config.getJSONArray("inbounds");JSONObject socks=null;
    for(int i=0;i<inbounds.length();i++){JSONObject item=inbounds.getJSONObject(i);if("socks".equals(item.optString("protocol"))){if(socks!=null)throw new IOException("XRAY_MULTIPLE_SOCKS_INBOUNDS");socks=item;}}
    if(socks==null || !"127.0.0.1".equals(socks.optString("listen")))throw new IOException("XRAY_INVALID_SOCKS_BIND");
    int port=socks.getInt("port");if(port<1024 || port>65535)throw new IOException("XRAY_INVALID_SOCKS_PORT");
    String user=token(),password=token();JSONObject settings=socks.optJSONObject("settings");if(settings==null)settings=new JSONObject();
    settings.put("auth","password").put("udp",true).put("accounts",new JSONArray().put(new JSONObject().put("user",user).put("pass",password)));socks.put("settings",settings);
    config.put("log",new JSONObject().put("access","none").put("error","none").put("loglevel","none"));
    // Xray appends controllers process-wide. Install once and route to the
    // current owner, without retaining destroyed service instances.
    if(processProtector==null)processProtector=fd->{
     XrayTunnelEngine owner=active;
     return owner!=null && fd>=0 && fd<=Integer.MAX_VALUE && owner.vpnService.protect((int)fd);
    };
    stage="PROTECTOR";
    if(!controllerRegistered){LibXray.registerDialerController(processProtector);controllerRegistered=true;}
    stage="DNS";LibXray.setDNS(processProtector,dns);
    stage="CORE";invoke("runXray",new JSONObject().put("xrayJson",config.toString()));coreStarted=true;
    stage="FD";duplicate=ParcelFileDescriptor.dup(tun.getFileDescriptor());
    stage="HEV_CONFIG";hevConfig=File.createTempFile("graphite-hev-",".yml",context.getCacheDir());
    if(!hevConfig.setReadable(false,false) || !hevConfig.setWritable(false,false) || !hevConfig.setReadable(true,true) || !hevConfig.setWritable(true,true))throw new IOException("HEV_PRIVATE_FILE_FAILED");
    String yaml="tunnel:\n  name: graphite-xray\n  mtu: "+mtu+"\n  ipv4: 10.111.0.2\n  ipv6: 'fd42:6767::2'\n  icmp: 'off'\nsocks5:\n  address: 127.0.0.1\n  port: "+port+"\n  udp: 'udp'\n  username: '"+user+"'\n  password: '"+password+"'\nmisc:\n  log-file: '/dev/null'\n  log-level: error\n";
    try(FileOutputStream out=new FileOutputStream(hevConfig)){out.write(yaml.getBytes(StandardCharsets.UTF_8));}
    stage="HEV_START";if(!TProxyService.TProxyStartService(hevConfig.getAbsolutePath(),duplicate.getFd()))throw new IOException("HEV_START_FAILED");
    stage="HEV_READY";SystemClock.sleep(100);if(!TProxyService.TProxyIsRunning())throw new IOException("HEV_INITIALIZATION_FAILED");
   }catch(Throwable failure){cleanup();if(failure instanceof LinkageError)throw new IOException("XRAY_NATIVE_LOAD_FAILED");throw new IOException("XRAY_START_"+stage+"_FAILED");}
  }
 }
 public boolean isRunning(){synchronized(LOCK){return active==this && coreStarted && TProxyService.TProxyIsRunning();}}
 public Snapshot getStats()throws IOException{synchronized(LOCK){if(!isRunning())throw new IOException("XRAY_NOT_RUNNING");long[] a=TProxyService.TProxyGetStats();if(a==null || a.length!=4 || a[1]<0 || a[3]<0)throw new IOException("XRAY_INVALID_STATS");return new Snapshot(a[3],a[1]);}}
 public void stop(){synchronized(LOCK){if(active==this)cleanup();}}
 private void cleanup(){
  try{TProxyService.TProxyStopService();}catch(Throwable ignored){}
  try{invoke("stopXray",new JSONObject());}catch(Throwable ignored){}
  try{LibXray.resetDNS();}catch(Throwable ignored){}
  coreStarted=false;
  if(duplicate!=null){try{duplicate.close();}catch(IOException ignored){}duplicate=null;}
  if(hevConfig!=null){hevConfig.delete();hevConfig=null;}if(active==this)active=null;
 }
 private static void invoke(String method,JSONObject payload)throws Exception{
  String request=new JSONObject().put("apiVersion",3).put("method",method).put("payload",payload).toString();
  JSONObject response=new JSONObject(LibXray.invoke(request));if(!response.optBoolean("success",false))throw new IOException("XRAY_API_FAILED");
 }
 private static String token(){byte[] b=new byte[16];new SecureRandom().nextBytes(b);StringBuilder t=new StringBuilder(32);for(byte n:b)t.append(String.format(java.util.Locale.ROOT,"%02x",n&255));java.util.Arrays.fill(b,(byte)0);return t.toString();}
 private void checkProcess()throws IOException{
  String name=null;if(Build.VERSION.SDK_INT>=28)name=Application.getProcessName();else{ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);if(manager!=null && manager.getRunningAppProcesses()!=null)for(ActivityManager.RunningAppProcessInfo p:manager.getRunningAppProcesses())if(p.pid==Process.myPid())name=p.processName;}
  if(!(context.getPackageName()+":xray").equals(name))throw new IOException("XRAY_REQUIRES_ISOLATED_PROCESS");
 }
}
