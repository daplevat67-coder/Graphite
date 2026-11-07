package art.graphite.vpn.domain.graphite

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

internal class SelfHostedDeploy {
    class Failure(val reason: Reason, val observedFingerprint: String? = null) : IOException(reason.name)
    enum class Reason { INVALID_INPUT, HOST_KEY, SSH, INSTALL, TIMEOUT }
    class Request(
        val host: String, val sshPort: String, val vpnPort: String,
        val fingerprint: String?,
        val secret: String, val privateKey: Boolean, val passphrase: String = "",
    )

    // Called on an interruptible IO worker. Only the validated profile leaves this function.
    fun install(request: Request, server: ByteArray, installer: ByteArray, quickInstaller: ByteArray): String {
        val host = request.host.trim()
        val sshPort = request.sshPort.toIntOrNull()
        val vpnPort = request.vpnPort.toIntOrNull()
        val fingerprint = request.fingerprint
        if (!host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) ||
            sshPort == null || sshPort !in 1..65535 || vpnPort == null || vpnPort !in 1024..65535 ||
            (fingerprint != null && !fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))) ||
            request.secret.isBlank()
        ) throw Failure(Reason.INVALID_INPUT)

        val pinned = PinnedHostKey(fingerprint)
        val ssh = JSch()
        ssh.setHostKeyRepository(pinned)
        val session = ssh.getSession("root", host, sshPort)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("server_host_key", "ecdsa-sha2-nistp256")
        session.setConfig("PreferredAuthentications", if (request.privateKey) "publickey" else "password")
        val secret = request.secret.toByteArray(Charsets.UTF_8)
        val passphrase = request.passphrase.takeIf(String::isNotEmpty)?.toByteArray(Charsets.UTF_8)
        try {
            if (request.privateKey) ssh.addIdentity("graphite-vps", secret, null, passphrase)
            else session.setPassword(secret)
            session.connect(12_000)
            val channel = session.openChannel("exec") as ChannelExec
            try {
                val payload = "GRAPHITE_SERVER_B64='${Base64.getEncoder().encodeToString(server)}'\n"
                    .toByteArray(Charsets.US_ASCII) +
                    "GRAPHITE_INSTALLER_B64='${Base64.getEncoder().encodeToString(installer)}'\n"
                        .toByteArray(Charsets.US_ASCII) + quickInstaller
                val output = channel.inputStream
                channel.setCommand("bash -s -- '$host' '$vpnPort'")
                channel.setInputStream(ByteArrayInputStream(payload))
                channel.connect(8_000)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    if (System.nanoTime() >= deadline) throw Failure(Reason.TIMEOUT)
                    val available = output.available()
                    if (available > 0) {
                        val count = output.read(buffer, 0, minOf(available, buffer.size))
                        if (count < 0 || result.size() + count > 1024) throw Failure(Reason.INSTALL)
                        result.write(buffer, 0, count)
                    } else if (channel.isClosed) {
                        if (output.available() == 0) break
                    } else Thread.sleep(25)
                }
                if (channel.exitStatus != 0) throw Failure(Reason.INSTALL)
                val profile = try {
                    GraphiteProfile.parse(result.toString("UTF-8").trim())
                } catch (_: Exception) {
                    throw Failure(Reason.INSTALL)
                }
                if (!profile.host.equals(host, ignoreCase = true) || profile.port != vpnPort || profile.certificatePin == null) {
                    throw Failure(Reason.INSTALL)
                }
                return profile.link
            } finally {
                channel.disconnect()
            }
        } catch (_: JSchException) {
            val observed = pinned.rejectedFingerprint
            if (observed != null) throw Failure(Reason.HOST_KEY, observed)
            throw Failure(Reason.SSH)
        } catch (failure: Failure) {
            throw failure
        } catch (_: IOException) {
            throw Failure(Reason.INSTALL)
        } finally {
            session.disconnect()
            ssh.removeAllIdentity()
            secret.fill(0)
            passphrase?.fill(0)
        }
    }

    private class PinnedHostKey(private val expected: String?) : HostKeyRepository {
        @Volatile var rejectedFingerprint: String? = null
            private set
        override fun check(host: String, key: ByteArray): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            val actual = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
            if (actual == expected) return HostKeyRepository.OK
            rejectedFingerprint = actual
            return HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "Graphite pinned SSH key"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

}
