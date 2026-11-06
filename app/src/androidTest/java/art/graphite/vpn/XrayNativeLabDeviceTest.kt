package art.graphite.vpn
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.vpn.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.net.InetSocketAddress
import java.util.Base64
/** Only an opt-in isolated emulator, public synthetic fixtures and localhost servers. */
class XrayNativeLabDeviceTest {
 @Test fun isolatedVlessAndVmessPassPayloadAndReconnect(){
  assumeTrue(InstrumentationRegistry.getArguments().getString("graphiteLocalFixture")=="true");assertTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
  val instrument=InstrumentationRegistry.getInstrumentation();val c=instrument.targetContext
  val uuid=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cat /data/local/tmp/graphite-xray-uuid.txt")).use{it.readBytes().toString(Charsets.UTF_8).trim()};assertTrue(uuid.matches(Regex("[0-9a-f-]{36}")))
  val store=ProfileStore(c);val old=store.inspect().profiles.single{it.name=="GRAPHITE isolated update lab"};assertTrue(store.config(old.id).peers.isNotEmpty())
  val vless="vless://$uuid@127.0.0.1:52401?encryption=none&security=none&type=tcp#LocalVless"
  val vmess="vmess://"+Base64.getEncoder().encodeToString("""{"v":"2","ps":"LocalVmess","add":"127.0.0.1","port":"52402","id":"$uuid","aid":"0","net":"tcp","type":"none","tls":"","scy":"auto"}""".toByteArray())
  val profiles=listOf("vless" to vless,"vmess" to vmess).map{(p,l)->val raw=l.toByteArray();try{store.`import`("GRAPHITE local $p",raw)}finally{raw.fill(0)}}
  ActivityScenario.launch<MainActivity>(android.content.Intent(c, MainActivity::class.java)).use {
   // A real user can connect after first composition/draw. No delay between cycles.
   instrument.waitForIdleSync()
   for(p in profiles){store.select(p.id);repeat(2){ cycle ->
    var rx=0L;var tx=0L
    VpnRuntime.connect(c,p.id,p.protocolId)
    try {
     await(45000){VpnRuntime.state.value.phase in setOf(VpnPhase.UP,VpnPhase.ERROR)};val s=VpnRuntime.state.value;assertEquals("${p.protocolId}: ${s.failureStage}/${s.diagnostic}",VpnPhase.UP,s.phase)
     assertTrue(c.getSystemService(ActivityManager::class.java).getRunningServices(100).any{it.process==c.packageName+":xray"})
     var lastSocketFailure="none";var response="";val end=SystemClock.elapsedRealtime()+30000
     while(SystemClock.elapsedRealtime()<end && !response.contains("GRAPHITE_XRAY_LAB_OK")){
      try{Socket().use{socket->socket.connect(InetSocketAddress("198.18.0.1",18080),5000);socket.soTimeout=5000;socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: graphite-lab\r\n\r\n".toByteArray());response=socket.getInputStream().bufferedReader().readText()}}catch(e:Exception){lastSocketFailure=e.javaClass.simpleName;SystemClock.sleep(500)}
     }
     assertTrue("${p.protocolId} real payload: socket=$lastSocketFailure responseBytes=${response.length} rx=${VpnRuntime.state.value.rxBytes} tx=${VpnRuntime.state.value.txBytes} phase=${VpnRuntime.state.value.phase}",response.contains("GRAPHITE_XRAY_LAB_OK"));await(15000){(VpnRuntime.state.value.rxBytes ?: 0)>0 && (VpnRuntime.state.value.txBytes ?: 0)>0};assertNull(VpnRuntime.state.value.lastHandshakeEpochMillis);rx=VpnRuntime.state.value.rxBytes ?: 0;tx=VpnRuntime.state.value.txBytes ?: 0
    }finally{VpnRuntime.disconnect(c);await(25000){VpnRuntime.state.value.phase in setOf(VpnPhase.DOWN,VpnPhase.ERROR)}}
    assertEquals(VpnPhase.DOWN,VpnRuntime.state.value.phase)
    instrument.sendStatus(0,android.os.Bundle().apply{putString("stream","\nGRAPHITE_NATIVE_SESSION protocol=${p.protocolId} cycle=${cycle+1} payload=OK rxBytes=$rx txBytes=$tx stopped=DOWN\n")})
   }}
  }
 }
 private fun await(ms:Long,p:()->Boolean){val end=SystemClock.elapsedRealtime()+ms;while(!p() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertTrue("Xray timeout: ${VpnRuntime.state.value.phase}/${VpnRuntime.state.value.diagnostic}",p())}
}
