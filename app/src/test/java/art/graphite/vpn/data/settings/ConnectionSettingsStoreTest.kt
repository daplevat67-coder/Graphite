package art.graphite.vpn.data.settings

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionSettingsStoreTest {
    @Test fun restoresDevicePreferencesWithoutTouchingProfileStorage() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("connection-settings", Context.MODE_PRIVATE).edit().clear().commit()
        val expected = ConnectionSettings(true, 15, 6, 2000, 12,liveStatistics=false,statisticsInBackground=true,notificationSpeed=false)
        ConnectionSettingsStore(context).save(expected)
        assertEquals(expected, ConnectionSettingsStore(context).load())
    }
    @Test fun clampsOldOutOfRangeValuesToSupportedBounds() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("connection-settings", Context.MODE_PRIVATE).edit().clear()
            .putInt("retry-delay", -1).putInt("retry-attempts", 100)
            .putInt("stats-interval", 0).putInt("service-timeout", 100).commit()
        val actual = ConnectionSettingsStore(context).load()
        assertEquals(1, actual.retryDelaySeconds)
        assertEquals(10, actual.retryAttempts)
        assertEquals(500, actual.statsIntervalMillis)
        assertEquals(30, actual.serviceStartTimeoutSeconds)
    }
}
