package art.graphite.vpn.domain.model

data class Protocol(val id: String, val name: String, val descriptionRes: Int, val example: String, val icon: String = "shield")
/** Null means not measured; demo chart samples are never exposed as measured telemetry. */
data class Diagnostics(val latencyMs: Long? = null, val lossPercent: Float? = null, val downloadedBytes: Long? = null)
