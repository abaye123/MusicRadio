package dev.kdroid.musicradio.data

import androidx.compose.runtime.Immutable
import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import io.ktor.client.HttpClient

@Immutable
data class ShiurPage(val items: List<ShiurItem>, val fromRow: Int, val hasMore: Boolean)

/**
 * How a catalog call ended.
 *
 * [RateLimited] is its own case rather than one more failure string because it is the one the user
 * can act on - waiting helps, and retrying does not.
 */
sealed interface CatalogResult<out T> {
    @Immutable
    data class Ok<T>(val value: T) : CatalogResult<T>

    data object RateLimited : CatalogResult<Nothing>

    @Immutable
    data class Failed(val reason: String?) : CatalogResult<Nothing>
}

/**
 * Read access to one rav's recordings.
 *
 * A rav's archive here is tens of items, not thousands, so the implementation is free to hold a
 * whole one in memory - which is why [shiurim] can promise a complete list and [shiurimInFolder]
 * can be a filter rather than another round trip.
 */
interface ShiurCatalog {
    suspend fun shiurim(ravId: Int): CatalogResult<ShiurPage>

    suspend fun folders(ravId: Int): CatalogResult<List<ShiurFolder>>

    suspend fun shiurimInFolder(ravId: Int, folderId: Int): CatalogResult<ShiurPage>
}

/**
 * The catalog for this platform, or `null` where it cannot be reached.
 *
 * `null` on the browser build: the site answers without an `Access-Control-Allow-Origin` header, so
 * a page served from anywhere else cannot read it, and this app is served from GitHub Pages with no
 * proxy to put in front of it. Rather than ship a rav card that fails on click, the browser build
 * does not show one. Everything downstream keys on this being `null`, so there is exactly one place
 * that decision is made.
 *
 * [http] is the app's own client, borrowed rather than duplicated: one connection pool, keep-alive
 * preserved, and the platform's certificate handling already set up on it.
 */
expect fun createShiurCatalog(http: HttpClient): ShiurCatalog?
