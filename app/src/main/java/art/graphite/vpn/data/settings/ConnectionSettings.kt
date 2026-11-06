package art.graphite.vpn.data.settings

import android.content.Context

/** Device-local options. A connection captures them once; edits apply on next connect. */
data class ConnectionSettings(
    val autoReconnect: Boolean = false,
    val retryDelaySeconds: Int = 3,
    val retryAttempts: Int = 3,
    val statsIntervalMillis: Int = 1000,
    val serviceStartTimeoutSeconds: Int = 7,
    val liveStatistics:Boolean=true,
    val statisticsInBackground:Boolean=false,
    val notificationSpeed:Boolean=true,
) {
    init {
        require(retryDelaySeconds in 1..30)
        require(retryAttempts in 1..10)
        require(statsIntervalMillis in 500..5000)
        require(serviceStartTimeoutSeconds in 5..30)
    }
    /** Bounded exponential delay; number is one-based. */
    fun retryDelayMillis(attempt: Int): Long {
        require(attempt in 1..10)
        return (retryDelaySeconds * 1000L * (1L shl (attempt - 1))).coerceAtMost(60_000L)
    }
}

class ConnectionSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("connection-settings", Context.MODE_PRIVATE)
    fun load(): ConnectionSettings = ConnectionSettings(
        notificationSpeed = preferences.getBoolean("notification-speed",true),
        liveStatistics = preferences.getBoolean("live-statistics",true),
        statisticsInBackground = preferences.getBoolean("background-statistics",false),
        autoReconnect = preferences.getBoolean("auto-reconnect", false),
        retryDelaySeconds = preferences.getInt("retry-delay", 3).coerceIn(1, 30),
        retryAttempts = preferences.getInt("retry-attempts", 3).coerceIn(1, 10),
        statsIntervalMillis = preferences.getInt("stats-interval", 1000).coerceIn(500, 5000),
        serviceStartTimeoutSeconds = preferences.getInt("service-timeout", 7).coerceIn(5, 30),
    )
    fun save(value: ConnectionSettings) {
        preferences.edit().putBoolean("notification-speed",value.notificationSpeed).putBoolean("live-statistics",value.liveStatistics).putBoolean("background-statistics",value.statisticsInBackground).putBoolean("auto-reconnect", value.autoReconnect)
            .putInt("retry-delay", value.retryDelaySeconds).putInt("retry-attempts", value.retryAttempts)
            .putInt("stats-interval", value.statsIntervalMillis).putInt("service-timeout", value.serviceStartTimeoutSeconds).apply()
    }
}
