package art.graphite.vpn.vpn

import art.graphite.vpn.data.profile.ProfileException
import android.content.pm.PackageManager
import org.amnezia.awg.backend.BackendException
import java.io.IOException
import java.security.GeneralSecurityException

/** Safe diagnostics: exception text/format can contain endpoint or key material. */
internal data class VpnProblem(val stage: VpnStage, val diagnostic: VpnDiagnostic, val nativeErrorCode: Int? = null)

internal object VpnErrors {
    fun classify(failure: Throwable, stage: VpnStage): VpnProblem {
        val backend = generateSequence(failure) { it.cause }.take(8).filterIsInstance<BackendException>().firstOrNull()
        if (backend != null) {
            val diagnostic = when (backend.reason) {
                BackendException.Reason.TUNNEL_MISSING_CONFIG -> VpnDiagnostic.TUNNEL_MISSING_CONFIG
                BackendException.Reason.VPN_NOT_AUTHORIZED -> VpnDiagnostic.VPN_NOT_AUTHORIZED
                BackendException.Reason.UNABLE_TO_START_VPN -> VpnDiagnostic.UNABLE_TO_START_VPN
                BackendException.Reason.TUN_CREATION_ERROR -> VpnDiagnostic.TUN_CREATION_ERROR
                BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> VpnDiagnostic.GO_ACTIVATION_ERROR_CODE
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> VpnDiagnostic.DNS_RESOLUTION_FAILURE
                else -> VpnDiagnostic.BACKEND_UNSUPPORTED
            }
            val code = if (diagnostic == VpnDiagnostic.GO_ACTIVATION_ERROR_CODE)
                backend.format.firstOrNull()?.let { if (it is Number) it.toInt() else null } else null
            return VpnProblem(stage, diagnostic, code)
        }
        val diagnostic = when {
            stage == VpnStage.PROFILE_LOAD && failure is ProfileException && failure.safeCode == "STORAGE_KEY_UNAVAILABLE" -> VpnDiagnostic.PROFILE_STORAGE_KEY
            stage == VpnStage.PROFILE_LOAD && failure is ProfileException && failure.safeCode in setOf("INVALID_XRAY_CONFIG", "XRAY_PROFILE", "NOT_XRAY_PROFILE", "INVALID_AWG_CONFIG", "INVALID_CONFIG", "MISSING_ADDRESS", "MISSING_PEER", "MISSING_ENDPOINT", "MISSING_ROUTES") -> VpnDiagnostic.PROFILE_INVALID
            stage == VpnStage.PROFILE_LOAD && failure is GeneralSecurityException -> VpnDiagnostic.PROFILE_STORAGE_KEY
            stage == VpnStage.PROFILE_LOAD && failure is IllegalArgumentException -> VpnDiagnostic.PROFILE_INVALID
            stage == VpnStage.PROFILE_LOAD -> VpnDiagnostic.PROFILE_UNAVAILABLE
            failure is UnsatisfiedLinkError -> VpnDiagnostic.NATIVE_LIBRARY_MISSING
            failure is LinkageError -> VpnDiagnostic.NATIVE_RUNTIME_INCOMPATIBLE
            failure is PackageManager.NameNotFoundException -> VpnDiagnostic.APPLICATION_NOT_INSTALLED
            stage == VpnStage.FOREGROUND && failure is SecurityException -> VpnDiagnostic.FOREGROUND_PERMISSION_DENIED
            stage == VpnStage.FOREGROUND && failure is IllegalArgumentException -> VpnDiagnostic.FOREGROUND_TYPE_REJECTED
            stage == VpnStage.FOREGROUND && failure.javaClass.simpleName == "ForegroundServiceStartNotAllowedException" -> VpnDiagnostic.FOREGROUND_START_NOT_ALLOWED
            stage == VpnStage.FOREGROUND -> VpnDiagnostic.FOREGROUND_NOTIFICATION_REJECTED
            stage == VpnStage.SERVICE_START -> VpnDiagnostic.SERVICE_START_REJECTED
            failure is SecurityException -> VpnDiagnostic.SYSTEM_PERMISSION_DENIED
            failure is InterruptedException -> VpnDiagnostic.OPERATION_INTERRUPTED
            failure is IOException -> VpnDiagnostic.SYSTEM_IO_ERROR
            stage == VpnStage.STATISTICS -> VpnDiagnostic.STATS_READ_FAILED
            stage == VpnStage.TUNNEL_STOP -> VpnDiagnostic.STOP_FAILED
            failure is IllegalArgumentException -> VpnDiagnostic.TUN_CONFIGURATION_REJECTED
            else -> VpnDiagnostic.UNEXPECTED_ERROR
        }
        return VpnProblem(stage, diagnostic)
    }
}
