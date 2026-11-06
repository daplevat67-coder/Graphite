package art.graphite.vpn.domain.graphite

import art.graphite.vpn.data.importing.*
import art.graphite.vpn.data.profile.ProfileDocuments
import art.graphite.vpn.domain.xray.XrayConfigGenerator
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GraphiteProtocolTest {
    @Test fun importsAndRoundTripsSecretWithoutExposingIt() {
        val link = "graphite://${"A".repeat(43)}@vpn.example:8443"
        val payload = ImportPayloadDecoder.decode(link) as ConfigPayload
        assertEquals(ImportFormat.GRAPHITE,payload.format)
        val doc = ProfileDocuments.parse(payload.config)
        val profile = requireNotNull(doc.graphite)
        assertEquals("vpn.example:8443",profile.endpoint)
        assertEquals(link,doc.bytes().decodeToString())
        assertFalse(profile.toString().contains(link))
        assertTrue(ImportPayloadDecoder.decode(link.replace("A".repeat(43),"A".repeat(42)+"B")) is Unsupported)
        assertTrue(ImportPayloadDecoder.decode(link+"?allowInsecure=true") is Unsupported)
    }

    @Test fun generatedConfigRoutesDnsViaTcpAndBlocksOtherUdp() {
        val data = XrayConfigGenerator.graphiteBridge(12345,"synthetic-user","synthetic-password",listOf("1.1.1.1"))
        val config = JSONObject(data)
        assertEquals("tcp://1.1.1.1:53",config.getJSONObject("dns").getJSONArray("servers").getString(0))
        assertEquals("blackhole",config.getJSONArray("outbounds").getJSONObject(2).getString("protocol"))
        assertEquals("graphite-dns",config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        val file = File("build/reports/graphite-bridge-fixture.json")
        file.parentFile?.mkdirs()
        file.writeText(data)
    }
}
