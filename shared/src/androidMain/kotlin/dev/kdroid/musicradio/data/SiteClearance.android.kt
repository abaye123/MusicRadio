package dev.kdroid.musicradio.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import dev.kdroid.musicradio.platform.androidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The page to sit on while the check runs. Any page on the origin will do; the rav page is real. */
private const val CLEARANCE_URL = "https://www.kolhalashon.com/he/regularSite/ravs/674"

/** The cookie the whole exercise is for. Without it nothing else collected is worth carrying. */
private const val CLEARANCE_COOKIE = "cf_clearance"

/** How long to let the check run before giving up. It is normally seconds; slow networks are not. */
private const val CLEARANCE_TIMEOUT_MS = 45_000L

private const val POLL_INTERVAL_MS = 500L

/** Long enough that a rejection loop cannot spin the browser, short enough to recover in a session. */
private const val RETRY_FLOOR_MS = 60_000L

actual fun createClearanceProvider(): ClearanceProvider? = WebViewClearanceProvider(androidContext())

/**
 * Runs the site in an off-screen [WebView] until Cloudflare's check passes, then hands the cookies
 * it left behind to the HTTP client.
 *
 * The WebView is never attached to a window. It only has to load a page and run script, which it
 * does perfectly well unattached, and a visible browser flashing up mid-app would be worse than the
 * wait it replaces.
 */
private class WebViewClearanceProvider(private val context: Context) : ClearanceProvider {

    private val gate = Mutex()
    private var held: SiteClearance? = null
    private var lastAttemptAt = 0L

    override suspend fun clearance(refresh: Boolean): SiteClearance? = gate.withLock {
        if (!refresh) held?.let { return it }
        val now = System.currentTimeMillis()
        // A site that has decided to refuse us will refuse us again in a second's time. Failing
        // fast between attempts keeps a dead end from becoming a browser launched per request.
        if (held == null && lastAttemptAt != 0L && now - lastAttemptAt < RETRY_FLOOR_MS) return null
        lastAttemptAt = now
        held = null
        held = solve()
        held
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solve(): SiteClearance? = withTimeoutOrNull(CLEARANCE_TIMEOUT_MS) {
        // WebView is main-thread only, from construction to destruction.
        withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            val web = WebView(context)
            try {
                cookies.setAcceptThirdPartyCookies(web, true)
                // Solving the check *is* running their script. Everything else stays off.
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                val userAgent = web.settings.userAgentString
                web.loadUrl(CLEARANCE_URL)
                // Polled rather than driven off onPageFinished: the check redirects and reloads
                // several times, so "a page finished" says nothing about whether it was the last.
                // The cookie appearing is the only signal that means what it looks like.
                while (true) {
                    val header = cookies.getCookie(CLEARANCE_URL)
                    if (header != null && CLEARANCE_COOKIE in header) {
                        cookies.flush()
                        return@withContext SiteClearance(
                            cookieHeader = header,
                            userAgent = userAgent,
                            obtainedAt = System.currentTimeMillis(),
                        )
                    }
                    delay(POLL_INTERVAL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }
    }
}
