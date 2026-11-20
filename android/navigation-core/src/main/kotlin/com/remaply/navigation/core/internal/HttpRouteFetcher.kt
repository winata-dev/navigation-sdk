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
    private val providerFor: (TravelProfile) -> WellKnownRouteProvider,
) {
    private val client: OkHttpClient = client ?: defaultClient
    private val adapters = ConcurrentHashMap<TravelProfile, RouteAdapter>()

    suspend fun fetch(request: RouteRequest): List<NavigationRoute> = withContext(Dispatchers.IO) {
        val adapter = adapters.getOrPut(request.profile) {
            try {
                RouteAdapter.fromWellKnownRouteProvider(providerFor(request.profile))
            } catch (e: Exception) {
                throw NavigationException.IllegalUse("Invalid route provider configuration: ${e.message}")
            }
        }

        val engineRequest = try {
            adapter.generateRequest(request.origin.toFerrostar(), request.waypoints.map { it.toFerrostar() })
        } catch (e: Exception) {
            throw NavigationException.IllegalUse("Could not build the route request: ${e.message}")
        }

        val response = try {
            client.newCall(engineRequest.toOkHttp(headers)).await()
        } catch (e: IOException) {
            throw NavigationException.Network(e)
        }

        val body = response.use { it.body.bytes() }
        if (!response.isSuccessful) {
            throw httpError(response.code, body.decodeToString())
        }

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

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /** Both Valhalla's `NoRoute` (HTTP 400) and the Remaply API's `route_not_found` (404) mean the same. */
        internal fun httpError(code: Int, body: String): NavigationException =
            if (body.contains("route_not_found") || body.contains("\"NoRoute\"")) {
                NavigationException.NoRouteFound()
            } else {
                NavigationException.Http(code, body.ifBlank { null })
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
