package art.graphite.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.mutableStateOf
import art.graphite.vpn.data.importing.IncomingImportRequest
import art.graphite.vpn.ui.screens.GraphiteApp

class MainActivity : ComponentActivity() {
    private val incoming=mutableStateOf<IncomingImportRequest?>(null)
    private var nextId=0L
    private fun accept(source:Intent?) {
        val value=when(source?.action) {
            Intent.ACTION_SEND->source.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW->source.dataString
            else->null
        }
        if(value!=null && value.length<=262144)incoming.value=IncomingImportRequest(++nextId,value)
        // Avoid replaying secret-bearing launch extras on recreation.
        setIntent(Intent(this,MainActivity::class.java))
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);accept(intent)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {GraphiteApp(incoming.value){incoming.value=null}}
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);accept(intent)}
}
