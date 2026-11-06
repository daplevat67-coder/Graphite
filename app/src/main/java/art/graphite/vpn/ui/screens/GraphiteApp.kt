package art.graphite.vpn.ui.screens

import android.Manifest
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import art.graphite.vpn.data.settings.ConnectionSettingsStore
import art.graphite.vpn.data.settings.TunnelOptionsStore
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import art.graphite.vpn.data.profile.*
import art.graphite.vpn.vpn.*
import art.graphite.vpn.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.graphite.vpn.R
import art.graphite.vpn.data.demo.protocols
import art.graphite.vpn.domain.model.Protocol
import art.graphite.vpn.navigation.Destination
import art.graphite.vpn.ui.components.*
import art.graphite.vpn.ui.theme.GraphiteTheme

@Composable fun GraphiteApp(incomingImport:art.graphite.vpn.data.importing.IncomingImportRequest?=null,onIncomingConsumed:()->Unit={}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val store=remember {ProfileStore(context)}
    val prefs=remember {context.getSharedPreferences("appearance",0)}
    val prober=remember {ServerProber()}
    val probeLimit=remember {Semaphore(3)}
    val probeJobs=remember {mutableMapOf<String,kotlinx.coroutines.Job>()}
    val tunnelOptionsStore=remember {TunnelOptionsStore(context)}
    var tunnelOptions by remember {mutableStateOf(tunnelOptionsStore.load())}
    var megabits by rememberSaveable {mutableStateOf(prefs.getBoolean("megabits",true))}
    val connectionStore=remember {ConnectionSettingsStore(context)}
    var connectionSettings by remember {mutableStateOf(connectionStore.load())}
    val probeResults=remember {mutableStateMapOf<String,ProbeResult>()}
    val probing=remember {mutableStateMapOf<String,Boolean>()}
    val probeTimes=remember {mutableStateMapOf<String,Long>()}
    var light by rememberSaveable {mutableStateOf(prefs.getBoolean("light",false))}
    var animations by rememberSaveable {mutableStateOf(prefs.getBoolean("animations",true))}
    var advanced by rememberSaveable {mutableStateOf(prefs.getBoolean("advanced",false))}
    var swipe by rememberSaveable {mutableStateOf(prefs.getBoolean("swipe",true))}
    var tactile by rememberSaveable {mutableStateOf(prefs.getBoolean("haptics",true))}
    var pageMillis by rememberSaveable {mutableIntStateOf(prefs.getInt("pageMillisV2",3500).coerceIn(1000,5000))}
    var pageStyle by rememberSaveable {mutableStateOf(NotebookMotionDefaults.style(prefs.getString("pageStyle",null)))}
    var drawing by rememberSaveable {mutableStateOf(prefs.getBoolean("drawing",true))}
    var drawingMillis by rememberSaveable {mutableIntStateOf(prefs.getInt("drawingMillis",1200).coerceIn(300,3000))}
    var pingCount by rememberSaveable {mutableIntStateOf(prefs.getInt("pingCount",5).coerceIn(1,10))}
    var pingTimeout by rememberSaveable {mutableIntStateOf(prefs.getInt("pingTimeout",2).coerceIn(1,10))}
    var pingInterval by rememberSaveable {mutableIntStateOf(prefs.getInt("pingInterval",250).coerceIn(0,1000))}
    var pingDns by rememberSaveable {mutableIntStateOf(prefs.getInt("pingDns",2500).coerceIn(500,5000))}
    var pingFamily by rememberSaveable {mutableStateOf(runCatching {ProbeAddressFamily.valueOf(prefs.getString("pingFamily","AUTO") ?: "AUTO")}.getOrDefault(ProbeAddressFamily.AUTO))}
    var pingFreshness by rememberSaveable {mutableIntStateOf(prefs.getInt("pingFreshness",30).coerceIn(5,300))}
    var autoPing by rememberSaveable {mutableStateOf(prefs.getBoolean("autoPing",true))}
    var refreshing by remember {mutableStateOf(true)}
    var refreshEpoch by remember {mutableIntStateOf(0)}
    var systemScale by remember {mutableFloatStateOf(Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f))}
    DisposableEffect(context) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange:Boolean) {systemScale=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)}}
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose {context.contentResolver.unregisterContentObserver(observer)}
    }
    val animate=animations && systemScale>0f
    val haptics=LocalHapticFeedback.current
    val nativeTunnel by VpnRuntime.state.collectAsState()
    val ike = remember(context) { PlatformIke(context.applicationContext) }
    var ikeTunnel by remember { mutableStateOf<VpnSnapshot?>(null) }
    LaunchedEffect(ike) {
        if (Build.VERSION.SDK_INT >= 33) {
            (context as? ComponentActivity)?.lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    ikeTunnel = runCatching { ike.snapshot() }.getOrNull()
                    delay(500)
                }
            }
        }
    }
    val tunnel = if (nativeTunnel.phase in setOf(VpnPhase.STARTING, VpnPhase.UP, VpnPhase.STOPPING)) nativeTunnel
        else ikeTunnel ?: nativeTunnel
    DisposableEffect(context) {
        val lifecycle=(context as? ComponentActivity)?.lifecycle
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME)VpnRuntime.refreshSystemProtection()
            if(event==Lifecycle.Event.ON_START)VpnRuntime.setUiVisible(true)
            if(event==Lifecycle.Event.ON_STOP)VpnRuntime.setUiVisible(false)
        }
        lifecycle?.addObserver(observer)
        VpnRuntime.attach(context)
        VpnRuntime.refreshSystemProtection()
        VpnRuntime.setUiVisible(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)==true)
        onDispose {lifecycle?.removeObserver(observer);VpnRuntime.setUiVisible(false)}
    }
    val locked=tunnel.phase in setOf(VpnPhase.STARTING,VpnPhase.UP,VpnPhase.STOPPING)
    var profiles by remember {mutableStateOf<List<StoredProfile>>(emptyList())}
    var unreadable by remember {mutableStateOf<List<String>>(emptyList())}
    var selected by remember {mutableStateOf<StoredProfile?>(null)}
    var busy by remember {mutableStateOf(false)}
    var notice by remember {mutableStateOf<Int?>(null)}
    var tab by rememberSaveable {mutableStateOf(Destination.HOME)}
    var detail by rememberSaveable {mutableStateOf<String?>(null)}
    var sortMode by rememberSaveable {mutableIntStateOf(0)}
    var fileImportProtocol by rememberSaveable {mutableStateOf<String?>(null)}
    var editorProtocol by rememberSaveable {mutableStateOf<String?>(null)}
    fun selectNewProfile() = VpnRuntime.state.value.phase in setOf(VpnPhase.DOWN,VpnPhase.ERROR)
    var returnToProtocol by rememberSaveable {mutableStateOf<String?>(null)}
    var importSeed by remember {mutableStateOf<String?>(null)}
    var importSession by remember {mutableIntStateOf(0)}
    var profilesOpen by rememberSaveable {mutableStateOf(false)}
    var editorOpen by rememberSaveable {mutableStateOf(false)}
    var vlessEditor by rememberSaveable {mutableStateOf(false)}
    var editorId by rememberSaveable {mutableStateOf<String?>(null)}
    var editorSession by rememberSaveable {mutableIntStateOf(0)}
    var editorDirty by remember {mutableStateOf(false)}
    var discardAction by remember {mutableStateOf<(() -> Unit)?>(null)}
    var deleteId by remember {mutableStateOf<String?>(null)}
    var tearingId by remember {mutableStateOf<String?>(null)}
    var collapsingId by remember {mutableStateOf<String?>(null)}
    var exportId by rememberSaveable {mutableStateOf<String?>(null)}
    var exportConfirm by remember {mutableStateOf<String?>(null)}
    var pendingPermission by rememberSaveable {mutableStateOf<String?>(null)}
    var about by remember {mutableStateOf(false)}
    var diagnosticsTab by rememberSaveable {mutableIntStateOf(0)}
    var direction by remember {mutableIntStateOf(1)}
    suspend fun reload() {
        refreshing=true
        try {
            val result=withContext(Dispatchers.IO) {store.inspect() to runCatching {store.selected()}.getOrNull()}
            profiles=result.first.profiles;unreadable=result.first.unreadableIds;selected=result.second
            if(unreadable.isNotEmpty())notice=R.string.corrupt_notice
        }catch(_:Exception){notice=R.string.storage_error}finally{refreshing=false;refreshEpoch++}
    }
    LaunchedEffect(Unit){reload()}
    LaunchedEffect(tab){if(tab==Destination.PING)tab=Destination.SERVERS}
    fun pingServer(id:String) {
        if(probing[id]==true)return
        val profile=profiles.find{it.id==id} ?: return
        val host=profile.endpoints.firstOrNull()?.let(ServerProber::hostFromEndpoint)
        if(host==null){probeResults[id]=ProbeResult(status=ProbeStatus.ERROR,code=ProbeCode.INVALID_HOST);return}
        val options=ProbeOptions(pingCount,pingTimeout,pingInterval,pingDns,pingFamily)
        probing[id]=true
        probeJobs[id]=scope.launch {
            try {probeResults[id]=probeLimit.withPermit{prober.probe(host,options)};probeTimes[id]=System.currentTimeMillis()}
            catch(e:CancellationException){throw e}
            catch(_:Exception){probeResults[id]=ProbeResult(status=ProbeStatus.ERROR,code=ProbeCode.PROCESS_ERROR)}
            finally {probing.remove(id);probeJobs.remove(id)}
        }
    }
    LaunchedEffect(tab,profiles.map{it.id},autoPing,pingFreshness) {
        if(tab==Destination.SERVERS && autoPing)profiles.forEach{if(System.currentTimeMillis()-(probeTimes[it.id] ?: 0L)>pingFreshness*1000L)pingServer(it.id)}
    }
    LaunchedEffect(tearingId) {
        val id=tearingId ?: return@LaunchedEffect
        if(animate)delay(780)
        collapsingId=id
        if(animate)delay(360)
        reload();tearingId=null;collapsingId=null;busy=false
    }
    fun leaveEditor(action:()->Unit) {if(editorOpen && editorDirty)discardAction=action else action()}
    fun openEditor(id:String?, protocol:String?=null) {
        if ((id==null || !locked) && !busy) {
            editorProtocol=protocol
            editorId=id
            vlessEditor=protocol=="vless" || profiles.any { it.id==id && it.protocolId=="vless" }
            editorSession++
            editorDirty=false
            editorOpen=true
            direction=1
        }
    }
    fun navigate(destination:Destination) {leaveEditor {direction=if(destination.ordinal>=tab.ordinal)1 else -1;editorOpen=false;profilesOpen=false;detail=null;returnToProtocol=null;tab=destination;if(tactile)haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)}}
    fun back() {leaveEditor {direction=-1;when {editorOpen->editorOpen=false;profilesOpen->profilesOpen=false;detail!=null->{detail=returnToProtocol;returnToProtocol=null};else->tab=Destination.HOME}}}
    BackHandler(editorOpen || profilesOpen || detail!=null || tab!=Destination.HOME){back()}
    LaunchedEffect(incomingImport,locked,busy,editorOpen) {
        if(incomingImport!=null) {
            if(busy || editorOpen)notice=R.string.import_stop_first
            else {returnToProtocol=protocols.find{it.id==detail && it.id!="other"}?.id ?: returnToProtocol;importSeed=incomingImport.text;importSession++;profilesOpen=false;detail="import";direction=1;onIncomingConsumed()}
        }
    }
    fun startAuthorized(id:String) {
        pendingPermission=null
        scope.launch {
            val current=withContext(Dispatchers.IO){runCatching {store.selected()?.id}.getOrNull()}
            if(current==id && !busy && !editorOpen)VpnRuntime.connect(context,id,selected?.protocolId ?: "wg") else notice=R.string.error_profile
        }
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){pendingPermission?.let(::startAuthorized)}
    fun afterConsent(id:String) {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startAuthorized(id)
    }
    val consent=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result ->
        val id=pendingPermission
        if(result.resultCode==Activity.RESULT_OK && id!=null)afterConsent(id)
        else {pendingPermission=null;notice=R.string.permission_declined}
    }
    fun startIke(id: String) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            ike.start(id)
            ikeTunnel = ike.snapshot() ?: VpnSnapshot(statisticsEnabled=false, phase=VpnPhase.STARTING, profileId=id)
        } catch (_: Exception) { notice = R.string.ike_error }
    }
    val ikeConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingPermission
        pendingPermission = null
        if (result.resultCode == Activity.RESULT_OK && id != null) startIke(id)
        else {
            if (Build.VERSION.SDK_INT >= 33) runCatching { ike.discard() }
            notice = R.string.permission_declined
        }
    }
    fun connectIke(id: String) {
        if (Build.VERSION.SDK_INT < 33) {
            notice = R.string.ike_android_version
            return
        }
        pendingPermission = id
        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) { ike.provision(store.ikeProfile(id)) }
                if (request != null) ikeConsent.launch(request)
                else { pendingPermission = null; startIke(id) }
            } catch (_: Exception) { pendingPermission = null; notice = R.string.ike_error }
        }
    }
    fun stopTunnel() {
        if (Build.VERSION.SDK_INT >= 33 && ikeTunnel != null && tunnel === ikeTunnel) {
            try { ike.stop(); ikeTunnel = null } catch (_: Exception) { notice = R.string.ike_error }
        } else VpnRuntime.disconnect(context)
    }
    fun openSystemVpn() {
        try {context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))}catch(_:Exception){notice=R.string.system_vpn_unavailable}
    }
    fun openMtProxy(id: String) {
        scope.launch {
            val link = withContext(Dispatchers.IO) { runCatching { store.mtProxyLink(id) }.getOrNull() }
            if (link == null) {
                notice = R.string.error_profile
                return@launch
            }
            val uri = android.net.Uri.parse(link)
            for (app in listOf("org.telegram.messenger", "org.telegram.messenger.web")) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(app))
                    return@launch
                } catch (_: android.content.ActivityNotFoundException) {
                    // Try the other official Android distribution.
                } catch (_: SecurityException) {
                    // An unavailable activity must never send the secret to a browser.
                }
            }
            notice = R.string.mtproxy_telegram_missing
        }
    }
    fun openOpenVpn(id: String) {
        busy = true
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val directory = java.io.File(context.cacheDir, "openvpn-transfer").apply { mkdirs() }
                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3600000 }
                        ?.forEach { it.delete() }
                    val file = java.io.File.createTempFile("GRAPHITE-", ".ovpn", directory)
                    try {
                        file.writeText(store.openVpnDocument(id).document)
                        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".openvpn", file)
                    } catch (failure: Exception) {
                        file.delete()
                        throw failure
                    }
                }
                for (client in listOf("de.blinkt.openvpn", "net.openvpn.openvpn")) {
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-openvpn-profile")
                        .setPackage(client).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                        val app = context.applicationContext
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching { app.contentResolver.delete(uri, null, null) }
                        }, 600_000)
                        return@launch
                    }
                    catch (_: android.content.ActivityNotFoundException) { /* Try the other client. */ }
                    catch (_: SecurityException) { /* Never send the config to an arbitrary app. */ }
                }
                context.contentResolver.delete(uri, null, null)
                notice = R.string.openvpn_unavailable
            } catch (_: Exception) { notice = R.string.error_profile }
            finally { busy = false }
        }
    }
    fun connectOrStop() {
        if(busy || pendingPermission!=null || editorOpen)return
        if(tunnel.alwaysOn==true){openSystemVpn();return}
        if(tunnel.phase==VpnPhase.UP || tunnel.phase==VpnPhase.STARTING){stopTunnel();return}
        if(tunnel.phase==VpnPhase.STOPPING)return
        val id=selected?.id ?: run {navigate(Destination.SERVERS);return}
        if (selected?.isIke == true) { connectIke(id); return }
        pendingPermission=id
        try {val request=VpnService.prepare(context);if(request!=null)consent.launch(request) else afterConsent(id)}
        catch(_:Exception){pendingPermission=null;notice=R.string.error_permission}
    }
    fun connectServer(id:String) {
        if(busy || pendingPermission!=null || editorOpen)return
        if (profiles.any { it.id == id && it.isOpenVpn }) {
            if (!locked) openOpenVpn(id)
            return
        }
        if (profiles.any { it.id == id && it.isMtProxy }) { openMtProxy(id); return }
        if(tunnel.alwaysOn==true){openSystemVpn();return}
        if(locked){if(tunnel.profileId==id && tunnel.phase!=VpnPhase.STOPPING)stopTunnel();return}
        busy=true
        scope.launch {
            try {withContext(Dispatchers.IO){store.select(id)};reload();busy=false;connectOrStop()}
            catch(_:Exception){notice=R.string.error_profile}
            finally {busy=false}
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        val expectedProtocol=fileImportProtocol
        fileImportProtocol=null
        if(uri!=null && !busy) {
            busy=true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val name=runCatching {context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null}}.getOrNull()?.removeSuffix(".conf")?.removeSuffix(".json")?.removeSuffix(".ovpn") ?: "VPN"
                        val raw=context.contentResolver.openInputStream(uri)?.use {input ->
                            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                            try {while(true){val count=input.read(buffer);if(count<0)break;if(output.size()+count>65536)throw ProfileException("PROFILE_TOO_LARGE");output.write(buffer,0,count)};output.toByteArray()}
                            finally {buffer.fill(0)}
                        } ?: throw ProfileException("INVALID_CONFIG")
                        try {store.`import`(name,raw,selectNewProfile(),expectedProtocol)}finally{raw.fill(0)}
                    }
                    reload()
                    if(detail=="import") {
                        detail=expectedProtocol
                        if(expectedProtocol==null)tab=Destination.SERVERS
                        returnToProtocol=null;direction=-1
                    }
                    notice=R.string.connection_saved
                } catch(e:CancellationException){throw e}
                catch(e:ProfileException){
                    notice=if(e.safeCode=="WRONG_PROTOCOL") R.string.connection_wrong_protocol else R.string.import_error
                } catch(_:Exception){notice=R.string.import_error}finally{busy=false}
            }
        }
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri ->
        val id=exportId;exportId=null
        if(uri!=null && id!=null && !busy) {
            busy=true;scope.launch {
                try {withContext(Dispatchers.IO){val raw=store.export(id);try{(context.contentResolver.openOutputStream(uri,"wt") ?: throw ProfileException("STORAGE_UNAVAILABLE")).use{it.write(raw)}}finally{raw.fill(0)}};notice=R.string.export_done}
                catch(_:Exception){notice=R.string.export_error}finally{busy=false}
            }
        }
    }
    SideEffect {val style=if(light)SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT);(context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle=style,navigationBarStyle=style)}
    GraphiteTheme(light) {
        val colors=MaterialTheme.colorScheme
        Surface(color=colors.background) {
            Column(Modifier.fillMaxSize().paper(colors.onSurface,colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                val heading=when {editorOpen->R.string.advanced_mode;profilesOpen->R.string.profiles;detail=="tunneling"->R.string.tunneling_screen;detail=="import"->R.string.connection_create;detail?.startsWith("server:")==true->R.string.server_details;detail!=null->R.string.protocol;tab==Destination.HOME->R.string.vpn_title;else->tab.label}
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(editorOpen || profilesOpen || detail!=null)IconAction("back",R.string.back){back()}else SketchIcon(if(tab==Destination.HOME)"shield" else tab.icon,Modifier.size(30.dp))
                    Text(stringResource(heading),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    IconAction("info",R.string.about){about=true}
                }
                val route=when {editorOpen->"editor/${editorId ?: "new"}/$editorSession/${if(vlessEditor) "vless" else "existing"}";profilesOpen->"profiles";detail!=null->"detail/$detail";else->"tab/${if(tab==Destination.PING)Destination.SERVERS.name else tab.name}"}
                NotebookPager(route,animate,Modifier.weight(1f),direction,pageMillis,swipeEnabled=swipe && !editorOpen,style=pageStyle,onSwipe={delta->
                    if(profilesOpen || detail!=null){if(delta<0)back()}
                    else Destination.visible.getOrNull(Destination.visible.indexOf(tab)+delta)?.let(::navigate)
                }) {page ->
                    SketchReveal(refreshKey="$page/$refreshEpoch",enabled=drawing && animate && page in setOf("tab/MENU","tab/SERVERS","profiles","tab/PROTOCOLS"),modifier=Modifier.fillMaxSize(),durationMillis=drawingMillis) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        val pageProtocol=protocols.find{page=="detail/${it.id}"}
                        when {
                            page.startsWith("editor/")->{
                                val pieces=page.split('/')
                                val session=pieces.getOrNull(2)?.toIntOrNull()
                                val id=pieces[1].takeUnless { it=="new" }
                                val onSaved:()->Unit = {
                                    if (editorOpen && session==editorSession) {
                                        editorDirty=false;editorOpen=false;profilesOpen=false
                                        if(detail=="import") {
                                            detail=returnToProtocol
                                            if(detail==null)tab=Destination.SERVERS
                                            returnToProtocol=null
                                        } else if(detail==null)tab=Destination.SERVERS
                                        direction=-1;scope.launch { reload() }
                                    }
                                }
                                val onCancel:()->Unit = {
                                    if (editorOpen && session==editorSession) {
                                        editorDirty=false;editorOpen=false;direction=-1
                                    }
                                }
                                val onDirty:(Boolean)->Unit = { if (editorOpen && session==editorSession) editorDirty=it }
                                if (pieces.getOrNull(3)=="vless") VlessEditor(id,onSaved,onCancel,onDirty,::selectNewProfile)
                                else if(id==null) AdvancedEditor(null,onSaved,onCancel,onDirty,editorProtocol)
                                else ProfileEditor(id,profiles.any{it.id==id && it.protocolId !in setOf("awg","wg")},onSaved,onCancel,onDirty)
                            }
                            page=="profiles" || page=="tab/SERVERS" || (pageProtocol!=null && pageProtocol.id!="other")->{
                                val protocolId=pageProtocol?.id
                                val matchingProfiles=if(protocolId==null)profiles else profiles.filter{it.protocolId==protocolId}
                                val visibleProfiles=when(sortMode) {
                                    1->matchingProfiles.sortedBy{probeResults[it.id]?.rttMs ?: Double.POSITIVE_INFINITY}
                                    2->matchingProfiles.sortedBy{it.protocolName}
                                    else->matchingProfiles
                                }
                                if(pageProtocol!=null) {
                                    ProtocolDetails(pageProtocol)
                                    Text(stringResource(R.string.protocol_servers,pageProtocol.name),style=MaterialTheme.typography.titleLarge)
                                }
                                ServerListActions(
                                    canAdd=!busy && pendingPermission==null,
                                    canProbe=visibleProfiles.isNotEmpty() && visibleProfiles.none{it.id in probing},
                                    sortMode=sortMode,onSort={sortMode=it},
                                    onCreate={
                                        importSeed=null;importSession++;returnToProtocol=protocolId;detail="import";direction=1
                                    },
                                    onClipboard={
                                        val value=readProfileClipboard(context)
                                        if(value.isNullOrBlank())notice=R.string.clipboard_empty
                                        else {
                                            importSeed=value.take(262144);importSession++;returnToProtocol=protocolId;detail="import";direction=1
                                        }
                                    },
                                    onProbeAll={visibleProfiles.forEach{pingServer(it.id)}},
                                )
                                ProfilesScreen(visibleProfiles,if(protocolId==null)unreadable else emptyList(),selected?.id,busy,locked,advanced,tearingId,collapsingId,animate,
                                onEdit={openEditor(it)},
                                onSelect={id->if(!locked && !busy){busy=true;scope.launch{try{withContext(Dispatchers.IO){store.select(id)};reload()}catch(_:Exception){notice=R.string.storage_error}finally{busy=false}}}},onDelete={deleteId=it},onExport={exportConfirm=it},
                                probeResults=probeResults.toMap(),probingIds=probing.keys.toSet(),probeTimes=probeTimes.toMap(),
                                onProbe=::pingServer,onConnect=::connectServer,
                                onDetails={profilesOpen=false;returnToProtocol=protocolId;if(protocolId==null)tab=Destination.SERVERS;detail="server:$it";direction=1},activeProfileId=tunnel.profileId,tunnelPhase=tunnel.phase,alwaysOn=tunnel.alwaysOn==true,drawLoading=drawing && animate,protocol=pageProtocol)
                            }
                            page=="detail/tunneling"->TunnelPreferences(tunnelOptions,onSave={tunnelOptionsStore.save(it);tunnelOptions=it})
                            page=="detail/import"->key(importSession) {
                                val protocolId=returnToProtocol
                                ProfileImportScreen(importSeed,onSeedConsumed={importSeed=null},
                                    onFile={
                                        fileImportProtocol=protocolId
                                        importer.launch(arrayOf("*/*"))
                                    },
                                    onWireGuard=if(protocolId==null || protocolId in setOf("wg","awg")) {
                                        {openEditor(null,protocolId)}
                                    } else null,
                                    onVless=if(protocolId==null || protocolId=="vless") {
                                        {openEditor(null,"vless")}
                                    } else null,
                                    onImport={name,raw->
                                        if(busy)false else {
                                            busy=true
                                            try {
                                                withContext(Dispatchers.IO){store.`import`(name,raw,selectNewProfile(),protocolId)}
                                                reload();detail=protocolId;if(protocolId==null)tab=Destination.SERVERS
                                                returnToProtocol=null;direction=-1;notice=R.string.connection_saved;true
                                            } catch(e:CancellationException){throw e}
                                            catch(e:ProfileException){
                                                if(e.safeCode=="WRONG_PROTOCOL")notice=R.string.connection_wrong_protocol
                                                false
                                            }
                                            catch(_:Exception){false}finally{busy=false}
                                        }
                                    })
                            }
                            page.startsWith("detail/server:")->{
                                val id=page.substringAfter("detail/server:")
                                profiles.find{it.id==id}?.let {p->
                                    val state=if(tunnel.profileId==id || selected?.id==id && tunnel.phase==VpnPhase.ERROR)tunnel else VpnSnapshot()
                                    Text(p.name,style=MaterialTheme.typography.headlineLarge)
                                    ServerProbeCard(probeResults[id],probing[id]==true,probeTimes[id],p.endpoints.size>1,drawLoading=drawing && animate){pingServer(id)}
                                    OutlinedButton(onClick={connectServer(id)},enabled=!busy && pendingPermission==null && state.phase!=VpnPhase.STOPPING && (!locked || tunnel.profileId==id),modifier=Modifier.fillMaxWidth()){Text(stringResource(if(p.isOpenVpn)R.string.openvpn_open else if(p.isMtProxy)R.string.mtproxy_open else if(state.alwaysOn==true)R.string.system_vpn_manage else if(state.phase==VpnPhase.UP || state.phase==VpnPhase.STARTING)R.string.disconnect else R.string.connect))}
                                    if(p.protocolId=="vless" || advanced) {
                                        TextButton(onClick={openEditor(id)},enabled=!locked && !busy){
                                            Text(stringResource(if(p.protocolId=="vless")R.string.vless_edit else R.string.advanced_edit))
                                        }
                                    }
                                    ConnectionFormats(p,store)
                                    ConnectionFailureCard(state)
                                    DiagnosticsScreen(diagnosticsTab,state,p){diagnosticsTab=it}
                                }
                            }
                            pageProtocol!=null->ProtocolDetails(pageProtocol)
                            page=="tab/HOME"->Home(animate,tunnel,selected,pendingPermission!=null,busy,::connectOrStop,{navigate(Destination.SERVERS)},{navigate(Destination.PROTOCOLS)},megabits=megabits,pingText=selected?.id?.let{probeResults[it]}?.let{probeShortText(it)})
                            page=="tab/PROTOCOLS"->ProtocolList{detail=it.id;direction=1}
                            page=="tab/PROFILE"->{Mountains();SketchRow("user",stringResource(R.string.local_profile),stringResource(R.string.local_body));SketchRow("server",stringResource(R.string.profiles),selected?.name ?: stringResource(R.string.profile_empty)){navigate(Destination.SERVERS)};Preferences(light,animations,{light=it;prefs.edit{putBoolean("light",it)}},{animations=it;prefs.edit{putBoolean("animations",it)}})}
                            else->{
                                Preferences(light,animations,{light=it;prefs.edit{putBoolean("light",it)}},{animations=it;prefs.edit{putBoolean("animations",it)}})
                                ConnectionPreferences(connectionSettings,tunnel,{connectionSettings=it;connectionStore.save(it)},::openSystemVpn)
                                SketchCard {ToggleRow(R.string.speed_megabits,R.string.speed_megabits_hint,megabits){megabits=it;prefs.edit{putBoolean("megabits",it)}}}
                                SketchRow("route",stringResource(R.string.tunneling_screen),stringResource(R.string.tunneling_screen_hint)){detail="tunneling";direction=1}
                                    SketchCard {
                                        Text(stringResource(R.string.ping_settings),style=MaterialTheme.typography.titleMedium)
                                        ToggleRow(R.string.auto_ping,R.string.auto_ping_hint,autoPing){autoPing=it;prefs.edit{putBoolean("autoPing",it)}}
                                        IntSetting(R.string.ping_count_setting,pingCount,1,10,1){pingCount=it;prefs.edit{putInt("pingCount",it)}}
                                        IntSetting(R.string.ping_timeout_setting,pingTimeout,1,10,1){pingTimeout=it;prefs.edit{putInt("pingTimeout",it)}}
                                        IntSetting(R.string.ping_interval_setting,pingInterval,0,1000,50){pingInterval=it;prefs.edit{putInt("pingInterval",it)}}
                                        IntSetting(R.string.ping_dns_setting,pingDns,500,5000,500){pingDns=it;prefs.edit{putInt("pingDns",it)}}
                                        IntSetting(R.string.ping_freshness_setting,pingFreshness,5,300,5){pingFreshness=it;prefs.edit{putInt("pingFreshness",it)}}
                                        Text(stringResource(R.string.ping_family),style=MaterialTheme.typography.bodyMedium)
                                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                            ProbeAddressFamily.entries.forEach {family->FilterChip(selected=pingFamily==family,onClick={pingFamily=family;prefs.edit{putString("pingFamily",family.name)}},label={Text(stringResource(when(family){ProbeAddressFamily.AUTO->R.string.ping_family_auto;ProbeAddressFamily.IPV4->R.string.ping_family_ipv4;ProbeAddressFamily.IPV6->R.string.ping_family_ipv6}))})}
                                        }
                                        Text(stringResource(R.string.ping_freshness_hint),style=MaterialTheme.typography.labelMedium)
                                        Text(stringResource(R.string.ping_accuracy_hint),style=MaterialTheme.typography.labelMedium)
                                    }
                                SketchCard {ToggleRow(R.string.advanced_mode,R.string.advanced_hint,advanced){advanced=it;prefs.edit{putBoolean("advanced",it)}}}
                                if(advanced) {
                                    SketchCard {
                                        ToggleRow(R.string.swipe_pages,R.string.swipe_hint,swipe){swipe=it;prefs.edit{putBoolean("swipe",it)}}
                                        ToggleRow(R.string.haptic_feedback,R.string.haptic_hint,tactile){tactile=it;prefs.edit{putBoolean("haptics",it)}}
                                        Text(stringResource(R.string.page_style),style=MaterialTheme.typography.titleMedium)
                                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            PageTurnStyle.entries.forEach {style->
                                                FilterChip(selected=pageStyle==style,onClick={pageStyle=style;prefs.edit{putString("pageStyle",style.name)}},label={Text(stringResource(if(style==PageTurnStyle.CURL)R.string.page_style_curl else R.string.page_style_roll))})
                                            }
                                        }
                                        IntSetting(R.string.page_duration_precise,pageMillis,1000,5000,100,seconds=true){pageMillis=it;prefs.edit{putInt("pageMillisV2",it)}}
                                        ToggleRow(R.string.drawing_animation,R.string.drawing_animation_hint,drawing){drawing=it;prefs.edit{putBoolean("drawing",it)}}
                                        IntSetting(R.string.drawing_duration,drawingMillis,300,3000,100){drawingMillis=it;prefs.edit{putInt("drawingMillis",it)}}
                                        OutlinedButton(onClick={refreshEpoch++}){Text(stringResource(R.string.drawing_refresh))}
                                    }
                                    SketchRow("settings",stringResource(R.string.advanced_profiles),stringResource(R.string.advanced_hint)){navigate(Destination.SERVERS)}
                                }
                                SketchRow("pulse",stringResource(R.string.server_checks),stringResource(R.string.server_checks_hint)){navigate(Destination.SERVERS)}
                                SketchRow("layers",stringResource(R.string.custom_protocol),stringResource(R.string.graphite_experimental)){detail="graphite";direction=1}
                                SketchRow("info",stringResource(R.string.about),stringResource(R.string.version)){about=true}
                            }
                        }
                    }
                    }
                }
                if(refreshing || busy || tunnel.phase==VpnPhase.STARTING)LoadingSketch(stringResource(R.string.drawing_wait),drawing && animate,Modifier.padding(horizontal=20.dp))
                notice?.let {message->Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text(stringResource(message),Modifier.weight(1f).heightIn(max=100.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.labelMedium);TextButton(onClick={notice=null}){Text(stringResource(R.string.close))}}}
                BottomBar(tab){navigate(it)}
            }
            if(about)AlertDialog(onDismissRequest={about=false},title={Text(stringResource(R.string.app_name))},text={Text(stringResource(R.string.about_body))},confirmButton={TextButton(onClick={about=false}){Text(stringResource(R.string.close))}})
            discardAction?.let {action->AlertDialog(onDismissRequest={discardAction=null},title={Text(stringResource(R.string.discard_title))},text={Text(stringResource(R.string.discard_body))},confirmButton={TextButton(onClick={discardAction=null;editorDirty=false;action()}){Text(stringResource(R.string.discard))}},dismissButton={TextButton(onClick={discardAction=null}){Text(stringResource(R.string.cancel))}})}
            deleteId?.let {id->AlertDialog(onDismissRequest={deleteId=null},title={Text(stringResource(R.string.delete_profile))},text={Text(stringResource(R.string.delete_confirm))},confirmButton={TextButton(enabled=!locked && !busy,onClick={deleteId=null;busy=true;scope.launch{try{withContext(Dispatchers.IO){store.delete(id)};if(tactile)haptics.performHapticFeedback(HapticFeedbackType.LongPress);tearingId=id}catch(_:Exception){notice=R.string.delete_error;busy=false}}}){Text(stringResource(R.string.delete_profile))}},dismissButton={TextButton(onClick={deleteId=null}){Text(stringResource(R.string.cancel))}})}
            exportConfirm?.let {id->AlertDialog(onDismissRequest={exportConfirm=null},title={Text(stringResource(R.string.export_profile))},text={Text(stringResource(R.string.export_confirm))},confirmButton={TextButton(onClick={exportConfirm=null;exportId=id;exporter.launch("${profiles.find{it.id==id}?.name ?: "VPN"}.${profiles.find{it.id==id}?.fileExtension ?: "conf"}")}){Text(stringResource(R.string.export_profile))}},dismissButton={TextButton(onClick={exportConfirm=null}){Text(stringResource(R.string.cancel))}})}
        }
    }
}
@Composable private fun IconAction(icon:String,label:Int,onClick:()->Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        val text=stringResource(label)
        SketchIcon(icon,Modifier.size(22.dp).semantics{contentDescription=text})
    }
}
@Composable private fun BottomBar(selected:Destination,onSelect:(Destination)->Unit) {
    val colors=MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color=colors.outline.copy(alpha=.4f),thickness=.6.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=5.dp).selectableGroup()) {
            Destination.visible.forEach { tab ->
                val active=tab==selected
                val label=stringResource(tab.label)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                    .then(if(active)Modifier.sketchPanel(colors.onSurface,colors.surfaceVariant) else Modifier)
                    .selectable(active,role=Role.Tab,onClick={onSelect(tab)}).padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    SketchIcon(tab.icon,Modifier.size(23.dp),tint=if(active)colors.onSurface else colors.onSurfaceVariant)
                    Text(label,style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center,color=if(active)colors.onSurface else colors.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable private fun Preferences(light:Boolean,animations:Boolean,onLight:(Boolean)->Unit,onAnimations:(Boolean)->Unit) {
    SketchCard {
        Text(stringResource(R.string.appearance),style=MaterialTheme.typography.titleMedium)
        ToggleRow(R.string.light_theme,R.string.theme_hint,light,onLight)
        HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.25f))
        ToggleRow(R.string.animations,R.string.animation_hint,animations,onAnimations)
    }
}
@Composable internal fun ToggleRow(title:Int,subtitle:Int,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked,role=Role.Switch,onValueChange=onChange).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {Text(stringResource(title),style=MaterialTheme.typography.bodyLarge);Text(stringResource(subtitle),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(checked,onCheckedChange=null)
    }
}
@Composable internal fun IntSetting(label:Int,value:Int,minimum:Int,maximum:Int,step:Int,seconds:Boolean=false,onChange:(Int)->Unit) {
    Column {
        val formatted:Any=if(seconds)value/1000.0 else value
        Text(stringResource(label,formatted),style=MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={onChange((value-step).coerceAtLeast(minimum))},enabled=value>minimum){Text("−")}
            Slider(value=value.toFloat(),onValueChange={onChange((minimum+((it-minimum)/step).toInt()*step).coerceIn(minimum,maximum))},valueRange=minimum.toFloat()..maximum.toFloat(),steps=((maximum-minimum)/step-1).coerceAtLeast(0),modifier=Modifier.weight(1f))
            TextButton(onClick={onChange((value+step).coerceAtMost(maximum))},enabled=value<maximum){Text("+")}
        }
    }
}
