package art.graphite.vpn.domain.extra

import art.graphite.vpn.domain.xray.XrayProfileParser
import org.json.JSONObject
import java.util.Base64

internal class IkeProfile private constructor(
    val server: String,
    val identity: String,
    private val secret: String,
    val document: String,
) {
    fun key(): ByteArray = Base64.getDecoder().decode(secret)
    override fun toString() = "IkeProfile(redacted)"

    companion object {
        fun parseOrNull(text: String): IkeProfile? {
            if (!text.startsWith('{')) return null
            XrayProfileParser.boundDepth(text, 32)
            val json = JSONObject(text)
            if (json.optString("protocol") != "ikev2") return null
            require(json.keys().asSequence().toSet() == setOf("protocol", "server", "identity", "psk"))
            val server = XrayProfileParser.host(json.getString("server"))
            val identity = json.getString("identity")
            require(identity.length in 1..255 && identity.none(Char::isISOControl))
            val profile = IkeProfile(server, identity, json.getString("psk"), json.toString())
            val key = profile.key()
            try { require(key.size in 16..128) } finally { key.fill(0) }
            return profile
        }
    }
}

internal class OpenVpnDocument private constructor(val document: String, val endpoints: List<String>) {
    override fun toString() = "OpenVpnDocument(redacted)"

    companion object {
        fun parseOrNull(text: String): OpenVpnDocument? {
            val lines = text.lineSequence().map(String::trim).toList()
            if ("client" !in lines) return null
            val endpoints = lines.mapNotNull { line ->
                val words = line.split(Regex("\\s+"), limit = 4)
                if (words.first() != "remote") return@mapNotNull null
                val host = XrayProfileParser.host(words.getOrElse(1) { "" })
                val port = words.getOrNull(2)?.toInt() ?: 1194
                require(port in 1..65535)
                if (host.contains(':')) "[$host]:$port" else "$host:$port"
            }
            require(endpoints.isNotEmpty())
            return OpenVpnDocument(text, endpoints.distinct())
        }
    }
}
