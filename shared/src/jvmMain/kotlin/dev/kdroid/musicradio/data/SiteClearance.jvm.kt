package dev.kdroid.musicradio.data

/**
 * No clearance on the desktop yet, so the feature that needs it does not run here.
 *
 * The JDK ships no browser engine. Getting past a JavaScript bot check needs a real one - JavaFX's
 * WebKit is not reliably enough of a browser for it, and the alternative is embedding Chromium,
 * which is a hundred megabytes and a native toolchain per platform. That is a decision worth making
 * deliberately rather than smuggling in behind a cookie jar, so until it is made the desktop build
 * reports no catalogue rather than a broken one.
 */
actual fun createClearanceProvider(): ClearanceProvider? = null
