package art.graphite.vpn.data.settings

import java.io.ByteArrayInputStream
import org.amnezia.awg.config.Config
import org.junit.Assert.*
import org.junit.Test

class TunnelOptionsTest {
    private fun fixture():Config=Config.parse(ByteArrayInputStream("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE=
        Address = 10.0.0.2/32
        DNS = 9.9.9.9, example.test
        MTU = 1360
        ExcludedApplications = org.example.old
        Jc = 3
        Jmin = 10
        Jmax = 20
        H1 = 123
        H2 = 223
        H3 = 323
        H4 = 423
        S1 = 5
        S2 = 8
        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 5-7
        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAI=
        Endpoint = 192.0.2.2:51820
        AllowedIPs = 10.1.0.0/16
        PersistentKeepalive = 15
    """.trimIndent().toByteArray()))
    @Test fun disabledOverridesPreserveExactConfigAndAwgParameters() {
        val config=fixture()
        assertSame(config,TunnelConfigOverrides.apply(config,TunnelOptions()))
    }
    @Test fun changingMtuAndDnsDoesNotRewriteSourceOrAwgOrKeepaliveRange() {
        val config=fixture();val before=config.toAwgQuickString()
        val result=TunnelConfigOverrides.apply(config,TunnelOptions(overrideMtu=true,mtu=1420,overrideDns=true,dnsServers="1.1.1.1, 2606:4700:4700::1111"))
        assertEquals(before,config.toAwgQuickString())
        assertEquals(1420,result.`interface`.mtu.get())
        assertEquals(2,result.`interface`.dnsServers.size)
        assertTrue(result.`interface`.dnsSearchDomains.isEmpty())
        assertEquals("5-7",result.peers[0].persistentKeepalive.get())
        val ignore=setOf("MTU","DNS")
        fun stable(c:Config)=c.toAwgQuickString().lineSequence().filter{it.substringBefore('=').trim() !in ignore}.toList()
        assertEquals(stable(config),stable(result))
    }
    @Test fun onlyModeReplacesExcludedListAndKeepaliveAffectsEveryPeer() {
        val result=TunnelConfigOverrides.apply(fixture(),TunnelOptions(overrideKeepalive=true,keepaliveSeconds=25,splitApplications=true,appRoutingMode=AppRoutingMode.ONLY,applications="org.example.one,org.example.two"))
        assertTrue(result.`interface`.excludedApplications.isEmpty())
        assertEquals(setOf("org.example.one","org.example.two"),result.`interface`.includedApplications)
        result.peers.forEach{assertEquals("25",it.persistentKeepalive.get())}
    }
    @Test fun zeroKeepaliveDisablesWhileOffKeepsRanges() {
        val result=TunnelConfigOverrides.apply(fixture(),TunnelOptions(overrideKeepalive=true,keepaliveSeconds=0))
        result.peers.forEach{assertEquals("0",it.persistentKeepalive.get())}
    }
    @Test fun invalidEnabledValuesAndInjectionFailBeforeConfigChanges() {
        listOf(TunnelOptions(overrideMtu=true,mtu=9000),TunnelOptions(overrideDns=true,dnsServers="example.org"),
            TunnelOptions(overrideDns=true,dnsServers="1.1.1.1\n[Peer]"),TunnelOptions(splitApplications=true),
            TunnelOptions(splitApplications=true,applications="org.example.app = invalid"),
            TunnelOptions(overrideKeepalive=true,keepaliveSeconds=-1)).forEach {
            try {TunnelConfigOverrides.apply(fixture(),it);fail("Invalid option accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
