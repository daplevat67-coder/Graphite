package art.graphite.vpn.ui.format
import java.util.Locale
object TrafficFormat {
    fun rate(bytesPerSecond:Double?,megabits:Boolean):String? {
        val n=bytesPerSecond?.takeIf{it.isFinite() && it>=0} ?: return null
        return String.format(Locale.ROOT,"%.2f %s",n/(if(megabits)125000.0 else 1024.0),if(megabits)"Мбит/с" else "КиБ/с")
    }
}
