package art.graphite.vpn.data.profile

import org.amnezia.awg.config.Config
import art.graphite.vpn.domain.model.AwgParameters
import art.graphite.vpn.domain.model.AwgParametersException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

internal object ProfileValidation {
    fun parse(bytes: ByteArray): Config {
        if (bytes.isEmpty()) throw ProfileException("INVALID_CONFIG")
        if (bytes.size > ProfileCodec.MAX_CONFIG_BYTES) throw ProfileException("PROFILE_TOO_LARGE")
        val config = try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            AwgParameters.validate(AwgParameters.fromRawConfig(text))
            Config.parse(text.byteInputStream())
        } catch (_: AwgParametersException) { throw ProfileException("INVALID_AWG_CONFIG") }
        catch (_: Exception) { throw ProfileException("INVALID_CONFIG") }
        try {
            AwgParameters.validate(AwgParameters.fromConfig(config))
            config.peers.forEach { AwgParameters.validatePersistentKeepalive(it.persistentKeepalive.orElse("")) }
        } catch (_: AwgParametersException) { throw ProfileException("INVALID_AWG_CONFIG") }
        if (config.`interface`.addresses.isEmpty()) throw ProfileException("MISSING_ADDRESS")
        if (config.peers.isEmpty()) throw ProfileException("MISSING_PEER")
        if (config.peers.any { !it.endpoint.isPresent }) throw ProfileException("MISSING_ENDPOINT")
        if (config.peers.any { it.allowedIps.isEmpty() }) throw ProfileException("MISSING_ROUTES")
        return config
    }

    /** Validates a display name; import filename cleanup belongs to the caller so this is idempotent. */
    fun name(value: String): String {
        val name = value.trim()
        if (name.isEmpty() || name.length > 80 || name.any { it.isISOControl() }) throw ProfileException("INVALID_NAME")
        return name
    }
}
