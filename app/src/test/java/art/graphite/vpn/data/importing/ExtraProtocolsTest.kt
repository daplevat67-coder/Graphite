package art.graphite.vpn.data.importing

import art.graphite.vpn.data.profile.ProfileDocuments
import art.graphite.vpn.domain.xray.XrayConfigGenerator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExtraProtocolsTest {
    @Test fun socksCredentialsGoOnlyToTheSocksOutbound() {
        val input = "socks5://user:p%40ss@[::1]:1080#Example"
        val payload = ImportPayloadDecoder.decode(input) as ConfigPayload
        val profile = requireNotNull(ProfileDocuments.parse(payload.config).xray)
        val config = JSONObject(XrayConfigGenerator.generate(profile))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", outbound.getString("protocol"))
        assertEquals("p@ss", outbound.getJSONObject("settings").getString("pass"))
        assertEquals(1, config.getJSONArray("outbounds").length())
        assertFalse(config.getJSONArray("inbounds").toString().contains("p@ss"))
        assertTrue(ImportPayloadDecoder.decode("socks5://user@example.org:1080") is Unsupported)
    }

    @Test fun ikePskAndOpenVpnSurviveStorageRoundTrip() {
        val ike = """{"protocol":"ikev2","server":"example.org","identity":"client@example.org","psk":"MDEyMzQ1Njc4OWFiY2RlZg=="}"""
        val ikePayload = ImportPayloadDecoder.decode(ike) as ConfigPayload
        assertEquals(ImportFormat.IKEV2, ikePayload.format)
        val profile = ProfileDocuments.parse(ikePayload.config)
        assertEquals(16, profile.ike?.key()?.size)
        assertNotNull(ProfileDocuments.parse(profile.bytes()).ike)
        assertTrue(ImportPayloadDecoder.decode(ike.replace("MDEyMzQ1Njc4OWFiY2RlZg==", "YQ==")) is Unsupported)

        val ovpn = "client\ndev tun\nremote example.org 1194\n<ca>\nFAKE TEST CA\n</ca>"
        val openPayload = ImportPayloadDecoder.decode(ovpn) as ConfigPayload
        assertEquals(ImportFormat.OPENVPN, openPayload.format)
        val document = ProfileDocuments.parse(openPayload.config)
        assertTrue(document.isCompanion)
        assertEquals(listOf("example.org:1194"), document.openVpn?.endpoints)
        assertEquals(ovpn, document.bytes().decodeToString())
    }
}
