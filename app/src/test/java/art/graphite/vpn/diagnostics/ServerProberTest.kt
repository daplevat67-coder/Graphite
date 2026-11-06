package art.graphite.vpn.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ServerProberTest {
    @Test fun endpointsAreParsedWithoutPortOrShellInput() {
        assertEquals("example.org", ServerProber.hostFromEndpoint("example.org:51820"))
        assertEquals("2001:db8::1", ServerProber.hostFromEndpoint("[2001:db8::1]:51820"))
        assertNull(ServerProber.hostFromEndpoint("example.org:0"))
        assertNull(ServerProber.hostFromEndpoint("example.org:65536"))
        assertNull(ServerProber.hostFromEndpoint("2001:db8::1:51820"))
        assertNull(ServerProber.hostFromEndpoint("-c 500:51820"))
        assertNull(ServerProber.hostFromEndpoint("example.org;id:51820"))
        assertNull(ServerProber.hostFromEndpoint("999.1.1.1:51820"))
    }

    @Test fun actualEchoRepliesProvideLatencyNotProcessOrSummaryTiming() {
        val reply = ServerProber.parseEcho("64 bytes from 192.0.2.1: icmp_seq=1 ttl=64 time=12.4 ms", 0)
        assertTrue(reply.received)
        assertEquals(12.4, reply.rtt!!, 0.0001)
        val summaryOnly = ServerProber.parseEcho("1 packets transmitted, 0 received, 100% packet loss, time 2001ms", 1)
        assertFalse(summaryOnly.received)
        assertNull(summaryOnly.rtt)
    }

    @Test fun subMillisecondReplyRemainsAnUpperBound() {
        val reply = ServerProber.parseEcho("64 bytes from ::1: icmp_seq=1 ttl=64 time<1 ms", 0)
        val result = ServerProber.aggregate(listOf(reply, reply, reply))
        assertEquals(ProbeStatus.REPLY, result.status)
        assertNull(result.rttMs)
        assertEquals(1.0, result.rttUpperBoundMs!!, 0.0)
        assertEquals(0.0, result.lossPercent!!, 0.0)
    }

    @Test fun partialRepliesKeepMeasuredMeanAndRealLoss() {
        val good = ServerProber.parseEcho("64 bytes from 192.0.2.1: icmp_seq=1 ttl=64 time=20 ms", 0)
        val lost = ServerProber.parseEcho("", 1)
        val result = ServerProber.aggregate(listOf(good, lost, good))
        assertEquals(ProbeCode.PARTIAL_REPLY, result.code)
        assertEquals(3, result.attempts)
        assertEquals(2, result.received)
        assertEquals(20.0, result.rttMs!!, 0.0)
        assertEquals(100.0 / 3, result.lossPercent!!, 0.0001)
    }

    @Test fun unavailableAndErrorsDoNotInventPacketLoss() {
        val denied = ServerProber.parseEcho("ping: socket: Operation not permitted", 2)
        val result = ServerProber.aggregate(listOf(denied, denied, denied))
        assertEquals(ProbeStatus.UNAVAILABLE, result.status)
        assertNull(result.lossPercent)
        assertNull(result.rttMs)
        val timeout = ServerProber.aggregate(List(3) { ServerProber.Companion.Echo(code = ProbeCode.PROCESS_TIMEOUT) })
        assertEquals(ProbeStatus.ERROR, timeout.status)
        assertNull(timeout.lossPercent)
    }

    @Test fun replyWithoutTimingIsNotFabricatedZero() {
        val echo = ServerProber.parseEcho("64 bytes from 192.0.2.1: icmp_seq=1 ttl=64", 0)
        val result = ServerProber.aggregate(List(3) { echo })
        assertEquals(ProbeCode.REPLY_WITHOUT_TIMING, result.code)
        assertEquals(ProbeStatus.REPLY, result.status)
        assertNull(result.rttMs)
        assertNull(result.rttUpperBoundMs)
    }
}
