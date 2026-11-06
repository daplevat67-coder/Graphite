package art.graphite.vpn.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

// Compose can keep two notebook pages mounted during a turn. Each page holds a lease.
private class SecureWindowLease(val wasSecure: Boolean, var count: Int = 0)
private val secureWindows = mutableMapOf<Window, SecureWindowLease>()

private tailrec fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.activityWindow()
    else -> null
}

@Composable
internal fun SecureWindow(onRelease: () -> Unit = {}) {
    val window = LocalContext.current.activityWindow()
    val release = rememberUpdatedState(onRelease)
    DisposableEffect(window) {
        val lease = window?.let {
            secureWindows.getOrPut(it) {
                SecureWindowLease(it.attributes.flags and FLAG_SECURE != 0).also { _ ->
                    it.addFlags(FLAG_SECURE)
                }
            }.also { it.count++ }
        }
        onDispose {
            try {
                release.value()
            } finally {
                if (window != null && lease != null && --lease.count == 0) {
                    secureWindows.remove(window)
                    if (!lease.wasSecure) window.clearFlags(FLAG_SECURE)
                }
            }
        }
    }
}
