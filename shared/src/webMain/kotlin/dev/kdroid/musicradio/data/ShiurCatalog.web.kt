package dev.kdroid.musicradio.data

import io.ktor.client.HttpClient

/**
 * No catalog in the browser, so no rav card, no rav screen and nothing to fail on click.
 *
 * The API publishes no CORS policy - the notes taken from the site leave it explicitly unknown -
 * and this build is served from GitHub Pages, which is static and has no proxy to route the JSON
 * calls through. Shipping the feature here would mean shipping a button that throws.
 *
 * Playback itself would likely have been fine: a media element loads cross-origin without CORS.
 * It is the listing calls that cannot be made, and a player with no catalog is not a feature.
 */
actual fun createShiurCatalog(http: HttpClient): ShiurCatalog? = null
