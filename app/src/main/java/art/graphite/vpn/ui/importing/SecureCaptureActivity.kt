package art.graphite.vpn.ui.importing
import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
/** QR data can contain private keys. Never save barcode images or allow screenshots. */
class SecureCaptureActivity:CaptureActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
