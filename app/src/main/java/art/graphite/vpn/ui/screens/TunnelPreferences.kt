package art.graphite.vpn.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import art.graphite.vpn.R
import art.graphite.vpn.data.settings.*
import art.graphite.vpn.ui.components.SketchCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TunnelPreferences(value:TunnelOptions,onSave:(TunnelOptions)->Unit) {
    var draft by remember(value){mutableStateOf(value)}
    var message by remember{mutableStateOf<Int?>(null)}
    var apps by remember{mutableStateOf<List<Pair<String,String>>?>(null)}
    var loading by remember{mutableStateOf(false)}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    SketchCard {
        Text(stringResource(R.string.tunnel_options),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.tunnel_options_hint),style=MaterialTheme.typography.labelMedium)
        ToggleRow(R.string.override_mtu,R.string.override_mtu_hint,draft.overrideMtu){draft=draft.copy(overrideMtu=it);message=null}
        if(draft.overrideMtu)IntSetting(R.string.mtu_setting,draft.mtu.coerceIn(1280,1500),1280,1500,10){draft=draft.copy(mtu=it);message=null}
        ToggleRow(R.string.override_dns,R.string.override_dns_hint,draft.overrideDns){draft=draft.copy(overrideDns=it);message=null}
        if(draft.overrideDns)OutlinedTextField(value=draft.dnsServers,onValueChange={if(it.length<=200){draft=draft.copy(dnsServers=it);message=null}},label={Text(stringResource(R.string.custom_dns_addresses))},modifier=Modifier.fillMaxWidth(),maxLines=3)
        ToggleRow(R.string.override_keepalive,R.string.override_keepalive_hint,draft.overrideKeepalive){draft=draft.copy(overrideKeepalive=it);message=null}
        if(draft.overrideKeepalive)IntSetting(R.string.keepalive_setting,draft.keepaliveSeconds.coerceIn(0,120),0,120,5){draft=draft.copy(keepaliveSeconds=it);message=null}
        ToggleRow(R.string.split_apps,R.string.split_apps_hint,draft.splitApplications){draft=draft.copy(splitApplications=it);message=null}
        if(draft.splitApplications) {
            ToggleRow(R.string.only_selected_apps,R.string.only_selected_apps_hint,draft.appRoutingMode==AppRoutingMode.ONLY){draft=draft.copy(appRoutingMode=if(it)AppRoutingMode.ONLY else AppRoutingMode.EXCLUDE);message=null}
            OutlinedTextField(value=draft.applications,onValueChange={if(it.length<=16000){draft=draft.copy(applications=it);message=null}},label={Text(stringResource(R.string.app_packages))},modifier=Modifier.fillMaxWidth(),maxLines=4)
            OutlinedButton(onClick={loading=true;scope.launch {
                apps=withContext(Dispatchers.IO){runCatching {context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map{it.activityInfo.packageName to it.loadLabel(context.packageManager).toString()}.filter{it.first!=context.packageName}.distinctBy{it.first}.sortedBy{it.second.lowercase()}}.getOrDefault(emptyList())};loading=false
            }},enabled=!loading){Text(stringResource(if(loading)R.string.apps_loading else R.string.apps_choose))}
            Text(stringResource(R.string.app_split_lockdown_hint),style=MaterialTheme.typography.labelMedium)
        }
        Text(stringResource(R.string.override_apply_hint),style=MaterialTheme.typography.labelMedium)
        if(draft!=value)Text(stringResource(R.string.options_unsaved),style=MaterialTheme.typography.labelMedium)
        message?.let{Text(stringResource(it),style=MaterialTheme.typography.labelMedium)}
        Button(onClick={try{TunnelConfigOverrides.validate(draft);onSave(draft);message=R.string.options_saved}catch(_:Exception){message=R.string.options_invalid}},enabled=draft!=value,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.options_save))}
    }
    apps?.let {available->
        AlertDialog(onDismissRequest={apps=null},title={Text(stringResource(R.string.apps_choose))},text={
            if(available.isEmpty())Text(stringResource(R.string.apps_empty))
            else LazyColumn(Modifier.heightIn(max=420.dp)) {items(available,key={it.first}){(id,name)->
                val chosen=TunnelConfigOverrides.entries(draft.applications).toSet()
                Row(Modifier.fillMaxWidth().toggleable(id in chosen,role=Role.Switch,onValueChange={enabled->draft=draft.copy(applications=(if(enabled)chosen+id else chosen-id).sorted().joinToString(", "));message=null}).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(name);Text(id,style=MaterialTheme.typography.labelSmall)};Switch(id in chosen,onCheckedChange=null)}
            }}
        },confirmButton={TextButton(onClick={apps=null}){Text(stringResource(R.string.close))}})
    }
}
