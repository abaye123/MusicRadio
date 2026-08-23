package dev.kdroid.musicradio.data

import javafx.application.Platform
import javafx.scene.web.WebEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI

/** Any page on the origin will do; a real one is less likely to be treated as a probe. */
private const val CLEARANCE_URL = "https://www.kolhalashon.com/he/regularSite/ravs/674"
private const val ORIGIN = "https://www.kolhalashon.com/"

/** The cookie the whole exercise is for. Without it nothing else collected is worth carrying. */
private const val CLEARANCE_COOKIE = "cf_clearance"

private const val CLEARANCE_TIMEOUT_MS = 60_000L
private const val POLL_INTERVAL_MS = 500L

/** Long enough that a rejection loop cannot spin the engine, short enough to recover in a session. */
private const val RETRY_FLOOR_MS = 60_000L

actual fun createClearanceProvider(): ClearanceProvider? = WebEngineClearanceProvider

/**
 * Drives JavaFX's WebKit until the site's bot check passes, then hands over the cookies it left.
 *
 * No window is ever shown. [WebEngine] loads and runs script without a `Scene`, which is all this
 * needs, and a browser flashing up mid-app would be worse than the wait it replaces.
 *
 * An object rather than a class: the JavaFX toolkit starts once per process, and so does the cookie
 * store this reads from.
 */
private object WebEngineClearanceProvider : ClearanceProvider {

    private val gate = Mutex()
    private var held: SiteClearance? = null
    private var lastAttemptAt = 0L

    /**
     * Kept rather than read back from [CookieHandler.getDefault], so that something else installing
     * its own handler later cannot quietly cost us the cookies we are waiting on.
     */
    private val cookies: CookieManager by lazy {
        trustTheMachinesCertificates()
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(manager)
        manager
    }

    private var toolkitStarted = false

    override suspend fun clearance(refresh: Boolean): SiteClearance? = gate.withLock {
        if (!refresh) held?.let { return it }
        val now = System.currentTimeMillis()
        // A site that has decided to refuse us will refuse us again a second later. Failing fast
        // between attempts keeps a dead end from becoming a browser launched per request.
        if (held == null && lastAttemptAt != 0L && now - lastAttemptAt < RETRY_FLOOR_MS) return null
        lastAttemptAt = now
        held = null
        held = solve()
        held
    }

    private suspend fun solve(): SiteClearance? {
        val manager = cookies
        startToolkit() ?: return null
        val engine = CompletableDeferred<WebEngine>()
        Platform.runLater {
            val web = WebEngine()
            web.isJavaScriptEnabled = true
            web.load(CLEARANCE_URL)
            engine.complete(web)
        }
        val web = engine.await()
        return try {
            withTimeoutOrNull(CLEARANCE_TIMEOUT_MS) {
                // Polled rather than driven off the load worker's state: the check redirects and
                // reloads several times, so "a page finished" says nothing about whether it was the
                // last one. The cookie appearing is the only signal that means what it looks like.
                while (true) {
                    val jar = manager.cookieStore.get(URI.create(ORIGIN))
                    if (jar.any { it.name == CLEARANCE_COOKIE }) {
                        return@withTimeoutOrNull SiteClearance(
                            cookieHeader = jar.joinToString("; ") { "${it.name}=${it.value}" },
                            userAgent = web.userAgent,
                            obtainedAt = System.currentTimeMillis(),
                        )
                    }
                    delay(POLL_INTERVAL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            Platform.runLater { runCatching { web.load(null) } }
        }
    }

    /** `null` when JavaFX is not on this machine at all, which is a reason to give up quietly. */
    private suspend fun startToolkit(): Unit? {
        if (toolkitStarted) return Unit
        val up = CompletableDeferred<Boolean>()
        try {
            Platform.startup { up.complete(true) }
        } catch (_: IllegalStateException) {
            // Already running - something else in the process got there first, which is fine.
            up.complete(true)
        } catch (_: UnsupportedOperationException) {
            return null
        } catch (_: NoClassDefFoundError) {
            return null
        }
        up.await()
        toolkitStarted = true
        return Unit
    }
}

/**
 * Points the JDK's TLS at the certificates the rest of the machine already trusts.
 *
 * Same problem `HttpClientFactory.jvm.kt` solves with Nucleus's native SSL, in the one place that
 * cannot use it: JavaFX has its own HTTP stack and goes through the JDK's default trust store,
 * which ignores the operating system's. On a line that terminates TLS - NetFree, corporate
 * filtering - every page load failed before it began, which looked exactly like the bot check
 * winning and was nothing of the sort.
 *
 * Set only if the property is absent, so a deployment that has already chosen a trust store keeps
 * it. Linux is left alone: its JDK builds normally read the distribution's own CA bundle.
 */
private fun trustTheMachinesCertificates() {
    if (System.getProperty("javax.net.ssl.trustStoreType") != null) return
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val store = when {
        os.startsWith("win") -> "Windows-ROOT"
        os.startsWith("mac") -> "KeychainStore"
        else -> return
    }
    runCatching { System.setProperty("javax.net.ssl.trustStoreType", store) }
}
