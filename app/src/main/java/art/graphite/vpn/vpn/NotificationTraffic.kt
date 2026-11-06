package art.graphite.vpn.vpn

import art.graphite.vpn.data.settings.ConnectionSettings
import art.graphite.vpn.ui.format.TrafficFormat

/** Small pure policy shared by notification rendering and sampling. */
internal object NotificationTraffic {
    fun needsSampling(options:ConnectionSettings,uiVisible:Boolean):Boolean =
        options.liveStatistics && (uiVisible || options.statisticsInBackground || options.notificationSpeed)
    fun speedLine(snapshot:VpnSnapshot):String? {
        if(snapshot.phase!=VpnPhase.UP || snapshot.error!=null || snapshot.statisticsPaused || !snapshot.statisticsEnabled)return null
        val download=TrafficFormat.rate(snapshot.rxBytesPerSecond,true) ?: return null
        val upload=TrafficFormat.rate(snapshot.txBytesPerSecond,true) ?: return null
        return "↓ $download · ↑ $upload"
    }
}

/** Elapsed realtime is monotonic on device; a backwards test clock starts a new interval. */
internal class NotificationRateLimiter(private val minimumMillis:Long=2000) {
    private var lastUpdate:Long?=null
    fun markUpdated(now:Long){lastUpdate=now}
    fun allow(now:Long):Boolean {
        val previous=lastUpdate
        if(previous!=null && now>=previous && now-previous<minimumMillis)return false
        lastUpdate=now
        return true
    }
}
