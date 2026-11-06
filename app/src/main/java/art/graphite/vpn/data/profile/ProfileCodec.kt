package art.graphite.vpn.data.profile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never contains parser input, config contents, file paths, keys, or a nested cause. */
class ProfileException internal constructor(val safeCode: String) : Exception(safeCode)
enum class ProfileSource(val code: Int) {
    UNKNOWN(0), LINK(1), PARAMETERS(2), JSON(3), FILE(4)
}

internal data class ProfilePayload(val name: String, val config: ByteArray, val source: ProfileSource = ProfileSource.UNKNOWN)

/** Versioned AES-GCM envelope; the provider supplies a fresh random IV on every encryption. */
internal object ProfileCodec {
    const val MAX_CONFIG_BYTES = 64 * 1024
    const val MAX_BLOB_BYTES = MAX_CONFIG_BYTES + 1024
    private val header = byteArrayOf(0x47, 0x56, 0x50, 1)
    private const val IV_SIZE = 12
    private const val MAX_NAME_BYTES = 320

    fun encrypt(payload: ProfilePayload, key: SecretKey, binding: String): ByteArray = safe {
        val name = payload.name.toByteArray(Charsets.UTF_8)
        if (name.isEmpty() || name.size > MAX_NAME_BYTES || payload.config.isEmpty() || payload.config.size > MAX_CONFIG_BYTES)
            throw ProfileException("PROFILE_TOO_LARGE")
        val plain = ByteArrayOutputStream().use { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(name.size); data.write(name)
                data.writeInt(payload.config.size); data.write(payload.config)
                if (payload.source != ProfileSource.UNKNOWN) data.writeByte(payload.source.code)
            }
            out.toByteArray()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(header)
            cipher.updateAAD(binding.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            if (iv.size != IV_SIZE) throw ProfileException("STORAGE_UNAVAILABLE")
            header + iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }

    fun decrypt(blob: ByteArray, key: SecretKey, binding: String): ProfilePayload = safe {
        if (blob.size !in (header.size + IV_SIZE + 16 + 10)..MAX_BLOB_BYTES ||
            !blob.copyOfRange(0, header.size).contentEquals(header)) throw ProfileException("PROFILE_UNREADABLE")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(header.size, header.size + IV_SIZE)))
        } catch (_: Exception) { throw ProfileException("STORAGE_KEY_UNAVAILABLE") }
        cipher.updateAAD(header)
        cipher.updateAAD(binding.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(blob, header.size + IV_SIZE, blob.size - header.size - IV_SIZE)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { data ->
                val nameSize = data.readInt()
                if (nameSize !in 1..MAX_NAME_BYTES || nameSize > data.available() - 4) throw ProfileException("PROFILE_UNREADABLE")
                val name = ByteArray(nameSize).also(data::readFully).toString(Charsets.UTF_8)
                val configSize = data.readInt()
                if (configSize !in 1..MAX_CONFIG_BYTES || data.available() - configSize !in 0..1) throw ProfileException("PROFILE_UNREADABLE")
                val config = ByteArray(configSize).also(data::readFully)
                val source = if (data.available() == 0) ProfileSource.UNKNOWN
                    else {
                        val code = data.readUnsignedByte()
                        ProfileSource.entries.firstOrNull { it.code == code } ?: ProfileSource.UNKNOWN
                    }
                ProfilePayload(name, config, source)
            }
        } finally { plain.fill(0) }
    }

    private inline fun <T> safe(block: () -> T): T = try { block() }
    catch (e: ProfileException) { throw e }
    catch (_: Exception) { throw ProfileException("PROFILE_UNREADABLE") }
}
