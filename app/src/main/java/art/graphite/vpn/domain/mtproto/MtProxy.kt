package art.graphite.vpn.domain.mtproto

import art.graphite.vpn.domain.xray.XrayProfileParser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

internal class MtProxy private constructor(
    val host: String,
    val port: Int,
    private val secret: String,
) {
    val endpoint get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    val link get() = "tg://proxy?server=${encode(host)}&port=$port&secret=${encode(secret)}"

    override fun toString() = "MtProxy(redacted)"

    companion object {
        fun matches(text: String): Boolean = text.startsWith("tg://proxy?", true) ||
            text.startsWith("https://t.me/proxy?", true) || text.startsWith("http://t.me/proxy?", true) ||
            text.startsWith("t.me/proxy?", true)

        fun parse(text: String): MtProxy {
            require(text.length <= 4096 && matches(text))
            val uri = URI(if (text.startsWith("t.me/", true)) "https://$text" else text)
            require(uri.rawFragment == null && uri.rawUserInfo == null && uri.port == -1)
            val fields = mutableMapOf<String, String>()
            for (entry in requireNotNull(uri.rawQuery).split('&')) {
                require(entry.contains('='))
                val key = decode(entry.substringBefore('='))
                require(fields.put(key, decode(entry.substringAfter('='))) == null)
            }
            require(fields.keys == setOf("server", "port", "secret"))
            val host = XrayProfileParser.host(fields.getValue("server"))
            val port = fields.getValue("port").toInt()
            require(port in 1..65535)
            val secret = fields.getValue("secret")
            require(secret.length in 22..1024)
            val bytes = if (secret.length % 2 == 0 && secret.matches(Regex("[0-9a-fA-F]+"))) {
                ByteArray(secret.length / 2) { i -> secret.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } else Base64.getUrlDecoder().decode(secret)
            try {
                require(bytes.size == 16 ||
                    (bytes.size == 17 && bytes[0] == 0xdd.toByte()) ||
                    (bytes.size > 17 && bytes[0] == 0xee.toByte()))
            } finally {
                bytes.fill(0)
            }
            return MtProxy(host, port, secret)
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }
}
