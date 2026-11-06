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
class AdditionalProfileFormatsTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val vless = """{"protocol":"vless","settings":{"address":"example.org","port":443,"id":"$id"},"streamSettings":{"network":"ws","security":"tls","wsSettings":{"path":"/vpn","host":"example.org"}}}"""
    private val vmess = """{"protocol":"vmess","settings":{"vnext":[{"address":"example.org","port":443,"users":[{"id":"$id","security":"auto","alterId":0}]}]}}"""

    @Test fun jsonRebuildsTunnelWithOnlySelectedServer() {
        val raw = """{"remarks":"Test","inbounds":[{"listen":"0.0.0.0"}],"outbounds":[$vless,{"protocol":"freedom"}]}"""
        val payload = ImportPayloadDecoder.decode(raw) as ConfigPayload
        assertEquals("Test", payload.name)
        val doc = ProfileDocuments.parse(payload.config)
        assertEquals(raw, doc.bytes().decodeToString())
        val generated = JSONObject(XrayConfigGenerator.generate(requireNotNull(doc.xray)))
        assertEquals(1, generated.getJSONArray("outbounds").length())
        assertEquals("127.0.0.1", generated.getJSONArray("inbounds").getJSONObject(0).getString("listen"))
        assertEquals("ws", doc.xray?.network)
        assertEquals("vmess", ProfileDocuments.parse(vmess.toByteArray()).xray?.protocol)
    }

    @Test fun rejectsAmbiguousAndInsecureJson() {
        assertTrue(ImportPayloadDecoder.decode("""{"outbounds":[$vless,$vmess]}""") is Unsupported)
        val insecure = JSONObject(vless).also { it.getJSONObject("streamSettings").put("tlsSettings", JSONObject().put("allowInsecure", true)) }
        assertTrue(ImportPayloadDecoder.decode(insecure.toString()) is Unsupported)
    }

    @Test fun telegramLinksAreParsedLocallyBeforeHttpsFetch() {
        val secret = "0123456789abcdef0123456789abcdef"
        val payload = ImportPayloadDecoder.decode("https://t.me/proxy?server=example.org&port=443&secret=$secret") as ConfigPayload
        assertEquals(ImportFormat.MTPROTO, payload.format)
        val proxy = requireNotNull(ProfileDocuments.parse(payload.config).mtProxy)
        assertEquals("example.org:443", proxy.endpoint)
        assertEquals("tg://proxy?server=example.org&port=443&secret=$secret", proxy.link)
        assertFalse(proxy.toString().contains(secret))
        assertTrue(ImportPayloadDecoder.decode(proxy.link + "&port=1") is Unsupported)
        assertTrue(ImportPayloadDecoder.decode(proxy.link.replace(secret, "broken")) is Unsupported)
    }
}
