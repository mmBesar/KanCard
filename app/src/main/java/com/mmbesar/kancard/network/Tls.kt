package com.mmbesar.kancard.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Helpers for certificate fingerprints. A fingerprint is the SHA-256 hash of the
 * certificate, written as "AA:BB:CC:...". It is how you recognise "your" server.
 */
object CertFingerprint {

    /** Fingerprint of one certificate, upper-case hex with colons. */
    fun sha256(cert: X509Certificate): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    /** Lets people paste fingerprints with or without colons/spaces, any case. */
    fun normalize(text: String): String =
        text.filter { it.isLetterOrDigit() }.uppercase()
}

/**
 * The "trust this one certificate" rule used for self-signed servers.
 *
 * It accepts the server ONLY when its certificate is exactly the one the user
 * approved earlier (matching SHA-256). Any other certificate - including a
 * normally valid one - is rejected, so a pinned server cannot be silently
 * swapped for someone else's. If the server's certificate is renewed, the app
 * asks the user to approve the new one.
 */
class PinnedTrustManager(pinnedSha256: String) : X509TrustManager {

    private val pin = CertFingerprint.normalize(pinnedSha256)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // We never present a client certificate, so nobody should ask us to check one.
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("The server sent no certificate")
        val actual = CertFingerprint.normalize(CertFingerprint.sha256(leaf))
        if (actual != pin) {
            throw CertificateException("The server certificate is not the one you trusted")
        }
    }

    // No certificate authorities are trusted in pinned mode - only the exact pin.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** What the user sees when asked "do you trust this certificate?". */
data class CertInfo(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notBefore: Date,
    val notAfter: Date,
    val selfSigned: Boolean,
)

/**
 * Reads a server's certificate WITHOUT trusting it, so it can be shown to the user.
 *
 * Safe because only the TLS handshake happens: no username, token or request is
 * ever sent over this connection, and it is closed straight away.
 */
object CertFetcher {

    private const val TIMEOUT_MS = 10_000

    /** Accepts anything - used only to look at the certificate, never to talk to the server. */
    private object LookOnlyTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    suspend fun fetch(host: String, port: Int): CertInfo = withContext(Dispatchers.IO) {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(LookOnlyTrustManager), SecureRandom())

        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        raw.soTimeout = TIMEOUT_MS
        // Wrapping the plain socket lets us pass the host name, so servers that
        // pick a certificate by name (SNI) give us the right one.
        val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.use {
            it.startHandshake()
            val leaf = it.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("The server sent no certificate")
            CertInfo(
                sha256 = CertFingerprint.sha256(leaf),
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                notBefore = leaf.notBefore,
                notAfter = leaf.notAfter,
                selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
            )
        }
    }
}
