package art.graphite.vpn

import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.vpn.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.InetSocketAddress

/** Actual native WireGuard against an isolated local netstack server, never production. */
class NativeVpnLabDeviceTest {
    @Test fun encryptedProfileSurvivesUpdateAndNativeTunnelExchangesPayload() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("graphiteLocalFixture")=="true")
        assertTrue("Isolated emulator required",Build.HARDWARE in listOf("ranchu","goldfish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=ProfileStore(context)
        val profile=requireNotNull(store.selected())
        assertEquals(1,store.inspect().profiles.size)
        assertEquals("GRAPHITE isolated update lab",profile.name)
        assertEquals(listOf("10.199.0.2/32"),profile.addresses)
        assertEquals(listOf("10.199.0.0/24"),profile.routes)
        assertEquals(listOf("127.0.0.1:51880"),profile.endpoints)
        assertTrue("Encrypted old-version profile must decrypt",store.config(profile.id).peers.isNotEmpty())
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNull("Grant isolated emulator ACTIVATE_VPN before test",VpnService.prepare(context))
            VpnRuntime.connect(context,profile.id)
            try {
                await(30000){VpnRuntime.state.value.phase in setOf(VpnPhase.UP,VpnPhase.ERROR)}
                val state=VpnRuntime.state.value
                assertEquals("${state.failureStage}/${state.diagnostic}/${state.nativeErrorCode}",VpnPhase.UP,state.phase)
                var response=""
                val end=SystemClock.elapsedRealtime()+20000
                while(SystemClock.elapsedRealtime()<end && !response.contains("GRAPHITE_LAB_OK")) {
                    try {Socket().use {socket->
                        socket.connect(InetSocketAddress("10.199.0.1",8080),3000);socket.soTimeout=3000
                        socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: graphite-lab\r\n\r\n".toByteArray())
                        response=socket.getInputStream().bufferedReader().readText()
                    }} catch(_:Exception){SystemClock.sleep(500)}
                }
                assertTrue("Real tunnel HTTP payload must return",response.contains("GRAPHITE_LAB_OK"))
                await(10000){VpnRuntime.state.value.lastHandshakeEpochMillis!=null && (VpnRuntime.state.value.rxBytes ?: 0)>0}
            } finally {VpnRuntime.disconnect(context);await(15000){VpnRuntime.state.value.phase==VpnPhase.DOWN}}
            // Exercise the recreated Android service / GoBackend service future.
            VpnRuntime.connect(context,profile.id)
            try {
                await(30000){VpnRuntime.state.value.phase in setOf(VpnPhase.UP,VpnPhase.ERROR)}
                val retry=VpnRuntime.state.value
                assertEquals("Reconnect: ${retry.failureStage}/${retry.diagnostic}/${retry.nativeErrorCode}",VpnPhase.UP,retry.phase)
                await(15000){(VpnRuntime.state.value.lastHandshakeEpochMillis ?: 0)>0}
            } finally {VpnRuntime.disconnect(context);await(15000){VpnRuntime.state.value.phase==VpnPhase.DOWN}}
        }
    }
    private fun await(timeout:Long,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!predicate() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
        assertTrue("Timed out waiting for native VPN state",predicate())
    }
}
