package com.remaply.navigation.core

/**
 * Everything about the current trip, as one immutable snapshot. Collect
 * [RemaplyNavigator.state] to follow it.
 */
public data class NavigationState(
    val status: Status = Status.IDLE,
    /** The route being followed. Changes when the user is rerouted. */
    val route: NavigationRoute? = null,
    /** The last location fix, as reported by the GPS (or the simulator). */
    val location: UserLocation? = null,
    /** [location] snapped onto the route line. Use it for the puck while navigating. */
    val snappedLocation: UserLocation? = null,
    /** The next maneuver to show. */
    val instruction: Instruction? = null,
    val progress: TripProgress? = null,
    /** The road the user is on, when known. */
    val currentRoadName: String? = null,
    /**
     * The speed limit where the user is, in km/h, when the routing server provides it
     * (Valhalla's `maxspeed` annotation). Convert to mph yourself where needed.
     */
    val speedLimitKmh: Double? = null,
    /** Steps left, the current one first. */
    val remainingSteps: List<RouteStep> = emptyList(),
    /** Stops left, the destination last. */
    val remainingWaypoints: List<Waypoint> = emptyList(),
    /** True when the user is too far from the route. */
    val isOffRoute: Boolean = false,
    /** True while a new route is being fetched because the user left the route. */
    val isRerouting: Boolean = false,
    /** True while following a simulated drive rather than the GPS. */
    val isSimulating: Boolean = false,
    val isMuted: Boolean = false,
) {
    public enum class Status {
        /** Not navigating. [location] may still update. */
        IDLE,

        /** Fetching the first route of a trip started with [RemaplyNavigator.navigateTo]. */
        ROUTING,

        /** Following a route. */
        NAVIGATING,

        /** The destination was reached. Call [RemaplyNavigator.stopNavigation] to finish. */
        ARRIVED,
    }

    public val isNavigating: Boolean get() = status == Status.NAVIGATING
}

/** Things that happen during a trip, for analytics, sounds or your own UI. */
public sealed interface NavigationEvent {
    public data class NavigationStarted(val route: NavigationRoute, val simulated: Boolean) : NavigationEvent

    /** The user moved on to the next step; [instruction] is the new one. */
    public data class StepAdvanced(val instruction: Instruction?, val stepsRemaining: Int) : NavigationEvent

    /** The user left the route. A new route is requested if rerouting is on. */
    public data object OffRoute : NavigationEvent

    /** The user is back on the route without a reroute. */
    public data object BackOnRoute : NavigationEvent

    /** A new route replaced the old one after the user left it. */
    public data class Rerouted(val route: NavigationRoute) : NavigationEvent

    /** An intermediate stop was reached. [index] counts stops from 0, excluding the start. */
    public data class WaypointReached(val waypoint: Waypoint, val index: Int) : NavigationEvent

    /** The destination was reached. */
    public data object Arrived : NavigationEvent

    /** Navigation was stopped, by the app, the user (e.g. from the notification) or [RemaplyNavigator.close]. */
    public data object NavigationStopped : NavigationEvent

    /** Something failed without stopping navigation, e.g. a reroute request. */
    public data class Error(val error: NavigationException) : NavigationEvent
}
