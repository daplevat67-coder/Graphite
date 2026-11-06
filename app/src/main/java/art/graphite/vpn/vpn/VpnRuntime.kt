package art.graphite.vpn.vpn

import android.os.Handler
import android.os.Looper
import art.graphite.vpn.vpn.xray.XrayVpnClient
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import art.graphite.vpn.data.settings.TunnelOptions
import art.graphite.vpn.data.settings.TunnelOptionsStore
import art.graphite.vpn.data.settings.TunnelConfigOverrides
import art.graphite.vpn.data.settings.ConnectionSettings
import art.graphite.vpn.data.settings.ConnectionSettingsStore
import art.graphite.vpn.data.profile.ProfileStore
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One serialized native tunnel. Only Android always-on may restore a selected profile. */
object VpnRuntime {
    private data class Request(val generation: Long, val profileId: String, val options: ConnectionSettings, val tunnelOptions:TunnelOptions)
    private val gate = Any()
    private val mutableState = MutableStateFlow(VpnSnapshot())
    val state: StateFlow<VpnSnapshot> = mutableState.asStateFlow()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "graphite-vpn").apply { isDaemon = true }
    }
    @Volatile private var uiVisible=false
    fun setUiVisible(visible:Boolean){uiVisible=visible;XrayVpnClient.setVisible(visible)}
    @Volatile private var xrayActive=false
    fun attach(context:Context){XrayVpnClient.observe(context,::remoteState)}
    private fun remoteState(value:VpnSnapshot) {
        if(!xrayActive && request!=null)return
        if(!xrayActive && value.phase !in setOf(VpnPhase.UP,VpnPhase.STARTING))return
        synchronized(gate){
            val terminal=value.phase in setOf(VpnPhase.DOWN,VpnPhase.ERROR)
            xrayActive=!terminal
            // Retire the old binding before exposing DOWN to a new caller.
            if(terminal)XrayVpnClient.detach()
            mutableState.value=value
        }
    }
    private var generation = 0L
    @Volatile private var request: Request? = null
    @Volatile private var service: GraphiteVpnService? = null
    // Native/backend fields are accessed exclusively by worker.
    private var backend: GoBackend? = null
    private var backendOwner: GraphiteVpnService? = null
    private var sampling: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var retryAttempt = 0
    private val tunnel = object : Tunnel {
        override fun getName() = "graphite"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    /** Caller handles the system permission activity before invoking this method. */
    fun connect(context: Context, profileId: String, protocolId:String="wg") {
        if(protocolId in setOf("vless","vmess","socks5","graphite")) {
            synchronized(gate){if(mutableState.value.phase in setOf(VpnPhase.STARTING,VpnPhase.UP,VpnPhase.STOPPING))return;xrayActive=true;request=null;++generation;mutableState.value=VpnSnapshot(phase=VpnPhase.STARTING,profileId=profileId)}
            XrayVpnClient.start(context,profileId,::remoteState);return
        }
        val app = context.applicationContext
        val next = synchronized(gate) {
            // Disconnect before switching; old stopSelf must not tear down a new session.
            if (mutableState.value.phase in setOf(VpnPhase.STARTING, VpnPhase.UP, VpnPhase.STOPPING)) return
            val permission = try { VpnService.prepare(app) == null } catch (_: Exception) { false }
            if (!permission) {
                mutableState.value = VpnSnapshot(phase = VpnPhase.ERROR, profileId = profileId, error = VpnFailure.PERMISSION,
                    failureStage = VpnStage.CONSENT, diagnostic = VpnDiagnostic.VPN_CONSENT_REQUIRED,
                    events = event(mutableState.value.events, VpnEventKind.ERROR))
                return
            }
            val next = Request(++generation, profileId, ConnectionSettingsStore(app).load(),TunnelOptionsStore(app).load())
            request = next
            mutableState.value = VpnSnapshot(phase = VpnPhase.STARTING, profileId = profileId,
                events = event(mutableState.value.events, VpnEventKind.STARTING))
            next
        }
        try {
            app.startForegroundService(Intent(app, GraphiteVpnService::class.java)
                .putExtra(GraphiteVpnService.EXTRA_GENERATION, next.generation))
            worker.schedule({
                synchronized(gate) {
                    if (request == next && service == null && mutableState.value.phase == VpnPhase.STARTING) {
                        request = null
                        val problem = VpnProblem(VpnStage.SERVICE_START, VpnDiagnostic.SERVICE_READY_TIMEOUT)
                        mutableState.value = mutableState.value.copy(phase = VpnPhase.ERROR,
                            error = VpnFailure.START, failureStage = problem.stage, diagnostic = problem.diagnostic,
                            events = event(mutableState.value.events, VpnEventKind.ERROR, problem))
                        try { app.stopService(Intent(app, GraphiteVpnService::class.java)) } catch (_: Exception) { }
                    }
                }
            }, next.options.serviceStartTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (failure: Exception) {
            val problem = VpnErrors.classify(failure, VpnStage.SERVICE_START)
            synchronized(gate) {
                if (request == next) {
                    request = null
                    mutableState.value = mutableState.value.copy(phase = VpnPhase.ERROR,
                        error = VpnFailure.START, failureStage = problem.stage, diagnostic = problem.diagnostic,
                        nativeErrorCode = problem.nativeErrorCode, events = event(mutableState.value.events, VpnEventKind.ERROR, problem))
                }
            }
            try { app.stopService(Intent(app, GraphiteVpnService::class.java)) } catch (_: Exception) { }
        }
    }

    fun disconnect(context: Context) {
        if(xrayActive){if(mutableState.value.alwaysOn!=true){mutableState.value=mutableState.value.copy(phase=VpnPhase.STOPPING);XrayVpnClient.stop()};return}
        // Always-on is controlled from Android settings. Keep its mandatory tunnel alive.
        refreshSystemProtection()
        if (mutableState.value.alwaysOn == true) return
        val target = synchronized(gate) {
            ++generation
            request = null
            val old = mutableState.value
            mutableState.value = old.copy(phase = if (old.phase == VpnPhase.DOWN) VpnPhase.DOWN else VpnPhase.STOPPING,
                error = null, failureStage = null, diagnostic = null, nativeErrorCode = null,
                rxBytesPerSecond = null, txBytesPerSecond = null)
            service
        }
        // Also cancels a start that Android has accepted but not delivered yet.
        try { context.applicationContext.stopService(Intent(context.applicationContext, GraphiteVpnService::class.java)) }
        catch (_: Exception) { }
        worker.execute {
            cancelSampling()
            cancelRetry()
            if (target != null) {
                try {target.showStopping()}catch(_:Exception){}
                stopBackend(target)
                target.stopSelf()
            } else synchronized(gate) {
                if (request == null && service == null) finishStopped()
            }
        }
    }

    internal fun created(target: GraphiteVpnService) {
        service = target
        worker.execute {
            if (!target.foregroundReady || target.destroyed) target.stopSelf()
        }
    }

    /** Unflagged starts are delivered by Android for always-on; profile I/O stays off main. */
    internal fun started(target: GraphiteVpnService, requestedGeneration: Long, fromSystem: Boolean = false) {
        worker.execute {
            val observedGeneration = synchronized(gate) { generation }
            if (fromSystem && request == null && service === target && !target.destroyed && target.foregroundReady) {
                val selected = try { ProfileStore(target).selected() } catch (_: Exception) { null }
                if (selected == null) {
                    synchronized(gate) {
                        mutableState.value = VpnSnapshot(phase = VpnPhase.ERROR, error = VpnFailure.PROFILE,
                            failureStage = VpnStage.PROFILE_LOAD, diagnostic = VpnDiagnostic.PROFILE_UNAVAILABLE)
                    }
                    target.stopSelf()
                    return@execute
                }
                if(selected.isIke){target.stopSelf();return@execute}
                if(selected.isXray){
                    synchronized(gate){if(generation!=observedGeneration || target.destroyed)return@execute;xrayActive=true;request=null;mutableState.value=VpnSnapshot(phase=VpnPhase.STARTING,profileId=selected.id)}
                    Handler(Looper.getMainLooper()).post{XrayVpnClient.start(target.applicationContext,selected.id,::remoteState);target.stopSelf()};return@execute
                }
                synchronized(gate) {
                    // A manual stop/revoke during encrypted profile I/O cancels this start.
                    if (generation != observedGeneration || request != null || target.destroyed || service !== target)
                        return@execute
                    request = Request(++generation, selected.id, ConnectionSettingsStore(target).load(),TunnelOptionsStore(target).load())
                    mutableState.value = VpnSnapshot(phase = VpnPhase.STARTING, profileId = selected.id,
                        events = event(mutableState.value.events, VpnEventKind.STARTING))
                }
            }
            refreshSystemProtection()
            val pending = request
            if (pending == null || service !== target || target.destroyed || !target.foregroundReady) {
                target.stopSelf()
                return@execute
            }
            // Android can deliver a cancelled start after a newer request was
            // accepted for this same service instance. Ignore its stale command;
            // stopSelf here would also cancel the newer valid start.
            if (!fromSystem && pending.generation != requestedGeneration) return@execute
            if (mutableState.value.phase == VpnPhase.UP || retry?.isDone == false) return@execute
            if (mutableState.value.retryAttempt == 0) retryAttempt = 0
            val config = try { TunnelConfigOverrides.apply(ProfileStore(target).config(pending.profileId),pending.tunnelOptions) }
            catch (failure: Exception) { failAndStop(target, pending, VpnFailure.PROFILE, VpnErrors.classify(failure, VpnStage.PROFILE_LOAD)); return@execute }
            if (!isWanted(target, pending)) { target.stopSelf(); return@execute }
            try {
                if (VpnService.prepare(target) != null) {
                    failAndStop(target, pending, VpnFailure.PERMISSION, VpnProblem(VpnStage.CONSENT, VpnDiagnostic.VPN_CONSENT_REQUIRED))
                    return@execute
                }
                val engine = backend ?: GoBackend(target.applicationContext).also { backend = it }
                backendOwner = target
                engine.setState(tunnel, Tunnel.State.UP, config)
                if (!isWanted(target, pending)) {
                    stopBackend(target)
                    target.stopSelf()
                    return@execute
                }
                synchronized(gate) {
                    if (isWanted(target, pending)) mutableState.value = mutableState.value.copy(
                        phase = VpnPhase.UP, statisticsEnabled=pending.options.liveStatistics,
                        effectiveMtu=config.`interface`.mtu.orElse(1280),effectiveDns=config.`interface`.dnsServers.mapNotNull{it.hostAddress},
                        error = null, failureStage = null, diagnostic = null, nativeErrorCode = null,
                        events = event(mutableState.value.events, VpnEventKind.INTERFACE_UP), retryAtEpochMillis = null)
                }
                try { target.showRunning() } catch (_: Exception) { }
                beginSampling(target, pending)
            } catch (failure: Exception) {
                val problem = VpnErrors.classify(failure, VpnStage.TUNNEL_START)
                val kind = if (problem.diagnostic == VpnDiagnostic.VPN_NOT_AUTHORIZED) VpnFailure.PERMISSION else VpnFailure.START
                failAndStop(target, pending, kind, problem)
            } catch (failure: LinkageError) {
                failAndStop(target, pending, VpnFailure.START, VpnErrors.classify(failure, VpnStage.NATIVE_LOAD))
            }
        }
    }

    internal fun foregroundFailed(target: GraphiteVpnService, failure: Exception) {
        val problem = VpnErrors.classify(failure, VpnStage.FOREGROUND)
        synchronized(gate) {
            request = null
            ++generation
            mutableState.value = mutableState.value.copy(phase = VpnPhase.STOPPING, error = VpnFailure.START,
                failureStage = problem.stage, diagnostic = problem.diagnostic, nativeErrorCode = problem.nativeErrorCode,
                events = event(mutableState.value.events, VpnEventKind.ERROR, problem))
        }
    }

    internal fun revoked(target: GraphiteVpnService) {
        synchronized(gate) {
            if (service !== target) return
            request = null
            ++generation
            mutableState.value = mutableState.value.copy(phase = VpnPhase.STOPPING, error = VpnFailure.PERMISSION,
                failureStage = VpnStage.CONSENT, diagnostic = VpnDiagnostic.VPN_CONSENT_REVOKED, nativeErrorCode = null,
                rxBytesPerSecond = null, txBytesPerSecond = null,
                events = event(mutableState.value.events, VpnEventKind.REVOKED))
        }
        worker.execute { cancelSampling(); cancelRetry(); stopBackend(target) }
    }

    internal fun destroyed(target: GraphiteVpnService) {
        if (service === target) service = null
        worker.execute {
            cancelSampling()
            cancelRetry()
            stopBackend(target)
            target.removeNotification()
            synchronized(gate) {
                if (service == null && !xrayActive) {
                    request = null
                    finishStopped()
                }
            }
        }
    }

    private fun isWanted(target: GraphiteVpnService, pending: Request) =
        request == pending && service === target && !target.destroyed && target.foregroundReady

    private fun failAndStop(target: GraphiteVpnService, pending: Request, failure: VpnFailure, problem: VpnProblem) {
        cancelSampling()
        stopBackend(target)
        val delay = ReconnectPolicy.nextDelayMillis(pending.options, retryAttempt, problem.diagnostic,
            cleanupFailed = mutableState.value.error == VpnFailure.STOP)
        if (isWanted(target, pending) && delay != null) {
            val attempt = ++retryAttempt
            synchronized(gate) {
                if (!isWanted(target, pending)) return
                mutableState.value = mutableState.value.copy(phase = VpnPhase.STARTING, error = failure,
                    failureStage = problem.stage, diagnostic = problem.diagnostic, nativeErrorCode = problem.nativeErrorCode,
                    retryAttempt = attempt, retryAtEpochMillis = System.currentTimeMillis() + delay,
                    rxBytes = null, txBytes = null, rxBytesPerSecond = null, txBytesPerSecond = null,
                    lastHandshakeEpochMillis = null, sampledElapsedNanos = null,
                    events = event(mutableState.value.events, VpnEventKind.ERROR, problem))
            }
            try {target.showStarting()}catch(_:Exception){}
            retry = worker.schedule({
                retry = null
                if (isWanted(target, pending)) started(target, pending.generation)
            }, delay, TimeUnit.MILLISECONDS)
            return
        }
        synchronized(gate) {
            if (request == pending) {
                request = null
                mutableState.value = mutableState.value.copy(phase = VpnPhase.STOPPING, error = failure,
                    failureStage = problem.stage, diagnostic = problem.diagnostic, nativeErrorCode = problem.nativeErrorCode,
                    events = event(mutableState.value.events, VpnEventKind.ERROR, problem))
            }
        }
        target.stopSelf()
    }

    private fun stopBackend(target: GraphiteVpnService) {
        if (backendOwner !== target) return
        try { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
        catch (failure: Exception) {
            val problem = VpnErrors.classify(failure, VpnStage.TUNNEL_STOP)
            synchronized(gate) {
                mutableState.value = mutableState.value.copy(error = VpnFailure.STOP,
                    failureStage = problem.stage, diagnostic = problem.diagnostic, nativeErrorCode = problem.nativeErrorCode,
                    events = event(mutableState.value.events, VpnEventKind.ERROR, problem))
            }
        }
        backendOwner = null
    }

    private fun beginSampling(target: GraphiteVpnService, pending: Request) {
        cancelSampling()
        val rates = TransferRates()
        var handshakeReported = false
        var paused=false
        sampling = worker.scheduleWithFixedDelay({
            if (!isWanted(target, pending)) return@scheduleWithFixedDelay
            try {
                val engine = backend ?: return@scheduleWithFixedDelay
                if (engine.getState(tunnel) != Tunnel.State.UP) {
                    failAndStop(target, pending, VpnFailure.START,
                        VpnProblem(VpnStage.TUNNEL_START, VpnDiagnostic.UNABLE_TO_START_VPN))
                    return@scheduleWithFixedDelay
                }
                if(!NotificationTraffic.needsSampling(pending.options,uiVisible)) {
                    if(!paused) {
                        rates.reset();paused=true
                        synchronized(gate){if(isWanted(target,pending))mutableState.value=mutableState.value.copy(rxBytesPerSecond=null,txBytesPerSecond=null,statisticsPaused=true)}
                    }
                    if(isWanted(target,pending))try {target.showTraffic(mutableState.value,pending.options.notificationSpeed,pending.options.liveStatistics)}catch(_:Exception){}
                    return@scheduleWithFixedDelay
                }
                paused=false
                val stats = engine.getStatistics(tunnel)
                val rx = stats.totalRx()
                val tx = stats.totalTx()
                val sampledAt = System.nanoTime()
                val speed = rates.sample(rx, tx, sampledAt)
                val handshake = stats.peers().mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis() }
                    .filter { it > 0 }.maxOrNull()
                synchronized(gate) {
                    if (isWanted(target, pending)) {
                        val old = mutableState.value
                        val events = if (handshake != null && !handshakeReported) {
                            handshakeReported = true
                            event(old.events, VpnEventKind.HANDSHAKE)
                        } else old.events
                        mutableState.value = old.copy(rxBytes = rx, txBytes = tx,
                            rxBytesPerSecond = speed.first, txBytesPerSecond = speed.second,
                            lastHandshakeEpochMillis = handshake, sampledElapsedNanos = sampledAt,statisticsPaused=false,
                            error = null, failureStage = null, diagnostic = null, nativeErrorCode = null, events = events)
                    }
                }
            } catch (failure: Exception) {
                val problem = VpnErrors.classify(failure, VpnStage.STATISTICS)
                synchronized(gate) {
                    if (isWanted(target, pending)) {
                        val old = mutableState.value
                        mutableState.value = old.copy(error = VpnFailure.STATS,
                            failureStage = problem.stage, diagnostic = problem.diagnostic, nativeErrorCode = problem.nativeErrorCode,
                            rxBytesPerSecond = null, txBytesPerSecond = null,
                            events = if (old.error == VpnFailure.STATS) old.events else event(old.events, VpnEventKind.ERROR, problem))
                    }
                }
            }
            if(isWanted(target,pending))try {target.showTraffic(mutableState.value,pending.options.notificationSpeed,pending.options.liveStatistics)}catch(_:Exception){}
        }, 0, pending.options.statsIntervalMillis.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Unknown is kept explicit when no live service or on pre-29 Android. */
    fun refreshSystemProtection() {
        if(xrayActive){XrayVpnClient.refresh();return}
        val current = service
        val alwaysOn = if (current != null && !current.destroyed) {
            if (Build.VERSION.SDK_INT >= 29) try { current.isAlwaysOn } catch (_: Exception) { null }
            else null
        } else null
        val lockdown = if (current != null && !current.destroyed && Build.VERSION.SDK_INT >= 29)
            try { current.isLockdownEnabled } catch (_: Exception) { null } else null
        synchronized(gate) {
            mutableState.value = mutableState.value.copy(alwaysOn = alwaysOn, lockdown = lockdown)
        }
    }

    private fun cancelRetry() { retry?.cancel(false); retry = null; retryAttempt = 0 }

    private fun cancelSampling() { sampling?.cancel(false); sampling = null }

    /** Must hold gate. Publish terminal state only after actual native cleanup. */
    private fun finishStopped() {
        val old = mutableState.value
        val failure = old.error?.takeUnless { it == VpnFailure.STATS }
        mutableState.value = VpnSnapshot(phase = if (failure == null) VpnPhase.DOWN else VpnPhase.ERROR,
            profileId = if (failure != null) old.profileId else null, error = failure,
            failureStage = if (failure != null) old.failureStage else null,
            diagnostic = if (failure != null) old.diagnostic else null,
            nativeErrorCode = if (failure != null) old.nativeErrorCode else null,
            events = event(old.events, VpnEventKind.STOPPED))
    }

    private fun event(old: List<VpnEvent>, kind: VpnEventKind, problem: VpnProblem? = null) =
        (old + VpnEvent(System.currentTimeMillis(), kind, problem?.stage, problem?.diagnostic, problem?.nativeErrorCode)).takeLast(50)
}
