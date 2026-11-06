package art.graphite.vpn

import art.graphite.vpn.diagnostics.ProbeOptions
import art.graphite.vpn.diagnostics.ProbeAddressFamily
import art.graphite.vpn.diagnostics.ProbeStatus
import art.graphite.vpn.diagnostics.ServerProber
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Local-only ICMP smoke test; no remote endpoint or VPN credentials. */
class ServerProberDeviceTest {
    @Test fun loopbackProducesActualEchoReplies() = runBlocking {
        val result = ServerProber().probe("127.0.0.1",ProbeOptions(addressFamily=ProbeAddressFamily.IPV4))
        assertEquals(result.code.toString(), ProbeStatus.REPLY, result.status)
        assertEquals("127.0.0.1",result.targetAddress)
        assertEquals(3, result.attempts)
        assertEquals(3, result.received)
        assertTrue(result.rttMs != null || result.rttUpperBoundMs != null)
        assertEquals(0.0, result.lossPercent!!, 0.0)
    }
}
