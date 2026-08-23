package dev.kdroid.musicradio.data

/**
 * The browser build needs none of this - it *is* a browser, and carries whatever clearance the user
 * already has. Its problem is CORS, which no cookie fixes, and is why the catalogue is absent here
 * anyway.
 */
actual fun createClearanceProvider(): ClearanceProvider? = null
