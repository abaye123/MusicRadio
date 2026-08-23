package dev.kdroid.musicradio.data

import androidx.compose.runtime.Immutable

/**
 * Proof that a real browser session on this machine got past the site's bot check, in a form a
 * plain HTTP client can carry.
 *
 * [userAgent] is not decoration. Cloudflare binds a clearance cookie to the IP **and** the
 * user-agent string that earned it, so a client that sends a different one is turned away exactly
 * as if it had no cookie at all. Whatever obtains the clearance must report the user-agent it used,
 * and every request made with [cookieHeader] must send that same string.
 *
 * Clearance also expires - the site chooses how fast, and half an hour is common - so this is a
 * thing to refresh on rejection, never a thing to fetch once and keep.
 */
@Immutable
data class SiteClearance(
    /** Ready to send as a `Cookie` header: `name=value; name=value`. */
    val cookieHeader: String,
    val userAgent: String,
    /** Epoch millis, for diagnostics and for not thrashing the browser on repeated failures. */
    val obtainedAt: Long,
)

/**
 * Obtains [SiteClearance] by driving a real browser engine.
 *
 * `null` from [createClearanceProvider] means this platform has no engine to drive, and the
 * feature that needs clearance does not ship there.
 */
interface ClearanceProvider {
    /**
     * The current clearance, obtaining one if there is none.
     *
     * @param refresh discards what is held and runs the browser again. Pass it when a request came
     *   back challenged, which is the only reliable signal that clearance has expired.
     * @return `null` when the browser could not get past the check, which is a real outcome and not
     *   an error: the site may have tightened its rules, and the caller should say so plainly
     *   rather than retry in a loop.
     */
    suspend fun clearance(refresh: Boolean = false): SiteClearance?
}

/**
 * The clearance provider for this platform, or `null` where there is no browser engine to drive.
 *
 * Android has a WebView in the framework. The desktop JVM has nothing comparable without pulling in
 * a full Chromium embedding, and the browser build needs none of this - it *is* a browser, and its
 * problem is CORS rather than bot management.
 */
expect fun createClearanceProvider(): ClearanceProvider?
