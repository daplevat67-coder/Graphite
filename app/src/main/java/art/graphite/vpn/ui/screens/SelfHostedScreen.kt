package art.graphite.vpn.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
internal fun SelfHostedScreen(onProfile: (String) -> Unit) {
    SecureWindow()
    val assets = LocalContext.current.assets
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var domain by remember { mutableStateOf("") }
    var vpnPort by remember { mutableStateOf("8443") }
    var fingerprint by remember { mutableStateOf("") }
    var certificate by remember { mutableStateOf("") }
    var certificateKey by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    Column {
        Text(stringResource(R.string.selfhost_help))
        SelfHostedField(host, { host = it }, R.string.selfhost_host, !busy)
        SelfHostedField(sshPort, { sshPort = it }, R.string.selfhost_ssh_port, !busy, numeric = true)
        SelfHostedField(domain, { domain = it }, R.string.selfhost_domain, !busy)
        SelfHostedField(vpnPort, { vpnPort = it }, R.string.selfhost_vpn_port, !busy, numeric = true)
        SelfHostedField(fingerprint, { fingerprint = it }, R.string.selfhost_fingerprint, !busy)
        Text(stringResource(R.string.selfhost_fingerprint_hint))
        SelfHostedField(certificate, { certificate = it }, R.string.selfhost_certificate, !busy)
        SelfHostedField(certificateKey, { certificateKey = it }, R.string.selfhost_certificate_key, !busy)
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
            enabled = !busy && listOf(host, sshPort, domain, vpnPort, fingerprint, certificate, certificateKey, secret).all { it.isNotBlank() },
            onClick = {
                val request = SelfHostedDeploy.Request(host, sshPort, domain, vpnPort, fingerprint,
                    certificate.trim(), certificateKey.trim(), secret, privateKey, passphrase)
                secret = ""
                passphrase = ""
                busy = true
                error = null
                scope.launch {
                    try {
                        val profile = runInterruptible(Dispatchers.IO) {
                            val server = assets.open("server.py").use { it.readBytes() }
                            val installer = assets.open("install-systemd.sh").use { it.readBytes() }
                            SelfHostedDeploy().install(request, server, installer)
                        }
                        onProfile(profile)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failed: SelfHostedDeploy.Failure) {
                        error = when (failed.reason) {
                            SelfHostedDeploy.Reason.INVALID_INPUT -> R.string.selfhost_invalid
                            SelfHostedDeploy.Reason.HOST_KEY -> R.string.selfhost_bad_fingerprint
                            SelfHostedDeploy.Reason.SSH -> R.string.selfhost_ssh_failed
                            SelfHostedDeploy.Reason.INSTALL -> R.string.selfhost_install_failed
                            SelfHostedDeploy.Reason.TIMEOUT -> R.string.selfhost_timeout
                        }
                    } catch (_: Exception) {
                        error = R.string.selfhost_install_failed
                    } finally {
                        busy = false
                    }
                }
            },
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
