package art.graphite.vpn.data.importing

import art.graphite.vpn.data.profile.ProfileValidation
import art.graphite.vpn.domain.xray.XrayProfileParser
import art.graphite.vpn.domain.xray.XrayProfileException
import art.graphite.vpn.domain.xray.XrayJsonProfile
import art.graphite.vpn.domain.mtproto.MtProxy
import art.graphite.vpn.domain.graphite.GraphiteProfile
import art.graphite.vpn.domain.extra.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.Inflater
import org.json.JSONObject

sealed interface ImportPayload {
    /** The caller owns this secret buffer and must wipe it after import/cancellation. */
    class ConfigPayload(val config:ByteArray,val name:String?,val format:ImportFormat):ImportPayload {
        override fun toString()="ConfigPayload(redacted)"
    }
    /** Classification only. No network I/O is performed by the decoder. */
    class HttpsConfigLink(val uri:URI):ImportPayload { override fun toString()="HttpsConfigLink(redacted)" }
    class Unsupported(val reason:ImportRejection):ImportPayload { override fun toString()="Unsupported($reason)" }
}
typealias ConfigPayload=ImportPayload.ConfigPayload
typealias HttpsConfigLink=ImportPayload.HttpsConfigLink
typealias Unsupported=ImportPayload.Unsupported
enum class ImportFormat { RAW_CONFIG, BASE64_CONFIG, AMNEZIA, XRAY, MTPROTO, IKEV2, OPENVPN, GRAPHITE }
enum class ImportRejection { EMPTY, TOO_LARGE, INVALID_DATA, UNSUPPORTED_SCHEME, UNSUPPORTED_PROTOCOL, UNSUPPORTED_AMNEZIA, MULTIPART_QR, LEGACY_XRAY_TRANSPORT }

/** Offline payload decoder. These limits apply before Base64, inflation, JSON and profile parsing. */
object ImportPayloadDecoder {
    const val MAX_INPUT_CHARS=262144
    const val MAX_CONFIG_BYTES=65536
    const val MAX_COMPRESSED_BYTES=65536
    const val MAX_JSON_BYTES=1048576
    private class Rejected(val reason:ImportRejection):Exception()
    fun decode(value:String):ImportPayload {
        if(value.length>MAX_INPUT_CHARS)return Unsupported(ImportRejection.TOO_LARGE)
        val text=value.trim().removePrefix("\uFEFF").trim()
        if(text.isEmpty())return Unsupported(ImportRejection.EMPTY)
        return try {
            when {
                XrayProfileParser.isShareLink(text)-> {
                    val profile=XrayProfileParser.parse(text)
                    ConfigPayload(profile.originalShareLink.toByteArray(Charsets.UTF_8),profile.name,ImportFormat.XRAY)
                }
                GraphiteProfile.matches(text) -> {
                    val profile = GraphiteProfile.parse(text)
                    ConfigPayload(profile.link.toByteArray(Charsets.UTF_8), "Graphite Tunnel", ImportFormat.GRAPHITE)
                }
                MtProxy.matches(text) -> {
                    val proxy = MtProxy.parse(text)
                    ConfigPayload(proxy.link.toByteArray(Charsets.UTF_8), "MTProto Proxy", ImportFormat.MTPROTO)
                }
                looksLikeConfig(text)->config(text,null,ImportFormat.RAW_CONFIG)
                text.startsWith("https://",true)->https(text)
                text.startsWith("wireguard://",true) || text.startsWith("awg://",true)-> {
                    val bytes=base64(text.substringAfter("://"))
                    try{config(utf8(bytes),null,ImportFormat.BASE64_CONFIG)}finally{bytes.fill(0)}
                }
                text.startsWith("vpn://",true)-> {
                    val bytes=base64(text.substringAfter("://"))
                    try{amneziaBytes(bytes)}finally{bytes.fill(0)}
                }
                Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(text)->Unsupported(
                    if(text.substringBefore("://").lowercase() in setOf("trojan","ss","ssd","socks","socks5","happ","incy"))ImportRejection.UNSUPPORTED_PROTOCOL else ImportRejection.UNSUPPORTED_SCHEME)
                text.startsWith('{') -> {
                    if (text.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) amneziaJson(text)
                    else IkeProfile.parseOrNull(text)?.let {
                        ConfigPayload(it.document.toByteArray(Charsets.UTF_8), "IKEv2", ImportFormat.IKEV2)
                    } ?: XrayJsonProfile.parseOrNull(text)?.let {
                        ConfigPayload(text.toByteArray(Charsets.UTF_8), it.name, ImportFormat.XRAY)
                    } ?: amneziaJson(text)
                }
                else -> {
                    OpenVpnDocument.parseOrNull(text)?.let {
                        if (text.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) throw Rejected(ImportRejection.TOO_LARGE)
                        return ConfigPayload(it.document.toByteArray(Charsets.UTF_8), "OpenVPN", ImportFormat.OPENVPN)
                    }
                    val bytes=base64(text)
                    try {
                        if(bytes.size>=8 && (bytes[0].toInt() and 255)==7 && (bytes[1].toInt() and 255)==192) {
                            val total=bytes[2].toInt() and 255;val index=bytes[3].toInt() and 255
                            val length=unsignedInt(bytes,4)
                            if(total<1 || index>=total || length!=bytes.size-8L)throw Rejected(ImportRejection.INVALID_DATA)
                            if(total!=1)throw Rejected(ImportRejection.MULTIPART_QR)
                            if(index!=0)throw Rejected(ImportRejection.INVALID_DATA)
                            val payload=bytes.copyOfRange(8,bytes.size)
                            try{amneziaBytes(payload)}finally{payload.fill(0)}
                        } else amneziaBytes(bytes)
                    }finally{bytes.fill(0)}
                }
            }
        }catch(e:Rejected){Unsupported(e.reason)}
        catch(e:XrayProfileException){
            Unsupported(if(e.safeCode=="XRAY_REMOVED_TRANSPORT") ImportRejection.LEGACY_XRAY_TRANSPORT else ImportRejection.INVALID_DATA)
        }catch(_:Exception){Unsupported(ImportRejection.INVALID_DATA)}
    }
    private fun https(text:String):ImportPayload {
        val uri=URI(text)
        if(uri.scheme.lowercase()!="https" || uri.host.isNullOrBlank() || uri.rawUserInfo!=null || uri.rawFragment!=null || uri.port !in -1..65535 || uri.port==0 || text.any{it.isISOControl()})throw Rejected(ImportRejection.INVALID_DATA)
        return HttpsConfigLink(uri)
    }
    private fun base64(text:String):ByteArray {
        if(text.length>MAX_INPUT_CHARS || !text.matches(Regex("[A-Za-z0-9_+/=-]+")))throw Rejected(ImportRejection.INVALID_DATA)
        val result=try{Base64.getUrlDecoder().decode(text)}catch(_:IllegalArgumentException){Base64.getDecoder().decode(text)}
        if(result.size>MAX_JSON_BYTES){result.fill(0);throw Rejected(ImportRejection.TOO_LARGE)}
        return result
    }
    private fun unsignedInt(b:ByteArray,offset:Int):Long=(0..3).fold(0L){n,i->(n shl 8) or (b[offset+i].toInt() and 255).toLong()}
    private fun amneziaBytes(bytes:ByteArray):ImportPayload {
        if(bytes.size>MAX_JSON_BYTES)throw Rejected(ImportRejection.TOO_LARGE)
        if(bytes.isEmpty())throw Rejected(ImportRejection.INVALID_DATA)
        // Official importController permits Base64URL plain JSON in addition to qCompress.
        if(bytes.first().toInt()==123 || bytes.first().toInt()==91)return amneziaText(utf8(bytes))
        if(bytes.size<6 || bytes.size>MAX_COMPRESSED_BYTES)throw Rejected(ImportRejection.TOO_LARGE)
        val expected=unsignedInt(bytes,0)
        if(expected !in 1..MAX_JSON_BYTES.toLong())throw Rejected(ImportRejection.TOO_LARGE)
        val inflater=Inflater();val output=ByteArrayOutputStream(expected.toInt());val chunk=ByteArray(8192)
        try {
            inflater.setInput(bytes,4,bytes.size-4)
            while(!inflater.finished()) {
                val count=inflater.inflate(chunk)
                if(count<=0 || output.size()+count>expected || output.size()+count>MAX_JSON_BYTES)throw Rejected(ImportRejection.INVALID_DATA)
                output.write(chunk,0,count)
            }
            if(output.size().toLong()!=expected || inflater.remaining!=0)throw Rejected(ImportRejection.INVALID_DATA)
            val decoded=output.toByteArray()
            return try{amneziaText(utf8(decoded))}finally{decoded.fill(0)}
        }finally{inflater.end();chunk.fill(0);output.reset()}
    }
    private fun amneziaText(text:String):ImportPayload=if(looksLikeConfig(text))config(text,null,ImportFormat.AMNEZIA) else amneziaJson(text)
    private fun amneziaJson(text:String):ImportPayload {
        if(text.toByteArray(Charsets.UTF_8).size>MAX_JSON_BYTES)throw Rejected(ImportRejection.TOO_LARGE)
        boundJsonDepth(text)
        val root=JSONObject(text)
        if(listOf("userName","password","api_key").any{(root.opt(it) as? String)?.isNotEmpty()==true})throw Rejected(ImportRejection.UNSUPPORTED_AMNEZIA)
        val name=(root.opt("description") as? String)?.trim()?.takeIf{it.length in 1..80 && it.none(Char::isISOControl)}
        val containers=root.optJSONArray("containers") ?: throw Rejected(ImportRejection.UNSUPPORTED_AMNEZIA)
        if(containers.length() !in 1..20)throw Rejected(ImportRejection.UNSUPPORTED_AMNEZIA)
        val selected=(root.opt("defaultContainer") as? String)?.takeIf{it.isNotBlank()}
        val candidates=ArrayList<String>()
        for(i in 0 until containers.length()) {
            val container=containers.optJSONObject(i) ?: continue
            val kind=(container.opt("container") as? String)
            if(selected!=null && kind!=selected)continue
            if(kind!=null && kind !in setOf("awg","wireguard","amnezia-awg","amnezia-wireguard"))continue
            for(protocol in listOf("awg","wireguard","amnezia-awg","amnezia-wireguard")) {
                val p=container.optJSONObject(protocol) ?: continue
                val raw=p.opt("last_config")
                val inner=when(raw){is JSONObject->raw;is String->{if(raw.length>MAX_JSON_BYTES)throw Rejected(ImportRejection.TOO_LARGE);boundJsonDepth(raw);JSONObject(raw)};else->continue}
                val native=inner.opt("config")
                if(native is String && looksLikeConfig(native))candidates+=native
            }
        }
        // Multiple client profiles are not silently reduced to an arbitrary server/protocol.
        val distinct=candidates.distinct()
        if(distinct.size!=1)throw Rejected(ImportRejection.UNSUPPORTED_AMNEZIA)
        return config(distinct.single(),name,ImportFormat.AMNEZIA)
    }
    private fun boundJsonDepth(text: String) = XrayProfileParser.boundDepth(text, maxDepth = 32)
    private fun looksLikeConfig(text:String):Boolean {
        var interfaze=false;var peer=false
        for(line in text.lineSequence()) {
            when(line.trim()) {"[Interface]"->interfaze=true;"[Peer]"->peer=true}
            if(interfaze && peer)return true
        }
        return false
    }
    private fun config(text:String,name:String?,format:ImportFormat):ConfigPayload {
        val bytes=text.toByteArray(Charsets.UTF_8)
        if(bytes.size>MAX_CONFIG_BYTES){bytes.fill(0);throw Rejected(ImportRejection.TOO_LARGE)}
        try {ProfileValidation.parse(bytes)}catch(_:Exception){bytes.fill(0);throw Rejected(ImportRejection.INVALID_DATA)}
        return ConfigPayload(bytes,name,format)
    }
    private fun utf8(bytes:ByteArray):String=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
