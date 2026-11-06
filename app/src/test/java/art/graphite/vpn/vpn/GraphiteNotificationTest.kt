package art.graphite.vpn.vpn

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Builds the actual Android foreground notification; no tunnel/server is created. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GraphiteNotificationTest {
    @Test fun notificationShowsActualRatesAndThrottlesChanges() {
        val controller=Robolectric.buildService(GraphiteVpnService::class.java).create()
        val service=controller.get()
        try {
            service.showRunning()
            val manager=service.getSystemService(NotificationManager::class.java)
            fun text()=manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            val sample=VpnSnapshot(phase=VpnPhase.UP,rxBytesPerSecond=125000.0,txBytesPerSecond=250000.0)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            service.showTraffic(sample,true,true)
            assertEquals("↓ 1.00 Мбит/с · ↑ 2.00 Мбит/с",text())
            service.showTraffic(sample.copy(rxBytesPerSecond=250000.0),true,true)
            assertEquals("↓ 1.00 Мбит/с · ↑ 2.00 Мбит/с",text())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            service.showTraffic(sample.copy(rxBytesPerSecond=250000.0),true,true)
            assertEquals("↓ 2.00 Мбит/с · ↑ 2.00 Мбит/с",text())
        }finally {controller.destroy()}
    }
}
