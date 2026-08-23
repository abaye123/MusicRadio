package dev.kdroid.musicradio.platform

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JDK's own store does not know a filtered line's root even when the browser and every other
 * app on the machine do, and the failure surfaces as a track title that is simply never there -
 * `IcyMetadata` swallows the handshake exception. These pin the two halves of the fix that could
 * silently undo it: the stores are combined rather than swapped, and both are handed to OkHttp.
 */
class SystemTrustTest {

    private val jdkIssuers: Array<X509Certificate> = jdkTrustManager().acceptedIssuers

    @Test
    fun `a chain only the operating system knows is trusted`() {
        val composite = CompositeTrustManager(listOf(refusing("jdk"), accepting()))
        composite.checkServerTrusted(emptyArray(), "RSA")
    }

    @Test
    fun `when nothing trusts the chain, the jdk is the one that gets to complain`() {
        val composite = CompositeTrustManager(listOf(refusing("jdk"), refusing("os")))
        val failure = runCatching { composite.checkServerTrusted(emptyArray(), "RSA") }.exceptionOrNull()
        assertTrue(failure is CertificateException, "expected a certificate failure, got $failure")
        // The OS store's complaint is noise about a chain it was never going to know; on an
        // ordinary line the JDK's is the real reason and the one worth putting in front of anyone.
        assertEquals("jdk", failure.message)
    }

    @Test
    fun `every root reaches okhttp's chain cleaner`() {
        val first = jdkIssuers.first()
        val second = jdkIssuers.last()
        val composite = CompositeTrustManager(
            listOf(accepting(arrayOf(first)), accepting(arrayOf(second))),
        )
        // OkHttp cleans the chain against this list rather than asking the trust manager, so a root
        // missing here fails the connection even though checkServerTrusted would have accepted it.
        assertEquals(setOf(first, second), composite.acceptedIssuers.toSet())
    }

    @Test
    fun `the operating system roots are added to the jdk's, never swapped in`() {
        val trust = SystemTrust.trustManager
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("win") && !os.contains("mac")) {
            // Linux points the JDK's own cacerts at the system bundle: nothing to add, and wrapping
            // the default in a pass-through could only introduce bugs.
            assertNull(trust, "nothing should have been added on $os")
            return
        }
        assertNotNull(trust, "the OS root store did not load on $os")
        val combined = trust.acceptedIssuers.toSet()
        val missing = jdkIssuers.filterNot { it in combined }
        assertTrue(
            missing.isEmpty(),
            "${missing.size} of the JDK's own roots stopped being trusted, e.g. ${missing.firstOrNull()?.subjectX500Principal}",
        )
    }

    private fun jdkTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun accepting(issuers: Array<X509Certificate> = emptyArray()) = fake(null, issuers)

    private fun refusing(message: String) = fake(message, emptyArray())

    private fun fake(refusal: String?, issuers: Array<X509Certificate>) = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (refusal != null) throw CertificateException(refusal)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = issuers
    }
}
