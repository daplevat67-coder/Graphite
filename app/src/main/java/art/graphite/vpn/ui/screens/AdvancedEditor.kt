package art.graphite.vpn.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import art.graphite.vpn.R
import art.graphite.vpn.data.profile.ProfileException
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.domain.model.AwgParameters
import art.graphite.vpn.domain.model.WireGuardDraft
import art.graphite.vpn.domain.model.WireGuardDraftException
import art.graphite.vpn.domain.model.WireGuardPeerDraft
import art.graphite.vpn.ui.components.SketchCard
import art.graphite.vpn.vpn.VpnPhase
import art.graphite.vpn.vpn.VpnRuntime
import org.amnezia.awg.crypto.KeyPair
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A deliberately non-saveable secret draft. Root page supplies scrolling. */
@Composable
fun AdvancedEditor(
    profileId: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onDirtyChanged: (Boolean) -> Unit = {},
    expectedProtocol: String? = null
) {
    val context = LocalContext.current
    val store = remember(context) { ProfileStore(context) }
    val scope = rememberCoroutineScope()
    val state by VpnRuntime.state.collectAsState()
    val stopped = state.phase == VpnPhase.DOWN || state.phase == VpnPhase.ERROR
    var draft by remember(profileId) { mutableStateOf(WireGuardDraft()) }
    var baseline by remember(profileId) { mutableStateOf(WireGuardDraft()) }
    var awgExpanded by remember(profileId) { mutableStateOf(false) }
    var loading by remember(profileId) { mutableStateOf(profileId != null) }
    var loadFailed by remember(profileId) { mutableStateOf(false) }
    var saving by remember(profileId) { mutableStateOf(false) }
    var generating by remember(profileId) { mutableStateOf(false) }
    var peerTokens by remember(profileId) { mutableStateOf(listOf(0)) }
    var nextPeerToken by remember(profileId) { mutableIntStateOf(1) }
    var error by remember(profileId) { mutableStateOf<Int?>(null) }
    var rawMode by remember(profileId) { mutableStateOf(false) }
    var rawText by remember(profileId) { mutableStateOf("") }
    var rawAtOpen by remember(profileId) { mutableStateOf("") }
    var confirmRaw by remember { mutableStateOf(false) }
    var confirmRawDiscard by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var generateTarget by remember { mutableStateOf<Int?>(null) } // -1 interface, otherwise peer index
    val dirty = draft != baseline || (rawMode && rawText != rawAtOpen)
    val dirtyCallback by rememberUpdatedState(onDirtyChanged)
    LaunchedEffect(dirty) { dirtyCallback(dirty) }
    SecureWindow { dirtyCallback(false) }
    LaunchedEffect(profileId) {
        if (profileId != null) {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val name = store.inspect().profiles.find { it.id == profileId }?.name
                        ?: throw WireGuardDraftException()
                    val bytes = store.export(profileId)
                    try { WireGuardDraft.fromConfig(bytes,name) } finally { bytes.fill(0) }
                }
                draft = loaded; baseline = loaded
                peerTokens = loaded.peers.indices.toList(); nextPeerToken = loaded.peers.size
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loadFailed = true; error = R.string.advanced_error_storage }
            finally { loading = false }
        }
    }
    fun cancel() {
        if (!saving && !generating) { if (dirty) confirmDiscard = true else onCancel() }
    }
    BackHandler { cancel() }
    val editable = !loading && !loadFailed && !saving && !generating && (profileId == null || stopped)
    fun edit(value: WireGuardDraft) { draft = value; error = null }
    fun peer(index: Int, value: WireGuardPeerDraft) {
        edit(draft.copy(peers = draft.peers.mapIndexed { i, old -> if(i == index) value else old }))
    }
    fun generate(target: Int) {
        if (!editable) return
        generating = true
        scope.launch {
            try {
                val value = withContext(Dispatchers.Default) {
                    if (target == -1) KeyPair().privateKey.toBase64()
                    else {
                        val bytes = ByteArray(32)
                        try { SecureRandom().nextBytes(bytes); Base64.getEncoder().encodeToString(bytes) }
                        finally { bytes.fill(0) }
                    }
                }
                if (target == -1) edit(draft.copy(privateKey = value))
                else draft.peers.getOrNull(target)?.let { peer(target,it.copy(presharedKey = value)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.advanced_error_key }
            finally { generating = false }
        }
    }
    fun requestGenerate(target: Int) {
        val existing = if(target == -1) draft.privateKey else draft.peers.getOrNull(target)?.presharedKey.orEmpty()
        if(existing.isNotBlank()) generateTarget = target else generate(target)
    }
    fun applyRaw() {
        try {
            val bytes = rawText.toByteArray(Charsets.UTF_8)
            val parsed = try { WireGuardDraft.fromConfig(bytes,draft.profileName) } finally { bytes.fill(0) }
            edit(parsed)
            peerTokens = parsed.peers.map { nextPeerToken++ }
            rawMode = false; rawText = ""; rawAtOpen = ""
        } catch (_: Exception) { error = R.string.advanced_error_config }
    }
    fun leaveRawWithoutApplying() {
        // Keep the structured draft intact, including any edits made before opening raw text.
        rawMode = false; rawText = ""; rawAtOpen = ""; error = null
    }
    fun save() {
        if(!editable) return
        saving = true; error = null
        val snapshot = draft
        val rawSnapshot = if(rawMode) rawText else null
        scope.launch {
            try {
                val savedDraft = withContext(Dispatchers.IO) {
                    if(profileId != null && VpnRuntime.state.value.phase !in setOf(VpnPhase.DOWN,VpnPhase.ERROR))
                        throw EditorTunnelRunning()
                    val parsed = if(rawSnapshot != null) {
                        val bytes = rawSnapshot.toByteArray(Charsets.UTF_8)
                        try { WireGuardDraft.fromConfig(bytes,snapshot.profileName) } finally { bytes.fill(0) }
                    } else snapshot
                    val bytes = parsed.toConfigBytes()
                    try {
                        if(profileId != null && VpnRuntime.state.value.phase !in setOf(VpnPhase.DOWN,VpnPhase.ERROR))
                            throw EditorTunnelRunning()
                        if(profileId == null) store.`import`(parsed.profileName, bytes,
                            select = VpnRuntime.state.value.phase in setOf(VpnPhase.DOWN, VpnPhase.ERROR),
                            expectedProtocol = expectedProtocol,
                            source = if (rawSnapshot == null) art.graphite.vpn.data.profile.ProfileSource.PARAMETERS
                                else art.graphite.vpn.data.profile.ProfileSource.FILE)
                        else store.update(profileId,parsed.profileName,bytes)
                    } finally { bytes.fill(0) }
                    parsed
                }
                draft = savedDraft; baseline = savedDraft; rawMode = false; rawText = ""; rawAtOpen = ""
                dirtyCallback(false); onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: EditorTunnelRunning) { error = R.string.advanced_locked }
            catch (failure: ProfileException) { error = if (failure.safeCode == "WRONG_PROTOCOL") R.string.connection_wrong_protocol else advancedError(failure.safeCode) }
            catch (_: WireGuardDraftException) { error = R.string.advanced_error_config }
            catch (_: Exception) { error = R.string.advanced_error_storage }
            finally { saving = false }
        }
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.advanced_title),style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.advanced_hint),style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(loading) Text(stringResource(R.string.advanced_loading))
        if(profileId != null && !stopped) SketchCard { Text(stringResource(R.string.advanced_locked)) }
        error?.let { message -> SketchCard { Text(stringResource(message),style = MaterialTheme.typography.bodyMedium) } }
        SketchCard {
            DraftField(R.string.advanced_profile_name,draft.profileName,{ if(it.length<=80) edit(draft.copy(profileName=it)) },editable)
        }
        if(rawMode) {
            SketchCard {
                Text(stringResource(R.string.advanced_raw_title),style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(value=rawText,onValueChange={ if(it.toByteArray(Charsets.UTF_8).size<=WireGuardDraft.MAX_BYTES) {rawText=it;error=null} else error=R.string.advanced_error_size },
                    enabled=editable,modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.advanced_raw_field))},minLines=8,maxLines=16,
                    keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Ascii))
                OutlinedButton(onClick={applyRaw()},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_raw_apply))}
                TextButton(onClick={
                    if(rawText != rawAtOpen) confirmRawDiscard = true else leaveRawWithoutApplying()
                },enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_raw_back))}
            }
        } else {
            SketchCard {
                Text(stringResource(R.string.advanced_interface),style=MaterialTheme.typography.titleMedium)
                DraftField(R.string.advanced_private_key,draft.privateKey,{edit(draft.copy(privateKey=it))},editable,secret=true)
                OutlinedButton(onClick={requestGenerate(-1)},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_generate_private))}
                DraftField(R.string.advanced_address,draft.addresses,{edit(draft.copy(addresses=it))},editable,R.string.advanced_address_hint)
                DraftField(R.string.advanced_dns,draft.dns,{edit(draft.copy(dns=it))},editable,R.string.advanced_dns_hint)
                DraftField(R.string.advanced_listen_port,draft.listenPort,{edit(draft.copy(listenPort=it))},editable,R.string.advanced_optional,numeric=true)
                DraftField(R.string.advanced_mtu,draft.mtu,{edit(draft.copy(mtu=it))},editable,R.string.advanced_optional,numeric=true)
            }
            SketchCard {
                Text(stringResource(R.string.awg_title),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.awg_hint),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={awgExpanded=!awgExpanded},enabled=editable) {
                    Text(stringResource(if(awgExpanded)R.string.awg_hide_fields else R.string.awg_show_fields,draft.awgParameters.count { it.value.isNotBlank() }))
                }
                if(awgExpanded)AwgParameters.names.forEach { name ->
                    val value=draft.awgParameters[name].orEmpty()
                    var revealed by remember(name) { mutableStateOf(false) }
                    OutlinedTextField(value=value,onValueChange={next->
                        if(next.toByteArray(Charsets.UTF_8).size<=WireGuardDraft.MAX_BYTES)edit(draft.copy(awgParameters=draft.awgParameters.toMutableMap().apply { if(next.isEmpty())remove(name) else put(name,next) }))
                    },enabled=editable,modifier=Modifier.fillMaxWidth(),singleLine=true,
                        label={Text(stringResource(R.string.awg_parameter,name))},
                        supportingText={Text(stringResource(when {
                            name=="HeaderProtectionKey"->R.string.awg_hpk_hint
                            name.startsWith("I")->R.string.awg_template_hint
                            name in setOf("RandomTrailers","DisableCookies")->R.string.awg_bool_hint
                            else->R.string.awg_range_hint
                        }))},
                        visualTransformation=if(name=="HeaderProtectionKey" && !revealed)PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=if(name=="HeaderProtectionKey")KeyboardType.Password else KeyboardType.Ascii),
                        trailingIcon=if(name=="HeaderProtectionKey") { @Composable {TextButton(onClick={revealed=!revealed},enabled=editable){Text(stringResource(if(revealed)R.string.advanced_hide else R.string.advanced_show))}} } else null)
                }
            }
            SketchCard {
                Text(stringResource(R.string.advanced_apps),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.advanced_apps_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                DraftField(R.string.advanced_included,draft.includedApplications,{edit(draft.copy(includedApplications=it))},editable && draft.excludedApplications.isBlank())
                DraftField(R.string.advanced_excluded,draft.excludedApplications,{edit(draft.copy(excludedApplications=it))},editable && draft.includedApplications.isBlank())
            }
            draft.peers.forEachIndexed { index, item ->
                // Stable composition group preserves each existing peer's reveal state while typing.
                key(peerTokens.getOrElse(index) { index }) {
                    SketchCard {
                        Text(stringResource(R.string.advanced_peer,index+1),style=MaterialTheme.typography.titleMedium)
                        DraftField(R.string.advanced_public_key,item.publicKey,{peer(index,item.copy(publicKey=it))},editable)
                        DraftField(R.string.advanced_preshared_key,item.presharedKey,{peer(index,item.copy(presharedKey=it))},editable,R.string.advanced_optional,secret=true)
                        OutlinedButton(onClick={requestGenerate(index)},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_generate_psk))}
                        DraftField(R.string.advanced_endpoint,item.endpoint,{peer(index,item.copy(endpoint=it))},editable,R.string.advanced_endpoint_hint)
                        DraftField(R.string.advanced_allowed_ips,item.allowedIps,{peer(index,item.copy(allowedIps=it))},editable)
                        OutlinedButton(onClick={peer(index,item.copy(allowedIps="0.0.0.0/0"))},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_route_ipv4))}
                        OutlinedButton(onClick={peer(index,item.copy(allowedIps="0.0.0.0/0, ::/0"))},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_route_both))}
                        DraftField(R.string.advanced_keepalive,item.persistentKeepalive,{peer(index,item.copy(persistentKeepalive=it))},editable,R.string.awg_keepalive_hint)
                        TextButton(onClick={
                            peerTokens=peerTokens.filterIndexed { i,_->i!=index }
                            edit(draft.copy(peers=draft.peers.filterIndexed { i,_->i!=index }))
                        },enabled=editable) {Text(stringResource(R.string.advanced_remove_peer))}
                    }
                }
            }
            OutlinedButton(onClick={peerTokens=peerTokens+nextPeerToken++;edit(draft.copy(peers=draft.peers+WireGuardPeerDraft()))},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_add_peer))}
            TextButton(onClick={confirmRaw=true},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_raw))}
        }
        Button(onClick={save()},enabled=editable,modifier=Modifier.fillMaxWidth()) {Text(stringResource(if(saving)R.string.advanced_saving else R.string.advanced_save))}
        OutlinedButton(onClick={cancel()},enabled=!saving && !generating,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.advanced_cancel))}
    }
    if(confirmRaw) AlertDialog(onDismissRequest={confirmRaw=false},title={Text(stringResource(R.string.advanced_raw_title))},text={Text(stringResource(R.string.advanced_raw_warning))},
        confirmButton={TextButton(onClick={
            confirmRaw=false
            // Opening raw text is not a validation operation: empty/invalid drafts can paste a complete .conf.
            // A serialization failure never destroys the existing structured draft.
            val text = if(profileId == null && draft == WireGuardDraft(profileName=draft.profileName)) ""
                else try { draft.toConfigText() } catch (_: Exception) { "" }
            rawText=text; rawAtOpen=text; rawMode=true; error=null
        }) {Text(stringResource(R.string.advanced_raw_open))}},dismissButton={TextButton(onClick={confirmRaw=false}) {Text(stringResource(R.string.advanced_cancel))}})
    if(confirmRawDiscard) AlertDialog(onDismissRequest={confirmRawDiscard=false},title={Text(stringResource(R.string.advanced_raw_discard_title))},text={Text(stringResource(R.string.advanced_raw_discard_body))},
        confirmButton={TextButton(onClick={confirmRawDiscard=false;leaveRawWithoutApplying()}) {Text(stringResource(R.string.advanced_raw_discard))}},
        dismissButton={TextButton(onClick={confirmRawDiscard=false}) {Text(stringResource(R.string.advanced_continue))}})
    if(confirmDiscard) AlertDialog(onDismissRequest={confirmDiscard=false},title={Text(stringResource(R.string.advanced_discard_title))},text={Text(stringResource(R.string.advanced_discard_body))},
        confirmButton={TextButton(onClick={confirmDiscard=false;dirtyCallback(false);onCancel()}) {Text(stringResource(R.string.advanced_discard))}},
        dismissButton={TextButton(onClick={confirmDiscard=false}) {Text(stringResource(R.string.advanced_continue))}})
    generateTarget?.let { target -> AlertDialog(onDismissRequest={generateTarget=null},title={Text(stringResource(R.string.advanced_replace_key_title))},text={Text(stringResource(R.string.advanced_replace_key_body))},
        confirmButton={TextButton(onClick={generateTarget=null;generate(target)}) {Text(stringResource(R.string.advanced_generate))}},
        dismissButton={TextButton(onClick={generateTarget=null}) {Text(stringResource(R.string.advanced_cancel))}}) }
}

@Composable private fun DraftField(
    label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    hint: Int? = null,
    secret: Boolean = false,
    numeric: Boolean = false
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value=value,onValueChange=onValueChange,enabled=enabled,modifier=Modifier.fillMaxWidth(),singleLine=true,
        label={Text(stringResource(label))},
        supportingText=hint?.let { resource -> @Composable {Text(stringResource(resource))} },
        visualTransformation=if(secret && !revealed)PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=if(secret)KeyboardType.Password else if(numeric)KeyboardType.Number else KeyboardType.Ascii),
        trailingIcon=if(secret) { @Composable {TextButton(onClick={revealed=!revealed},enabled=enabled) {Text(stringResource(if(revealed)R.string.advanced_hide else R.string.advanced_show))}} } else null
    )
}
private class EditorTunnelRunning : Exception()
private fun advancedError(code: String): Int = when(code) {
    "INVALID_NAME"->R.string.advanced_error_name
    "PROFILE_TOO_LARGE"->R.string.advanced_error_size
    "PROFILE_LIMIT"->R.string.advanced_error_limit
    "MISSING_ADDRESS"->R.string.advanced_error_address
    "MISSING_PEER"->R.string.advanced_error_peer
    "MISSING_ENDPOINT"->R.string.advanced_error_endpoint
    "MISSING_ROUTES"->R.string.advanced_error_routes
    "INVALID_AWG_CONFIG"->R.string.awg_error_config
    "INVALID_CONFIG"->R.string.advanced_error_config
    else->R.string.advanced_error_storage
}
