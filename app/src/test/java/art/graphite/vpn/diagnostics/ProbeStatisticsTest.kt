package art.graphite.vpn.diagnostics
import org.junit.Assert.*
import art.graphite.vpn.diagnostics.ServerProber.Companion.Echo
import org.junit.Test
class ProbeStatisticsTest {
 @Test fun medianIsStableAgainstOutlierAndJitterUsesRealReplyOrder() {
  val r=ServerProber.aggregate(listOf(10.0,11.0,90.0,12.0,13.0).map{Echo(true,rtt=it,code=ProbeCode.OK)})
  assertEquals(12.0,r.medianRttMs!!,0.0001);assertEquals(27.2,r.rttMs!!,0.0001)
  assertEquals(10.0,r.minRttMs!!,0.0);assertEquals(90.0,r.maxRttMs!!,0.0);assertEquals(39.75,r.jitterMs!!,0.0001)
 }
 @Test fun upperBoundIsNeverConvertedIntoExactStatistics() {
  val r=ServerProber.aggregate(listOf(Echo(true,upper=1.0,code=ProbeCode.OK)))
  assertNull(r.medianRttMs);assertNull(r.jitterMs);assertNull(r.minRttMs)
  assertEquals(1.0,r.rttUpperBoundMs!!,0.0)
 }
 @Test fun invalidProbeBudgetIsRejected() {assertThrows(IllegalArgumentException::class.java){ProbeOptions(count=0)}}
}
