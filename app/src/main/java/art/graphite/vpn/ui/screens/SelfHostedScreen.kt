package art.graphite.vpn.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import art.graphite.vpn.R
import art.graphite.vpn.domain.graphite.SelfHostedDeploy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@Composable
internal fun SelfHostedScreen(onProfile: suspend (String) -> Unit) {
    SecureWindow()
    val assets = LocalContext.current.assets
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var vpnPort by remember { mutableStateOf("8443") }
    var hostKey by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var privateKey by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun install(fingerprint: String? = null) {
        val request = SelfHostedDeploy.Request(host, sshPort, vpnPort, fingerprint,
            secret, privateKey, passphrase)
        busy = true
        error = null
        hostKey = null
        scope.launch {
            try {
                val profile = runInterruptible(Dispatchers.IO) {
                    val server = assets.open("server.py").use { it.readBytes() }
                    val installer = assets.open("install-systemd.sh").use { it.readBytes() }
                    val quickInstaller = assets.open("install-quick.sh").use { it.readBytes() }
                    SelfHostedDeploy().install(request, server, installer, quickInstaller)
                }
                secret = ""
                passphrase = ""
                onProfile(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: SelfHostedDeploy.Failure) {
                if (fingerprint == null && failed.reason == SelfHostedDeploy.Reason.HOST_KEY &&
                    failed.observedFingerprint != null
                ) {
                    hostKey = failed.observedFingerprint
                } else {
                    error = when (failed.reason) {
                        SelfHostedDeploy.Reason.INVALID_INPUT -> R.string.selfhost_invalid
                        SelfHostedDeploy.Reason.HOST_KEY -> R.string.selfhost_bad_fingerprint
                        SelfHostedDeploy.Reason.SSH -> R.string.selfhost_ssh_failed
                        SelfHostedDeploy.Reason.INSTALL -> R.string.selfhost_install_failed
                        SelfHostedDeploy.Reason.TIMEOUT -> R.string.selfhost_timeout
                    }
                }
            } catch (_: Exception) {
                error = R.string.selfhost_install_failed
            } finally {
                busy = false
            }
        }
    }

    fun rejectKey() {
        hostKey = null
        secret = ""
        passphrase = ""
    }

    hostKey?.let { candidate ->
        AlertDialog(
            onDismissRequest = ::rejectKey,
            title = { Text(stringResource(R.string.selfhost_fingerprint)) },
            text = { Text(candidate + "\n\n" + stringResource(R.string.selfhost_fingerprint_hint)) },
            confirmButton = {
                TextButton(onClick = { install(candidate) }) {
                    Text(stringResource(R.string.selfhost_confirm_key))
                }
            },
            dismissButton = {
                TextButton(onClick = ::rejectKey) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column {
        Text(stringResource(R.string.selfhost_help))
        SelfHostedField(host, { host = it }, R.string.selfhost_host, !busy)
        TextButton(onClick = { advanced = !advanced }, enabled = !busy) {
            Text(stringResource(R.string.selfhost_ports))
        }
        if (advanced) {
            SelfHostedField(sshPort, { sshPort = it }, R.string.selfhost_ssh_port, !busy, numeric = true)
            SelfHostedField(vpnPort, { vpnPort = it }, R.string.selfhost_vpn_port, !busy, numeric = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = privateKey, onCheckedChange = {
                privateKey = it
                secret = ""
                passphrase = ""
            }, enabled = !busy)
            Text(stringResource(R.string.selfhost_use_key))
        }
        SelfHostedField(secret, { secret = it },
            if (privateKey) R.string.selfhost_ssh_private_key else R.string.selfhost_password,
            !busy, sensitive = true, multiline = privateKey)
        if (privateKey) SelfHostedField(passphrase, { passphrase = it }, R.string.selfhost_passphrase,
            !busy, sensitive = true)
        Button(
            enabled = !busy && host.isNotBlank() && secret.isNotBlank(),
            onClick = { install() },
        ) { Text(stringResource(R.string.selfhost_install)) }
        if (busy) {
            CircularProgressIndicator()
            Text(stringResource(R.string.selfhost_installing))
        }
        error?.let { Text(stringResource(it)) }
    }
}

@Composable
private fun SelfHostedField(
    value: String, update: (String) -> Unit, label: Int, enabled: Boolean,
    numeric: Boolean = false, sensitive: Boolean = false, multiline: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= if (multiline) 32768 else 1024) update(it) },
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = !multiline,
        maxLines = if (multiline) 4 else 1,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            numeric -> KeyboardType.Number
            sensitive -> KeyboardType.Password
            else -> KeyboardType.Uri
        }),
    )
}
