package art.graphite.vpn.domain.xray

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/** Bounded share-link parser, not a raw Xray JSON/admin/subscription loader. */
object XrayProfileParser {
    const val MAX_LINK_BYTES=65536
    fun isShareLink(text:String)=text.trim().removePrefix("\uFEFF").trim().let{it.startsWith("vless://",true) || it.startsWith("vmess://",true) || it.startsWith("socks5://",true)}
    fun parse(text:String):XrayProfile {
        try {
            val link=text.trim().removePrefix("\uFEFF").trim()
            check(link.toByteArray(Charsets.UTF_8).size in 1..MAX_LINK_BYTES,"XRAY_LINK_SIZE")
            check(link.none{it.isISOControl()},"XRAY_CONTROL_CHARACTER")
            val protocol=link.substringBefore("://").lowercase()
            check(protocol in setOf("vless","vmess","socks5"),"XRAY_PROTOCOL")
            val result=if(protocol=="socks5") socks(link) else if(protocol=="vmess" && !link.substringAfter("://").substringBefore('?').contains('@'))legacyVmess(link) else authority(link,protocol)
            validate(result)
            return result
        }catch(e:XrayProfileException){throw e}catch(_:Exception){throw XrayProfileException("INVALID_XRAY_LINK")}
    }
    private fun socks(link: String): XrayProfile {
        val uri = URI(link)
        check(uri.rawQuery == null && uri.rawPath in setOf("", "/"), "SOCKS_URI")
        val auth = uri.rawUserInfo
        check(auth == null || ':' in auth, "SOCKS_AUTH")
        return XrayProfile(
            "socks5", host(uri.host ?: throw XrayProfileException("SOCKS_HOST")), uri.port, "",
            name = displayName(uri.rawFragment?.let(::decode)), originalShareLink = link,
            proxyUser = auth?.substringBefore(':')?.let(::decode).orEmpty(),
            proxyPassword = auth?.substringAfter(':')?.let(::decode).orEmpty(),
        )
    }

    private fun authority(link:String,protocol:String):XrayProfile {
        val uri=URI(link)
        check(uri.scheme.equals(protocol,true),"XRAY_PROTOCOL")
        check(uri.rawUserInfo!=null && !uri.rawUserInfo.contains(':'),"XRAY_UUID")
        check(uri.rawPath.isNullOrEmpty() || uri.rawPath=="/","XRAY_PATH")
        val host=host(uri.host ?: throw XrayProfileException("XRAY_HOST"))
        check(uri.port in 1..65535,"XRAY_PORT")
        val query=linkedMapOf<String,String>()
        uri.rawQuery?.takeIf{it.isNotEmpty()}?.split('&')?.forEach {
            check(it.contains('='),"XRAY_QUERY")
            val key=decode(it.substringBefore('='));val value=decode(it.substringAfter('='))
            check(query.put(key,value)==null,"XRAY_DUPLICATE_PARAMETER")
        }
        val allowed=setOf("encryption","type","security","sni","serverName","fp","pbk","sid","spx","flow","host","path","serviceName","mode","alpn","headerType","allowInsecure","insecure","authority","extra","pqv","ech","mtu","tti","uplinkCapacity","downlinkCapacity","cwndMultiplier","maxSendingWindow")
        check(query.keys.all{it in allowed},"XRAY_UNSUPPORTED_PARAMETER")
        check(query["allowInsecure"] !in setOf("1","true") && query["insecure"] !in setOf("1","true"),"XRAY_INSECURE_TLS")
        check(query["allowInsecure"] in setOf(null,"0","false") && query["insecure"] in setOf(null,"0","false"),"XRAY_INSECURE_TLS")
        val net=network(query["type"] ?: "tcp")
        check(!query.containsKey("mode") || net in setOf("grpc","xhttp"),"XRAY_UNSUPPORTED_PARAMETER")
        check(!query.containsKey("serviceName") || net=="grpc","XRAY_UNSUPPORTED_PARAMETER")
        check(!query.containsKey("authority") || net=="grpc","XRAY_UNSUPPORTED_PARAMETER")
        val encryption=query["encryption"] ?: if(protocol=="vless")"none" else "auto"
        val sn=(query["sni"] ?: query["serverName"] ?: "").let{if(it.isEmpty())it else host(it)}
        if(query.containsKey("sni") && query.containsKey("serverName"))check(query["sni"]==query["serverName"],"XRAY_AMBIGUOUS_SNI")
        return XrayProfile(protocol=protocol,host=host,port=uri.port,uuid=uuid(decode(uri.rawUserInfo)),network=net,
            security=security(query["security"] ?: "none"),name=displayName(uri.rawFragment?.let(::decode)),
            serverName=sn,fingerprint=query["fp"] ?: "",alpn=alpn(query["alpn"] ?: ""),flow=query["flow"] ?: "",
            cipher=if(protocol=="vmess")encryption else "auto",path=if(net=="grpc") "/" else query["path"].orEmpty().ifEmpty{"/"},transportHost=query["host"] ?: "",
            serviceName=query["serviceName"] ?: if(net=="grpc")query["path"].orEmpty().removePrefix("/") else "",grpcMode=if(net=="grpc")query["mode"].orEmpty().ifEmpty{"gun"} else "gun",authority=query["authority"] ?: "",
            realityPublicKey=publicKey(query["pbk"] ?: ""),realityShortId=query["sid"] ?: "",spiderX=query["spx"] ?: "/",
            xhttpMode=if(net=="xhttp")query["mode"] ?: "auto" else "auto",headerType=query["headerType"].orEmpty().ifEmpty{"none"},originalShareLink=link,
            transportOptions=XrayTransportOptions.fromLink(net, query),vlessEncryption=if(protocol=="vless")encryption else "none",
            realityVerification=query["pqv"].orEmpty(),tlsEch=query["ech"].orEmpty())
    }
    private fun legacyVmess(link:String):XrayProfile {
        val value=link.substringAfter("://")
        check(!value.contains('?') && !value.contains('#'),"XRAY_VM_FORMAT")
        val bytes=base64(value)
        val json=try{utf8(bytes)}finally{bytes.fill(0)}
        check(json.length<=MAX_LINK_BYTES,"XRAY_LINK_SIZE");boundDepth(json)
        val o=JSONObject(json)
        val allowed=setOf("v","ps","add","port","id","aid","scy","net","type","host","path","tls","sni","alpn","fp","allowInsecure","insecure","mode","serviceName","authority")
        check(o.keys().asSequence().all{it in allowed},"XRAY_UNSUPPORTED_PARAMETER")
        check(o.optString("v","2") in setOf("","2"),"XRAY_VM_VERSION")
        check(o.optString("aid","0") in setOf("","0"),"XRAY_VM_LEGACY_AID")
        check(o.optString("allowInsecure","false") in setOf("false","0","") && o.optString("insecure","false") in setOf("false","0",""),"XRAY_INSECURE_TLS")
        val net=network(o.optString("net","tcp"));val type=o.optString("type","none")
        check(net=="tcp" || type in setOf("","none","gun","multi"),"XRAY_UNSUPPORTED_HEADER")
        val security=when(o.optString("tls","")){"","none"->"none";"tls"->"tls";else->throw XrayProfileException("XRAY_SECURITY")}
        return XrayProfile(protocol="vmess",host=host(o.getString("add")),port=o.getString("port").toInt(),uuid=uuid(o.getString("id")),network=net,
            security=security,name=displayName(o.optString("ps","")),serverName=o.optString("sni","").let{if(it.isEmpty())it else host(it)},fingerprint=o.optString("fp",""),alpn=alpn(o.optString("alpn","")),
            cipher=o.optString("scy","auto").ifEmpty{"auto"},path=o.optString("path","/").ifEmpty{"/"},transportHost=o.optString("host",""),
            serviceName=o.optString("serviceName",if(net=="grpc")o.optString("path","") else ""),grpcMode=o.optString("mode",if(type=="multi")"multi" else "gun"),authority=o.optString("authority",""),
            headerType=if(net=="tcp")type.ifEmpty{"none"} else "none",originalShareLink=link)
    }
    internal fun validate(p:XrayProfile) {
        if (p.protocol == "socks5") {
            host(p.host)
            check(p.port in 1..65535, "SOCKS_PORT")
            check(p.proxyUser.isEmpty() == p.proxyPassword.isEmpty(), "SOCKS_AUTH")
            for (value in listOf(p.proxyUser, p.proxyPassword)) {
                check(value.toByteArray(Charsets.UTF_8).size <= 255 && value.none(Char::isISOControl), "SOCKS_AUTH")
            }
            return
        }
        check(p.protocol in setOf("vless","vmess"),"XRAY_PROTOCOL");host(p.host);uuid(p.uuid)
        check(p.port in 1..65535,"XRAY_PORT")
        check(p.security in setOf("none","tls","reality"),"XRAY_SECURITY")
        check(p.flow in setOf("","xtls-rprx-vision","xtls-rprx-vision-udp443"),"XRAY_FLOW")
        check(p.cipher in setOf("auto","aes-128-gcm","chacha20-poly1305","none","zero"),"XRAY_CIPHER")
        check(p.headerType in setOf("none","http"),"XRAY_UNSUPPORTED_HEADER")
        check(p.network=="tcp" || p.headerType=="none","XRAY_UNSUPPORTED_HEADER")
        listOf(p.path,p.transportHost,p.serviceName,p.authority,p.spiderX).forEach{check(it.length<=2048 && it.none(Char::isISOControl),"XRAY_TRANSPORT_VALUE")}
        check(p.path.startsWith('/'),"XRAY_PATH")
        check(p.serverName.length<=253 && p.serverName.none{it.isWhitespace() || it.isISOControl()},"XRAY_SNI")
        if(p.serverName.isNotEmpty())host(p.serverName)
        check(p.fingerprint.isEmpty() || p.fingerprint.matches(Regex("[A-Za-z0-9_-]{1,64}")),"XRAY_FINGERPRINT")
        check(p.grpcMode in setOf("gun","multi"),"XRAY_GRPC_MODE")
        check(p.xhttpMode in setOf("auto","packet-up","stream-up","stream-one"),"XRAY_XHTTP_MODE")
        check(p.transportHost.isEmpty() || p.transportHost.none{it.isWhitespace()},"XRAY_HOST_HEADER")
        if(p.network=="grpc" && p.security=="tls" && p.alpn.isNotEmpty())check(p.alpn.first()=="h2","XRAY_GRPC_ALPN")
        if(p.protocol=="vless")validateVlessEncryption(p.vlessEncryption)
        check(p.tlsEch.isEmpty() || (p.security=="tls" && p.tlsEch.length<=8192 && p.tlsEch.none(Char::isISOControl)),"XRAY_ECH")
        if(p.protocol=="vless" && p.security=="none" && p.vlessEncryption=="none")check(privateHost(p.host),"XRAY_VLESS_REQUIRES_TLS")
        if(p.protocol=="vmess" && p.security=="none" && p.cipher in setOf("none","zero"))check(privateHost(p.host),"XRAY_VM_REQUIRES_ENCRYPTION")
        if(p.security=="reality") {
            check(p.protocol=="vless" && p.network in setOf("tcp","grpc","xhttp"),"XRAY_REALITY_TRANSPORT")
            val key=base64(p.realityPublicKey)
            try{check(key.size==32,"XRAY_REALITY_KEY")}finally{key.fill(0)}
            check(p.realityShortId.matches(Regex("[0-9a-fA-F]{0,16}")) && p.realityShortId.length%2==0,"XRAY_REALITY_SHORT_ID")
            if(p.realityVerification.isNotEmpty()) {
                val key=base64(p.realityVerification)
                try { check(key.size==1952 && Base64.getUrlEncoder().withoutPadding().encodeToString(key)==p.realityVerification,"XRAY_REALITY_VERIFY") } finally { key.fill(0) }
            }
            check(p.spiderX.startsWith('/'),"XRAY_REALITY_SPIDER")
        } else check(p.realityPublicKey.isEmpty() && p.realityShortId.isEmpty() && p.spiderX=="/" && p.realityVerification.isEmpty(),"XRAY_REALITY_WITHOUT_SECURITY")
        if(p.flow.isNotEmpty())check(p.protocol=="vless" && p.network=="tcp" && p.headerType=="none" && p.security in setOf("tls","reality"),"XRAY_VISION_TRANSPORT")
    }
    internal fun privateHost(value:String):Boolean {
        if(value=="localhost" || value.endsWith(".localhost"))return true
        if(!value.contains(':') && !value.matches(Regex("[0-9.]+")))return false
        val a=try{InetAddress.getByName(value)}catch(_:Exception){return false}
        if(a.isAnyLocalAddress)return false
        return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || (a.address.size==16 && (a.address[0].toInt() and 0xfe)==0xfc)
    }
    internal fun host(value:String):String {
        val v=value.removePrefix("[").removeSuffix("]")
        check(v.length in 1..253 && v.none{it.isWhitespace() || it.isISOControl() || it in "/\\%@?#"},"XRAY_HOST")
        if(v.contains(':')) {
            check(v.matches(Regex("[0-9a-fA-F:.]+")),"XRAY_HOST")
            check(InetAddress.getByName(v).address.size==16,"XRAY_HOST");return v.lowercase()
        }
        if(v.matches(Regex("[0-9.]+")))check(v.split('.').let{it.size==4 && it.all{x->x.matches(Regex("[0-9]{1,3}")) && x.toInt() in 0..255}},"XRAY_HOST")
        val ascii=IDN.toASCII(v,IDN.USE_STD3_ASCII_RULES).lowercase()
        check(ascii.trimEnd('.').split('.').all{it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() && it.all{c->c.isLetterOrDigit() || c=='-'}},"XRAY_HOST")
        return ascii
    }
    internal fun publicKey(value:String):String {
        if(value.isEmpty())return value
        val bytes=base64(value)
        return try{check(bytes.size==32,"XRAY_REALITY_KEY");Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}finally{bytes.fill(0)}
    }
    internal fun uuid(value:String):String {
        check(value.length==36,"XRAY_UUID");val id=UUID.fromString(value).toString();check(id.equals(value,true),"XRAY_UUID");return id
    }
    internal fun security(value:String)=when(value.lowercase()){ "https"->"tls";""->"none";else->value.lowercase() }

    internal fun network(value:String)=when(value.lowercase()){
        "tcp","raw"->"tcp"
        "ws","websocket"->"ws"
        "grpc","gun"->"grpc"
        "xhttp","splithttp"->"xhttp"
        "kcp","mkcp"->"kcp"
        "httpupgrade"->"httpupgrade"
        "http","https","h2","h3","quic"->throw XrayProfileException("XRAY_REMOVED_TRANSPORT")
        else->throw XrayProfileException("XRAY_TRANSPORT")
    }

    private fun validateVlessEncryption(value:String) {
        if(value=="none")return
        val parts=value.split('.')
        check(parts.size>=4 && parts[0]=="mlkem768x25519plus" &&
            parts[1] in setOf("native","xorpub","random") && parts[2] in setOf("0rtt","1rtt"),"XRAY_UNSUPPORTED_ENCRYPTION")
        var hasKey=false
        for(part in parts.drop(3)) {
            if(part.length<20) {
                check(part.isNotEmpty() && part.all { it in '0'..'9' || it=='-' },"XRAY_UNSUPPORTED_ENCRYPTION")
            } else {
                val key=Base64.getUrlDecoder().decode(part)
                try { check(key.size in setOf(32,1184) && Base64.getUrlEncoder().withoutPadding().encodeToString(key)==part,"XRAY_UNSUPPORTED_ENCRYPTION") } finally { key.fill(0) }
                hasKey=true
            }
        }
        check(hasKey,"XRAY_UNSUPPORTED_ENCRYPTION")
    }
    private fun displayName(value:String?)=value?.trim()?.takeIf{it.isNotEmpty()}?.also{check(it.length<=80 && it.none(Char::isISOControl),"XRAY_NAME")}
    private fun alpn(value:String):List<String> =if(value.isEmpty())emptyList() else value.split(',').also{check(it.size<=8 && it.all{x->x.length in 1..32 && x.none{c->c.isWhitespace() || c.isISOControl()}},"XRAY_ALPN")}
    private fun decode(value:String)=URLDecoder.decode(value.replace("+","%2B"),"UTF-8")
    private fun base64(value:String):ByteArray {
        check(value.length in 1..MAX_LINK_BYTES && value.matches(Regex("[A-Za-z0-9_+/=-]+")),"XRAY_BASE64")
        return try{Base64.getUrlDecoder().decode(value)}catch(_:IllegalArgumentException){Base64.getDecoder().decode(value)}
    }
    private fun utf8(bytes:ByteArray)=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private val jsonLiteral = Regex("""(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?=[ \t\r\n,}\]]|$)""")

    internal fun boundDepth(value: String, maxDepth: Int = 16) {
        var depth = 0
        var index = 0
        while (index < value.length) {
            when (value[index++]) {
                ' ', '\t', '\r', '\n', ',', ':' -> Unit
                '[', '{' -> check(++depth <= maxDepth, "XRAY_JSON_DEPTH")
                ']', '}' -> check(--depth >= 0, "XRAY_JSON_DEPTH")
                '"' -> {
                    var closed = false
                    while (index < value.length) {
                        val char = value[index++]
                        if (char == '"') {
                            closed = true
                            break
                        }
                        check(char >= ' ', "XRAY_JSON_SYNTAX")
                        if (char == '\\') {
                            check(index < value.length, "XRAY_JSON_SYNTAX")
                            when (value[index++]) {
                                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                                'u' -> {
                                    check(index + 4 <= value.length, "XRAY_JSON_SYNTAX")
                                    repeat(4) {
                                        check(value[index++] in "0123456789abcdefABCDEF", "XRAY_JSON_SYNTAX")
                                    }
                                }
                                else -> check(false, "XRAY_JSON_SYNTAX")
                            }
                        }
                    }
                    check(closed, "XRAY_JSON_SYNTAX")
                }
                else -> {
                    val literal = jsonLiteral.matchAt(value, index - 1)
                        ?: throw XrayProfileException("XRAY_JSON_SYNTAX")
                    index = literal.range.last + 1
                }
            }
        }
        check(depth == 0, "XRAY_JSON_DEPTH")
    }
    private fun check(condition:Boolean,code:String){if(!condition)throw XrayProfileException(code)}
}
