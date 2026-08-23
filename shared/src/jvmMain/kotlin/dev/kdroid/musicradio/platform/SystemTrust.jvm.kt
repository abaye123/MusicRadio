package dev.kdroid.musicradio.platform

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The operating system's roots, added to the JDK's own rather than replacing them.
 *
 * Replacing would be the shorter code and the wrong behaviour: a machine whose OS store is thin or
 * unreadable would go from trusting the public web to trusting nothing, and the app would break for
 * everyone to fix it for a few. Adding leaves the ordinary case untouched.
 */
internal object SystemTrust {

    /**
     * Null when there is nothing to add - no OS store on this platform, or none that would load.
     * That is the signal to leave the HTTP engine at its defaults rather than wrap it in a
     * pass-through that can only introduce bugs.
     */
    val trustManager: X509TrustManager? by lazy { runCatching { build() }.getOrNull() }

    fun socketFactory(trust: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory

    private fun build(): X509TrustManager? {
        val platform = osStoreTypes().mapNotNull(::load)
        if (platform.isEmpty()) return null
        val jdk = fromStore(null) ?: return null
        return CompositeTrustManager(listOf(jdk) + platform)
    }

    /**
     * Windows exposes the machine's roots through SunMSCAPI under this one name. macOS splits them
     * in two, and a certificate installed by hand lands in either depending on how it was
     * installed, so both are asked for. Linux distributions point the JDK's own cacerts at the
     * system bundle, which leaves nothing to add.
     */
    private fun osStoreTypes(): List<String> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> listOf("Windows-ROOT")
            os.contains("mac") -> listOf("KeychainStore", "KeychainStore-ROOT")
            else -> emptyList()
        }
    }

    /** `KeychainStore-ROOT` only exists on newer JDKs, so a missing store type is not an error. */
    private fun load(type: String): X509TrustManager? = runCatching {
        val store = KeyStore.getInstance(type)
        store.load(null, null)
        fromStore(store)
    }.getOrNull()

    /** A null store means "whatever the JDK would have used", which is exactly the default. */
    private fun fromStore(store: KeyStore?): X509TrustManager? {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }
}

/** Trusts a chain that any one of the stores trusts. */
internal class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        var first: CertificateException? = null
        for (delegate in delegates) {
            try {
                delegate.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                // The JDK store is asked first, so its complaint is the one worth reporting: on an
                // unfiltered line it is the real reason, and the OS store's is noise about a chain
                // it was never going to know.
                if (first == null) first = e
            }
        }
        throw first ?: CertificateException("no trust store was available to check the chain")
    }

    /** Nothing here presents a client certificate; the JDK store's answer stands. */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegates.first().checkClientTrusted(chain, authType)
    }

    /**
     * OkHttp builds its certificate chain cleaner out of this list rather than by asking the trust
     * manager, so a root missing here fails the connection even though [checkServerTrusted] would
     * have accepted it. Both stores have to be represented.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { it.acceptedIssuers.asList() }.distinct().toTypedArray()
}
