package art.graphite.vpn.domain.xray

import java.net.URLEncoder

internal object XrayShareLink {
    fun encode(profile: XrayProfile, name: String): String? = runCatching {
        if (profile.protocol == "socks5") {
            val auth = if (profile.proxyUser.isEmpty()) ""
                else "${escape(profile.proxyUser)}:${escape(profile.proxyPassword)}@"
            val link = "socks5://$auth${profile.endpoint}#${escape(name)}"
            val parsed = XrayProfileParser.parse(link)
            return@runCatching link.takeIf { XrayConfigGenerator.generate(parsed) == XrayConfigGenerator.generate(profile) }
        }
        if (profile.protocol !in setOf("vless", "vmess")) return@runCatching null
        val query = linkedMapOf(
            "type" to profile.network, "security" to profile.security,
            "encryption" to if (profile.protocol == "vless") profile.vlessEncryption else profile.cipher,
            "sni" to profile.serverName, "fp" to profile.fingerprint,
            "alpn" to profile.alpn.joinToString(","), "flow" to profile.flow,
            "host" to profile.transportHost, "path" to profile.path,
            "headerType" to profile.headerType, "pbk" to profile.realityPublicKey,
            "sid" to profile.realityShortId, "spx" to profile.spiderX,
            "pqv" to profile.realityVerification, "ech" to profile.tlsEch,
        )
        when (profile.network) {
            "grpc" -> {
                query["mode"] = profile.grpcMode
                query["serviceName"] = profile.serviceName
                query["authority"] = profile.authority
            }
            "xhttp" -> {
                query["mode"] = profile.xhttpMode
                query["extra"] = profile.transportOptions
            }
            "kcp" -> if (profile.transportOptions.isNotEmpty()) {
                val options = org.json.JSONObject(profile.transportOptions)
                for (key in listOf("mtu", "tti", "uplinkCapacity", "downlinkCapacity", "cwndMultiplier", "maxSendingWindow")) {
                    if (options.has(key)) query[key] = options.getString(key)
                }
            }
        }
        val parameters = query.filterValues(String::isNotEmpty).entries.joinToString("&") {
            "${it.key}=${escape(it.value)}"
        }
        val link = "${profile.protocol}://${profile.uuid}@${profile.endpoint}?$parameters#${escape(name)}"
        val parsed = XrayProfileParser.parse(link)
        // A JSON-only option must never silently disappear when copying a link.
        link.takeIf { XrayConfigGenerator.generate(parsed) == XrayConfigGenerator.generate(profile) }
    }.getOrNull()

    private fun escape(value: String) =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
