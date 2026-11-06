package art.graphite.vpn.ui.screens
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
import art.graphite.vpn.data.profile.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@Composable internal fun ProfileEditor(id:String?,xray:Boolean,onSaved:()->Unit,onCancel:()->Unit,onDirtyChanged:(Boolean)->Unit){
 if(!xray || id==null){AdvancedEditor(id,onSaved,onCancel,onDirtyChanged);return}
 val context=LocalContext.current;val scope=rememberCoroutineScope();val store=remember{ProfileStore(context)}
 var name by remember{mutableStateOf("")};var link by remember{mutableStateOf("")};var loaded by remember{mutableStateOf(false)};var saving by remember{mutableStateOf(false)};var failure by remember{mutableStateOf(false)}
 SecureWindow { link = "" }
 LaunchedEffect(id){try{val result=withContext(Dispatchers.IO){val p=store.profile(id);val raw=store.export(id);try{p.name to raw.toString(Charsets.UTF_8)}finally{raw.fill(0)}};name=result.first;link=result.second;loaded=true}catch(_:Exception){failure=true}}
 Text(stringResource(R.string.xray_edit_title),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.xray_edit_hint))
 OutlinedTextField(name,{name=it;onDirtyChanged(true)},label={Text(stringResource(R.string.import_profile_name))},enabled=loaded && !saving,modifier=Modifier.fillMaxWidth())
 OutlinedTextField(link,{if(it.length<=65536){link=it;onDirtyChanged(true)}},label={Text(stringResource(R.string.xray_share_link))},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=loaded && !saving,modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6)
 if(failure)Text(stringResource(R.string.xray_edit_failed))
 Button(onClick={saving=true;scope.launch{val raw=link.toByteArray();try{withContext(Dispatchers.IO){store.update(id,name,raw)};onDirtyChanged(false);onSaved()}catch(_:Exception){failure=true}finally{raw.fill(0);saving=false}}},enabled=loaded && !saving,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.options_save))}
 TextButton(onClick=onCancel,enabled=!saving){Text(stringResource(R.string.cancel))}
}
