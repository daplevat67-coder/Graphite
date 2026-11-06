package art.graphite.vpn.data.profile

import art.graphite.vpn.domain.xray.*
import art.graphite.vpn.domain.graphite.GraphiteProfile
import art.graphite.vpn.domain.extra.*
import art.graphite.vpn.domain.mtproto.MtProxy
import org.amnezia.awg.config.Config
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The AES-GCM envelope remains v1; its bounded config payload may now be a share link. */
internal class ProfileDocument(val wireGuard:Config?=null,val xray:XrayProfile?=null,val mtProxy:MtProxy?=null,val ike:IkeProfile?=null,val openVpn:OpenVpnDocument?=null,val graphite:GraphiteProfile?=null) {
    val isCompanion get() = mtProxy != null || openVpn != null
    fun bytes():ByteArray=(wireGuard?.toAwgQuickString() ?: xray?.originalShareLink ?: mtProxy?.link ?: ike?.document ?: openVpn?.document ?: graphite?.link ?: throw ProfileException("INVALID_CONFIG")).toByteArray(Charsets.UTF_8)
    override fun toString()="ProfileDocument(redacted)"
}
internal object ProfileDocuments {
    fun parse(bytes:ByteArray):ProfileDocument {
        if(bytes.isEmpty())throw ProfileException("INVALID_CONFIG")
        if(bytes.size>ProfileCodec.MAX_CONFIG_BYTES)throw ProfileException("PROFILE_TOO_LARGE")
        val text=try{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}catch(_:Exception){throw ProfileException("INVALID_CONFIG")}
        val normalized = text.trim().removePrefix("\uFEFF").trim()
        if (GraphiteProfile.matches(normalized)) {
            return try { ProfileDocument(graphite=GraphiteProfile.parse(normalized)) }
            catch (_: Exception) { throw ProfileException("INVALID_CONFIG") }
        }
        if (MtProxy.matches(normalized)) {
            return try { ProfileDocument(mtProxy=MtProxy.parse(normalized)) }
            catch (_: Exception) { throw ProfileException("INVALID_CONFIG") }
        }
        if (normalized.startsWith('{')) {
            return try {
                IkeProfile.parseOrNull(normalized)?.let { return ProfileDocument(ike=it) }
                ProfileDocument(xray=XrayJsonProfile.parseOrNull(normalized) ?: throw ProfileException("INVALID_CONFIG"))
            } catch (_: Exception) { throw ProfileException("INVALID_XRAY_CONFIG") }
        }
        try { OpenVpnDocument.parseOrNull(normalized)?.let { return ProfileDocument(openVpn=it) } }
        catch (_: Exception) { throw ProfileException("INVALID_CONFIG") }
        return if(XrayProfileParser.isShareLink(text)) {
            try{ProfileDocument(xray=XrayProfileParser.parse(text))}catch(_:XrayProfileException){throw ProfileException("INVALID_XRAY_CONFIG")}
        } else ProfileDocument(wireGuard=ProfileValidation.parse(bytes))
    }
}
