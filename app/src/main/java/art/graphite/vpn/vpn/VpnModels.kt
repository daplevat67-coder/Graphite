package art.graphite.vpn.vpn

enum class VpnPhase { DOWN, STARTING, UP, STOPPING, ERROR }
enum class VpnFailure { PROFILE, PERMISSION, START, STOP, STATS }
enum class VpnEventKind { STARTING, INTERFACE_UP, HANDSHAKE, STOPPED, REVOKED, ERROR }
enum class VpnStage { CONSENT, SERVICE_START, FOREGROUND, PROFILE_LOAD, NATIVE_LOAD, TUNNEL_START, STATISTICS, TUNNEL_STOP, SERVICE_DESTROY }
enum class VpnDiagnostic {
    VPN_CONSENT_REQUIRED, VPN_CONSENT_REVOKED, PROFILE_UNAVAILABLE, PROFILE_STORAGE_KEY, PROFILE_INVALID,
    SERVICE_START_REJECTED, SERVICE_READY_TIMEOUT, FOREGROUND_START_NOT_ALLOWED, FOREGROUND_TYPE_REJECTED,
    FOREGROUND_PERMISSION_DENIED, FOREGROUND_NOTIFICATION_REJECTED,
    TUNNEL_MISSING_CONFIG, VPN_NOT_AUTHORIZED, UNABLE_TO_START_VPN, TUN_CREATION_ERROR,
    GO_ACTIVATION_ERROR_CODE, DNS_RESOLUTION_FAILURE, TUN_CONFIGURATION_REJECTED, APPLICATION_NOT_INSTALLED,
    NATIVE_LIBRARY_MISSING, NATIVE_RUNTIME_INCOMPATIBLE, SYSTEM_PERMISSION_DENIED, SYSTEM_IO_ERROR,
    OPERATION_INTERRUPTED, BACKEND_UNSUPPORTED, STATS_READ_FAILED, STOP_FAILED, UNEXPECTED_ERROR
}
data class VpnEvent(val atEpochMillis: Long, val kind: VpnEventKind,
    val stage: VpnStage? = null, val diagnostic: VpnDiagnostic? = null, val nativeErrorCode: Int? = null)
data class VpnSnapshot(
    val statisticsEnabled:Boolean=true,
    val statisticsPaused:Boolean=false,
    val effectiveMtu:Int?=null,
    val effectiveDns:List<String>?=null,
    val phase: VpnPhase = VpnPhase.DOWN,
    val profileId: String? = null,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val rxBytesPerSecond: Double? = null,
    val txBytesPerSecond: Double? = null,
    val lastHandshakeEpochMillis: Long? = null,
    val sampledElapsedNanos: Long? = null,
    val error: VpnFailure? = null,
    val failureStage: VpnStage? = null,
    val diagnostic: VpnDiagnostic? = null,
    val nativeErrorCode: Int? = null,
    val events: List<VpnEvent> = emptyList(),
    val alwaysOn: Boolean? = null,
    val lockdown: Boolean? = null,
    val retryAttempt: Int = 0,
    val retryAtEpochMillis: Long? = null,
)

/** Counter deltas use monotonic time; the first/reset sample has no inferred rate. */
internal class TransferRates {
    private var previous: Triple<Long, Long, Long>? = null
    fun reset(){previous=null}
    fun sample(rx: Long, tx: Long, elapsedNanos: Long): Pair<Double?, Double?> {
        val old = previous
        previous = Triple(rx, tx, elapsedNanos)
        if (old == null || elapsedNanos <= old.third || rx < old.first || tx < old.second)
            return null to null
        val seconds = (elapsedNanos - old.third) / 1_000_000_000.0
        return (rx - old.first) / seconds to (tx - old.second) / seconds
    }
}
