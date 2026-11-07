package art.graphite.vpn.domain.graphite

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal object GraphiteTls {
    fun socketFactory(certificatePin: String?): SSLSocketFactory {
        if (certificatePin == null) return SSLSocketFactory.getDefault() as SSLSocketFactory
        require(certificatePin.matches(Regex("[0-9a-f]{64}")))
        val expected = certificatePin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trust = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Missing Graphite certificate")
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(actual, expected)) {
                    throw CertificateException("Graphite certificate pin mismatch")
                }
                leaf.checkValidity()
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not used")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
    }
}
