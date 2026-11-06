package art.graphite.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.net.VpnProfileState
import android.os.Build
import androidx.annotation.RequiresApi
import art.graphite.vpn.domain.extra.IkeProfile

/** API 33+ exposes the platform state needed to show a truthful connection status. */
internal class PlatformIke(private val context: Context) {
    private val prefs = context.getSharedPreferences("platform-ike", Context.MODE_PRIVATE)
    @get:RequiresApi(33)
    private val manager get() = checkNotNull(context.getSystemService(VpnManager::class.java))

    @RequiresApi(33)
    fun provision(profile: IkeProfile): Intent? {
        val key = profile.key()
        return try {
            val platform = Ikev2VpnProfile.Builder(profile.server, profile.identity)
                .setAuthPsk(key).setBypassable(false).build()
            manager.provisionVpnProfile(platform)
        } finally { key.fill(0) }
    }

    @RequiresApi(33)
    fun start(id: String) {
        check(prefs.edit().putString("profile", id).commit())
        try { manager.startProvisionedVpnProfileSession() }
        catch (failure: Exception) {
            prefs.edit().remove("profile").apply()
            throw failure
        }
    }

    @RequiresApi(33)
    fun discard() {
        manager.deleteProvisionedVpnProfile()
    }

    @RequiresApi(33)
    fun stop() {
        check(manager.provisionedVpnProfileState?.isAlwaysOn != true)
        manager.stopProvisionedVpnProfile()
        discard()
        prefs.edit().remove("profile").apply()
    }

    fun snapshot(): VpnSnapshot? {
        if (Build.VERSION.SDK_INT < 33) return null
        val id = prefs.getString("profile", null) ?: return null
        val state = manager.provisionedVpnProfileState ?: return null
        val phase = when (state.state) {
            VpnProfileState.STATE_CONNECTING -> VpnPhase.STARTING
            VpnProfileState.STATE_CONNECTED -> VpnPhase.UP
            VpnProfileState.STATE_FAILED -> VpnPhase.ERROR
            else -> return null
        }
        return VpnSnapshot(
            statisticsEnabled = false,
            phase = phase,
            profileId = id,
            error = if (phase == VpnPhase.ERROR) VpnFailure.START else null,
            failureStage = if (phase == VpnPhase.ERROR) VpnStage.TUNNEL_START else null,
            diagnostic = if (phase == VpnPhase.ERROR) VpnDiagnostic.UNABLE_TO_START_VPN else null,
            alwaysOn = state.isAlwaysOn,
            lockdown = state.isLockdownEnabled,
        )
    }
}
