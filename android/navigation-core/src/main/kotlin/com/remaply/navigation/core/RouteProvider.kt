package com.remaply.navigation.core

import android.content.Context
import com.remaply.navigation.core.internal.AppIdentity
import com.remaply.navigation.core.internal.HttpRouteFetcher
import com.remaply.navigation.core.internal.Json
import okhttp3.OkHttpClient
import uniffi.ferrostar.GraphHopperVoiceUnits
import uniffi.ferrostar.WellKnownRouteProvider

/** What a [RouteProvider] is asked for. */
public data class RouteRequest(
    /** Where the user is. Its course, when known, lets the server avoid routes that start with a U-turn. */
    val origin: UserLocation,
    /** Where to go, in order. The last one is the destination. Never empty. */
    val waypoints: List<Waypoint>,
    val profile: TravelProfile,
    /** True when the user left the route and a new one is needed. */
    val isReroute: Boolean,
)

/**
 * Where routes come from. Use [ValhallaRouteProvider] or [GraphHopperRouteProvider] for those
 * servers, or implement this interface for anything else (an in-house API, offline routing...)
 * and build the routes with [NavigationRoute.fromOsrm].
 *
 * The first route returned is the one followed when rerouting.
 */
public fun interface RouteProvider {
    /**
     * Returns routes for [request], best first, or throws a [NavigationException].
     * Called on a background thread.
     */
    public suspend fun getRoutes(request: RouteRequest): List<NavigationRoute>
}

/**
 * Routes from a [Valhalla](https://github.com/valhalla/valhalla) server, or any API that accepts
 * Valhalla's route request and answers in OSRM format (Stadia Maps, the Remaply API...).
 *
 * @param endpointUrl the full route URL, e.g. `https://valhalla.example.com/route`.
 * @param headers sent with every request, e.g. an API key header.
 * @param options extra fields merged into the request body, e.g.
 *   `mapOf("units" to "kilometers", "language" to "id-ID")`.
 * @param costing the Valhalla costing for each profile. Defaults to [defaultCosting].
 * @param httpClient to share connection pools, add interceptors or change timeouts.
 */
public class ValhallaRouteProvider @JvmOverloads constructor(
    endpointUrl: String,
    headers: Map<String, String> = emptyMap(),
    options: Map<String, Any?> = emptyMap(),
    costing: (TravelProfile) -> String = ::defaultCosting,
    httpClient: OkHttpClient? = null,
) : RouteProvider {

    private val fetcher = HttpRouteFetcher(httpClient, headers) { profile ->
        WellKnownRouteProvider.Valhalla(endpointUrl, costing(profile), Json.encodeOrNull(options))
    }

    override suspend fun getRoutes(request: RouteRequest): List<NavigationRoute> = fetcher.fetch(request)

    public companion object {
        /** Valhalla's standard costing names. */
        @JvmStatic
        public fun defaultCosting(profile: TravelProfile): String = when (profile) {
            TravelProfile.CAR -> "auto"
            TravelProfile.MOTORCYCLE -> "motorcycle"
            TravelProfile.TRUCK -> "truck"
            TravelProfile.BICYCLE -> "bicycle"
            TravelProfile.FOOT -> "pedestrian"
        }

        /**
         * Routes from the Remaply Platform API (`POST /v1/navigation/route`).
         *
         * Sends the key in `X-Api-Key`, plus this app's package name and signing certificate,
         * so keys restricted to your Android app work.
         *
         * @param apiKey a key with the `routing` scope.
         * @param baseUrl the API's base URL, without a trailing slash.
         */
        @JvmStatic
        @JvmOverloads
        public fun remaply(
            context: Context,
            apiKey: String,
            baseUrl: String = "https://api.remaply.com",
            httpClient: OkHttpClient? = null,
        ): ValhallaRouteProvider {
            require(apiKey.isNotBlank()) { "apiKey must not be blank" }
            return ValhallaRouteProvider(
                endpointUrl = baseUrl.trimEnd('/') + "/v1/navigation/route",
                headers = mapOf("X-Api-Key" to apiKey) + AppIdentity.headers(context),
                httpClient = httpClient,
            )
        }
    }
}

/**
 * Routes from [GraphHopper](https://www.graphhopper.com/)'s navigate endpoint.
 *
 * @param endpointUrl e.g. `https://graphhopper.com/api/1/navigate/?key=YOUR_KEY`.
 * @param locale language of the instructions, e.g. "en" or "id".
 * @param profileName the GraphHopper profile for each travel profile.
 */
public class GraphHopperRouteProvider @JvmOverloads constructor(
    endpointUrl: String,
    locale: String = "en",
    useMetricUnits: Boolean = true,
    headers: Map<String, String> = emptyMap(),
    options: Map<String, Any?> = emptyMap(),
    profileName: (TravelProfile) -> String = ::defaultProfileName,
    httpClient: OkHttpClient? = null,
) : RouteProvider {

    private val fetcher = HttpRouteFetcher(httpClient, headers) { profile ->
        WellKnownRouteProvider.GraphHopper(
            endpointUrl,
            profileName(profile),
            locale,
            if (useMetricUnits) GraphHopperVoiceUnits.METRIC else GraphHopperVoiceUnits.IMPERIAL,
            Json.encodeOrNull(options),
        )
    }

    override suspend fun getRoutes(request: RouteRequest): List<NavigationRoute> = fetcher.fetch(request)

    public companion object {
        @JvmStatic
        public fun defaultProfileName(profile: TravelProfile): String = when (profile) {
            TravelProfile.CAR -> "car"
            TravelProfile.MOTORCYCLE -> "motorcycle"
            TravelProfile.TRUCK -> "truck"
            TravelProfile.BICYCLE -> "bike"
            TravelProfile.FOOT -> "foot"
        }
    }
}
