package art.graphite.vpn.vpn.xray
import android.os.Bundle
import art.graphite.vpn.vpn.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class XrayMessagesTest {
 @Test fun metadataRoundTripKeepsUnknownPolicyAndRealCounters(){
  val source=VpnSnapshot(phase=VpnPhase.UP,profileId="11111111-1111-4111-8111-111111111111",rxBytes=1234,txBytes=4321,rxBytesPerSecond=12.0,txBytesPerSecond=21.0,sampledElapsedNanos=5000,effectiveMtu=1280,effectiveDns=listOf("1.1.1.1"),alwaysOn=null,lockdown=false)
  val decoded=XrayMessages.decode(XrayMessages.encode(source));assertEquals(source,decoded);assertNull(decoded.lastHandshakeEpochMillis)
 }
 @Test fun failureAndDisabledStatsAreNotReportedAsUpOrProtected(){
  val source=VpnSnapshot(phase=VpnPhase.ERROR,error=VpnFailure.PERMISSION,failureStage=VpnStage.CONSENT,diagnostic=VpnDiagnostic.VPN_CONSENT_REVOKED,statisticsEnabled=false,statisticsPaused=true)
  assertEquals(source,XrayMessages.decode(XrayMessages.encode(source)))
  assertEquals(VpnPhase.ERROR,XrayMessages.decode(Bundle()).phase)
 }
}
