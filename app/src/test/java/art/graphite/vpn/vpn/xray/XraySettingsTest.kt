package art.graphite.vpn.vpn.xray

import android.os.Bundle
import art.graphite.vpn.data.settings.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XraySettingsTest {
    @Test fun startIntentCapturesAllOptionsBeforeLaterPreferenceChanges() {
        val context = RuntimeEnvironment.getApplication()
        val connection = ConnectionSettings(true, 17, 8, 2500, 23,
            liveStatistics = false, statisticsInBackground = true, notificationSpeed = false)
        val tunnel = TunnelOptions(true, 1400, true, "9.9.9.9, 2620:fe::fe",
            true, 41, true, AppRoutingMode.ONLY, "art.graphite.vpn, org.example.reader")
        ConnectionSettingsStore(context).save(connection)
        TunnelOptionsStore(context).save(tunnel)
        val intent = XraySettings.startIntent(context, "11111111-1111-4111-8111-111111111111")
        ConnectionSettingsStore(context).save(ConnectionSettings())
        TunnelOptionsStore(context).save(TunnelOptions())
        val received = XraySettings.decode(requireNotNull(intent.getBundleExtra(XraySettings.EXTRA)))
        assertEquals(connection, received.connection)
        assertEquals(tunnel, received.tunnel)
        assertEquals("11111111-1111-4111-8111-111111111111", intent.getStringExtra("profile"))
        assertEquals("XraySettingsSnapshot(redacted)", received.toString())
    }

    @Test fun rejectsMalformedAndOutOfRangeSettingsInsteadOfReadingStalePreferences() {
        val valid = XraySettings.encode(ConnectionSettings(), TunnelOptions(overrideMtu = true))
        val invalid = listOf(
            Bundle(valid).apply { putString("statsIntervalMillis", "1000") },
            Bundle(valid).apply { putInt("statsIntervalMillis", 1) },
            Bundle(valid).apply { putInt("mtu", 900) },
            Bundle(valid).apply { putString("appRoutingMode", "UNKNOWN") },
            Bundle(valid).apply { remove("notificationSpeed") },
            Bundle(valid).apply { putInt("version", 2) },
        )
        invalid.forEach { assertTrue(runCatching { XraySettings.decode(it) }.isFailure) }
    }
}
