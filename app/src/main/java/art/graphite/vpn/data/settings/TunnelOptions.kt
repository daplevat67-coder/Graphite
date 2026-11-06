package art.graphite.vpn.data.settings

import android.content.Context
import java.io.ByteArrayInputStream
import java.net.InetAddress
import org.amnezia.awg.config.Config

enum class AppRoutingMode { EXCLUDE, ONLY }
data class TunnelOptions(
    val overrideMtu:Boolean=false,val mtu:Int=1280,
    val overrideDns:Boolean=false,val dnsServers:String="1.1.1.1, 1.0.0.1",
    val overrideKeepalive:Boolean=false,val keepaliveSeconds:Int=25,
    val splitApplications:Boolean=false,val appRoutingMode:AppRoutingMode=AppRoutingMode.EXCLUDE,
    val applications:String="",
) { override fun toString()="TunnelOptions(redacted)" }
class TunnelOptionsStore(context:Context) {
    private val p=context.applicationContext.getSharedPreferences("tunnel-options",0)
    fun load()=TunnelOptions(p.getBoolean("mtu-enabled",false),p.getInt("mtu",1280),
        p.getBoolean("dns-enabled",false),p.getString("dns","1.1.1.1, 1.0.0.1") ?: "",
        p.getBoolean("keepalive-enabled",false),p.getInt("keepalive",25),
        p.getBoolean("split-enabled",false),runCatching {AppRoutingMode.valueOf(p.getString("app-mode","EXCLUDE") ?: "EXCLUDE")}.getOrDefault(AppRoutingMode.EXCLUDE),p.getString("apps","") ?: "")
    fun save(v:TunnelOptions) {
        TunnelConfigOverrides.validate(v)
        p.edit().putBoolean("mtu-enabled",v.overrideMtu).putInt("mtu",v.mtu)
            .putBoolean("dns-enabled",v.overrideDns).putString("dns",v.dnsServers)
            .putBoolean("keepalive-enabled",v.overrideKeepalive).putInt("keepalive",v.keepaliveSeconds)
            .putBoolean("split-enabled",v.splitApplications).putString("app-mode",v.appRoutingMode.name)
            .putString("apps",v.applications).apply()
    }
}
object TunnelConfigOverrides {
    fun entries(value:String)=value.split(Regex("[,\\s]+")).filter{it.isNotBlank()}.distinct()
    fun validate(v:TunnelOptions) {
        require(!v.overrideMtu || v.mtu in 1280..1500){"INVALID_MTU"}
        require(!v.overrideKeepalive || v.keepaliveSeconds in 0..65535){"INVALID_KEEPALIVE"}
        if(v.overrideDns) {
            val addresses=entries(v.dnsServers)
            require(addresses.size in 1..4 && addresses.all {s->
                if(s.contains(':'))s.matches(Regex("[a-fA-F0-9:.]+")) && runCatching{InetAddress.getByName(s).address.size==16}.getOrDefault(false)
                else s.split('.').let{it.size==4 && it.all{x->x.matches(Regex("[0-9]{1,3}")) && x.toInt() in 0..255}}
            }){"INVALID_DNS"}
        }
        if(v.splitApplications)require(entries(v.applications).let{it.size in 1..200 && it.all {x->x.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))}}){"INVALID_APPLICATIONS"}
    }
    /** Source profile is immutable; only enabled keys are replaced, all AWG fields survive. */
    fun apply(config:Config,v:TunnelOptions):Config {
        validate(v)
        if(!v.overrideMtu && !v.overrideDns && !v.overrideKeepalive && !v.splitApplications)return config
        val interf=linkedMapOf<String,String?>()
        if(v.overrideMtu)interf["MTU"]=v.mtu.toString()
        if(v.overrideDns)interf["DNS"]=entries(v.dnsServers).joinToString(", ")
        if(v.splitApplications){interf["IncludedApplications"]=null;interf["ExcludedApplications"]=null;interf[if(v.appRoutingMode==AppRoutingMode.ONLY)"IncludedApplications" else "ExcludedApplications"]=entries(v.applications).joinToString(", ")}
        val peer=if(v.overrideKeepalive)mapOf("PersistentKeepalive" to v.keepaliveSeconds.toString()) else emptyMap()
        val output=StringBuilder();var section=""
        fun fields():Map<String,String?> = when(section){"[Interface]"->interf;"[Peer]"->peer;else->emptyMap()}
        fun flush(){fields().forEach{(k,value)->if(value!=null)output.append(k).append(" = ").append(value).append('\n')}}
        config.toAwgQuickString().lineSequence().forEach {line->
            if(line.startsWith('[')){flush();section=line.trim();output.append(line).append('\n')}
            else if(fields().keys.none{it.equals(line.substringBefore('=').trim(),true)})output.append(line).append('\n')
        };flush()
        val bytes=output.toString().toByteArray(Charsets.UTF_8)
        return try{Config.parse(ByteArrayInputStream(bytes))}finally{bytes.fill(0)}
    }
}
