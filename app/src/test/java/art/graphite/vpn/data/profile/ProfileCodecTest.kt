package art.graphite.vpn.data.profile

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class ProfileCodecTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val payload = ProfilePayload("Личный профиль", "[Interface]\nPrivateKey = secret-test-value".toByteArray())
    private val binding = "f67b0914-c7e9-4df6-b3c2-61a45dd0ebe1"
    private fun failure(code: String? = null, action: () -> Unit) {
        try { action(); fail("Expected safe profile failure") }
        catch (e: ProfileException) {
            if (code != null) assertEquals(code, e.safeCode)
            assertEquals(e.safeCode, e.message)
            assertNull(e.cause)
        }
    }
    @Test fun roundTripEncryptsNameAndConfig() {
        val key = key(); val encrypted = ProfileCodec.encrypt(payload, key, binding)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("PrivateKey"))
        val result = ProfileCodec.decrypt(encrypted, key, binding)
        assertEquals(payload.name, result.name); assertArrayEquals(payload.config, result.config)
        assertEquals(ProfileSource.UNKNOWN, result.source)
    }
    @Test fun sourceRoundTripsWithEncryptedProfile() {
        val key = key()
        for (source in ProfileSource.entries) {
            val encrypted = ProfileCodec.encrypt(payload.copy(source = source), key, binding)
            assertEquals(source, ProfileCodec.decrypt(encrypted, key, binding).source)
        }
    }
    @Test fun freshProviderIvForEveryEncryption() {
        val key = key()
        assertFalse(ProfileCodec.encrypt(payload,key,binding).contentEquals(ProfileCodec.encrypt(payload,key,binding)))
    }
    @Test fun ciphertextTamperRejected() {
        val key = key(); val bytes = ProfileCodec.encrypt(payload,key,binding)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(bytes,key,binding) }
    }
    @Test fun ivTamperRejected() {
        val key = key(); val bytes = ProfileCodec.encrypt(payload,key,binding)
        bytes[6] = (bytes[6].toInt() xor 1).toByte()
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(bytes,key,binding) }
    }
    @Test fun wrongKeyRejected() {
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(ProfileCodec.encrypt(payload,key(),binding),key(),binding) }
    }
    @Test fun renamedFileRejectedByBinding() {
        val key = key()
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(ProfileCodec.encrypt(payload,key,binding),key,"other-profile") }
    }
    @Test fun versionTamperRejected() {
        val key = key(); val bytes = ProfileCodec.encrypt(payload,key,binding); bytes[3]=2
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(bytes,key,binding) }
    }
    @Test fun oversizedConfigRejectedBeforeEncryption() {
        failure("PROFILE_TOO_LARGE") { ProfileCodec.encrypt(ProfilePayload("test",ByteArray(ProfileCodec.MAX_CONFIG_BYTES+1)),key(),binding) }
    }
    @Test fun maximumConfigRoundTrip() {
        val key = key(); val config = ByteArray(ProfileCodec.MAX_CONFIG_BYTES){42}
        val result = ProfileCodec.decrypt(ProfileCodec.encrypt(ProfilePayload("test",config),key,binding),key,binding)
        assertArrayEquals(config,result.config)
    }
    @Test fun truncatedBlobRejected() {
        val key = key(); val bytes=ProfileCodec.encrypt(payload,key,binding)
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(bytes.copyOf(bytes.size-1),key,binding) }
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(ByteArray(0),key,binding) }
    }
    @Test fun oversizedBlobRejected() {
        failure("PROFILE_UNREADABLE") { ProfileCodec.decrypt(ByteArray(ProfileCodec.MAX_BLOB_BYTES+1),key(),binding) }
    }
    @Test fun authenticatedInvalidLengthsRejected() {
        val key = key(); val header = byteArrayOf(0x47,0x56,0x50,1)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key)
        cipher.updateAAD(header); cipher.updateAAD(binding.toByteArray())
        val malformed = java.nio.ByteBuffer.allocate(32).putInt(Int.MAX_VALUE).array()
        val blob = header + cipher.iv + cipher.doFinal(malformed)
        failure("PROFILE_UNREADABLE") {ProfileCodec.decrypt(blob,key,binding)}
    }
    @Test fun trailingAuthenticatedDataRejected() {
        val key = key(); val header = byteArrayOf(0x47,0x56,0x50,1)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key)
        cipher.updateAAD(header); cipher.updateAAD(binding.toByteArray())
        val malformed = java.nio.ByteBuffer.allocate(12).putInt(1).put(65.toByte()).putInt(1).put(66.toByte()).putShort(0).array()
        val blob = header + cipher.iv + cipher.doFinal(malformed)
        failure("PROFILE_UNREADABLE") {ProfileCodec.decrypt(blob,key,binding)}
    }
    @Test fun emptyOrOversizedNameRejected() {
        failure("PROFILE_TOO_LARGE") { ProfileCodec.encrypt(ProfilePayload("",payload.config),key(),binding) }
        failure("PROFILE_TOO_LARGE") { ProfileCodec.encrypt(ProfilePayload("z".repeat(321),payload.config),key(),binding) }
    }
}
