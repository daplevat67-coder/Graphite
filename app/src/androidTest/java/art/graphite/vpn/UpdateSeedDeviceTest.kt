package art.graphite.vpn

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import art.graphite.vpn.data.profile.ProfileStore
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

/** Opt-in isolated emulator fixture only; can run against the previous V0.2 APK. */
class UpdateSeedDeviceTest {
    @Test fun seedEncryptedProfileBeforeUpdate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("graphiteLocalFixture")=="true")
        assertTrue("Isolated emulator required",Build.HARDWARE in listOf("ranchu","goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val store=ProfileStore(instrumentation.targetContext)
        assertTrue("Isolated fresh installation required",store.inspect().profiles.isEmpty())
        val descriptor=instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/graphite-lab.conf")
        val raw=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{it.readBytes()}
        try {
            val p=store.`import`("GRAPHITE isolated update lab",raw)
            assertEquals(p.id,store.selected()?.id)
            assertEquals(1,store.inspect().profiles.size)
        } finally {raw.fill(0)}
    }
}
