package art.graphite.vpn.domain.graphite

import art.graphite.vpn.domain.xray.XrayProfileParser
import java.net.URI
import java.util.Base64

internal class GraphiteProfile private constructor(val host: String, val port: Int, val link: String) {
    val endpoint get() = if (':' in host) "[$host]:$port" else "$host:$port"
    fun token(): ByteArray = Base64.getUrlDecoder().decode(URI(link).rawUserInfo)
    override fun toString() = "GraphiteProfile(redacted)"

    companion object {
        fun matches(text: String) = text.startsWith("graphite://", ignoreCase = true)
        fun parse(text: String): GraphiteProfile {
            require(text.length <= 512 && matches(text))
            val uri = URI(text)
            require(uri.rawPath in setOf("", "/") && uri.rawQuery == null && uri.rawFragment == null)
            val host = XrayProfileParser.host(requireNotNull(uri.host))
            require(uri.port in 1..65535)
            val encoded = requireNotNull(uri.rawUserInfo)
            require(encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
            val key = Base64.getUrlDecoder().decode(encoded)
            try {
                require(key.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(key) == encoded)
            } finally { key.fill(0) }
            return GraphiteProfile(host, uri.port, text)
        }
    }
}
