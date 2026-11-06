package art.graphite.vpn.diagnostics

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.math.abs
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

enum class ProbeStatus { REPLY, NO_REPLY, UNAVAILABLE, ERROR }
enum class ProbeMethod { ICMP }
enum class ProbeAddressFamily { AUTO, IPV4, IPV6 }
enum class ProbeCode { OK, PARTIAL_REPLY, NO_ICMP_REPLY, ICMP_UNREACHABLE, ICMP_NOT_PERMITTED,
    PING_BINARY_UNAVAILABLE, INVALID_HOST, DNS_TIMEOUT, DNS_FAILURE, PROCESS_TIMEOUT, PROCESS_ERROR, REPLY_WITHOUT_TIMING,
    ADDRESS_FAMILY_UNAVAILABLE }
data class ProbeOptions(val count:Int=3,val timeoutSeconds:Int=2,val intervalMillis:Int=250,val dnsTimeoutMillis:Int=2500,
    val addressFamily:ProbeAddressFamily=ProbeAddressFamily.AUTO) {
    init {require(count in 1..20 && timeoutSeconds in 1..10 && intervalMillis in 0..2000 && dnsTimeoutMillis in 500..10000)}
}
data class ProbeResult(val status: ProbeStatus, val rttMs: Double? = null, val lossPercent: Double? = null,
    val code: ProbeCode, val method: ProbeMethod = ProbeMethod.ICMP, val attempts: Int = 0,
    val received: Int = 0, val rttUpperBoundMs: Double? = null,
    val minRttMs:Double?=null,val maxRttMs:Double?=null,val medianRttMs:Double?=null,val jitterMs:Double?=null,
    val targetAddress:String?=null)

/** ICMP echo through the current Android network; never a WireGuard handshake test. */
class ServerProber {
    suspend fun probe(host: String,options:ProbeOptions=ProbeOptions()): ProbeResult {
        val safeHost = normalizeHost(host) ?: return ProbeResult(ProbeStatus.ERROR, code = ProbeCode.INVALID_HOST)
        var resolutionCompleted = false
        val addresses = withTimeoutOrNull(options.dnsTimeoutMillis.toLong()) {
            val answer = resolve(safeHost)
            resolutionCompleted = true
            answer
        } ?: return ProbeResult(ProbeStatus.ERROR,
            code = if (resolutionCompleted) ProbeCode.DNS_FAILURE else ProbeCode.DNS_TIMEOUT)
        val address = selectAddress(addresses, options.addressFamily)
            ?: return ProbeResult(ProbeStatus.ERROR, code = if (addresses.isEmpty()) ProbeCode.DNS_FAILURE else ProbeCode.ADDRESS_FAMILY_UNAVAILABLE)
        val numeric = address.hostAddress ?: return ProbeResult(ProbeStatus.ERROR, code = ProbeCode.DNS_FAILURE)
        val ipv6 = numeric.contains(':')
        val binary = if (ipv6 && File("/system/bin/ping6").canExecute()) "/system/bin/ping6" else "/system/bin/ping"
        if (!File(binary).canExecute()) return ProbeResult(ProbeStatus.UNAVAILABLE, code = ProbeCode.PING_BINARY_UNAVAILABLE, targetAddress = numeric)
        val attempts = ArrayList<Echo>(options.count)
        repeat(options.count) { index ->
            val arguments = mutableListOf(binary)
            if (ipv6 && !binary.endsWith("ping6")) arguments.add("-6")
            arguments.addAll(listOf("-n", "-c", "1", "-W", options.timeoutSeconds.toString(), "-w", options.timeoutSeconds.toString(), numeric))
            attempts += execute(arguments,(options.timeoutSeconds+1).toLong())
            if(index<options.count-1 && options.intervalMillis>0)delay(options.intervalMillis.toLong())
        }
        return aggregate(attempts).copy(targetAddress = numeric)
    }

    private suspend fun resolve(host: String): List<InetAddress>? {
        // Numeric literals have no DNS work, including no resolver thread timeout.
        if (host.contains(':') || host.matches(Regex("[0-9.]+")))
            return try { listOf(InetAddress.getByName(host)) } catch (_: Exception) { null }
        if (Build.VERSION.SDK_INT >= 29) return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_NO_RETRY, dnsExecutor, cancellation,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (continuation.isActive) continuation.resume(if (rcode == 0) answer else null)
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
            } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
        }
        // Android26-28 has no cancellable platform resolver. Bound the caller's
        // deadline and the number of blocking resolver workers independently.
        return suspendCancellableCoroutine { continuation ->
            val future = legacyDnsExecutor.submit {
                val answer = try { InetAddress.getAllByName(host).toList() } catch (_: Exception) { null }
                if (continuation.isActive) continuation.resume(answer)
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }

    private suspend fun execute(arguments: List<String>,waitSeconds:Long): Echo = suspendCancellableCoroutine { continuation ->
        val running = AtomicReference<Process?>()
        continuation.invokeOnCancellation { running.get()?.destroyForcibly() }
        processExecutor.execute {
            if (!continuation.isActive) return@execute
            val echo = try {
                val process = ProcessBuilder(arguments).redirectErrorStream(true)
                    .apply { environment()["LC_ALL"] = "C" }.start()
                running.set(process)
                if (!continuation.isActive) { process.destroyForcibly(); return@execute }
                try {
                    if (!process.waitFor(waitSeconds, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        Echo(code = ProbeCode.PROCESS_TIMEOUT)
                    } else {
                        val output = process.inputStream.bufferedReader().use { reader ->
                            val chars = CharArray(8192)
                            val count = reader.read(chars)
                            if (count > 0) String(chars, 0, count) else ""
                        }
                        parseEcho(output, process.exitValue())
                    }
                } finally { process.destroyForcibly(); running.set(null) }
            } catch (_: SecurityException) { Echo(code = ProbeCode.ICMP_NOT_PERMITTED) }
              catch (_: IOException) { Echo(code = ProbeCode.PROCESS_ERROR) }
              catch (_: InterruptedException) { Thread.currentThread().interrupt(); Echo(code = ProbeCode.PROCESS_ERROR) }
            if (continuation.isActive) continuation.resume(echo)
        }
    }

    companion object {
        private val processExecutor = Executors.newFixedThreadPool(3) { Thread(it, "graphite-icmp").apply { isDaemon = true } }
        private val dnsExecutor = Executors.newSingleThreadExecutor { Thread(it, "graphite-dns-callback").apply { isDaemon = true } }
        private val legacyDnsExecutor = Executors.newFixedThreadPool(2) { Thread(it, "graphite-dns-legacy").apply { isDaemon = true } }

        /** AUTO preserves Android's resolver order. Forced families never silently fall back. */
        internal fun selectAddress(addresses: List<InetAddress>, family: ProbeAddressFamily): InetAddress? =
            addresses.firstOrNull { address -> when (family) {
                ProbeAddressFamily.AUTO -> true
                ProbeAddressFamily.IPV4 -> address is Inet4Address
                ProbeAddressFamily.IPV6 -> address is Inet6Address
            } }

        fun hostFromEndpoint(endpoint: String): String? {
            val match = Regex("^\\[([^]]+)]:(\\d{1,5})$").matchEntire(endpoint.trim())
                ?: Regex("^([^:]+):(\\d{1,5})$").matchEntire(endpoint.trim()) ?: return null
            val port = match.groupValues[2].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return normalizeHost(match.groupValues[1])
        }

        internal fun normalizeHost(value: String): String? {
            if (value.isEmpty() || value.length > 253 || value.any { it.isWhitespace() || it == '%' }) return null
            if (value.contains(':')) return value.takeIf { it.matches(Regex("[a-fA-F0-9:.]+")) &&
                try { InetAddress.getByName(it).hostAddress?.contains(':') == true } catch (_: Exception) { false } }
            if (value.matches(Regex("[0-9.]+"))) return value.takeIf { candidate ->
                val parts = candidate.split('.')
                parts.size == 4 && parts.all { it.isNotEmpty() && (it.toIntOrNull()?.let { n -> n in 0..255 } == true) }
            }
            val ascii = try { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase() } catch (_: Exception) { return null }
            return ascii.takeIf { candidate -> candidate.length <= 253 && candidate.trimEnd('.').split('.').all {
                it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
                    it.all { char -> char.isLetterOrDigit() || char == '-' }
            } }
        }

        internal data class Echo(val received: Boolean = false, val rtt: Double? = null,
            val upper: Double? = null, val code: ProbeCode)

        internal fun parseEcho(output: String, exit: Int): Echo {
            val text = output.lowercase()
            if ("operation not permitted" in text || "permission denied" in text || "socket: permission" in text)
                return Echo(code = ProbeCode.ICMP_NOT_PERMITTED)
            // Only packet reply lines count. The process duration includes startup,
            // scheduling and loss waiting and is never interpreted as latency.
            val reply = output.lineSequence().firstOrNull { Regex("(?i)bytes from .*(?:icmp_seq[= ]|seq=)").containsMatchIn(it) }
            if (reply != null) {
                val timing = Regex("(?i)time\\s*([=<])\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms").find(reply)
                val value = timing?.groupValues?.get(2)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                return if (value == null) Echo(true, code = ProbeCode.REPLY_WITHOUT_TIMING)
                else if (timing.groupValues[1] == "<") Echo(true, upper = value, code = ProbeCode.OK)
                else Echo(true, rtt = value, code = ProbeCode.OK)
            }
            return Echo(code = when {
                "unreachable" in text -> ProbeCode.ICMP_UNREACHABLE
                exit == 1 || exit == 0 -> ProbeCode.NO_ICMP_REPLY
                else -> ProbeCode.PROCESS_ERROR
            })
        }

        internal fun aggregate(echoes: List<Echo>): ProbeResult {
            val replies = echoes.filter { it.received }
            val n = replies.size
            val failure = echoes.firstOrNull { it.code == ProbeCode.ICMP_NOT_PERMITTED }?.code
                ?: echoes.firstOrNull { it.code == ProbeCode.PROCESS_ERROR }?.code
                ?: echoes.firstOrNull { it.code == ProbeCode.PROCESS_TIMEOUT }?.code
                ?: echoes.firstOrNull { it.code == ProbeCode.ICMP_UNREACHABLE }?.code ?: ProbeCode.NO_ICMP_REPLY
            val exact = replies.mapNotNull { it.rtt }
            val upper = replies.mapNotNull { it.upper }
            val timings = exact.takeIf { n>0 && it.size==n }
            val ordered = timings?.sorted()
            return ProbeResult(minRttMs=timings?.minOrNull(),maxRttMs=timings?.maxOrNull(),
                medianRttMs=ordered?.let{if(it.size%2==1)it[it.size/2] else (it[it.size/2-1]+it[it.size/2])/2},
                jitterMs=timings?.takeIf{it.size>1}?.zipWithNext{a,b->abs(b-a)}?.average(),status = if (n > 0) ProbeStatus.REPLY else if (failure == ProbeCode.ICMP_NOT_PERMITTED)
                ProbeStatus.UNAVAILABLE else if (failure in listOf(ProbeCode.PROCESS_ERROR, ProbeCode.PROCESS_TIMEOUT))
                ProbeStatus.ERROR else ProbeStatus.NO_REPLY,
                rttMs = if (n > 0 && exact.size == n) exact.average() else null,
                rttUpperBoundMs = if (n > 0 && exact.size + upper.size == n && upper.isNotEmpty())
                    (exact.sum() + upper.sum()) / n else null,
                lossPercent = if (echoes.isNotEmpty() && echoes.all { it.code !in listOf(ProbeCode.ICMP_NOT_PERMITTED, ProbeCode.PROCESS_ERROR, ProbeCode.PROCESS_TIMEOUT) })
                    (echoes.size - n) * 100.0 / echoes.size else null,
                attempts = echoes.size, received = n,
                code = if (n == 0) failure else if (replies.any { it.code == ProbeCode.REPLY_WITHOUT_TIMING })
                    ProbeCode.REPLY_WITHOUT_TIMING else if (n < echoes.size) ProbeCode.PARTIAL_REPLY else ProbeCode.OK)
        }
    }
}
