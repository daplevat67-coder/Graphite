package art.graphite.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.domain.model.WireGuardDraft
import art.graphite.vpn.vpn.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/** Generated loopback fixtures only. No production profile or external endpoint is used. */
class AwgNativeDeviceTest {
    @Test fun awg1FixedHeadersAndHandshakePadding() = exercise("awg1","198",51881)
    @Test fun awg2RangesTransportPaddingAndCps() = exercise("awg2","197",51882)
    @Test fun awg31ProtectionPaddingTimersAndKeepaliveRange() = exercise("awg3","196",51883)

    private fun exercise(mode:String,subnet:String,port:Int) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("graphiteLocalFixture")=="true")
        assertTrue("Isolated emulator required",Build.HARDWARE in listOf("ranchu","goldfish"))
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val store=ProfileStore(context)
        val previous=requireNotNull(store.selected())
        assertEquals("GRAPHITE isolated update lab",previous.name)
        assertEquals(listOf("127.0.0.1:51880"),previous.endpoints)
        assertEquals(1,store.inspect().profiles.size)
        val descriptor=instrument.uiAutomation.executeShellCommand("cat /data/local/tmp/graphite-$mode.conf")
        val raw=ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}
        val profile=try {store.`import`("GRAPHITE $mode isolated lab",raw)}finally {raw.fill(0)}
        try {
            assertEquals("awg",profile.protocolId)
            assertEquals(listOf("127.0.0.1:$port"),profile.endpoints)
            val exported=store.export(profile.id)
            val draft=try {WireGuardDraft.fromConfig(exported,profile.name)}finally {exported.fill(0)}
            assertTrue(draft.awgParameters.containsKey("Jc"))
            if(mode=="awg2")assertTrue(draft.awgParameters.getValue("H1").contains('-'))
            if(mode=="awg3") {
                assertTrue(draft.awgParameters.containsKey("HeaderProtectionKey"))
                assertEquals("5-7",draft.peers.single().persistentKeepalive)
            }
            val edited=draft.toConfigBytes()
            try {store.update(profile.id,profile.name,edited)}finally {edited.fill(0)}
            ActivityScenario.launch(MainActivity::class.java).use {
                assertNull("Preauthorize isolated emulator VPN",VpnService.prepare(context))
                VpnRuntime.connect(context,profile.id)
                try {
                    await(45000) {VpnRuntime.state.value.phase in setOf(VpnPhase.UP,VpnPhase.ERROR)}
                    val state=VpnRuntime.state.value
                    assertEquals("$mode: ${state.failureStage}/${state.diagnostic}/${state.nativeErrorCode}",VpnPhase.UP,state.phase)
                    var response=""
                    val end=SystemClock.elapsedRealtime()+30000
                    while(SystemClock.elapsedRealtime()<end && !response.contains("GRAPHITE_AWG_LAB_OK $mode")) {
                        try {Socket().use {socket ->
                            socket.connect(InetSocketAddress("10.$subnet.0.1",8080),5000)
                            socket.soTimeout=5000
                            socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: graphite-lab\r\n\r\n".toByteArray())
                            response=socket.getInputStream().bufferedReader().readText()
                        }} catch(_:Exception) {SystemClock.sleep(300)}
                    }
                    assertTrue("$mode encrypted tunnel payload",response.contains("GRAPHITE_AWG_LAB_OK $mode"))
                    await(15000) {(VpnRuntime.state.value.lastHandshakeEpochMillis ?: 0)>0 && (VpnRuntime.state.value.rxBytes ?: 0)>0}
                } finally {VpnRuntime.disconnect(context);await(20000) {VpnRuntime.state.value.phase==VpnPhase.DOWN}}
            }
        } finally {store.delete(profile.id);store.select(previous.id)}
    }
    private fun await(timeout:Long,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!predicate() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
        assertTrue("Timed out waiting for native AWG state",predicate())
    }
}
