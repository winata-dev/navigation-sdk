package com.remaply.navigation.ui.internal

import com.remaply.navigation.ui.MapAuthentication
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Adds [MapAuthentication] headers to MapLibre's requests. MapLibre sends every request through
 * one process-wide client, so this installs a client once and keeps a host → headers table.
 */
internal object MapRequestHeaders {
    private val headersByHost = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    private var installed = false

    /** The same settings as MapLibre's own default client: up to 20 requests per host, for tiles. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
            .build()
    }

    private val callFactory = object : Call.Factory {
        override fun newCall(request: Request): Call = client.newCall(withHeaders(request))
    }

    fun register(authentication: MapAuthentication) {
        add(authentication)
        install()
    }

    /** Without installing the client, which needs MapLibre's native library: for tests. */
    internal fun add(authentication: MapAuthentication) {
        authentication.hosts.forEach { headersByHost[it] = authentication.headers }
    }

    private fun install() {
        if (!installed) {
            synchronized(this) {
                if (!installed) {
                    HttpRequestUtil.setOkHttpClient(callFactory)
                    installed = true
                }
            }
        }
    }

    internal fun withHeaders(request: Request): Request {
        val headers = headersByHost[request.url.host.lowercase()] ?: return request
        return request.newBuilder().apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    }

    /** For tests. */
    internal fun clear() = headersByHost.clear()
}
