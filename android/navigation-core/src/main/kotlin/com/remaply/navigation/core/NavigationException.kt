package com.remaply.navigation.core

/** Everything the SDK throws or reports in [NavigationEvent.Error]. */
public sealed class NavigationException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The routing server found no route between the waypoints for this profile. */
    public class NoRouteFound(message: String = "No route found between these points") : NavigationException(message)

    /**
     * The routing server answered with an HTTP error.
     *
     * @property statusCode e.g. 401 for a wrong API key, 422 for a point too far from any road.
     * @property body the response body, which usually explains the error.
     */
    public class Http(public val statusCode: Int, public val body: String?) :
        NavigationException("Routing server returned HTTP $statusCode${body?.let { ": ${it.take(300)}" } ?: ""}")

    /** The routing server could not be reached (no connection, timeout, DNS…). */
    public class Network(cause: Throwable) : NavigationException("Could not reach the routing server: ${cause.message}", cause)

    /** The routing server's answer could not be understood. */
    public class InvalidResponse(message: String, cause: Throwable? = null) : NavigationException(message, cause)

    /** No location fix arrived in time. Check permissions, and that location is turned on. */
    public class LocationUnavailable(message: String = "No location fix available") : NavigationException(message)

    /** Location permission has not been granted. See [NavigationPermissions]. */
    public class PermissionDenied(message: String = "Location permission has not been granted") : NavigationException(message)

    /** The SDK was used in a way it does not support, e.g. starting after [RemaplyNavigator.close]. */
    public class IllegalUse(message: String) : NavigationException(message)
}
