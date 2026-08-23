package dev.kdroid.musicradio.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Named rather than left to the service loader, because the trust store below is configured
 * through the engine and there has to be a known engine to configure.
 */
internal actual fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        config()
        SystemTrust.trustManager?.let { trust ->
            engine {
                config {
                    sslSocketFactory(SystemTrust.socketFactory(trust), trust)
                }
            }
        }
    }
