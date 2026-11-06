package art.graphite.vpn.vpn

import art.graphite.vpn.data.settings.ConnectionSettings
import org.junit.Assert.*
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun transientFailuresBackOffAndStopAtBudget() {
        val options = ConnectionSettings(autoReconnect = true, retryDelaySeconds = 20, retryAttempts = 4)
        val delays = (0..4).map { ReconnectPolicy.nextDelayMillis(options, it, VpnDiagnostic.DNS_RESOLUTION_FAILURE, false) }
        assertEquals(listOf(20_000L, 40_000L, 60_000L, 60_000L, null), delays)
    }
    @Test fun consentProfileNativeAndCleanupFailuresNeverRetry() {
        val options = ConnectionSettings(autoReconnect = true)
        listOf(VpnDiagnostic.VPN_CONSENT_REVOKED, VpnDiagnostic.VPN_NOT_AUTHORIZED,
            VpnDiagnostic.PROFILE_INVALID, VpnDiagnostic.NATIVE_LIBRARY_MISSING,
            VpnDiagnostic.FOREGROUND_PERMISSION_DENIED).forEach {
            assertNull(ReconnectPolicy.nextDelayMillis(options, 0, it, false))
        }
        assertNull(ReconnectPolicy.nextDelayMillis(options, 0, VpnDiagnostic.DNS_RESOLUTION_FAILURE, true))
        assertNull(ReconnectPolicy.nextDelayMillis(ConnectionSettings(), 0, VpnDiagnostic.DNS_RESOLUTION_FAILURE, false))
    }
    @Test fun invalidSettingsFailBeforeTheyCanScheduleWork() {
        listOf<() -> ConnectionSettings>(
            { ConnectionSettings(retryAttempts = 0) }, { ConnectionSettings(retryAttempts = 11) },
            { ConnectionSettings(retryDelaySeconds = 0) }, { ConnectionSettings(statsIntervalMillis = 1) },
            { ConnectionSettings(serviceStartTimeoutSeconds = 60) }
        ).forEach { create ->
            try { create(); fail("Invalid range accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
