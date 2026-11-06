package art.graphite.vpn.ui.screens

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import art.graphite.vpn.R
import art.graphite.vpn.data.importing.*
import art.graphite.vpn.ui.components.SketchCard
import art.graphite.vpn.ui.importing.SecureCaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun readProfileClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    return if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
}

@Composable internal fun ProfileImportScreen(initialText:String?,onSeedConsumed:()->Unit,
    onFile:(()->Unit)?=null,onWireGuard:(()->Unit)?=null,onVless:(()->Unit)?=null,onImport:suspend(String,ByteArray)->Boolean) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var text by remember{mutableStateOf(initialText ?: "")}
    var name by remember{mutableStateOf(context.getString(R.string.import_name_default))}
    var payload by remember{mutableStateOf<ImportPayload?>(null)}
    var running by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<Int?>(null)}
    fun replace(next:ImportPayload?) {(payload as? ImportPayload.ConfigPayload)?.config?.fill(0);payload=next}
    fun read(value:String) {replace(null);text=value.take(262144);error=null}
    DisposableEffect(Unit) {
        val window=(context as? Activity)?.window
        val secure=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {(payload as? ImportPayload.ConfigPayload)?.config?.fill(0);if(!secure)window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    LaunchedEffect(Unit){onSeedConsumed()}
    val scanner=rememberLauncherForActivityResult(ScanContract()){result->result.contents?.let(::read)}
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){running=true;scope.launch {
            try {read(withContext(Dispatchers.IO){QrImageReader.read(context,uri)})}
            catch(e:CancellationException){throw e}catch(_:Exception){error=R.string.qr_read_error}
            finally{running=false}
        }}
    }
    fun decode(value:String) {
        running=true;error=null
        scope.launch {
            try {
                val result=withContext(Dispatchers.Default){ImportPayloadDecoder.decode(value)}
                replace(result);text=""
                if(result is ImportPayload.Unsupported)error=if(result.reason==art.graphite.vpn.data.importing.ImportRejection.LEGACY_XRAY_TRANSPORT) R.string.import_legacy_xray_transport else R.string.import_format_unsupported
            }catch(e:CancellationException){throw e}catch(_:Exception){error=R.string.import_decode_error}
            finally{running=false}
        }
    }
    SketchCard {
        onVless?.let { OutlinedButton(onClick=it,enabled=!running,modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.vless_create))
        } }
        onWireGuard?.let { OutlinedButton(onClick=it,enabled=!running,modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.connection_manual_wg))
        } }
        onFile?.let { OutlinedButton(onClick=it,enabled=!running,modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.import_profile))
        } }
        Text(stringResource(R.string.import_supported),style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick={scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setCaptureActivity(SecureCaptureActivity::class.java).setPrompt(context.getString(R.string.scan_prompt)).setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false))},enabled=!running,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.scan_camera))}
        OutlinedButton(onClick={gallery.launch(arrayOf("image/*"))},enabled=!running,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.scan_image))}
        OutlinedButton(onClick={val value=readProfileClipboard(context);if(value!=null)read(value) else error=R.string.clipboard_empty},enabled=!running,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.paste_import))}
        OutlinedTextField(value=text,onValueChange={if(it.length<=262144)read(it)},label={Text(stringResource(R.string.import_text))},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4,enabled=!running)
        Button(onClick={decode(text)},enabled=!running && text.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.import_check))}
        if(running)Text(stringResource(R.string.import_working),style=MaterialTheme.typography.labelMedium)
        error?.let{Text(stringResource(it),style=MaterialTheme.typography.bodyMedium)}
    }
    when(val current=payload) {
        is ImportPayload.HttpsConfigLink->SketchCard {
            Text(stringResource(R.string.import_download_title),style=MaterialTheme.typography.titleMedium)
            Text(current.uri.host,style=MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.import_download_hint),style=MaterialTheme.typography.bodyMedium)
            Button(onClick={running=true;error=null;scope.launch {
                try {
                    val body=HttpsConfigFetcher.fetch(current.uri)
                    val decoded=withContext(Dispatchers.Default){ImportPayloadDecoder.decode(body)}
                    if(decoded is ImportPayload.ConfigPayload)replace(decoded)
                    else {replace(null);error=R.string.import_format_unsupported}
                }catch(e:CancellationException){throw e}catch(_:Exception){error=R.string.import_download_error}
                finally{running=false}
            }},enabled=!running,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.import_download_confirm))}
        }
        is ImportPayload.ConfigPayload->SketchCard {
            Text(stringResource(R.string.import_config_ready),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.import_config_hint),style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value=name,onValueChange={if(it.length<=80)name=it},label={Text(stringResource(R.string.import_profile_name))},modifier=Modifier.fillMaxWidth(),enabled=!running)
            Button(onClick={running=true;error=null;scope.launch {
                try {if(onImport(name.trim(),current.config))replace(null) else error=R.string.import_error}
                catch(e:CancellationException){throw e}catch(_:Exception){error=R.string.import_error}
                finally{running=false}
            }},enabled=!running && name.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.import_save))}
        }
        else->Unit
    }
}
