package art.graphite.vpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import art.graphite.vpn.R
import art.graphite.vpn.data.profile.*
import art.graphite.vpn.data.subscription.SubscriptionDecoder
import art.graphite.vpn.data.importing.HttpsConfigFetcher
import art.graphite.vpn.ui.components.SketchCard
import art.graphite.vpn.vpn.VpnRuntime
import art.graphite.vpn.vpn.VpnPhase
import kotlinx.coroutines.*
import java.util.UUID

@Composable
internal fun SubscriptionsScreen(store: ProfileStore, locked: Boolean, initialUrl: String = "", onChanged: suspend () -> Unit) {
    SecureWindow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<SubscriptionRecord>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(initialUrl.takeIf { it.startsWith("https://",true) }.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<SubscriptionRecord?>(null) }
    suspend fun load() { records = withContext(Dispatchers.IO) { store.subscriptions() } }
    LaunchedEffect(Unit) { try { load() } catch (_: Exception) { message = R.string.storage_error } }
    fun idle() = VpnRuntime.state.value.phase in setOf(VpnPhase.DOWN, VpnPhase.ERROR)
    fun refresh(items: List<SubscriptionRecord>) {
        if (busy || !idle()) return
        busy = true; message = null
        scope.launch {
            var failed = false
            try {
                for (item in items) {
                    try {
                        val body = HttpsConfigFetcher.fetch(SubscriptionDecoder.url(item.url))
                        withContext(Dispatchers.IO) {
                            val entries = SubscriptionDecoder.decode(body)
                            try {
                                check(idle())
                                store.replaceSubscription(item.id, item.name, item.url, entries)
                            } finally { entries.forEach { it.bytes.fill(0) } }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                }
                load(); onChanged()
                if (!failed) { name = ""; url = "" }
                message = if (failed) R.string.subscription_failed else R.string.subscription_updated
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = R.string.storage_error }
            finally { busy = false }
        }
    }
    val enabled = !busy && !locked
    Text(stringResource(R.string.subscriptions_hint))
    if (locked) Text(stringResource(R.string.subscription_stop_vpn))
    SketchCard {
        OutlinedTextField(name, { if(it.length<=80) name=it }, label={Text(stringResource(R.string.subscription_name))}, enabled=enabled, singleLine=true, modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url, { if(it.length<=4096) url=it }, label={Text(stringResource(R.string.subscription_url))}, enabled=enabled, singleLine=true, visualTransformation=PasswordVisualTransformation(), modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={url=readProfileClipboard(context).orEmpty().take(4096)},enabled=enabled) { Text(stringResource(R.string.paste_import)) }
        Button(onClick={
            if (runCatching { SubscriptionDecoder.url(url) }.isFailure || name.isBlank()) message=R.string.subscription_invalid
            else refresh(listOf(SubscriptionRecord(UUID.randomUUID().toString(),name.trim(),url.trim(),emptyList(),0)))
        },enabled=enabled && name.isNotBlank() && url.isNotBlank()) { Text(stringResource(R.string.subscription_add)) }
    }
    if (records.isNotEmpty()) OutlinedButton(onClick={refresh(records)},enabled=enabled) { Text(stringResource(R.string.subscription_refresh_all)) }
    if (busy) CircularProgressIndicator()
    message?.let { Text(stringResource(it)) }
    records.forEach { item ->
        SketchCard {
            Text(item.name, style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.subscription_count,item.profileIds.size))
            Text(stringResource(R.string.subscription_date,java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.updatedAt))))
            OutlinedButton(onClick={refresh(listOf(item))},enabled=enabled) { Text(stringResource(R.string.subscription_refresh)) }
            TextButton(onClick={pendingDelete=item},enabled=enabled) { Text(stringResource(R.string.subscription_delete)) }
        }
    }
    pendingDelete?.let { item ->
        AlertDialog(onDismissRequest={pendingDelete=null},title={Text(stringResource(R.string.subscription_delete))},
            text={Text(stringResource(R.string.subscription_delete_hint,item.name))},
            confirmButton={TextButton(enabled=enabled,onClick={
                pendingDelete=null
                if (idle() && !busy) { busy=true;scope.launch {
                    try { withContext(Dispatchers.IO){check(idle());store.deleteSubscription(item.id)};load();onChanged() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_:Exception){message=R.string.storage_error}
                    finally {busy=false}
                } }
            }){Text(stringResource(R.string.subscription_delete))}},
            dismissButton={TextButton(onClick={pendingDelete=null}){Text(stringResource(R.string.cancel))}})
    }
}
