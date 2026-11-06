package art.graphite.vpn.vpn

import art.graphite.vpn.data.settings.ConnectionSettings

/** Retries never cover revoked consent, invalid secrets/configuration or failed cleanup. */
internal object ReconnectPolicy {
    fun nextDelayMillis(options: ConnectionSettings, attemptsUsed: Int, diagnostic: VpnDiagnostic,
                        cleanupFailed: Boolean): Long? {
        if (!options.autoReconnect || cleanupFailed || attemptsUsed !in 0 until options.retryAttempts) return null
        if (diagnostic !in setOf(VpnDiagnostic.DNS_RESOLUTION_FAILURE, VpnDiagnostic.UNABLE_TO_START_VPN,
                VpnDiagnostic.TUN_CREATION_ERROR, VpnDiagnostic.SYSTEM_IO_ERROR)) return null
        return options.retryDelayMillis(attemptsUsed + 1)
    }
}
