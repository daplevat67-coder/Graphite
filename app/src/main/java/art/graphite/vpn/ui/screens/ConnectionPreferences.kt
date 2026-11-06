package art.graphite.vpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import art.graphite.vpn.R
import art.graphite.vpn.data.settings.ConnectionSettings
import art.graphite.vpn.ui.components.SketchCard
import art.graphite.vpn.vpn.VpnSnapshot

@Composable internal fun ConnectionPreferences(value:ConnectionSettings,state:VpnSnapshot,onChange:(ConnectionSettings)->Unit,onSystemVpn:()->Unit) {
    SketchCard {
        Text(stringResource(R.string.connection_settings),style=MaterialTheme.typography.titleMedium)
        ToggleRow(R.string.connection_auto_retry,R.string.connection_auto_retry_hint,value.autoReconnect){onChange(value.copy(autoReconnect=it))}
        if(value.autoReconnect) {
            IntSetting(R.string.connection_retry_delay,value.retryDelaySeconds,1,30,1){onChange(value.copy(retryDelaySeconds=it))}
            IntSetting(R.string.connection_retry_attempts,value.retryAttempts,1,10,1){onChange(value.copy(retryAttempts=it))}
        }
        IntSetting(R.string.connection_service_timeout,value.serviceStartTimeoutSeconds,5,30,1){onChange(value.copy(serviceStartTimeoutSeconds=it))}
        ToggleRow(R.string.notification_speed,R.string.notification_speed_hint,value.notificationSpeed){onChange(value.copy(notificationSpeed=it))}
        ToggleRow(R.string.live_statistics,R.string.live_statistics_hint,value.liveStatistics){onChange(value.copy(liveStatistics=it))}
        if(value.liveStatistics)ToggleRow(R.string.background_statistics,R.string.background_statistics_hint,value.statisticsInBackground){onChange(value.copy(statisticsInBackground=it))}
        IntSetting(R.string.connection_stats_interval,value.statsIntervalMillis,500,5000,500){onChange(value.copy(statsIntervalMillis=it))}
        Text(stringResource(R.string.connection_apply_hint),style=MaterialTheme.typography.labelMedium)
    }
    SketchCard {
        ToggleRow(R.string.system_killswitch,R.string.system_switch_hint,state.lockdown==true){onSystemVpn()}
        ToggleRow(R.string.always_on_switch,R.string.system_switch_hint,state.alwaysOn==true){onSystemVpn()}
        Text(stringResource(R.string.system_killswitch_body),style=MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.system_always_on_status,protectionText(state.alwaysOn)),style=MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.system_lockdown_status,protectionText(state.lockdown)),style=MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.system_protection_hint),style=MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick=onSystemVpn,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.system_vpn_open))}
        Text(stringResource(R.string.system_killswitch_steps),style=MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.system_killswitch_routes),style=MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun protectionText(enabled:Boolean?):String=stringResource(when(enabled){true->R.string.protection_on;false->R.string.protection_off;null->R.string.protection_unknown})
