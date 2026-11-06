package art.graphite.vpn.domain.xray

import org.json.JSONArray
import org.json.JSONObject

/** Generates only a client proxy; no direct fallback, insecure certificate flag or arbitrary JSON merges. */
object XrayConfigGenerator {
    fun generate(profile:XrayProfile,socksPort:Int=10808,dnsServers:List<String> = listOf("1.1.1.1","1.0.0.1"),serverAddress:String?=null,socksUsername:String="",socksPassword:String=""):String {
        XrayProfileParser.validate(profile)
        require(socksPort in 1..65535){"INVALID_SOCKS_PORT"}
        require(dnsServers.size in 1..4 && dnsServers.all(::numericAddress)){"INVALID_DNS"}
        require(serverAddress==null || numericAddress(serverAddress)){"INVALID_SERVER_ADDRESS"}
        require((socksUsername.isEmpty() && socksPassword.isEmpty()) || (socksUsername.length in 1..255 && socksPassword.length in 1..255)){"INVALID_SOCKS_AUTH"}
        if (profile.protocol == "socks5") {
            val settings = JSONObject().put("address", serverAddress ?: profile.host).put("port", profile.port)
            if (profile.proxyUser.isNotEmpty()) settings.put("user", profile.proxyUser).put("pass", profile.proxyPassword)
            val outbound = JSONObject().put("tag", "proxy").put("protocol", "socks").put("settings", settings)
            return tunnelConfig(outbound, socksPort, dnsServers, socksUsername, socksPassword)
        }
        val user=JSONObject().put("id",profile.uuid).put("level",0)
        if(profile.protocol=="vless")user.put("encryption",profile.vlessEncryption).also{if(profile.flow.isNotEmpty())it.put("flow",profile.flow)}
        else user.put("alterId",0).put("security",profile.cipher)
        val stream=JSONObject().put("network",profile.network).put("security",profile.security)
        when(profile.network) {
            "ws"->stream.put("wsSettings",JSONObject().put("path",profile.path).also{if(profile.transportHost.isNotEmpty())it.put("headers",JSONObject().put("Host",profile.transportHost))})
            "grpc"->stream.put("grpcSettings",XrayTransportOptions.apply("grpc", profile.transportOptions, JSONObject().put("serviceName",profile.serviceName).put("multiMode",profile.grpcMode=="multi").also{if(profile.authority.isNotEmpty())it.put("authority",profile.authority)}))
            "xhttp"->stream.put("xhttpSettings",XrayTransportOptions.apply("xhttp", profile.transportOptions, JSONObject().put("path",profile.path).put("mode",profile.xhttpMode).also{if(profile.transportHost.isNotEmpty())it.put("host",profile.transportHost)}))
            "kcp"->stream.put("kcpSettings", XrayTransportOptions.apply("kcp", profile.transportOptions, JSONObject()))
            "httpupgrade"->stream.put("httpupgradeSettings",JSONObject().put("path",profile.path).also{if(profile.transportHost.isNotEmpty())it.put("host",profile.transportHost)})
            "tcp"->if(profile.headerType=="http")stream.put("tcpSettings",JSONObject().put("header",JSONObject().put("type","http").put("request",JSONObject().put("path",JSONArray().put(profile.path)).also{if(profile.transportHost.isNotEmpty())it.put("headers",JSONObject().put("Host",JSONArray().put(profile.transportHost)))})))
        }
        if(profile.security=="tls") {
            val tls=JSONObject().put("allowInsecure",false).put("serverName",profile.serverName.ifEmpty{profile.host})
            if(profile.tlsEch.isNotEmpty())tls.put("echConfigList",profile.tlsEch)
            if(profile.fingerprint.isNotEmpty())tls.put("fingerprint",profile.fingerprint)
            val alpn=profile.alpn.ifEmpty{if(profile.network=="grpc")listOf("h2") else emptyList()}
            if(alpn.isNotEmpty())tls.put("alpn",JSONArray(alpn))
            stream.put("tlsSettings",tls)
        }
        if(profile.security=="reality") {
            stream.put("realitySettings",JSONObject().put("serverName",profile.serverName.ifEmpty{profile.host}).put("fingerprint",profile.fingerprint.ifEmpty{"chrome"})
                .put("password",profile.realityPublicKey).put("shortId",profile.realityShortId).put("spiderX",profile.spiderX).also{if(profile.realityVerification.isNotEmpty())it.put("mldsa65Verify",profile.realityVerification)})
        }
        val server = JSONObject().put("address", serverAddress ?: profile.host).put("port", profile.port)
            .put("users", JSONArray().put(user))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", profile.protocol)
            .put("settings", JSONObject().put("vnext", JSONArray().put(server))).put("streamSettings", stream)
        return tunnelConfig(outbound, socksPort, dnsServers, socksUsername, socksPassword)
    }

    private fun tunnelConfig(outbound:JSONObject,socksPort:Int,dnsServers:List<String>,socksUsername:String,socksPassword:String):String {
        val socks=JSONObject().put("auth",if(socksUsername.isEmpty())"noauth" else "password").put("udp",true).put("ip","127.0.0.1")
        if(socksUsername.isNotEmpty())socks.put("accounts",JSONArray().put(JSONObject().put("user",socksUsername).put("pass",socksPassword)))
        return JSONObject()
            .put("log",JSONObject().put("loglevel","none"))
            .put("dns",JSONObject().put("servers",JSONArray(dnsServers)).put("queryStrategy","UseIP"))
            .put("inbounds",JSONArray().put(JSONObject().put("tag","tun").put("listen","127.0.0.1").put("port",socksPort).put("protocol","socks").put("settings",socks)))
            .put("outbounds",JSONArray().put(outbound))
            .put("routing",JSONObject().put("domainStrategy","AsIs"))
            .put("stats",JSONObject())
            .put("policy",JSONObject().put("system",JSONObject().put("statsOutboundUplink",true).put("statsOutboundDownlink",true)))
            .toString()
    }
    /** System DNS A/AAAA queries use Xray's routed DNS-over-TCP client. Other UDP stays blocked. */
    internal fun graphiteBridge(port:Int, username:String, password:String, resolvers:List<String>):String {
        require(resolvers.isNotEmpty() && resolvers.all(::numericAddress))
        val loopback = XrayProfile("socks5", "127.0.0.1", port, "", originalShareLink = "",
            proxyUser = username, proxyPassword = password)
        val config = JSONObject(generate(loopback, dnsServers = resolvers))
        val servers = resolvers.map { if (':' in it) "tcp://[$it]:53" else "tcp://$it:53" }
        config.getJSONObject("dns").put("servers", JSONArray(servers))
        config.getJSONArray("outbounds")
            .put(JSONObject().put("tag","graphite-dns").put("protocol","dns")
                .put("settings",JSONObject().put("rules",JSONArray()
                    .put(JSONObject().put("action","hijack").put("qType","1,28"))
                    .put(JSONObject().put("action","return").put("rCode",5)))))
            .put(JSONObject().put("tag","graphite-drop").put("protocol","blackhole"))
        config.getJSONObject("routing").put("rules",JSONArray()
            .put(JSONObject().put("type","field").put("inboundTag",JSONArray().put("tun"))
                .put("network","udp").put("port",53).put("outboundTag","graphite-dns"))
            .put(JSONObject().put("type","field").put("network","udp").put("outboundTag","graphite-drop")))
        return config.toString()
    }

    private fun numericAddress(value:String):Boolean {
        if(value.contains(':'))return value.matches(Regex("[0-9a-fA-F:.]+")) && runCatching{java.net.InetAddress.getByName(value).address.size==16}.getOrDefault(false)
        return value.split('.').let{it.size==4 && it.all{x->x.matches(Regex("[0-9]{1,3}")) && x.toInt() in 0..255}}
    }
}
