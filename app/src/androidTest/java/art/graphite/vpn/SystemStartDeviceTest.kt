package art.graphite.vpn

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.data.settings.ConnectionSettings
import art.graphite.vpn.data.settings.ConnectionSettingsStore
import art.graphite.vpn.vpn.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.net.InetSocketAddress

/** Exercises Android's unflagged service-start entry point. Does not claim OS lockdown testing. */
class SystemStartDeviceTest {
    @Test fun unflaggedStartRestoresEncryptedSelectionAndUsesConfiguredSampling() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("graphiteLocalFixture")=="true")
        assertTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val profiles=ProfileStore(context)
        val profile=profiles.inspect().profiles.single{it.name=="GRAPHITE isolated update lab"}
        assertEquals(listOf("127.0.0.1:51880"),profile.endpoints)
        profiles.select(profile.id)
        val settings=ConnectionSettingsStore(context)
        val original=settings.load()
        settings.save(ConnectionSettings(statsIntervalMillis=2500))
        VpnRuntime.setUiVisible(true)
        try {
            context.startForegroundService(Intent(context,GraphiteVpnService::class.java))
            await(30000){VpnRuntime.state.value.phase in setOf(VpnPhase.UP,VpnPhase.ERROR)}
            assertEquals(VpnPhase.UP,VpnRuntime.state.value.phase)
            assertEquals(profile.id,VpnRuntime.state.value.profileId)
            // API28 cannot report lockdown; never turn unknown into a protected claim.
            if(Build.VERSION.SDK_INT<29)assertNull(VpnRuntime.state.value.lockdown)
            var response=""
            val deadline=SystemClock.elapsedRealtime()+20000
            while(SystemClock.elapsedRealtime()<deadline && !response.contains("GRAPHITE_LAB_OK")) {
                try {Socket().use {socket->
                    socket.connect(InetSocketAddress("10.199.0.1",8080),3000);socket.soTimeout=3000
                    socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: graphite-lab\r\n\r\n".toByteArray())
                    response=socket.getInputStream().bufferedReader().readText()
                }}catch(_:Exception){SystemClock.sleep(500)}
            }
            assertTrue(response.contains("GRAPHITE_LAB_OK"))
            await(15000){VpnRuntime.state.value.sampledElapsedNanos!=null}
            val first=VpnRuntime.state.value.sampledElapsedNanos!!
            await(15000){VpnRuntime.state.value.sampledElapsedNanos!=first}
            assertTrue("Configured sampling cadence",VpnRuntime.state.value.sampledElapsedNanos!!-first>=2_000_000_000L)
        }finally {
            try {
                // Android binds to the active VpnService; stopService alone cannot
                // destroy it until the native tunnel has released that binding.
                VpnRuntime.disconnect(context)
                await(15000){VpnRuntime.state.value.phase==VpnPhase.DOWN}
            }finally {settings.save(original);VpnRuntime.setUiVisible(false)}
        }
    }
    private fun await(timeout:Long,predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!predicate() && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        assertTrue("Timed out awaiting service lifecycle",predicate())
    }
}
