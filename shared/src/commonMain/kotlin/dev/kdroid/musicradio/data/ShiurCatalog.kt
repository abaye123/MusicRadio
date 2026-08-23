package dev.kdroid.musicradio.data

import androidx.compose.runtime.Immutable
import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.domain.ShiurLanguage
import io.ktor.client.HttpClient

/** The page size the Kol Halashon site itself asks for. */
const val SHIUR_PAGE_SIZE = 24

@Immutable
data class ShiurPage(val items: List<ShiurItem>, val fromRow: Int, val hasMore: Boolean)

/**
 * How a catalog call ended.
 *
 * [RateLimited] is its own case rather than one more failure string because it is the one the user
 * can act on - waiting helps, and retrying does not. Flattening it into a generic error is what
 * turns a five-minute pause into a user hammering a button that keeps the block alive.
 */
sealed interface CatalogResult<out T> {
    @Immutable
    data class Ok<T>(val value: T) : CatalogResult<T>

    data object RateLimited : CatalogResult<Nothing>

    @Immutable
    data class Failed(val reason: String?) : CatalogResult<Nothing>
}

/**
 * Read access to one rav's shiurim.
 *
 * The interface names none of the client library's types on purpose. The library is not on the
 * browser build at all, so everything `commonMain` compiles against has to exist without it; the
 * implementation that does use it lives in the source set the browser never sees.
 */
interface ShiurCatalog {
    suspend fun shiurim(
        ravId: Int,
        language: ShiurLanguage,
        fromRow: Int = 0,
        rowsPerPage: Int = SHIUR_PAGE_SIZE,
    ): CatalogResult<ShiurPage>

    suspend fun folders(ravId: Int): CatalogResult<List<ShiurFolder>>

    suspend fun shiurimInFolder(
        ravId: Int,
        folderId: Int,
        language: ShiurLanguage,
        fromRow: Int = 0,
        rowsPerPage: Int = SHIUR_PAGE_SIZE,
    ): CatalogResult<ShiurPage>
}

/**
 * The catalog for this platform, or `null` where the feature does not ship.
 *
 * `null` on the browser build: the API sits behind Cloudflare with no published CORS policy, and
 * the app is served from GitHub Pages with no proxy to put in front of it. Rather than ship a rav
 * card that fails on click, the browser build does not show one. Everything downstream keys on
 * this being `null`, so there is exactly one place that decision is made.
 *
 * [http] is the app's own client, borrowed rather than duplicated: one connection pool, keep-alive
 * preserved, and the platform's certificate handling already set up on it.
 */
expect fun createShiurCatalog(http: HttpClient): ShiurCatalog?
