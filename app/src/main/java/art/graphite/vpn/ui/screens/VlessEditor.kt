package art.graphite.vpn.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import art.graphite.vpn.R
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.data.profile.ProfileValidation
import art.graphite.vpn.domain.xray.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VlessEditor(
    id: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onDirtyChanged: (Boolean) -> Unit,
    selectNewProfile: () -> Boolean = { true },
) {
    val context = LocalContext.current
    val store = remember(context) { ProfileStore(context) }
    val scope = rememberCoroutineScope()
    var draft by remember(id) { mutableStateOf(XrayProfile("vless", "", 443, "", security = "tls", originalShareLink = "")) }
    var name by remember(id) { mutableStateOf("") }
    var port by remember(id) { mutableStateOf("443") }
    var alpn by remember(id) { mutableStateOf("") }
    var loaded by remember(id) { mutableStateOf(id == null) }
    var saving by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    SecureWindow()
    LaunchedEffect(id) {
        if (id != null) {
            try {
                val (profileName, profile) = withContext(Dispatchers.IO) {
                    store.profile(id).name to store.xrayProfile(id)
                }
                name = profileName
                port = profile.port.toString()
                alpn = profile.alpn.joinToString(",")
                draft = profile
                loaded = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = R.string.error_profile
            }
        }
    }

    fun change(next: XrayProfile) {
        draft = next
        error = null
        onDirtyChanged(true)
    }
    val enabled = loaded && !saving
    Text(stringResource(if (id == null) R.string.vless_create else R.string.vless_edit), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.vless_editor_hint))
    VlessInput(name, stringResource(R.string.import_profile_name), enabled) {
        name = it; error = null; onDirtyChanged(true)
    }
    VlessInput(draft.host, stringResource(R.string.vless_address), enabled) { change(draft.copy(host = it)) }
    VlessInput(port, stringResource(R.string.vless_port), enabled, keyboard = KeyboardType.Number) {
        if (it.length <= 5 && it.all { digit -> digit in '0'..'9' }) {
            port = it; error = null; onDirtyChanged(true)
        }
    }
    VlessInput(draft.uuid, "UUID", enabled, secret = true) { change(draft.copy(uuid = it)) }

    VlessChoice(stringResource(R.string.vless_transport), draft.network, listOf(
        "tcp" to "RAW / TCP", "ws" to "WebSocket", "grpc" to "gRPC",
        "xhttp" to "XHTTP", "httpupgrade" to "HTTPUpgrade", "kcp" to "mKCP",
    ), enabled) {
        if (it != draft.network) change(draft.copy(network = it, transportOptions = ""))
    }
    val securityOptions = listOf("none" to stringResource(R.string.vless_no_security), "tls" to "TLS")
        .let { if (draft.security == "reality" || draft.network in setOf("tcp", "grpc", "xhttp")) it + ("reality" to "REALITY") else it }
    VlessChoice(stringResource(R.string.vless_security), draft.security, securityOptions, enabled) {
        change(draft.copy(security = it))
    }
    if (draft.security != "none") {
        VlessInput(draft.serverName, "SNI", enabled) { change(draft.copy(serverName = it)) }
        VlessInput(draft.fingerprint, stringResource(R.string.vless_fingerprint), enabled) {
            change(draft.copy(fingerprint = it))
        }
    }
    if (draft.security == "reality") {
        VlessInput(draft.realityPublicKey, stringResource(R.string.vless_public_key), enabled) {
            change(draft.copy(realityPublicKey = it))
        }
        VlessInput(draft.realityShortId, "Short ID", enabled) { change(draft.copy(realityShortId = it)) }
    }
    if (draft.network == "tcp") {
        VlessChoice(stringResource(R.string.vless_tcp_header), draft.headerType, listOf("none" to "—", "http" to "HTTP"), enabled) {
            change(draft.copy(headerType = it))
        }
        if (draft.security != "none" && draft.headerType == "none") {
            VlessChoice("Flow", draft.flow, listOf("" to "—", "xtls-rprx-vision" to "Vision",
                "xtls-rprx-vision-udp443" to "Vision UDP/443"), enabled) { change(draft.copy(flow = it)) }
        }
    }
    if (draft.network in setOf("ws", "xhttp", "httpupgrade") || draft.network == "tcp" && draft.headerType == "http") {
        VlessInput(draft.transportHost, "Host", enabled) { change(draft.copy(transportHost = it)) }
        VlessInput(draft.path, stringResource(R.string.vless_path), enabled) { change(draft.copy(path = it)) }
    }
    if (draft.network == "grpc") {
        VlessInput(draft.serviceName, "Service name", enabled) { change(draft.copy(serviceName = it)) }
        VlessInput(draft.authority, "Authority", enabled) { change(draft.copy(authority = it)) }
        VlessChoice("gRPC", draft.grpcMode, listOf("gun" to "gun", "multi" to "multi"), enabled) {
            change(draft.copy(grpcMode = it))
        }
    }
    if (draft.network == "xhttp") {
        VlessChoice("XHTTP", draft.xhttpMode, listOf("auto", "packet-up", "stream-up", "stream-one").map { it to it }, enabled) {
            change(draft.copy(xhttpMode = it))
        }
    }

    TextButton(onClick = { advanced = !advanced }, enabled = enabled) {
        Text(stringResource(if (advanced) R.string.vless_hide_advanced else R.string.advanced_edit))
    }
    if (advanced) {
        VlessInput(draft.vlessEncryption, "VLESS Encryption", enabled, secret = true) {
            change(draft.copy(vlessEncryption = it))
        }
        if (draft.security == "tls") {
            VlessInput(alpn, "ALPN", enabled) { alpn = it; error = null; onDirtyChanged(true) }
            VlessInput(draft.tlsEch, "ECH", enabled) { change(draft.copy(tlsEch = it)) }
        }
        if (draft.security == "reality") {
            VlessInput(draft.spiderX, "SpiderX", enabled) { change(draft.copy(spiderX = it)) }
            VlessInput(draft.realityVerification, "PQV", enabled) { change(draft.copy(realityVerification = it)) }
        }
        if (draft.network in setOf("kcp", "grpc", "xhttp")) {
            VlessInput(draft.transportOptions, stringResource(R.string.vless_transport_json), enabled, secret = true) {
                change(draft.copy(transportOptions = it))
            }
        }
    }

    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    Button(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = {
        saving = true
        error = null
        scope.launch {
            try {
                val cleanName = try { ProfileValidation.name(name) }
                    catch (_: Exception) { error = R.string.vless_bad_name; return@launch }
                val number = port.toIntOrNull()?.takeIf { it in 1..65535 }
                if (number == null) { error = R.string.vless_bad_port; return@launch }
                val profile = draft.copy(port = number, alpn = alpn.split(',').map(String::trim).filter(String::isNotEmpty))
                withContext(Dispatchers.IO) {
                    val bytes = VlessEditorProfile.document(profile)
                    try {
                        if (id == null) store.`import`(cleanName, bytes, select = selectNewProfile(), source = art.graphite.vpn.data.profile.ProfileSource.PARAMETERS) else store.update(id, cleanName, bytes)
                    } finally { bytes.fill(0) }
                }
                onDirtyChanged(false)
                onSaved()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: XrayProfileException) {
                error = when (failure.safeCode) {
                    "XRAY_HOST" -> R.string.vless_bad_address
                    "XRAY_UUID" -> R.string.vless_bad_uuid
                    "XRAY_REALITY_KEY", "XRAY_REALITY_SHORT_ID", "XRAY_REALITY_VERIFY", "XRAY_BASE64" -> R.string.vless_bad_reality
                    "XRAY_VLESS_REQUIRES_TLS" -> R.string.vless_requires_tls
                    else -> R.string.vless_bad_settings
                }
            } catch (_: IllegalArgumentException) {
                error = R.string.vless_bad_settings
            } catch (_: Exception) {
                error = R.string.xray_edit_failed
            } finally { saving = false }
        }
    }) { Text(stringResource(R.string.options_save)) }
    TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun VlessInput(
    value: String, label: String, enabled: Boolean, secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, { if (it.length <= 32768) onChange(it) }, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun VlessChoice(
    label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean, onChoice: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label · ${choices.firstOrNull { it.first == value }?.second.orEmpty()}")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for ((key, title) in choices) {
            DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onChoice(key) })
        }
    }
}
