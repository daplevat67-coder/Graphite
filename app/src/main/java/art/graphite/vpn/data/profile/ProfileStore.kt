package art.graphite.vpn.data.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.amnezia.awg.config.Config
import art.graphite.vpn.domain.model.AwgParameters
import art.graphite.vpn.domain.xray.XrayProfile
import art.graphite.vpn.domain.xray.XrayConfigGenerator
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.KeyGenerator

data class StoredProfile(
    val id: String,
    val name: String,
    val endpoints: List<String>,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val routes: List<String>,
    val protocolId: String = "wg",
    val fileExtension: String = "conf",
    val source: ProfileSource = ProfileSource.UNKNOWN
) {
    val isXray:Boolean get()=protocolId in setOf("vless","vmess","socks5","graphite")
    val isMtProxy:Boolean get()=protocolId=="mtproto"
    val isOpenVpn get() = protocolId == "openvpn"
    val isIke get() = protocolId == "ikev2"
    val protocolName:String get()=when(protocolId){"awg"->"AmneziaWG";"vless"->"VLESS";"vmess"->"VMess";"mtproto"->"MTProto Proxy";"socks5"->"SOCKS5";"graphite"->"Graphite Tunnel";"openvpn"->"OpenVPN";"ikev2"->"IPsec / IKEv2";else->"WireGuard"}
}
/** Recovery candidates are explicit UUIDs; nothing is deleted or quarantined automatically. */
data class ProfileInventory(val profiles: List<StoredProfile>, val unreadableIds: List<String>)

/** Synchronous: call on IO. Only the selected UUID is placed in SharedPreferences. */
class ProfileStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "wireguard-profiles")
    private val preferences = app.getSharedPreferences("selected-wireguard-profile", Context.MODE_PRIVATE)

    fun list(): List<StoredProfile> = guarded {
        files().map { entry ->
            val id = entry.name.removeSuffix(EXT)
            val (name, config, source) = read(id)
            metadata(id, name, config).copy(source = source)
        }.sortedBy { it.name.lowercase() }
    }

    /** A shared missing/invalid key fails globally, before any file is marked unreadable. */
    fun inspect(): ProfileInventory = guarded {
        val entries = files()
        if (entries.isEmpty()) return@guarded ProfileInventory(emptyList(), emptyList())
        val storageKey = key()
        try {
            javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").init(
                javax.crypto.Cipher.DECRYPT_MODE, storageKey, javax.crypto.spec.GCMParameterSpec(128, ByteArray(12))
            )
        } catch (_: Exception) { throw ProfileException("STORAGE_KEY_UNAVAILABLE") }
        val healthy = mutableListOf<StoredProfile>()
        val unreadable = mutableListOf<String>()
        entries.forEach { entry ->
            val id = entry.name.removeSuffix(EXT)
            try {
                val (name, config, source) = read(id, storageKey)
                healthy += metadata(id, name, config).copy(source = source)
            } catch (error: ProfileException) {
                if (error.safeCode == "STORAGE_KEY_UNAVAILABLE") throw error
                unreadable += id
            } catch (_: Exception) { unreadable += id }
        }
        ProfileInventory(healthy.sortedBy { it.name.lowercase() }, unreadable.sorted())
    }

    fun selected(): StoredProfile? = guarded {
        val id = preferences.getString(SELECTED, null) ?: return@guarded null
        checkId(id)
        if (!file(id).isFile) {
            if (!preferences.edit().remove(SELECTED).commit()) throw ProfileException("STORAGE_UNAVAILABLE")
            return@guarded null
        }
        val (name, config, source) = read(id)
        metadata(id, name, config).copy(source = source)
    }

    fun `import`(name: String, bytes: ByteArray, select: Boolean = true,
                 expectedProtocol: String? = null, source: ProfileSource? = null): StoredProfile = guarded {
        if (files().size >= MAX_PROFILES) throw ProfileException("PROFILE_LIMIT")
        val cleanName = ProfileValidation.name(name)
        val config = ProfileDocuments.parse(bytes)
        val id = UUID.randomUUID().toString()
        val profile = metadata(id, cleanName, config).copy(source = source ?: when {
            config.xray?.originalShareLink?.startsWith('{') == true || config.ike != null -> ProfileSource.JSON
            config.xray != null || config.graphite != null || config.mtProxy != null -> ProfileSource.LINK
            else -> ProfileSource.FILE
        })
        if (expectedProtocol != null && profile.protocolId != expectedProtocol) throw ProfileException("WRONG_PROTOCOL")
        val canonical = config.bytes()
        try {
            val encrypted = ProfileCodec.encrypt(ProfilePayload(cleanName, canonical, profile.source), key(), id)
            write(id, encrypted)
            if (select && !config.isCompanion && !preferences.edit().putString(SELECTED, id).commit()) {
                AtomicFile(file(id)).delete()
                throw ProfileException("STORAGE_UNAVAILABLE")
            }
            profile
        } finally { canonical.fill(0) }
    }

    /** Keeps UUID and selection. Caller must stop/refuse active tunnel edits before invoking. */
    fun update(id: String, name: String, bytes: ByteArray): StoredProfile = guarded {
        checkId(id)
        val cleanName = ProfileValidation.name(name)
        val config = ProfileDocuments.parse(bytes)
        val canonical = config.bytes()
        try {
            val storageKey = key()
            val (_, previous, source) = read(id, storageKey)
            if (previous.isCompanion != config.isCompanion) throw ProfileException("INVALID_CONFIG")
            val encrypted = ProfileCodec.encrypt(ProfilePayload(cleanName, canonical, source), storageKey, id)
            write(id, encrypted)
            metadata(id, cleanName, config).copy(source = source)
        } finally { canonical.fill(0) }
    }

    fun select(id: String): Unit = guarded {
        if (read(id).second.isCompanion) throw ProfileException("INVALID_CONFIG")
        if (!preferences.edit().putString(SELECTED, id).commit()) throw ProfileException("STORAGE_UNAVAILABLE")
    }
    fun delete(id: String): Unit = guarded {
        checkId(id)
        if (preferences.getString(SELECTED, null) == id && !preferences.edit().remove(SELECTED).commit())
            throw ProfileException("STORAGE_UNAVAILABLE")
        AtomicFile(file(id)).delete()
        if (file(id).exists()) throw ProfileException("STORAGE_UNAVAILABLE")
    }
    fun profile(id:String):StoredProfile = guarded { val (name,document,source)=read(id);metadata(id,name,document).copy(source=source) }
    fun config(id: String): Config = guarded { read(id).second.wireGuard ?: throw ProfileException("XRAY_PROFILE") }
    fun xrayProfile(id:String):XrayProfile = guarded { read(id).second.xray ?: throw ProfileException("NOT_XRAY_PROFILE") }
    internal fun graphiteProfile(id:String) = guarded {
        read(id).second.graphite ?: throw ProfileException("INVALID_CONFIG")
    }
    fun xrayConfig(id:String,socksPort:Int=10808,dnsServers:List<String> = listOf("1.1.1.1","1.0.0.1"),serverAddress:String?=null,socksUsername:String="",socksPassword:String=""):String = guarded {
        val profile=read(id).second.xray ?: throw ProfileException("NOT_XRAY_PROFILE")
        XrayConfigGenerator.generate(profile,socksPort,dnsServers,serverAddress,socksUsername,socksPassword)
    }
    /** Plaintext is returned only for the caller's explicit export/editor operation. */
    internal fun mtProxyLink(id: String): String = guarded {
        read(id).second.mtProxy?.link ?: throw ProfileException("INVALID_CONFIG")
    }

    internal fun ikeProfile(id: String) = guarded {
        read(id).second.ike ?: throw ProfileException("INVALID_CONFIG")
    }

    internal fun openVpnDocument(id: String) = guarded {
        read(id).second.openVpn ?: throw ProfileException("INVALID_CONFIG")
    }

    fun export(id: String): ByteArray = guarded { read(id).second.bytes() }

    private fun write(id: String, encrypted: ByteArray) {
        ensureDirectory()
        val atomic = AtomicFile(file(id))
        val stream = atomic.startWrite()
        try { stream.write(encrypted); atomic.finishWrite(stream) }
        catch (_: Exception) { atomic.failWrite(stream); throw ProfileException("STORAGE_UNAVAILABLE") }
    }

    private fun read(id: String, storageKey: SecretKey? = null): Triple<String, ProfileDocument, ProfileSource> {
        checkId(id)
        val atomic = AtomicFile(file(id))
        val bytes = atomic.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > ProfileCodec.MAX_BLOB_BYTES) throw ProfileException("PROFILE_TOO_LARGE")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val payload = ProfileCodec.decrypt(bytes, storageKey ?: key(), id)
        return try { Triple(ProfileValidation.name(payload.name), ProfileDocuments.parse(payload.config), payload.source) }
        finally { payload.config.fill(0) }
    }

    private fun metadata(id:String,name:String,document:ProfileDocument):StoredProfile {
        document.xray?.let{return StoredProfile(id,name,listOf(it.endpoint),emptyList(),emptyList(),emptyList(),it.protocol,if(it.originalShareLink.startsWith('{')) "json" else "txt")}
        document.graphite?.let { return StoredProfile(id,name,listOf(it.endpoint),emptyList(),emptyList(),emptyList(),"graphite","txt") }
        document.mtProxy?.let { return StoredProfile(id,name,listOf(it.endpoint),emptyList(),emptyList(),emptyList(),"mtproto","txt") }
        document.ike?.let { return StoredProfile(id,name,listOf(if(it.server.contains(':')) "[${it.server}]:500" else "${it.server}:500"),emptyList(),emptyList(),emptyList(),"ikev2","json") }
        document.openVpn?.let { return StoredProfile(id,name,it.endpoints,emptyList(),emptyList(),emptyList(),"openvpn","ovpn") }
        val config=document.wireGuard ?: throw ProfileException("INVALID_CONFIG")
        return StoredProfile(id,name,
            config.peers.map { it.endpoint.get().toString() },
            config.`interface`.addresses.map { it.toString() },
            config.`interface`.dnsServers.mapNotNull { it.hostAddress } + config.`interface`.dnsSearchDomains,
            config.peers.flatMap { peer -> peer.allowedIps.map { it.toString() } }.distinct(),
            if(AwgParameters.isAwg(config)) "awg" else "wg")
    }
    private fun file(id: String) = File(directory, "$id$EXT")
    private fun files(): List<File> {
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw ProfileException("STORAGE_UNAVAILABLE")
        val entries = directory.listFiles() ?: throw ProfileException("STORAGE_UNAVAILABLE")
        return entries.filter { it.isFile && it.name.endsWith(EXT) && isId(it.name.removeSuffix(EXT)) }
            .also { if (it.size > MAX_PROFILES) throw ProfileException("PROFILE_LIMIT") }
    }
    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw ProfileException("STORAGE_UNAVAILABLE")
    }
    private fun isId(id: String) = try { UUID.fromString(id).toString() == id } catch (_: IllegalArgumentException) { false }
    private fun checkId(id: String) { if (!isId(id)) throw ProfileException("INVALID_PROFILE_ID") }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing != null) return existing as? SecretKey ?: throw ProfileException("STORAGE_KEY_UNAVAILABLE")
        if (files().isNotEmpty()) throw ProfileException("STORAGE_KEY_UNAVAILABLE")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    private inline fun <T> guarded(block: () -> T): T = synchronized(LOCK) {
        try { block() }
        catch (e: ProfileException) { throw e }
        catch (_: Exception) { throw ProfileException("STORAGE_UNAVAILABLE") }
    }
    private companion object {
        val LOCK = Any()
        const val KEY_ALIAS = "graphite-wireguard-profiles-v1"
        const val SELECTED = "selected_uuid"
        const val EXT = ".gwp"
        const val MAX_PROFILES = 20
    }
}
