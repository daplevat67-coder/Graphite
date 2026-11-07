package art.graphite.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.graphite.vpn.ui.format.TrafficFormat
import art.graphite.vpn.R
import art.graphite.vpn.data.demo.protocols
import art.graphite.vpn.data.profile.StoredProfile
import art.graphite.vpn.domain.model.Protocol
import art.graphite.vpn.ui.components.*
import art.graphite.vpn.vpn.*
import art.graphite.vpn.diagnostics.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun Home(animate:Boolean,tunnel:VpnSnapshot,profile:StoredProfile?,awaiting:Boolean,busy:Boolean,onConnect:()->Unit,onProfiles:()->Unit,onProtocols:()->Unit,pingText:String?=null,megabits:Boolean=true) {
    Mountains()
    val status=when {awaiting->R.string.permission_wait;tunnel.phase==VpnPhase.STARTING->R.string.phase_starting;tunnel.phase==VpnPhase.UP->R.string.phase_up;tunnel.phase==VpnPhase.STOPPING->R.string.phase_stopping;tunnel.phase==VpnPhase.ERROR->R.string.phase_error;else->R.string.disconnected}
    val action=when {tunnel.alwaysOn==true->R.string.system_vpn_manage;awaiting->R.string.permission_wait;tunnel.phase==VpnPhase.UP || tunnel.phase==VpnPhase.STARTING->R.string.disconnect;tunnel.phase==VpnPhase.STOPPING->R.string.phase_stopping;profile==null->R.string.add_profile;tunnel.phase==VpnPhase.ERROR->R.string.retry;else->R.string.connect}
    val hint=when {tunnel.phase==VpnPhase.UP && profile?.isXray==true->R.string.xray_ready_hint;tunnel.phase==VpnPhase.UP && !tunnel.statisticsEnabled->R.string.stats_no_handshake;tunnel.phase==VpnPhase.UP && tunnel.lastHandshakeEpochMillis!=null->R.string.has_handshake;tunnel.phase==VpnPhase.UP->R.string.awaiting_handshake;profile==null->R.string.need_profile;else->R.string.ready_profile}
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(status),Modifier.clip(CircleShape).border(.7.dp,MaterialTheme.colorScheme.outline,CircleShape).padding(horizontal=18.dp,vertical=5.dp),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
        val interactions=remember{MutableInteractionSource()};val pressed by interactions.collectIsPressedAsState()
        val scale by animateFloatAsState(if(pressed).96f else 1f,tween(if(animate)220 else 0),label="power response")
        Box(Modifier.padding(vertical=2.dp).size(220.dp).graphicsLayer{scaleX=scale;scaleY=scale}.clip(CircleShape).clickable(enabled=!busy && !awaiting && tunnel.phase!=VpnPhase.STOPPING,interactionSource=interactions,indication=null,role=Role.Button,onClick=onConnect),contentAlignment=Alignment.Center) {
            PowerDisc(Modifier.fillMaxSize())
            Column(Modifier.padding(horizontal=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){SketchIcon("power",Modifier.size(48.dp));Text(stringResource(action),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)}
        }
        Text(stringResource(R.string.network_alpha),style=MaterialTheme.typography.labelSmall,letterSpacing=1.3.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ConnectionFailureCard(tunnel)
    SketchRow("server",stringResource(R.string.server),profile?.name ?: stringResource(R.string.profile_unselected),onProfiles)
    SketchRow("link",stringResource(R.string.protocol),profile?.protocolName ?: stringResource(R.string.profile_unselected),onProtocols)
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
        Metric(R.string.ping,"bolt",pingText ?: "—",Modifier.weight(1f));Metric(R.string.download,"download",TrafficFormat.rate(tunnel.rxBytesPerSecond,megabits) ?: "—",Modifier.weight(1f));Metric(R.string.upload,"upload",TrafficFormat.rate(tunnel.txBytesPerSecond,megabits) ?: "—",Modifier.weight(1f))
    }
    Text(stringResource(R.string.scope_hint),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun Metric(label:Int,icon:String,value:String,modifier:Modifier) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){SketchIcon(icon,Modifier.size(21.dp));Text(stringResource(label),style=MaterialTheme.typography.labelMedium,textAlign=TextAlign.Center);Text(value,style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center)}
}
private fun rate(value:Double?)=value?.let{String.format(Locale.ROOT,"%.1f КиБ/с",it/1024)} ?: "—"
private fun bytes(value:Long?)=value?.let{String.format(Locale.ROOT,"%.2f МиБ",it/1048576.0)} ?: "—"
private fun time(value:Long?)=value?.let{DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(it))} ?: "—"

private val Protocol.statusRes: Int get() = when(id) {
    "other" -> R.string.planned
    "graphite" -> R.string.graphite_experimental
    else -> R.string.available
}

@Composable internal fun ServerListActions(
    canAdd:Boolean, canProbe:Boolean, sortMode:Int, onSort:(Int)->Unit,
    onCreate:()->Unit, onClipboard:()->Unit, onProbeAll:()->Unit,
) {
    var sortOpen by remember {mutableStateOf(false)}
    OutlinedButton(onClick=onCreate,enabled=canAdd,modifier=Modifier.fillMaxWidth()){
        Text(stringResource(R.string.connection_create))
    }
    OutlinedButton(onClick=onClipboard,enabled=canAdd,modifier=Modifier.fillMaxWidth()){
        Text(stringResource(R.string.connection_clipboard))
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={sortOpen=true},modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.connection_sort))
        }
        DropdownMenu(expanded=sortOpen,onDismissRequest={sortOpen=false}){
            listOf(R.string.connection_sort_name,R.string.connection_sort_ping,R.string.connection_sort_protocol)
                .forEachIndexed{index,label->
                    DropdownMenuItem(text={Text(stringResource(label))},onClick={onSort(index);sortOpen=false},
                        trailingIcon={if(sortMode==index)Text("✓")})
                }
        }
    }
    OutlinedButton(onClick=onProbeAll,enabled=canProbe,modifier=Modifier.fillMaxWidth()){
        Text(stringResource(R.string.probe_all))
    }
}

@Composable internal fun ProtocolList(onSelect:(Protocol)->Unit) {
    Text(stringResource(R.string.protocol_intro),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    protocols.forEach {p->SketchRow(p.icon,if(p.id=="other")stringResource(R.string.others) else p.name,stringResource(p.statusRes)){onSelect(p)}}
}
@Composable internal fun ProtocolDetails(protocol:Protocol) {
    Mountains();Text(if(protocol.id=="other")stringResource(R.string.others) else protocol.name,style=MaterialTheme.typography.headlineLarge)
    Text(stringResource(protocol.statusRes),style=MaterialTheme.typography.labelMedium)
    SketchCard {Text(stringResource(protocol.descriptionRes))}
    if(protocol.id=="other")SketchCard {Text(stringResource(R.string.future_import_body))}
}
@Composable internal fun ProfilesScreen(profiles:List<StoredProfile>,unreadable:List<String>,selectedId:String?,busy:Boolean,locked:Boolean,advanced:Boolean,tearingId:String?,collapsingId:String?,animate:Boolean,onEdit:(String)->Unit,onSelect:(String)->Unit,onDelete:(String)->Unit,onExport:(String)->Unit,
    probeResults:Map<String,ProbeResult> = emptyMap(),probingIds:Set<String> = emptySet(),probeTimes:Map<String,Long> = emptyMap(),
    onProbe:(String)->Unit={},onConnect:(String)->Unit={},onDetails:(String)->Unit={},
    activeProfileId:String?=null,tunnelPhase:VpnPhase=VpnPhase.DOWN,alwaysOn:Boolean=false,drawLoading:Boolean=true,protocol:Protocol?=null) {
    if(busy)Text(stringResource(R.string.profile_operation),style=MaterialTheme.typography.labelMedium)
    if(profiles.isEmpty() && unreadable.isEmpty())SketchCard {Text(stringResource(R.string.profile_empty),style=MaterialTheme.typography.titleMedium);Text(if(protocol==null)stringResource(R.string.profile_empty_body) else stringResource(R.string.protocol_servers_empty,protocol.name))}
    profiles.forEach {p->key(p.id) {
        AnimatedVisibility(visible=collapsingId!=p.id,enter=EnterTransition.None,exit=shrinkVertically(tween(if(animate)340 else 0))) {
            TearableSheet(tearingId==p.id,animate,onGone={}) {
                SketchCard {
                    Text(p.name,style=MaterialTheme.typography.titleMedium)
                    p.subscriptionName?.let { Text(stringResource(R.string.subscription_origin,it),style=MaterialTheme.typography.labelMedium) }
                    Text(if(selectedId==p.id)stringResource(R.string.profile_protocol_selected,p.protocolName) else p.protocolName,style=MaterialTheme.typography.labelMedium)
                    Text(p.endpoints.joinToString(" · "),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    ServerProbeCard(probeResults[p.id],p.id in probingIds,probeTimes[p.id],p.endpoints.size>1,drawLoading=drawLoading){onProbe(p.id)}
                    val active=activeProfileId==p.id && tunnelPhase in setOf(VpnPhase.STARTING,VpnPhase.UP,VpnPhase.STOPPING)
                    OutlinedButton(onClick={onConnect(p.id)},enabled=!busy && tunnelPhase!=VpnPhase.STOPPING && (!locked || active || p.isMtProxy),modifier=Modifier.fillMaxWidth()){Text(stringResource(if(p.isOpenVpn)R.string.openvpn_open else if(p.isMtProxy)R.string.mtproxy_open else if(active && alwaysOn)R.string.system_vpn_manage else if(active)R.string.disconnect else R.string.connect))}
                    TextButton(onClick={onDetails(p.id)}){Text(stringResource(R.string.server_details))}
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        if (!p.isMtProxy && !p.isOpenVpn) OutlinedButton(onClick={onSelect(p.id)},enabled=!locked && !busy && selectedId!=p.id){Text(stringResource(R.string.select_profile))}
                        TextButton(onClick={onDelete(p.id)},enabled=!locked && !busy){Text(stringResource(R.string.delete_profile))}
                    }
                    if(advanced || p.protocolId=="vless")OutlinedButton(onClick={onEdit(p.id)},enabled=!locked && !busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(if(p.protocolId=="vless") R.string.vless_edit else R.string.advanced_edit))}
                    TextButton(onClick={onExport(p.id)},enabled=!busy){Text(stringResource(R.string.export_profile))}
                }
            }
        }
    }}
    unreadable.forEach {id->key(id) {
        AnimatedVisibility(visible=collapsingId!=id,exit=shrinkVertically(tween(if(animate)340 else 0))){TearableSheet(tearingId==id,animate,onGone={}){SketchCard {Text(stringResource(R.string.corrupt_profile));Text(id.take(8),style=MaterialTheme.typography.labelSmall);TextButton(onClick={onDelete(id)},enabled=!locked && !busy){Text(stringResource(R.string.delete_profile))}}}}
    }}
}
@Composable internal fun DiagnosticsScreen(tab:Int,tunnel:VpnSnapshot,profile:StoredProfile?,onTab:(Int)->Unit) {
    val colors=MaterialTheme.colorScheme
    var samples by remember {mutableStateOf<List<Double>>(emptyList())}
    LaunchedEffect(tunnel.profileId,tunnel.phase,tunnel.sampledElapsedNanos){if(tunnel.phase!=VpnPhase.UP)samples=emptyList() else tunnel.rxBytesPerSecond?.let{samples=(samples+it/1024).takeLast(60)}}
    Row(Modifier.fillMaxWidth().clip(CircleShape).border(.6.dp,colors.outline,CircleShape).selectableGroup()) {
        listOf(R.string.traffic,R.string.check,R.string.logs).forEachIndexed {i,label->Box(Modifier.weight(1f).clip(CircleShape).then(if(tab==i)Modifier.background(colors.surfaceVariant) else Modifier).selectable(tab==i,role=Role.Tab,onClick={onTab(i)}).padding(vertical=14.dp),contentAlignment=Alignment.Center){Text(stringResource(label),style=MaterialTheme.typography.bodyMedium)}}
    }
    when(tab){
        0->{SketchCard {Text(stringResource(R.string.traffic_graph),style=MaterialTheme.typography.titleMedium);if(samples.isEmpty())Text(stringResource(R.string.traffic_empty));TrafficGraph(samples);Text(stringResource(R.string.traffic_session),style=MaterialTheme.typography.labelMedium)};SketchRow("download",stringResource(R.string.rx_total),bytes(tunnel.rxBytes));SketchRow("upload",stringResource(R.string.tx_total),bytes(tunnel.txBytes))}
        1->{SketchRow("shield",stringResource(if(profile?.isXray==true)R.string.xray_engine_status else R.string.last_handshake),if(profile?.isXray==true)stringResource(if(tunnel.phase==VpnPhase.UP)R.string.xray_tunnel_ready else R.string.disconnected) else if(tunnel.lastHandshakeEpochMillis==null)stringResource(R.string.no_handshake) else time(tunnel.lastHandshakeEpochMillis));Text(stringResource(if(profile?.isXray==true)R.string.xray_ready_hint else R.string.handshake_not_ping),style=MaterialTheme.typography.labelMedium);SketchRow("route",stringResource(R.string.configured_routes),(if(profile?.isXray==true && tunnel.phase==VpnPhase.UP)listOf("0.0.0.0/0","::/0") else profile?.routes)?.joinToString(" · ") ?: "—");SketchRow("dns",stringResource(R.string.configured_dns),(tunnel.effectiveDns ?: profile?.dnsServers)?.takeIf{it.isNotEmpty()}?.joinToString(" · ") ?: stringResource(R.string.dns_not_set));tunnel.effectiveMtu?.let{Text(stringResource(R.string.effective_mtu,it),style=MaterialTheme.typography.labelMedium)};Text(stringResource(R.string.route_disclaimer),style=MaterialTheme.typography.labelMedium)}
        else->{Text(stringResource(R.string.logs_safe),style=MaterialTheme.typography.labelMedium);if(tunnel.events.isEmpty())SketchCard {Text(stringResource(R.string.logs_empty_v02))};tunnel.events.asReversed().forEach {event->SketchCard {Text(stringResource(when(event.kind){VpnEventKind.STARTING->R.string.event_start;VpnEventKind.INTERFACE_UP->R.string.event_up;VpnEventKind.HANDSHAKE->R.string.event_handshake;VpnEventKind.STOPPED->R.string.event_stop;VpnEventKind.REVOKED->R.string.event_revoke;VpnEventKind.ERROR->R.string.event_error}));Text(time(event.atEpochMillis),style=MaterialTheme.typography.labelSmall)}}}
    }
}

@Composable internal fun probeShortText(result:ProbeResult):String = when {
    result.status!=ProbeStatus.REPLY -> "—"
    result.rttUpperBoundMs!=null -> stringResource(R.string.probe_less_ms,result.rttUpperBoundMs)
    result.medianRttMs!=null -> stringResource(R.string.probe_ms,result.medianRttMs)
    result.rttMs!=null -> stringResource(R.string.probe_ms,result.rttMs)
    else -> stringResource(R.string.probe_reply)
}
@Composable internal fun ServerProbeCard(result:ProbeResult?,running:Boolean,at:Long?,multiplePeers:Boolean,drawLoading:Boolean=true,onProbe:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ping),style=MaterialTheme.typography.labelMedium)
                Text(if(running)stringResource(R.string.probe_running) else result?.let{probeShortText(it)} ?: "—",style=MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick=onProbe,enabled=!running){Text(stringResource(R.string.probe_start))}
        }
        if(running)LoadingSketch(stringResource(R.string.probe_running),enabled=drawLoading && android.provider.Settings.Global.getFloat(androidx.compose.ui.platform.LocalContext.current.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f)
        Text(stringResource(R.string.probe_path),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(multiplePeers)Text(stringResource(R.string.probe_first_peer),style=MaterialTheme.typography.labelSmall)
        if(result!=null && !running) {
            Text(stringResource(when(result.code){
                ProbeCode.OK->R.string.probe_ok
                ProbeCode.PARTIAL_REPLY->R.string.probe_partial
                ProbeCode.NO_ICMP_REPLY,ProbeCode.ICMP_UNREACHABLE,ProbeCode.PROCESS_TIMEOUT->R.string.probe_no_reply
                ProbeCode.ICMP_NOT_PERMITTED,ProbeCode.PING_BINARY_UNAVAILABLE->R.string.probe_unavailable
                ProbeCode.ADDRESS_FAMILY_UNAVAILABLE->R.string.probe_family_unavailable
                ProbeCode.DNS_TIMEOUT,ProbeCode.DNS_FAILURE->R.string.probe_dns
                ProbeCode.INVALID_HOST->R.string.probe_invalid
                ProbeCode.REPLY_WITHOUT_TIMING->R.string.probe_reply
                ProbeCode.PROCESS_ERROR->R.string.probe_error
            }),style=MaterialTheme.typography.bodyMedium)
            result.targetAddress?.let{Text(stringResource(R.string.probe_target,it),style=MaterialTheme.typography.labelSmall)}
            if(result.medianRttMs!=null)Text(stringResource(R.string.ping_median,result.medianRttMs),style=MaterialTheme.typography.labelMedium)
            if(result.minRttMs!=null && result.maxRttMs!=null)Text(stringResource(R.string.ping_range,result.minRttMs,result.maxRttMs),style=MaterialTheme.typography.labelSmall)
            result.jitterMs?.let{Text(stringResource(R.string.ping_jitter,it),style=MaterialTheme.typography.labelSmall)}
            if(result.attempts>0)Text(stringResource(R.string.probe_packets,result.received,result.attempts),style=MaterialTheme.typography.labelSmall)
            result.lossPercent?.let{Text(stringResource(R.string.probe_loss,it),style=MaterialTheme.typography.labelSmall)}
            at?.let{Text(stringResource(R.string.probe_time,time(it)),style=MaterialTheme.typography.labelSmall)}
        }
    }
}
@Composable internal fun ConnectionFailureCard(tunnel:VpnSnapshot) {
    if(tunnel.retryAtEpochMillis!=null)SketchCard {Text(stringResource(R.string.connection_retry_pending,tunnel.retryAttempt));Text(stringResource(R.string.connection_retry_stop_hint),style=MaterialTheme.typography.labelMedium)}
    tunnel.error?.let {failure->
        SketchCard {
            Text(stringResource(when(failure){VpnFailure.PROFILE->R.string.error_profile;VpnFailure.PERMISSION->R.string.error_permission;VpnFailure.START->R.string.error_start;VpnFailure.STOP->R.string.error_stop;VpnFailure.STATS->R.string.error_stats}))
            Text(listOfNotNull(tunnel.failureStage?.name,tunnel.diagnostic?.name,tunnel.nativeErrorCode?.toString()).joinToString(" / "),style=MaterialTheme.typography.labelSmall)
        }
    }
}
