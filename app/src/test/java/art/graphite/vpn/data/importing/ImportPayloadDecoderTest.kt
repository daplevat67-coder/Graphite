package art.graphite.vpn.data.importing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImportPayloadDecoderTest {
    private val fixture="""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE=
        Address = 10.0.0.2/32
        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()
    private fun encoded(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun qt(text:String):ByteArray {
        val data=text.toByteArray();val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use{it.write(data)}
        return ByteBuffer.allocate(4+compressed.size()).putInt(data.size).put(compressed.toByteArray()).array()
    }
    private fun envelope(vararg native:String):String=JSONObject().put("description","My QR profile").put("containers",JSONArray().apply {
        native.forEach {put(JSONObject().put("container","amnezia-awg").put("awg",JSONObject().put("last_config",JSONObject().put("config",it).toString())))}
    }).toString()
    private fun reject(value:String,expected:ImportRejection) {
        val result=ImportPayloadDecoder.decode(value)
        assertTrue(result.toString(),result is ImportPayload.Unsupported)
        assertEquals(expected,(result as ImportPayload.Unsupported).reason)
    }
    @Test fun rawConfigAndDocumentedBase64ExtensionsPreserveClientFields() {
        val raw=ImportPayloadDecoder.decode(fixture) as ImportPayload.ConfigPayload
        assertEquals(ImportFormat.RAW_CONFIG,raw.format);assertEquals(fixture,raw.config.toString(Charsets.UTF_8))
        for(scheme in listOf("wireguard","awg")) {
            val result=ImportPayloadDecoder.decode("$scheme://${encoded(fixture.toByteArray())}") as ImportPayload.ConfigPayload
            assertEquals(ImportFormat.BASE64_CONFIG,result.format);assertArrayEquals(raw.config,result.config)
        }
    }
    @Test fun httpsIsOnlyClassifiedAndUnsafeUrlsAreRejected() {
        val result=ImportPayloadDecoder.decode("https://example.org/profile.conf?secret=private") as ImportPayload.HttpsConfigLink
        assertEquals("example.org",result.uri.host);assertFalse(result.toString().contains("private"))
        reject("http://example.org/profile.conf",ImportRejection.UNSUPPORTED_SCHEME)
        reject("https://user:password@example.org/a",ImportRejection.INVALID_DATA)
        reject("https://example.org:65536/a",ImportRejection.INVALID_DATA)
        reject("https://example.org/a#unexpected",ImportRejection.INVALID_DATA)
    }
    @Test fun qtCompressedAmneziaAndOfficialSingleChunkQrDecodeActualNativeConfig() {
        val json=envelope(fixture);val compressed=qt(json)
        val direct=ImportPayloadDecoder.decode("vpn://${encoded(compressed)}") as ImportPayload.ConfigPayload
        assertEquals("My QR profile",direct.name);assertEquals(ImportFormat.AMNEZIA,direct.format)
        assertEquals(fixture,direct.config.toString(Charsets.UTF_8))
        val chunk=ByteBuffer.allocate(8+compressed.size).putShort(1984.toShort()).put(1.toByte()).put(0.toByte()).putInt(compressed.size).put(compressed).array()
        val scanned=ImportPayloadDecoder.decode(encoded(chunk)) as ImportPayload.ConfigPayload
        assertArrayEquals(direct.config,scanned.config)
        val plain=ImportPayloadDecoder.decode("vpn://${encoded(json.toByteArray())}") as ImportPayload.ConfigPayload
        assertArrayEquals(direct.config,plain.config)
    }
    @Test fun unsupportedProtocolAndMultipleClientsNeverBecomeAnArbitraryTunnel() {
        for(scheme in listOf("trojan","happ","incy"))reject("$scheme://secret",ImportRejection.UNSUPPORTED_PROTOCOL)
        reject("vpn://${encoded(qt(envelope(fixture,fixture.replace("192.0.2.1","192.0.2.2"))))}",ImportRejection.UNSUPPORTED_AMNEZIA)
        reject("vpn://${encoded(qt("""{"containers":[{"xray":{}}]}"""))}",ImportRejection.UNSUPPORTED_AMNEZIA)
    }
    @Test fun malformedAndOversizedInputsAreBoundedWithoutExposingSecrets() {
        reject("x".repeat(ImportPayloadDecoder.MAX_INPUT_CHARS+1),ImportRejection.TOO_LARGE)
        reject("vpn://${encoded(ByteBuffer.allocate(6).putInt(Int.MAX_VALUE).put(0x78).put(0x01).array())}",ImportRejection.TOO_LARGE)
        val wrong=qt(envelope(fixture));ByteBuffer.wrap(wrong).putInt(1)
        reject("vpn://${encoded(wrong)}",ImportRejection.INVALID_DATA)
        reject("awg://${encoded(byteArrayOf(0xc3.toByte()))}",ImportRejection.INVALID_DATA)
        reject("{".repeat(40)+"}".repeat(40),ImportRejection.INVALID_DATA)
        val result=ImportPayloadDecoder.decode(fixture) as ImportPayload.ConfigPayload
        assertFalse(result.toString().contains("PrivateKey"));assertFalse(result.toString().contains("AAAA"))
    }
    @Test fun multiFrameAmneziaQrReportsExplicitlyUnsupported() {
        val chunk=ByteBuffer.allocate(10).putShort(1984.toShort()).put(2.toByte()).put(0.toByte()).putInt(2).put(0).put(1).array()
        reject(encoded(chunk),ImportRejection.MULTIPART_QR)
    }
    @Test fun adminOrUnsupportedDefaultContainerIsNotImportedAsAnAvailableClient() {
        val admin=JSONObject(envelope(fixture)).put("userName","root").put("password","synthetic-secret").toString()
        reject("vpn://${encoded(qt(admin))}",ImportRejection.UNSUPPORTED_AMNEZIA)
        val other=JSONObject(envelope(fixture)).put("defaultContainer","amnezia-xray").toString()
        reject("vpn://${encoded(qt(other))}",ImportRejection.UNSUPPORTED_AMNEZIA)
    }
    @Test fun invalidWireGuardKeysAreRejectedBeforeImportConfirmation() {
        reject(fixture.replace("PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE=","PrivateKey = invalid"),ImportRejection.INVALID_DATA)
    }
    @Test fun vlessAndVmessUseValidatedProxyPayloadWithoutNetworkOrSecretLogging() {
        val link="vless://11111111-1111-4111-8111-111111111111@example.org:443?security=tls#Synthetic"
        val result=ImportPayloadDecoder.decode(link) as ImportPayload.ConfigPayload
        assertEquals(ImportFormat.XRAY,result.format);assertEquals("Synthetic",result.name)
        assertEquals(link,result.config.toString(Charsets.UTF_8));assertFalse(result.toString().contains("11111111"));result.config.fill(0)
        reject("vless://secret",ImportRejection.INVALID_DATA)
        reject("vmess://secret",ImportRejection.INVALID_DATA)
    }
}
