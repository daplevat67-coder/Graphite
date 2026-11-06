package art.graphite.vpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import art.graphite.vpn.R
import art.graphite.vpn.data.profile.ProfileSource
import art.graphite.vpn.data.profile.ProfileStore
import art.graphite.vpn.data.profile.StoredProfile
import art.graphite.vpn.domain.xray.XrayShareLink
import art.graphite.vpn.ui.components.SketchCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ConnectionFormats(profile: StoredProfile, store: ProfileStore) {
    var revealed by remember(profile.id) { mutableStateOf(false) }
    SketchCard {
        Text(stringResource(R.string.connection_format), style = MaterialTheme.typography.titleMedium)
        val source = when (profile.source) {
            ProfileSource.LINK -> R.string.connection_link
            ProfileSource.PARAMETERS -> R.string.connection_parameters
            ProfileSource.JSON -> R.string.connection_json
            ProfileSource.FILE -> R.string.connection_file
            ProfileSource.UNKNOWN -> R.string.connection_unknown_source
        }
        Text(stringResource(R.string.connection_source, stringResource(source)))
        TextButton(onClick = { revealed = !revealed }) {
            Text(stringResource(if (revealed) R.string.connection_hide else R.string.connection_reveal))
        }
        if (revealed) ConnectionFormatValues(profile, store)
    }
}

@Composable
private fun ConnectionFormatValues(profile: StoredProfile, store: ProfileStore) {
    val context = LocalContext.current
    SecureWindow()

    var formats by remember(profile.id) { mutableStateOf<Map<Int, String>?>(null) }
    var failure by remember(profile.id) { mutableStateOf(false) }
    var visible by remember(profile.id) {
        mutableStateOf(setOf(when (profile.source) {
            ProfileSource.LINK -> R.string.connection_link
            ProfileSource.JSON -> R.string.connection_json
            else -> R.string.connection_parameters
        }))
    }
    LaunchedEffect(profile.id, profile.source) {
        try {
            formats = withContext(Dispatchers.IO) {
                val raw = store.export(profile.id)
                val original = try { raw.toString(Charsets.UTF_8) } finally { raw.fill(0) }
                val xray = if (profile.protocolId in setOf("vless", "vmess", "socks5"))
                    store.xrayProfile(profile.id) else null
                val outbound = xray?.let {
                    JSONObject(art.graphite.vpn.domain.xray.XrayConfigGenerator.generate(it))
                        .getJSONArray("outbounds").getJSONObject(0)
                }
                val json = original.takeIf { it.startsWith('{') } ?: outbound?.toString(2)
                val link = if ("://" in original.substringBefore('\n') && !original.startsWith('{')) original
                    else xray?.let { XrayShareLink.encode(it, profile.name) }
                val parameters = if (outbound != null) parameterLines(outbound).joinToString("\n")
                else if (json != null) parameterLines(JSONObject(json)).joinToString("\n")
                else if (link == null) original
                else profile.endpoints.joinToString("\n")
                buildMap {
                    put(R.string.connection_parameters, parameters)
                    if (link != null) put(R.string.connection_link, link)
                    if (json != null) put(R.string.connection_json, json)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure = true
        }
        formats?.let { if (visible.intersect(it.keys).isEmpty()) visible = setOf(R.string.connection_parameters) }
    }
    if (failure) Text(stringResource(R.string.error_profile))
    formats?.forEach { (label, value) ->
        Column {
            val checked = label in visible
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { show ->
                    visible = if (show) visible + label else visible - label
                })
                Text(stringResource(label))
            }
            if (checked) {
                Text(value, style = MaterialTheme.typography.bodySmall)
                if (label != R.string.connection_parameters) TextButton(onClick = {
                    val clip = ClipData.newPlainText(context.getString(label), value)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                }) { Text(stringResource(R.string.connection_copy)) }
            }
        }
    }
}

private fun parameterLines(value: Any, path: String = ""): List<String> = when (value) {
    is JSONObject -> value.keys().asSequence().flatMap { key ->
        parameterLines(value.get(key), if (path.isEmpty()) key else "$path.$key").asSequence()
    }.toList()
    is org.json.JSONArray -> (0 until value.length()).flatMap { index ->
        parameterLines(value.get(index), "$path[${index + 1}]")
    }
    else -> listOf("$path = $value")
}
