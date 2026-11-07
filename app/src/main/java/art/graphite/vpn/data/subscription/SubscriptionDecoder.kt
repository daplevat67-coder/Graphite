package art.graphite.vpn.data.subscription

import art.graphite.vpn.data.importing.*
import art.graphite.vpn.domain.xray.XrayProfileParser
import java.net.URI
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray

class SubscriptionEntry(val name: String, val bytes: ByteArray) {
    override fun toString() = "SubscriptionEntry(redacted)"
}
object SubscriptionDecoder {
    const val MAX_SERVERS = 200
    fun url(value: String): URI {
        require(value.length <= 4096 && value.none { it.isISOControl() })
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535))
        return uri
    }
    fun decode(body: String): List<SubscriptionEntry> {
        require(body.length in 1..262144)
        var text = body.trim().removePrefix("\uFEFF").trim()
        if (!text.contains("://") && !text.startsWith('{') && !text.startsWith('[')) {
            val compact = text.filterNot { it.isWhitespace() }
            require(compact.matches(Regex("[A-Za-z0-9_+/=-]+")))
            val bytes = try { Base64.getDecoder().decode(compact) } catch (_: IllegalArgumentException) { Base64.getUrlDecoder().decode(compact) }
            try { text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().trim() }
            finally { bytes.fill(0) }
        }
        val inputs = if (text.startsWith('[') && !text.startsWith("[Interface]")) {
            XrayProfileParser.boundDepth(text, 32)
            val array = JSONArray(text)
            require(array.length() in 1..MAX_SERVERS)
            (0 until array.length()).map { i ->
                val item = array.get(i)
                require(item is String || item is org.json.JSONObject)
                item.toString()
            }
        } else if (text.startsWith('{') || text.startsWith("[Interface]")) listOf(text)
        else text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        require(inputs.size in 1..MAX_SERVERS)
        val entries = mutableListOf<SubscriptionEntry>()
        try {
            for (value in inputs.distinct()) {
                val payload = ImportPayloadDecoder.decode(value) as? ConfigPayload ?: error("INVALID_SUBSCRIPTION")
                if (payload.format == ImportFormat.GRAPHITE) { payload.config.fill(0); error("DISABLED_PROTOCOL") }
                val name = payload.name?.takeIf { it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl) } ?: "Сервер ${entries.size + 1}"
                entries += SubscriptionEntry(name, payload.config)
            }
            require(entries.isNotEmpty())
            return entries
        } catch (failure: Exception) { entries.forEach { it.bytes.fill(0) }; throw failure }
    }
}
