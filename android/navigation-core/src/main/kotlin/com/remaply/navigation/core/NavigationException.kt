package com.remaply.navigation.core

/** Everything the SDK throws or reports in [NavigationEvent.Error]. */
public sealed class NavigationException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The routing server found no route between the waypoints for this profile. */
    public class NoRouteFound(message: String = "No route found between these points") : NavigationException(message)

    /**
     * The routing server answered with an HTTP error other than 401 or 403 (those are [Unauthorized]).
     *
     * @property statusCode e.g. 422 for a point too far from any road, 429 for a rate limit.
     * @property body the response body, which usually explains the error.
     */
    public class Http(public val statusCode: Int, public val body: String?) :
        NavigationException("Routing server returned HTTP $statusCode${body?.let { ": ${it.take(300)}" } ?: ""}")

    /**
     * The routing server refused the credentials (HTTP 401 or 403), or there was no API key to send.
     *
     * @property reason why, as far as the server says. The Remaply API always says.
     * @property statusCode 401 or 403. Null for [Reason.MISSING_API_KEY], which is found before
     *   any request is sent.
     * @property body the response body.
     */
    public class Unauthorized(
        public val reason: Reason,
        public val statusCode: Int?,
        public val body: String?,
    ) : NavigationException(reason.describe(statusCode)) {

        public enum class Reason {
            /** The API key is blank, e.g. missing from this build's configuration. */
            MISSING_API_KEY,

            /** Wrong, revoked or expired key, or its organization is suspended (`invalid_api_key`). */
            INVALID_API_KEY,

            /** The key lacks the scope this endpoint needs, e.g. `routing` (`scope_not_allowed`). */
            SCOPE_NOT_ALLOWED,

            /**
             * The key is restricted to other apps, websites or IP addresses (`api_key_restricted`).
             * Check the package name and signing-certificate SHA-1 registered for the key.
             */
            API_KEY_RESTRICTED,

            /** A 401 or 403 the SDK does not recognize, e.g. from another provider. See [body]. */
            UNKNOWN;

            internal fun describe(statusCode: Int?): String = when (this) {
                MISSING_API_KEY -> "No API key: pass a publishable key (rmp_pk_...) to RemaplyCredentials"
                INVALID_API_KEY -> "The API key is wrong, revoked or expired"
                SCOPE_NOT_ALLOWED -> "The API key does not have the scope this request needs"
                API_KEY_RESTRICTED -> "The API key is not allowed from this app (check its package name and SHA-1)"
                UNKNOWN -> "The routing server refused the credentials"
            } + (statusCode?.let { " (HTTP $it)" } ?: "")
        }
    }

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
