package art.graphite.vpn.domain.model

import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** In-memory editor state only. Never use a Bundle, rememberSaveable, logs or preferences for it. */
data class WireGuardPeerDraft(
    val publicKey: String = "",
    val presharedKey: String = "",
    val endpoint: String = "",
    val allowedIps: String = "",
    val persistentKeepalive: String = ""
) {
    override fun toString(): String = "WireGuardPeerDraft(redacted)"
}

data class WireGuardDraft(
    val profileName: String = "",
    val privateKey: String = "",
    val addresses: String = "",
    val dns: String = "",
    val listenPort: String = "",
    val mtu: String = "",
    val includedApplications: String = "",
    val excludedApplications: String = "",
    val peers: List<WireGuardPeerDraft> = listOf(WireGuardPeerDraft()),
    val awgParameters: Map<String,String> = emptyMap()
) {
    override fun toString(): String = "WireGuardDraft(redacted)"

    /** Serializes the actual supported fields. Syntax/client validation is performed by the official parser/store. */
    fun toConfigText(): String {
        if (includedApplications.isNotBlank() && excludedApplications.isNotBlank()) throw WireGuardDraftException()
        if(awgParameters.keys.any { it !in AwgParameters.names })throw WireGuardDraftException()
        val fields = awgParameters.values.toList() + listOf(privateKey, addresses, dns, listenPort, mtu, includedApplications, excludedApplications) +
            peers.flatMap { listOf(it.publicKey,it.presharedKey,it.endpoint,it.allowedIps,it.persistentKeepalive) }
        if (fields.any { value -> value.any { it == '\n' || it == '\r' || it == '\u0000' } }) throw WireGuardDraftException()
        val text = buildString {
            append("[Interface]\n")
            fun field(key: String, value: String) { if (value.isNotBlank()) append(key).append(" = ").append(value.trim()).append('\n') }
            field("PrivateKey",privateKey); field("Address",addresses); field("DNS",dns)
            field("ListenPort",listenPort); field("MTU",mtu)
            field("IncludedApplications",includedApplications); field("ExcludedApplications",excludedApplications)
            AwgParameters.names.forEach { name -> awgParameters[name]?.let { field(name,it) } }
            peers.forEach { peer ->
                append("\n[Peer]\n")
                field("PublicKey",peer.publicKey); field("PresharedKey",peer.presharedKey)
                field("Endpoint",peer.endpoint); field("AllowedIPs",peer.allowedIps); field("PersistentKeepalive",peer.persistentKeepalive)
            }
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw WireGuardDraftException()
        return text
    }
    fun toConfigBytes(): ByteArray {
        try { AwgParameters.validate(awgParameters);peers.forEach { AwgParameters.validatePersistentKeepalive(it.persistentKeepalive) } }
        catch(_:AwgParametersException){throw WireGuardDraftException()}
        return toConfigText().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        /** Official parser handles case, comments, repeated fields, and canonical key validation. */
        fun fromConfig(bytes: ByteArray, profileName: String): WireGuardDraft {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw WireGuardDraftException()
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val text=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                AwgParameters.validate(AwgParameters.fromRawConfig(text))
                val config = Config.parse(text.byteInputStream())
                config.peers.forEach { AwgParameters.validatePersistentKeepalive(it.persistentKeepalive.orElse("")) }
                val awg=AwgParameters.fromConfig(config)
                AwgParameters.validate(awg)
                val iface = config.`interface`
                return WireGuardDraft(
                    profileName = profileName,
                    awgParameters = awg,
                    privateKey = iface.keyPair.privateKey.toBase64(),
                    addresses = iface.addresses.joinToString(", "),
                    dns = (iface.dnsServers.mapNotNull { it.hostAddress } + iface.dnsSearchDomains).joinToString(", "),
                    listenPort = iface.listenPort.map { it.toString() }.orElse(""),
                    mtu = iface.mtu.map { it.toString() }.orElse(""),
                    includedApplications = iface.includedApplications.joinToString(", "),
                    excludedApplications = iface.excludedApplications.joinToString(", "),
                    peers = config.peers.map { peer -> WireGuardPeerDraft(
                        publicKey = peer.publicKey.toBase64(),
                        presharedKey = peer.preSharedKey.map { it.toBase64() }.orElse(""),
                        endpoint = peer.endpoint.map { it.toString() }.orElse(""),
                        allowedIps = peer.allowedIps.joinToString(", "),
                        persistentKeepalive = peer.persistentKeepalive.map { it.toString() }.orElse("")
                    ) }
                )
            } catch (_: Exception) { throw WireGuardDraftException() }
        }
    }
}
/** Safe generic failure: no raw parser exception/cause is retained. */
class WireGuardDraftException : Exception("INVALID_CONFIG")
