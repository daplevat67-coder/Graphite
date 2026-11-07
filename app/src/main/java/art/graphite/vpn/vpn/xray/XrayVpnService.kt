package art.graphite.vpn.vpn.xray
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.*
import art.graphite.vpn.MainActivity
import art.graphite.vpn.R
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.data.settings.*
import art.graphite.vpn.vpn.*
import art.graphite.vpn.vpn.graphite.GraphiteBridge
import art.graphite.vpn.domain.xray.XrayConfigGenerator
import java.net.InetSocketAddress
import art.graphite.vpn.domain.graphite.GraphiteTls
import art.graphite.xray.XrayTunnelEngine
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
/** Dedicated process isolates independent Go runtimes. All native calls serialized. */
class XrayVpnService:VpnService(){
 private val worker=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"graphite-xray").apply{isDaemon=true}}
 @Volatile private var wanted=false
 @Volatile private var snapshot=VpnSnapshot(phase=VpnPhase.STARTING)
 private var graphiteBridge:GraphiteBridge?=null
 private var engine:XrayTunnelEngine?=null;private var tun:ParcelFileDescriptor?=null;private var timer:ScheduledFuture<*>?=null
 private val listeners=mutableSetOf<Messenger>();private var options=ConnectionSettings();private var uiVisible=false;private var lastNotification=0L;private val rates=TransferRates()
 private val messenger=Messenger(Handler(Looper.getMainLooper()){m->
  if(m.sendingUid!=Process.myUid())return@Handler true
  when(m.what){
   XrayMessages.REGISTER->m.replyTo?.let{c->worker.execute{listeners+=c;publish()}}
   XrayMessages.QUERY->worker.execute{publish()}
   XrayMessages.VISIBLE->{val v=m.data.getBoolean("visible");worker.execute{uiVisible=v}}
   XrayMessages.DISCONNECT->if(!systemAlwaysOn()){wanted=false;worker.execute{finish(null)}}
  };true
 })
 override fun onBind(intent:Intent?)=if(intent?.action==XrayMessages.CONTROL)messenger.binder else super.onBind(intent)
 override fun onCreate(){super.onCreate();val m=getSystemService(NotificationManager::class.java);m.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.xray_vpn_channel),NotificationManager.IMPORTANCE_LOW));try{if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)else startForeground(ID,notification())}catch(_:Exception){snapshot=VpnSnapshot(phase=VpnPhase.ERROR,error=VpnFailure.START,failureStage=VpnStage.FOREGROUND,diagnostic=VpnDiagnostic.FOREGROUND_START_NOT_ALLOWED);stopSelf()}}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action==XrayMessages.STOP){if(!systemAlwaysOn()){wanted=false;worker.execute{finish(null)}};return START_NOT_STICKY}
  if(wanted && snapshot.phase in setOf(VpnPhase.STARTING,VpnPhase.UP))return START_NOT_STICKY
  wanted=true;val id=intent?.getStringExtra("profile");val settings=intent?.getBundleExtra(XraySettings.EXTRA)?.let{Bundle(it)};worker.execute{start(id,settings)};return START_NOT_STICKY
 }
 private fun start(id:String?,captured:Bundle?){var stage="PROFILE";try{
  val store=ProfileStore(this);val profile=if(id==null)store.selected() else store.profile(id);requireNotNull(profile)
  if(!profile.isXray){startForegroundService(Intent(this,GraphiteVpnService::class.java));wanted=false;finish(null);return}
  stage="SETTINGS";val settingsSnapshot=captured?.let{XraySettings.decode(it)} ?: XraySettings.Snapshot(ConnectionSettingsStore(this).load(),TunnelOptionsStore(this).load());options=settingsSnapshot.connection;val settings=settingsSnapshot.tunnel;TunnelConfigOverrides.validate(settings)
  snapshot=VpnSnapshot(phase=VpnPhase.STARTING,profileId=profile.id,statisticsEnabled=options.liveStatistics);publish();stage="CONSENT";require(prepare(this)==null)
  stage="PHYSICAL_NETWORK";val cm=getSystemService(ConnectivityManager::class.java)
  fun physical(n:android.net.Network)=cm.getNetworkCapabilities(n)?.let{!it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true
  val physicalNetwork=cm.activeNetwork?.takeIf{physical(it)} ?: cm.allNetworks.filter{physical(it)}.sortedByDescending{cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true}.firstOrNull()
  val physical=physicalNetwork?.let{cm.getLinkProperties(it)?.dnsServers?.firstOrNull()?.hostAddress} ?: "1.1.1.1"
  val bootstrap=if(physical.contains(':'))"[$physical]:53" else "$physical:53"
  val dns=if(settings.overrideDns)TunnelConfigOverrides.entries(settings.dnsServers)else listOf("1.1.1.1","1.0.0.1");val mtu=if(settings.overrideMtu)settings.mtu else 1280
  stage="CONFIG"
  val config=if(profile.protocolId=="graphite") {
   stage="GRAPHITE_TLS"
   require(Build.VERSION.SDK_INT>=29)
   val network=requireNotNull(physicalNetwork)
   val peer=store.graphiteProfile(profile.id)
   val addresses=network.getAllByName(peer.host).distinct().map { InetSocketAddress(it,peer.port) }
   val token=peer.token()
   val bridge=try {
    GraphiteBridge(GraphiteTls.socketFactory(peer.certificatePin),
        addresses,peer.host,token) { socket ->
     protect(socket) && runCatching { network.bindSocket(socket); true }.getOrDefault(false)
    }
   } finally { token.fill(0) }
   graphiteBridge=bridge
   val localPort=bridge.start(options.serviceStartTimeoutSeconds * 1000L)
   XrayConfigGenerator.graphiteBridge(localPort,bridge.username(),bridge.password(),dns)
  } else store.xrayConfig(profile.id,dnsServers=dns)
  stage="BUILDER";val b=Builder().setSession("GRAPHITE · ${profile.protocolName}").setMtu(mtu).addAddress("10.111.0.2",32).addAddress("fd42:6767::2",128).addRoute("0.0.0.0",0).addRoute("::",0)
  dns.forEach{b.addDnsServer(it)};if(settings.splitApplications)TunnelConfigOverrides.entries(settings.applications).forEach{if(settings.appRoutingMode==AppRoutingMode.ONLY)b.addAllowedApplication(it)else b.addDisallowedApplication(it)}
  if(Build.VERSION.SDK_INT>=29)b.setMetered(false)
  if(!wanted){finish(null);return};stage="ESTABLISH";tun=requireNotNull(b.establish());stage="ENGINE";engine=XrayTunnelEngine(this,this).also{it.start(config,tun!!,bootstrap,mtu)}
  if(!wanted){finish(null);return};snapshot=snapshot.copy(phase=VpnPhase.UP,effectiveMtu=mtu,effectiveDns=dns);rates.reset();publish();notifyNow();timer=worker.scheduleWithFixedDelay({sample()},0,options.statsIntervalMillis.toLong(),TimeUnit.MILLISECONDS)
 }catch(_:Exception){android.util.Log.e("GraphiteXray","XRAY_START_FAILED stage=$stage");finish(VpnFailure.START)}catch(_:LinkageError){android.util.Log.e("GraphiteXray","XRAY_NATIVE_LOAD_FAILED stage=$stage");finish(VpnFailure.START)}}
 private fun sample() {
  if (!wanted) return
  val runningEngine = engine
  val running = try {
   runningEngine?.isRunning == true
  } catch (_: Exception) {
   false
  } catch (_: LinkageError) {
   false
  }
  if (!running || runningEngine == null) {
   finish(VpnFailure.START)
   return
  }

  val stats = if (NotificationTraffic.needsSampling(options, uiVisible)) {
   try {
    runningEngine.stats
   } catch (_: Exception) {
    null
   } catch (_: LinkageError) {
    null
   }
  } else null
  if (stats == null) {
   rates.reset()
   snapshot = snapshot.copy(rxBytesPerSecond=null, txBytesPerSecond=null,
       sampledElapsedNanos=null, statisticsPaused=true)
  } else {
   val at = System.nanoTime()
   val rate = rates.sample(stats.rxBytes, stats.txBytes, at)
   snapshot = snapshot.copy(rxBytes=stats.rxBytes, txBytes=stats.txBytes,
       rxBytesPerSecond=rate.first, txBytesPerSecond=rate.second,
       sampledElapsedNanos=at, statisticsPaused=false)
  }
  publish()
  if (options.notificationSpeed && SystemClock.elapsedRealtime() - lastNotification >= 2000) notifyNow()
 }
 private fun publish(){snapshot=snapshot.copy(alwaysOn=if(Build.VERSION.SDK_INT>=29)runCatching{isAlwaysOn}.getOrNull()else null,lockdown=if(Build.VERSION.SDK_INT>=29)runCatching{isLockdownEnabled}.getOrNull()else null);val encoded=XrayMessages.encode(snapshot);listeners.toList().forEach{c->try{c.send(Message.obtain(null,XrayMessages.STATE).apply{data=encoded})}catch(_:RemoteException){listeners-=c}};if(listeners.isEmpty())uiVisible=false}
 private fun cleanup(){timer?.cancel(false);timer=null;try{engine?.stop()}finally{engine=null;runCatching{graphiteBridge?.close()};graphiteBridge=null;runCatching{tun?.close()};tun=null}}
 private fun finish(failure:VpnFailure?){wanted=false;var error=failure;try{cleanup()}catch(_:Exception){error=VpnFailure.STOP};snapshot=VpnSnapshot(phase=if(error==null)VpnPhase.DOWN else VpnPhase.ERROR,profileId=if(error==null)null else snapshot.profileId,error=error,failureStage=if(error==null)null else VpnStage.TUNNEL_START,diagnostic=if(error==null)null else VpnDiagnostic.UNABLE_TO_START_VPN);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();publish()}
 override fun onRevoke(){wanted=false;worker.execute{finish(VpnFailure.PERMISSION)};super.onRevoke()}
 override fun onDestroy(){
  wanted=false
  worker.execute{
   runCatching{cleanup()};worker.shutdown()
   // Native stop has completed before DOWN is published. Keep the isolated
   // process reusable; killing it here could kill a newly connected service.
   // Every user connection receives fresh settings from the main process.
  }
  super.onDestroy()
 }
 private fun systemAlwaysOn()=Build.VERSION.SDK_INT>=29 && runCatching{isAlwaysOn}.getOrDefault(false)
 private fun notifyNow(){lastNotification=SystemClock.elapsedRealtime();getSystemService(NotificationManager::class.java).notify(ID,notification())}
 private fun notification():Notification{
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val text=when(snapshot.phase){VpnPhase.UP->if(options.notificationSpeed)NotificationTraffic.speedLine(snapshot) ?: getString(R.string.notification_speed_pending)else getString(R.string.xray_tunnel_ready);VpnPhase.ERROR->getString(R.string.phase_error);else->getString(R.string.phase_starting)}
  val b=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_vpn).setContentTitle(getString(R.string.app_name)).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
  if(!systemAlwaysOn())b.addAction(Notification.Action.Builder(null,getString(R.string.disconnect),PendingIntent.getService(this,1,Intent(this,XrayVpnService::class.java).setAction(XrayMessages.STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build());return b.build()
 }
 companion object{private const val CHANNEL="xray-vpn";private const val ID=31}
}
