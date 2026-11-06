package art.graphite.vpn.vpn

import art.graphite.vpn.data.settings.ConnectionSettings
import org.junit.Assert.*
import org.junit.Test

class NotificationTrafficTest {
    @Test fun notificationNeedsBackgroundCountersButDisabledStatisticsNeverSamples() {
        val options=ConnectionSettings(statisticsInBackground=false,notificationSpeed=true)
        assertTrue(NotificationTraffic.needsSampling(options,false))
        assertFalse(NotificationTraffic.needsSampling(options.copy(notificationSpeed=false),false))
        assertTrue(NotificationTraffic.needsSampling(options.copy(notificationSpeed=false),true))
        assertFalse(NotificationTraffic.needsSampling(options.copy(liveStatistics=false),true))
        assertFalse(NotificationTraffic.needsSampling(options.copy(liveStatistics=false),false))
    }
    @Test fun actualCountersAreConvertedWithoutInventingFirstOrFailedSamples() {
        val sample=VpnSnapshot(phase=VpnPhase.UP,rxBytesPerSecond=125000.0,txBytesPerSecond=250000.0)
        assertEquals("↓ 1.00 Мбит/с · ↑ 2.00 Мбит/с",NotificationTraffic.speedLine(sample))
        assertNull(NotificationTraffic.speedLine(sample.copy(rxBytesPerSecond=null)))
        assertNull(NotificationTraffic.speedLine(sample.copy(phase=VpnPhase.STARTING)))
        assertNull(NotificationTraffic.speedLine(sample.copy(statisticsPaused=true)))
        assertNull(NotificationTraffic.speedLine(sample.copy(error=VpnFailure.STATS)))
        assertNull(NotificationTraffic.speedLine(sample.copy(statisticsEnabled=false)))
    }
    @Test fun updatesAreLimitedEvenWithHalfSecondSampling() {
        val limiter=NotificationRateLimiter()
        limiter.markUpdated(10000)
        listOf(10000L,10500L,11000L,11999L).forEach{assertFalse(limiter.allow(it))}
        assertTrue(limiter.allow(12000))
        assertFalse(limiter.allow(12500))
        assertTrue(limiter.allow(14000))
    }
}
