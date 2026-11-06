package art.graphite.vpn.data.profile

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class XrayProfileStorageTest {
    private val link="vless://11111111-1111-4111-8111-111111111111@example.org:443?security=tls&type=ws&path=%2Fclient#Synthetic"
    @Test fun encryptedV1EnvelopePreservesShareLinkAndBinding() {
        val document=ProfileDocuments.parse(link.toByteArray())
        assertEquals("vless",document.xray?.protocol);assertNull(document.wireGuard)
        val canonical=document.bytes();assertEquals(link,canonical.toString(Charsets.UTF_8))
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val encrypted=ProfileCodec.encrypt(ProfilePayload("Synthetic",canonical),key,"binding-one")
        assertArrayEquals(byteArrayOf(0x47,0x56,0x50,1),encrypted.copyOfRange(0,4))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("11111111-1111"))
        val decrypted=ProfileCodec.decrypt(encrypted,key,"binding-one")
        try {assertArrayEquals(canonical,decrypted.config);assertEquals("vless",ProfileDocuments.parse(decrypted.config).xray?.protocol)}finally{canonical.fill(0);decrypted.config.fill(0)}
        try{ProfileCodec.decrypt(encrypted,key,"other-binding");fail("Wrong binding accepted")}catch(_:ProfileException){}
    }
    @Test fun invalidShareLinkUsesSafeProfileCodeRatherThanStorageFailure() {
        try{ProfileDocuments.parse("vless://secret".toByteArray());fail("Invalid profile accepted")}catch(e:ProfileException){assertEquals("INVALID_XRAY_CONFIG",e.safeCode);assertFalse(e.toString().contains("secret"))}
    }
    @Test fun manualVlessProfileReopensWithTransportAndCanBeEdited() {
        val draft = art.graphite.vpn.domain.xray.XrayProfile(
            "vless", "example.org", 443, "11111111-1111-4111-8111-111111111111",
            network = "grpc", security = "reality", serviceName = "vpn", grpcMode = "multi",
            realityPublicKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
            realityShortId = "aabb", originalShareLink = "",
        )
        val bytes = art.graphite.vpn.domain.xray.VlessEditorProfile.document(draft)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = ProfileCodec.encrypt(ProfilePayload("Lab", bytes), key, "binding")
        val payload = ProfileCodec.decrypt(encrypted, key, "binding")
        val reopened = requireNotNull(ProfileDocuments.parse(payload.config).xray)
        assertEquals("grpc", reopened.network)
        assertEquals("reality", reopened.security)
        assertEquals("multi", reopened.grpcMode)
        assertEquals("vpn", reopened.serviceName)
        assertEquals(draft.realityPublicKey, reopened.realityPublicKey)
        val edited = art.graphite.vpn.domain.xray.VlessEditorProfile.document(reopened.copy(
            network = "ws", security = "tls", path = "/test", transportOptions = "",
        ))
        val afterEdit = requireNotNull(ProfileDocuments.parse(edited).xray)
        assertEquals("ws", afterEdit.network)
        assertEquals("/test", afterEdit.path)
        assertEquals("", afterEdit.realityPublicKey)
    }
}
