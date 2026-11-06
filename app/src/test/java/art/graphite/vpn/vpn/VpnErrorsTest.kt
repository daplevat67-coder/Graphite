package art.graphite.vpn.vpn

import org.amnezia.awg.backend.BackendException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VpnErrorsTest {
    @Test fun backendReasonsArePreservedWithoutSensitiveFormatValues() {
        val secret = "private-config-never-display"
        val error = BackendException(BackendException.Reason.DNS_RESOLUTION_FAILURE, secret)
        val result = VpnErrors.classify(error, VpnStage.TUNNEL_START)
        assertEquals(VpnDiagnostic.DNS_RESOLUTION_FAILURE, result.diagnostic)
        assertEquals(VpnStage.TUNNEL_START, result.stage)
        assertFalse(result.toString().contains(secret))
        assertNull(result.nativeErrorCode)
    }

    @Test fun nativeErrorCodeIsOnlyNumericActivationArgument() {
        val activation = VpnErrors.classify(BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, -5), VpnStage.TUNNEL_START)
        assertEquals(-5, activation.nativeErrorCode)
        val untrusted = VpnErrors.classify(BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, "secret"), VpnStage.TUNNEL_START)
        assertNull(untrusted.nativeErrorCode)
    }

    @Test fun failuresRetainStageAndSafeClassifications() {
        assertEquals(VpnDiagnostic.NATIVE_LIBRARY_MISSING, VpnErrors.classify(UnsatisfiedLinkError("details"), VpnStage.NATIVE_LOAD).diagnostic)
        assertEquals(VpnDiagnostic.FOREGROUND_PERMISSION_DENIED, VpnErrors.classify(SecurityException("details"), VpnStage.FOREGROUND).diagnostic)
        assertEquals(VpnDiagnostic.PROFILE_INVALID, VpnErrors.classify(IllegalArgumentException("private config"), VpnStage.PROFILE_LOAD).diagnostic)
        assertEquals(VpnDiagnostic.SYSTEM_IO_ERROR, VpnErrors.classify(IOException("private path"), VpnStage.TUNNEL_START).diagnostic)
    }
}
