package com.remaply.navigation.core

import com.remaply.navigation.core.internal.toCoordinate
import com.remaply.navigation.core.internal.toFerrostar
import com.remaply.navigation.core.internal.toRouteStep
import uniffi.ferrostar.createRouteFromOsrm
import uniffi.ferrostar.createRouteFromOsrmRoute

/**
 * A route that can be followed with [RemaplyNavigator.startNavigation].
 *
 * Get one from [RemaplyNavigator.requestRoutes], or build one from an OSRM-format response with
 * [fromOsrm] when you write your own [RouteProvider].
 */
public class NavigationRoute internal constructor(
    @property:InternalNavigationApi public val engineRoute: uniffi.ferrostar.Route,
) {
    /** Length of the route in meters. */
    public val distanceMeters: Double get() = engineRoute.distance

    /** Expected travel time in seconds, the sum of the steps. */
    public val durationSeconds: Double get() = engineRoute.steps.sumOf { it.duration }

    /** The route line, from the start to the destination. */
    public val geometry: List<Coordinate> by lazy { engineRoute.geometry.map { it.toCoordinate() } }

    public val steps: List<RouteStep> by lazy { engineRoute.steps.map { it.toRouteStep() } }

    /** The waypoints this route visits, including the start. */
    public val waypoints: List<Waypoint> by lazy {
        engineRoute.waypoints.map {
            Waypoint(
                it.coordinate.toCoordinate(),
                if (it.kind == uniffi.ferrostar.WaypointKind.VIA) Waypoint.Kind.VIA else Waypoint.Kind.STOP,
            )
        }
    }

    override fun toString(): String =
        "NavigationRoute(distanceMeters=$distanceMeters, durationSeconds=$durationSeconds, steps=${engineRoute.steps.size})"

    public companion object {
        /**
         * Parses one route object from an OSRM-format response (an element of its `routes` array),
         * e.g. from Valhalla with `format=osrm`, OSRM, or any compatible server.
         *
         * @param routeJson the route object, as UTF-8 JSON bytes.
         * @param waypoints the waypoints of the request, including the start.
         * @param polylinePrecision 6 for `polyline6` geometry (Valhalla, Mapbox), 5 for `polyline`.
         * @throws NavigationException.InvalidResponse if the JSON is not a valid OSRM route.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromOsrm(
            routeJson: ByteArray,
            waypoints: List<Waypoint>,
            polylinePrecision: Int = 6,
        ): NavigationRoute = try {
            NavigationRoute(
                createRouteFromOsrmRoute(routeJson, waypoints.map { it.toFerrostar() }, polylinePrecision.toUInt()),
            )
        } catch (e: Exception) {
            throw NavigationException.InvalidResponse("Not a valid OSRM route: ${e.message}", e)
        }

        /**
         * Like [fromOsrm], but with the waypoints as the `waypoints` array of the OSRM response.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromOsrm(
            routeJson: ByteArray,
            osrmWaypointsJson: ByteArray,
            polylinePrecision: Int = 6,
        ): NavigationRoute = try {
            NavigationRoute(createRouteFromOsrm(routeJson, osrmWaypointsJson, polylinePrecision.toUInt()))
        } catch (e: Exception) {
            throw NavigationException.InvalidResponse("Not a valid OSRM route: ${e.message}", e)
        }
    }
}
