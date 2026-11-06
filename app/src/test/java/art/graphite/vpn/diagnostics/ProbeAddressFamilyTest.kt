package art.graphite.vpn.diagnostics

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProbeAddressFamilyTest {
    private val ipv4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val ipv6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })

    @Test fun automaticFamilyPreservesResolverOrder() {
        assertSame(ipv6, ServerProber.selectAddress(listOf(ipv6, ipv4), ProbeAddressFamily.AUTO))
        assertSame(ipv4, ServerProber.selectAddress(listOf(ipv4, ipv6), ProbeAddressFamily.AUTO))
    }

    @Test fun forcedFamilyActuallySelectsMatchingAddress() {
        assertSame(ipv4, ServerProber.selectAddress(listOf(ipv6, ipv4), ProbeAddressFamily.IPV4))
        assertSame(ipv6, ServerProber.selectAddress(listOf(ipv4, ipv6), ProbeAddressFamily.IPV6))
    }

    @Test fun missingFamilyDoesNotSilentlyUseAnotherFamily() {
        assertNull(ServerProber.selectAddress(listOf(ipv4), ProbeAddressFamily.IPV6))
        assertNull(ServerProber.selectAddress(listOf(ipv6), ProbeAddressFamily.IPV4))
        assertNull(ServerProber.selectAddress(emptyList(), ProbeAddressFamily.AUTO))
    }

    @Test fun ipv4LiteralWithForcedIpv6FailsBeforeStartingProcess() = runBlocking {
        val result = ServerProber().probe("127.0.0.1", ProbeOptions(addressFamily = ProbeAddressFamily.IPV6))
        assertEquals(ProbeCode.ADDRESS_FAMILY_UNAVAILABLE, result.code)
        assertEquals(0, result.attempts)
        assertNull(result.lossPercent)
        assertNull(result.rttMs)
        assertNull(result.targetAddress)
    }

    @Test fun ipv6LiteralWithForcedIpv4FailsBeforeStartingProcess() = runBlocking {
        val result = ServerProber().probe("::1", ProbeOptions(addressFamily = ProbeAddressFamily.IPV4))
        assertEquals(ProbeCode.ADDRESS_FAMILY_UNAVAILABLE, result.code)
        assertEquals(0, result.attempts)
        assertNull(result.targetAddress)
    }
}
