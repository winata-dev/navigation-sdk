package com.remaply.navigation.core.internal

import com.remaply.navigation.core.NavigationException
import com.remaply.navigation.core.NavigationRoute
import com.remaply.navigation.core.RouteRequest
import com.remaply.navigation.core.TravelProfile
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.RouteRequest as EngineRequest

/**
 * Builds the request with Ferrostar's request generator, sends it with OkHttp (adding our headers,
 * which Ferrostar's generators cannot), and parses the OSRM answer with Ferrostar's parser.
 *
 * Doing the HTTP call ourselves also keeps the error body, which FerrostarCore drops.
 */
internal class HttpRouteFetcher(
    client: OkHttpClient?,
    private val headers: Map<String, String>,
    /** When true, every fetch fails with [NavigationException.Unauthorized] without a request. */
    private val missingApiKey: Boolean = false,
    /** Used when the server has no navigation endpoint. Remaply API only. */
    private val fallback: RemaplyRouteFallback? = null,
    private val providerFor: (TravelProfile) -> WellKnownRouteProvider,
) {
    private val client: OkHttpClient = client ?: defaultClient
    private val adapters = ConcurrentHashMap<TravelProfile, RouteAdapter>()

    suspend fun fetch(request: RouteRequest): List<NavigationRoute> = withContext(Dispatchers.IO) {
        if (missingApiKey) {
            throw NavigationException.Unauthorized(NavigationException.Unauthorized.Reason.MISSING_API_KEY, null, null)
        }
        val adapter = adapters.getOrPut(request.profile) {
            try {
                RouteAdapter.fromWellKnownRouteProvider(providerFor(request.profile))
            } catch (e: Exception) {
                throw NavigationException.IllegalUse("Invalid route provider configuration: ${e.message}")
            }
        }

        val body = if (fallback?.isActive() == true) fetchFallback(fallback, request) else fetchEngine(adapter, request)

        val routes = try {
            adapter.parseResponse(body)
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("NoRoute", ignoreCase = true)) throw NavigationException.NoRouteFound()
            throw NavigationException.InvalidResponse("Could not parse the route response: $message", e)
        }
        if (routes.isEmpty()) throw NavigationException.NoRouteFound()
        routes.map(::NavigationRoute)
    }

    /** The request the engine builds for the configured server, e.g. Valhalla's POST /route. */
    private suspend fun fetchEngine(adapter: RouteAdapter, request: RouteRequest): ByteArray {
        val engineRequest = try {
            adapter.generateRequest(request.origin.toFerrostar(), request.waypoints.map { it.toFerrostar() })
        } catch (e: Exception) {
            throw NavigationException.IllegalUse("Could not build the route request: ${e.message}")
        }
        val (code, body) = send(engineRequest.toOkHttp(headers))
        if (code in 200..299) return body
        val text = body.decodeToString()
        if (fallback != null && RemaplyRouteFallback.isMissingEndpoint(code, text)) {
            fallback.activate()
            return fetchFallback(fallback, request)
        }
        throw httpError(code, text)
    }

    /** GET /v1/route, turned into an OSRM response. */
    private suspend fun fetchFallback(fallback: RemaplyRouteFallback, request: RouteRequest): ByteArray {
        val url = try {
            fallback.url(request)
        } catch (e: IllegalArgumentException) {
            throw NavigationException.IllegalUse("Could not build the route request: ${e.message}")
        }
        val (code, body) = send(Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build())
        val text = body.decodeToString()
        if (code !in 200..299) throw httpError(code, text)
        return try {
            fallback.toOsrm(text, request)
        } catch (e: Exception) {
            throw NavigationException.InvalidResponse("Could not read the route response: ${e.message}", e)
        }
    }

    private suspend fun send(request: Request): Pair<Int, ByteArray> {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw NavigationException.Network(e)
        }
        return response.use { it.code to it.body.bytes() }
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Both Valhalla's `NoRoute` (HTTP 400) and the Remaply API's `route_not_found` (404) mean
         * the same. 401 and 403 are credential errors; the Remaply API names the reason in
         * `{"error": {"code": ...}}`.
         */
        internal fun httpError(code: Int, body: String): NavigationException {
            if (body.contains("route_not_found") || body.contains("\"NoRoute\"")) {
                return NavigationException.NoRouteFound()
            }
            val bodyOrNull = body.ifBlank { null }
            if (code == 401 || code == 403) {
                return NavigationException.Unauthorized(authReason(body), code, bodyOrNull)
            }
            return NavigationException.Http(code, bodyOrNull)
        }

        private fun authReason(body: String): NavigationException.Unauthorized.Reason = when {
            body.contains("\"invalid_api_key\"") -> NavigationException.Unauthorized.Reason.INVALID_API_KEY
            body.contains("\"scope_not_allowed\"") -> NavigationException.Unauthorized.Reason.SCOPE_NOT_ALLOWED
            body.contains("\"api_key_restricted\"") -> NavigationException.Unauthorized.Reason.API_KEY_RESTRICTED
            else -> NavigationException.Unauthorized.Reason.UNKNOWN
        }
    }
}

private fun EngineRequest.toOkHttp(extraHeaders: Map<String, String>): Request {
    val builder = when (this) {
        is EngineRequest.HttpPost -> Request.Builder().url(url).post(body.toRequestBody()).also { b ->
            headers.forEach { (k, v) -> b.header(k, v) }
        }
        is EngineRequest.HttpGet -> Request.Builder().url(url).get().also { b ->
            headers.forEach { (k, v) -> b.header(k, v) }
        }
    }
    extraHeaders.forEach { (k, v) -> builder.header(k, v) }
    return builder.build()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
