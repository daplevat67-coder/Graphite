package art.graphite.vpn.vpn.xray

import android.app.ActivityManager
import android.content.*
import android.os.*
import art.graphite.vpn.vpn.*

/** Main-thread IPC owner. The main process never loads the native Xray binding. */
object XrayVpnClient {
 private val main=Handler(Looper.getMainLooper())
 private var app:Context?=null
 private var callback:((VpnSnapshot)->Unit)?=null
 private var pendingStart:Intent?=null
 private var remote:Messenger?=null
 private var receiver:Messenger?=null
 private var connection:ServiceConnection?=null
 private var bound=false
 private var visible=false
 private var epoch=0L
 private var bindingEpoch=0L
 private var lastPhase=VpnPhase.DOWN
 private fun onMain(action:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())action()else main.post{action()}}
 fun observe(context:Context,listener:(VpnSnapshot)->Unit)=onMain {
  app=context.applicationContext;callback=listener
  if(!bound){
   val running=runCatching{context.getSystemService(ActivityManager::class.java).getRunningServices(100).any{it.service.className==XrayVpnService::class.java.name}}.getOrDefault(false)
   if(running)bind(false)
  }else send(XrayMessages.QUERY)
 }
 fun start(context:Context,id:String,listener:(VpnSnapshot)->Unit)=onMain {
  app=context.applicationContext;callback=listener
  val intent = try { XraySettings.startIntent(context, id) } catch (_: Exception) {
   fail(VpnStage.SERVICE_START,VpnDiagnostic.SERVICE_START_REJECTED,id)
   return@onMain
  }
  val timeout = requireNotNull(intent.getBundleExtra(XraySettings.EXTRA))
      .getInt("serviceStartTimeoutSeconds") * 1000L
  val token=++epoch;lastPhase=VpnPhase.STARTING;pendingStart=intent
  main.postDelayed({
   if(token==epoch && lastPhase==VpnPhase.STARTING){
    if(remote!=null)send(XrayMessages.DISCONNECT)
    fail(VpnStage.SERVICE_START,VpnDiagnostic.SERVICE_READY_TIMEOUT,id)
   }
  },timeout)
  // Cold ART/application initialization finishes before the foreground deadline.
  if(remote!=null)launchPending()else if(!bound)bind(true)
  if(!bound)fail(VpnStage.SERVICE_START,VpnDiagnostic.SERVICE_START_REJECTED,id)
 }
 fun stop()=onMain {
  if(pendingStart!=null && remote==null){detach();callback?.invoke(VpnSnapshot(phase=VpnPhase.DOWN))}
  else if(remote!=null)send(XrayMessages.DISCONNECT)
 }
 fun refresh()=onMain{send(XrayMessages.QUERY)}
 fun setVisible(value:Boolean)=onMain{visible=value;send(XrayMessages.VISIBLE,Bundle().apply{putBoolean("visible",value)})}
 fun detach()=onMain {
  ++epoch;++bindingEpoch;pendingStart=null
  val old=connection;connection=null
  if(bound && old!=null)runCatching{app?.unbindService(old)}
  bound=false;remote=null;receiver=null
 }
 private fun fail(stage:VpnStage,diagnostic:VpnDiagnostic,id:String?=null){
  detach()
  callback?.invoke(VpnSnapshot(phase=VpnPhase.ERROR,profileId=id,error=VpnFailure.START,failureStage=stage,diagnostic=diagnostic))
 }
 private fun launchPending(){
  val intent=pendingStart ?: return;pendingStart=null
  val id=intent.getStringExtra("profile")
  try{val c=app ?: return;c.startForegroundService(intent)}
  catch(_:Exception){fail(VpnStage.SERVICE_START,VpnDiagnostic.SERVICE_START_REJECTED,id)}
 }
 private fun bind(create:Boolean){
  val c=app ?: return;val token=++bindingEpoch
  receiver=Messenger(Handler(Looper.getMainLooper()){m->
   if(token==bindingEpoch && m.what==XrayMessages.STATE && m.sendingUid==Process.myUid()){
    val value=XrayMessages.decode(m.data);lastPhase=value.phase;callback?.invoke(value)
   };true
  })
  val next=object:ServiceConnection {
   override fun onServiceConnected(name:ComponentName?,binder:IBinder?){
    if(token!=bindingEpoch)return
    if(name==null || binder==null){fail(VpnStage.SERVICE_START,VpnDiagnostic.SERVICE_START_REJECTED);return}
    remote=Messenger(binder);launchPending()
    send(XrayMessages.REGISTER);send(XrayMessages.VISIBLE,Bundle().apply{putBoolean("visible",visible)})
   }
   override fun onServiceDisconnected(name:ComponentName?){if(token==bindingEpoch)fail(VpnStage.SERVICE_DESTROY,VpnDiagnostic.UNABLE_TO_START_VPN)}
   override fun onBindingDied(name:ComponentName?)=onServiceDisconnected(name)
   override fun onNullBinding(name:ComponentName?)=onServiceDisconnected(name)
  }
  connection=next
  bound=try{c.bindService(Intent(c,XrayVpnService::class.java).setAction(XrayMessages.CONTROL),next,if(create)Context.BIND_AUTO_CREATE else 0)}catch(_:Exception){false}
 }
 private fun send(what:Int,payload:Bundle=Bundle()){
  try{remote?.send(Message.obtain(null,what).apply{replyTo=receiver;data=payload})}
  catch(_:RemoteException){fail(VpnStage.SERVICE_DESTROY,VpnDiagnostic.UNABLE_TO_START_VPN)}
 }
}
