package io.github.duskedge.synopilot.network

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 证书信息，用于让用户确认是否信任。 */
data class CertificateInfo(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notAfter: Long,
) {
    /** 形如 3F:A1:…:9C，便于和 DSM 控制面板里显示的指纹对照 */
    val fingerprint: String get() = sha256.chunked(2).joinToString(":") { it.uppercase() }

    companion object {
        fun of(cert: X509Certificate) = CertificateInfo(
            sha256 = sha256Hex(cert.encoded),
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notAfter = cert.notAfter.time,
        )

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** NAS 的证书不受系统信任（自签名、或域名对不上），需要用户确认指纹后固定。 */
class UntrustedCertificateException(val certificate: CertificateInfo) :
    CertificateException("证书不受信任：${certificate.subject}")

/** 用户已确认信任的证书指纹（SHA-256，小写十六进制）。 */
fun interface TrustedPins {
    fun contains(sha256: String): Boolean
}

/**
 * 先按系统规则校验；系统不信任时，只接受用户确认过的证书（按指纹固定）。
 * 绝不「信任所有证书」。
 *
 * Lint 的 CustomX509TrustManager 提醒自定义 TrustManager 容易出错：这里先完整走系统校验，
 * 只在系统拒绝时才比对用户确认过的指纹，拒绝其余一切，因此是有意为之。
 */
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(private val pins: TrustedPins) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            val info = CertificateInfo.of(leaf)
            if (!pins.contains(info.sha256)) throw UntrustedCertificateException(info)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/**
 * 主机名校验：系统规则通过即可；证书被用户固定过时也放行
 * （用 IP 访问 NAS 时，证书上的域名必然对不上）。
 * 校验失败时记下证书，方便上层提示用户确认。
 */
class PinningHostnameVerifier(private val pins: TrustedPins) : HostnameVerifier {
    private val default = HttpsURLConnection.getDefaultHostnameVerifier()

    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (default.verify(hostname, session)) return true
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val info = CertificateInfo.of(leaf)
        if (pins.contains(info.sha256)) return true
        lastRejected[hostname] = info
        return false
    }

    companion object {
        /** 最近因主机名不匹配被拒绝的证书，按主机名记录 */
        val lastRejected = ConcurrentHashMap<String, CertificateInfo>()
    }
}
