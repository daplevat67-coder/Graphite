package art.graphite.vpn.domain.xray

import org.json.JSONObject

/** Convert the form to the same validated client outbound used by JSON import. */
internal object VlessEditorProfile {
    fun document(draft: XrayProfile): ByteArray {
        require(draft.protocol == "vless")
        val tcp = draft.network == "tcp"
        val reality = draft.security == "reality"
        val tls = draft.security == "tls"
        val http = draft.network in setOf("ws", "xhttp", "httpupgrade") || tcp && draft.headerType == "http"
        val grpc = draft.network == "grpc"
        val profile = draft.copy(
            host = XrayProfileParser.host(draft.host.trim()),
            uuid = XrayProfileParser.uuid(draft.uuid.trim()),
            serverName = if (draft.security == "none") "" else draft.serverName.trim(),
            fingerprint = if (draft.security == "none") "" else draft.fingerprint.trim(),
            path = if (http) draft.path.trim().ifEmpty { "/" } else "/",
            transportHost = if (http) draft.transportHost.trim() else "",
            flow = if (tcp && draft.security != "none" && draft.headerType == "none") draft.flow else "",
            headerType = if (tcp) draft.headerType else "none",
            serviceName = if (grpc) draft.serviceName.trim() else "",
            authority = if (grpc) draft.authority.trim() else "",
            grpcMode = if (grpc) draft.grpcMode else "gun",
            xhttpMode = if (draft.network == "xhttp") draft.xhttpMode else "auto",
            realityPublicKey = if (reality) XrayProfileParser.publicKey(draft.realityPublicKey.trim()) else "",
            realityShortId = if (reality) draft.realityShortId.trim() else "",
            realityVerification = if (reality) draft.realityVerification.trim() else "",
            spiderX = if (reality) draft.spiderX.trim().ifEmpty { "/" } else "/",
            tlsEch = if (tls) draft.tlsEch.trim() else "",
            alpn = if (tls) draft.alpn else emptyList(),
            vlessEncryption = draft.vlessEncryption.trim().ifEmpty { "none" },
        )
        val config = JSONObject(XrayConfigGenerator.generate(profile))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0).toString()
        requireNotNull(XrayJsonProfile.parseOrNull(outbound))
        return outbound.toByteArray(Charsets.UTF_8)
    }
}
