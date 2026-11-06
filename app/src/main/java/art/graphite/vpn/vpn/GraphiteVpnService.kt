package art.graphite.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import art.graphite.vpn.MainActivity
import art.graphite.vpn.R
import org.amnezia.awg.backend.GoBackend

/** WireGuard service lifecycle and native calls are serialized by VpnRuntime. */
class GraphiteVpnService : GoBackend.VpnService() {
    @Volatile internal var destroyed = false
    @Volatile internal var foregroundReady = false
    private val notificationLimiter=NotificationRateLimiter()
    private var lastTrafficDetail:String?=null

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW))
            val notification = notification(false)
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(NOTIFICATION_ID, notification)
            foregroundReady = true
        } catch (failure: Exception) {
            VpnRuntime.foregroundFailed(this, failure)
            stopSelf()
        }
        VpnRuntime.created(this)
    }

    // VpnRuntime exclusively owns native handles on its worker. The superclass
    // still publishes/resets its service future on Android's lifecycle thread.
    override fun setOwner(owner: GoBackend) = Unit

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) VpnRuntime.disconnect(this)
        else VpnRuntime.started(this, intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L,
            fromSystem = intent?.hasExtra(EXTRA_GENERATION) != true)
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        VpnRuntime.revoked(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        VpnRuntime.destroyed(this)
    }

    internal fun showRunning() {
        if (!destroyed && foregroundReady) {
            notificationLimiter.markUpdated(SystemClock.elapsedRealtime())
            lastTrafficDetail=null
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(true))
        }
    }

    internal fun showStarting() {
        if(!destroyed && foregroundReady) {
            notificationLimiter.markUpdated(SystemClock.elapsedRealtime())
            lastTrafficDetail=null
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(false))
        }
    }

    internal fun showStopping() {
        if(!destroyed && foregroundReady) {
            notificationLimiter.markUpdated(SystemClock.elapsedRealtime())
            lastTrafficDetail=null
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(true,getString(R.string.phase_stopping)))
        }
    }

    /** Called by the single runtime worker; no extra service coroutine or sampler. */
    internal fun showTraffic(snapshot:VpnSnapshot,enabled:Boolean,statisticsEnabled:Boolean) {
        if(destroyed || !foregroundReady || !enabled || snapshot.phase!=VpnPhase.UP || !notificationLimiter.allow(SystemClock.elapsedRealtime()))return
        val detail=when {
            !statisticsEnabled->getString(R.string.notification_stats_disabled)
            snapshot.error!=null->getString(R.string.notification_stats_unavailable)
            else->NotificationTraffic.speedLine(snapshot) ?: getString(R.string.notification_speed_pending)
        }
        if(detail==lastTrafficDetail)return
        lastTrafficDetail=detail
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(true,detail))
    }

    internal fun removeNotification() { stopForeground(STOP_FOREGROUND_REMOVE) }

    private fun notification(running: Boolean,detail:String?=null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GraphiteVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(detail ?: getString(if (running) R.string.vpn_running else R.string.vpn_starting))
            .setStyle(Notification.BigTextStyle().bigText(detail ?: getString(if(running)R.string.vpn_running else R.string.vpn_starting)))
            .setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (VpnRuntime.state.value.alwaysOn != true)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
        return builder.build()
    }

    companion object {
        internal const val EXTRA_GENERATION = "generation"
        internal const val ACTION_STOP = "art.graphite.vpn.STOP"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 30
    }
}
