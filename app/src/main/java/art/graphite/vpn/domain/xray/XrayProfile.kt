package art.graphite.vpn.domain.xray

/** Immutable client settings. Never log this object or its original link. */
data class XrayProfile(
    val protocol:String,val host:String,val port:Int,val uuid:String,
    val network:String="tcp",val security:String="none",val name:String?=null,
    val serverName:String="",val fingerprint:String="",val alpn:List<String> = emptyList(),
    val flow:String="",val cipher:String="auto",val path:String="/",val transportHost:String="",
    val serviceName:String="",val grpcMode:String="gun",val authority:String="",
    val realityPublicKey:String="",val realityShortId:String="",val spiderX:String="/",
    val xhttpMode:String="auto",val headerType:String="none",val originalShareLink:String,
    val transportOptions:String="", val vlessEncryption:String="none",
    val realityVerification:String="", val tlsEch:String="",
    val proxyUser:String="",val proxyPassword:String="",
) {
    val endpoint:String get()=if(host.contains(':'))"[$host]:$port" else "$host:$port"
    override fun toString()="XrayProfile(redacted)"
}
class XrayProfileException(val safeCode:String):Exception(safeCode)
