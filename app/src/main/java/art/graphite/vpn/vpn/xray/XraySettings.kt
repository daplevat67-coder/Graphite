package art.graphite.vpn.vpn.xray

import android.content.Context
import android.content.Intent
import android.os.Bundle
import art.graphite.vpn.data.settings.*

/** Main-process settings snapshot; no share links, UUID credentials or key material. */
object XraySettings {
    const val EXTRA = "art.graphite.vpn.XRAY_SETTINGS_V1"
    data class Snapshot(val connection: ConnectionSettings, val tunnel: TunnelOptions) {
        override fun toString() = "XraySettingsSnapshot(redacted)"
    }

    fun startIntent(context: Context, profileId: String): Intent =
        Intent(context, XrayVpnService::class.java).putExtra("profile", profileId)
            .putExtra(EXTRA, encode(ConnectionSettingsStore(context).load(), TunnelOptionsStore(context).load()))

    fun encode(connection: ConnectionSettings, tunnel: TunnelOptions): Bundle {
        TunnelConfigOverrides.validate(tunnel)
        return Bundle().apply {
            putInt("version", 1)
            putBoolean("autoReconnect", connection.autoReconnect)
            putInt("retryDelaySeconds", connection.retryDelaySeconds)
            putInt("retryAttempts", connection.retryAttempts)
            putInt("statsIntervalMillis", connection.statsIntervalMillis)
            putInt("serviceStartTimeoutSeconds", connection.serviceStartTimeoutSeconds)
            putBoolean("liveStatistics", connection.liveStatistics)
            putBoolean("statisticsInBackground", connection.statisticsInBackground)
            putBoolean("notificationSpeed", connection.notificationSpeed)
            putBoolean("overrideMtu", tunnel.overrideMtu)
            putInt("mtu", tunnel.mtu)
            putBoolean("overrideDns", tunnel.overrideDns)
            putString("dnsServers", tunnel.dnsServers)
            putBoolean("overrideKeepalive", tunnel.overrideKeepalive)
            putInt("keepaliveSeconds", tunnel.keepaliveSeconds)
            putBoolean("splitApplications", tunnel.splitApplications)
            putString("appRoutingMode", tunnel.appRoutingMode.name)
            putString("applications", tunnel.applications)
        }
    }

    fun decode(bundle: Bundle): Snapshot {
        @Suppress("DEPRECATION")
        fun value(key: String): Any = requireNotNull(bundle.get(key)) { "XRAY_SETTINGS_MISSING" }
        fun integer(key: String): Int = (value(key) as? Int) ?: error("XRAY_SETTINGS_TYPE")
        fun boolean(key: String): Boolean = (value(key) as? Boolean) ?: error("XRAY_SETTINGS_TYPE")
        fun string(key: String): String = (value(key) as? String) ?: error("XRAY_SETTINGS_TYPE")
        require(integer("version") == 1) { "XRAY_SETTINGS_VERSION" }
        val connection = ConnectionSettings(
            autoReconnect = boolean("autoReconnect"), retryDelaySeconds = integer("retryDelaySeconds"),
            retryAttempts = integer("retryAttempts"), statsIntervalMillis = integer("statsIntervalMillis"),
            serviceStartTimeoutSeconds = integer("serviceStartTimeoutSeconds"), liveStatistics = boolean("liveStatistics"),
            statisticsInBackground = boolean("statisticsInBackground"), notificationSpeed = boolean("notificationSpeed"),
        )
        val tunnel = TunnelOptions(
            overrideMtu = boolean("overrideMtu"), mtu = integer("mtu"), overrideDns = boolean("overrideDns"),
            dnsServers = string("dnsServers"), overrideKeepalive = boolean("overrideKeepalive"),
            keepaliveSeconds = integer("keepaliveSeconds"), splitApplications = boolean("splitApplications"),
            appRoutingMode = AppRoutingMode.valueOf(string("appRoutingMode")), applications = string("applications"),
        )
        TunnelConfigOverrides.validate(tunnel)
        return Snapshot(connection, tunnel)
    }
}
