package com.mmbesar.kancard.network

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Builds the HTTP client for ONE Kanboard server. This is the place where the
 * app decides how strict to be - the three situations are:
 *
 *  1. https:// with a normal certificate  -> default Android trust (strictest).
 *  2. https:// with a self-signed one     -> [pinnedSha256] is set: only that exact
 *                                            certificate is accepted.
 *  3. http://                             -> [allowCleartext] is true. Nothing is
 *                                            encrypted; the UI warns about this.
 */
object HttpClients {

    fun create(pinnedSha256: String?, allowCleartext: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Plain HTTP is only on the list when this server was explicitly set up as http://.
        val specs = mutableListOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
        if (allowCleartext) specs += ConnectionSpec.CLEARTEXT
        builder.connectionSpecs(specs)

        if (pinnedSha256 != null) {
            val trustManager = PinnedTrustManager(pinnedSha256)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(context.socketFactory, trustManager)
            // A self-signed certificate usually has the wrong (or no) host name, e.g.
            // when you connect by IP. That is fine HERE only because the trust manager
            // above already insists on the exact certificate the user approved.
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }
}
