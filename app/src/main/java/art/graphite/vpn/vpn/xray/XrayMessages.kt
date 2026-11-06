package art.graphite.vpn.vpn.xray
import android.os.Bundle
import art.graphite.vpn.vpn.*
/** Safe Binder metadata; no keys, JSON configuration or share links. */
internal object XrayMessages {
 const val CONTROL="art.graphite.vpn.XRAY_CONTROL";const val STOP="art.graphite.vpn.XRAY_STOP"
 const val REGISTER=1;const val DISCONNECT=2;const val QUERY=3;const val VISIBLE=4;const val STATE=5
 fun encode(s:VpnSnapshot)=Bundle().apply {
  putString("phase",s.phase.name);putString("id",s.profileId)
  s.rxBytes?.let{putLong("rx",it)};s.txBytes?.let{putLong("tx",it)};s.rxBytesPerSecond?.let{putDouble("rxRate",it)};s.txBytesPerSecond?.let{putDouble("txRate",it)};s.sampledElapsedNanos?.let{putLong("sampled",it)}
  s.error?.let{putString("error",it.name)};s.failureStage?.let{putString("stage",it.name)};s.diagnostic?.let{putString("diag",it.name)}
  putInt("always",s.alwaysOn?.let{if(it)1 else 0} ?: -1);putInt("lock",s.lockdown?.let{if(it)1 else 0} ?: -1)
  putBoolean("stats",s.statisticsEnabled);putBoolean("paused",s.statisticsPaused);s.effectiveMtu?.let{putInt("mtu",it)};s.effectiveDns?.let{putStringArrayList("dns",ArrayList(it))}
 }
 fun decode(b:Bundle):VpnSnapshot {
  fun tri(k:String)=when(b.getInt(k,-1)){0->false;1->true;else->null}
  return VpnSnapshot(phase=runCatching{VpnPhase.valueOf(b.getString("phase") ?: "ERROR")}.getOrDefault(VpnPhase.ERROR),profileId=b.getString("id"),rxBytes=if(b.containsKey("rx"))b.getLong("rx") else null,txBytes=if(b.containsKey("tx"))b.getLong("tx") else null,
   rxBytesPerSecond=if(b.containsKey("rxRate"))b.getDouble("rxRate") else null,txBytesPerSecond=if(b.containsKey("txRate"))b.getDouble("txRate") else null,sampledElapsedNanos=if(b.containsKey("sampled"))b.getLong("sampled") else null,
   error=runCatching{VpnFailure.valueOf(b.getString("error") ?: "")}.getOrNull(),failureStage=runCatching{VpnStage.valueOf(b.getString("stage") ?: "")}.getOrNull(),diagnostic=runCatching{VpnDiagnostic.valueOf(b.getString("diag") ?: "")}.getOrNull(),
   alwaysOn=tri("always"),lockdown=tri("lock"),statisticsEnabled=b.getBoolean("stats",true),statisticsPaused=b.getBoolean("paused",false),effectiveMtu=if(b.containsKey("mtu"))b.getInt("mtu") else null,effectiveDns=b.getStringArrayList("dns"))
 }
}
