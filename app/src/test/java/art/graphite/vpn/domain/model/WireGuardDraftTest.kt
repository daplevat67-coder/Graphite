package art.graphite.vpn.domain.model

import org.amnezia.awg.config.Config
import org.junit.Assert.*
import org.junit.Test

class WireGuardDraftTest {
    private val privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE="
    private val publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE="
    private val secondKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAI="
    private val psk = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM="
    private val complete = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.0.0.2/32, fd00::2/128
        DNS = 1.1.1.1, 2001:db8::53, internal.example
        ListenPort = 51821
        MTU = 1380
        IncludedApplications = art.graphite.vpn, org.example.client

        [Peer]
        PublicKey = $publicKey
        PresharedKey = $psk
        Endpoint = vpn.example:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25

        [Peer]
        PublicKey = $secondKey
        Endpoint = [2001:db8::1]:51820
        AllowedIPs = 192.0.2.0/24
    """.trimIndent()

    @Test fun allSupportedSettingsAndMultiplePeersRoundTripWithoutKeyLoss() {
        val draft = WireGuardDraft.fromConfig(complete.toByteArray(),"Home")
        val original = Config.parse(complete.byteInputStream())
        val serialized = Config.parse(draft.toConfigBytes().inputStream())
        assertEquals(original.toAwgQuickString(),serialized.toAwgQuickString())
        assertEquals(privateKey,draft.privateKey)
        assertEquals(psk,draft.peers[0].presharedKey)
        assertEquals(publicKey,draft.peers[0].publicKey)
        assertEquals(secondKey,draft.peers[1].publicKey)
        assertEquals(2,draft.peers.size)
        assertEquals(draft,WireGuardDraft.fromConfig(draft.toConfigBytes(),draft.profileName))
    }
    @Test fun commentsCaseAndRepeatedAddressAndRouteFieldsAreHandledByOfficialParser() {
        val text = """
            # header comment
            [interface]
            privatekey = $privateKey # private comment
            ADDRESS = 10.0.0.2/32
            Address = fd00::2/128
            [peer]
            PUBLICKEY = $publicKey
            endpoint = vpn.example:51820 # endpoint comment
            AllowedIPs = 10.0.0.0/8
            allowedips = fd00::/8
        """.trimIndent()
        val draft=WireGuardDraft.fromConfig(text.toByteArray(),"test")
        assertEquals(2,Config.parse(draft.toConfigBytes().inputStream()).`interface`.addresses.size)
        assertEquals(2,Config.parse(draft.toConfigBytes().inputStream()).peers.first().allowedIps.size)
        assertEquals(privateKey,draft.privateKey)
        assertFalse(draft.toConfigText().contains("#"))
    }
    @Test fun excludedApplicationsSurviveRoundTrip() {
        val text=complete.replace("IncludedApplications", "ExcludedApplications")
        val draft=WireGuardDraft.fromConfig(text.toByteArray(),"test")
        assertTrue(draft.includedApplications.isBlank())
        assertEquals(Config.parse(text.byteInputStream()).toAwgQuickString(),Config.parse(draft.toConfigBytes().inputStream()).toAwgQuickString())
    }
    @Test fun editingSingleFieldKeepsOtherPeerKeysAndSettings() {
        val draft=WireGuardDraft.fromConfig(complete.toByteArray(),"test")
        val edited=draft.copy(mtu="1420",peers=draft.peers.mapIndexed { i,p->if(i==1)p.copy(endpoint="new.example:51821") else p })
        val parsed=WireGuardDraft.fromConfig(edited.toConfigBytes(),"test")
        assertEquals(privateKey,parsed.privateKey)
        assertEquals(draft.peers[0],parsed.peers[0])
        assertEquals(secondKey,parsed.peers[1].publicKey)
        assertEquals("1420",parsed.mtu)
    }
    @Test fun unknownFieldsAreRejectedInsteadOfDropped() {
        failure {WireGuardDraft.fromConfig(complete.replace("MTU = 1380","MTU = 1380\nUnknownFutureField = 8").toByteArray(),"test")}
    }
    @Test fun newlineInjectionInStructuredFieldsRejected() {
        val draft=WireGuardDraft.fromConfig(complete.toByteArray(),"test")
        failure { draft.copy(dns="1.1.1.1\nPrivateKey = discarded").toConfigBytes() }
    }
    @Test fun mutuallyExclusiveApplicationListsRejected() {
        val draft=WireGuardDraft.fromConfig(complete.toByteArray(),"test")
        failure { draft.copy(excludedApplications="org.example.other").toConfigBytes() }
    }
    @Test fun optionalKeysRemainAbsent() {
        val text="[Interface]\nPrivateKey = $privateKey\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = $publicKey\nEndpoint = vpn.example:51820\nAllowedIPs = 0.0.0.0/0"
        val draft=WireGuardDraft.fromConfig(text.toByteArray(),"test")
        assertEquals("",draft.peers.single().presharedKey)
        assertFalse(draft.toConfigText().contains("PresharedKey"))
    }
    @Test fun rawConfigSizeLimitAndInvalidUtf8AreRejected() {
        failure {WireGuardDraft.fromConfig(ByteArray(65537),"test")}
        failure {WireGuardDraft.fromConfig(byteArrayOf(0xc3.toByte(),0x28),"test")}
    }
    private fun failure(action:()->Unit) {
        try {action();fail("Expected safe draft failure")}
        catch(error:WireGuardDraftException) {assertEquals("INVALID_CONFIG",error.message);assertNull(error.cause)}
    }
}
