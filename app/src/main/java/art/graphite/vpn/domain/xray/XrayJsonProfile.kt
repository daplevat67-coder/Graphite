package art.graphite.vpn.domain.xray

import org.json.JSONObject
import org.json.JSONTokener

/** Imports one supported server and rebuilds the Android tunnel from validated fields. */
internal object XrayJsonProfile {
    fun parseOrNull(text: String): XrayProfile? {
        if (!text.startsWith('{')) return null
        XrayProfileParser.boundDepth(text, maxDepth = 32)
        val reader = JSONTokener(text)
        val root = reader.nextValue() as JSONObject
        require(reader.nextClean() == '\u0000')
        val outbound = if (root.has("outbounds")) {
            val outbounds = root.getJSONArray("outbounds")
            val supported = (0 until outbounds.length()).map(outbounds::getJSONObject)
                .filter { it.optString("protocol") in setOf("vless", "vmess", "socks") }
            require(supported.size == 1)
            supported.single()
        } else {
            if (root.optString("protocol") !in setOf("vless", "vmess", "socks")) return null
            root
        }
        require(!outbound.has("proxySettings"))
        val protocol = outbound.getString("protocol")
        val settings = outbound.getJSONObject("settings")
        if (protocol == "socks") {
            val server = if (settings.has("servers")) settings.singleObject("servers") else settings
            val user = if (server.has("users")) server.singleObject("users") else server
            val stream = outbound.optJSONObject("streamSettings")
            require(stream == null || stream.length() == 0)
            return XrayProfile(
                protocol = "socks5", host = XrayProfileParser.host(server.getString("address")),
                port = server.getString("port").toInt(), uuid = "", originalShareLink = text,
                proxyUser = user.optString("user"), proxyPassword = user.optString("pass"),
            ).also(XrayProfileParser::validate)
        }
        val server = if (settings.has("vnext")) settings.singleObject("vnext") else settings
        val user = if (server.has("users")) server.singleObject("users") else server
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        require(stream.optJSONObject("sockopt")?.has("dialerProxy") != true)
        val network = XrayProfileParser.network(stream.optString("network", "tcp"))
        val security = XrayProfileParser.security(stream.optString("security", "none"))
        val transport = stream.optJSONObject(when (network) {
            "kcp" -> "kcpSettings"
            "tcp" -> if (stream.has("rawSettings")) "rawSettings" else "tcpSettings"
            "xhttp" -> if (stream.has("splithttpSettings")) "splithttpSettings" else "xhttpSettings"
            else -> "${network}Settings"
        }) ?: JSONObject()
        val header = transport.optJSONObject("header")
        val request = header?.optJSONObject("request")
        val tls = if (stream.has("tlsSettings")) stream.getJSONObject("tlsSettings") else JSONObject()
        val supportedTlsOptions = setOf("allowInsecure", "serverName", "fingerprint", "alpn", "echConfigList")
        require(tls.keys().asSequence().all { it in supportedTlsOptions }) { "UNSUPPORTED_TLS_OPTION" }
        val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
        require(!tls.has("allowInsecure") || tls.getBoolean("allowInsecure") == false)
        require(user.optString("alterId", "0") == "0")
        val name = root.optString("remarks").ifBlank { outbound.optString("tag") }
            .takeIf { it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl) }
        val secure = if (security == "reality") reality else tls
        return XrayProfile(
            protocol = protocol,
            host = XrayProfileParser.host(server.getString("address")),
            port = server.getString("port").toInt(),
            uuid = XrayProfileParser.uuid(user.getString("id")),
            network = network,
            security = security,
            name = name,
            serverName = secure.optString("serverName"),
            fingerprint = secure.optString("fingerprint"),
            alpn = tls.optJSONArray("alpn")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            flow = user.optString("flow"),
            cipher = if (protocol == "vmess") user.optString("security", "auto") else "auto",
            path = request?.optJSONArray("path")?.optString(0)?.takeIf(String::isNotEmpty)
                ?: transport.optString("path", "/"),
            transportHost = transport.optString("host").ifEmpty {
                transport.optJSONObject("headers")?.optString("Host", "").orEmpty().ifEmpty {
                    request?.optJSONObject("headers")?.optJSONArray("Host")?.optString(0).orEmpty()
                }
            },
            serviceName = transport.optString("serviceName"),
            grpcMode = if (transport.optBoolean("multiMode")) "multi" else "gun",
            authority = transport.optString("authority"),
            realityPublicKey = reality.optString("password", reality.optString("publicKey")),
            realityShortId = reality.optString("shortId"),
            spiderX = reality.optString("spiderX", "/"),
            xhttpMode = if (network == "xhttp") transport.optString("mode", "auto") else "auto",
            headerType = header?.optString("type", "none") ?: "none",
            originalShareLink = text,
            transportOptions = XrayTransportOptions.fromJson(network, transport),
            vlessEncryption = if (protocol == "vless") user.optString("encryption", "none") else "none",
            realityVerification = reality.optString("mldsa65Verify"),
            tlsEch = tls.optString("echConfigList"),
        ).also {
            require(it.alpn.size <= 8 && it.alpn.all { item ->
                item.length in 1..32 && item.none { char -> char.isWhitespace() || char.isISOControl() }
            })
            XrayProfileParser.validate(it)
        }
    }

    private fun JSONObject.singleObject(key: String): JSONObject {
        val array = getJSONArray(key)
        require(array.length() == 1)
        return array.getJSONObject(0)
    }
}
