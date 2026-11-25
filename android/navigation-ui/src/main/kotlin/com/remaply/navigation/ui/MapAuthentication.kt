package com.remaply.navigation.ui

import com.remaply.navigation.core.RemaplyCredentials

/**
 * Headers the map adds to its own requests (style, TileJSON, tiles, sprites, glyphs) to the
 * given hosts, and only to them, for map servers that need an API key. Requests to any other
 * host (another tile provider in the same style, a font server...) are sent unchanged, so the
 * key is not leaked to them.
 *
 * Pass it to [RemaplyNavigationView] in a [MapStyle]. For the Remaply map, use [MapStyle.remaply],
 * which sets this up. The map's network client is shared by every MapLibre map
 * in the app, so:
 * - once a view has used it, the headers keep being added to requests to those hosts for the
 *   rest of the process, by any MapLibre map;
 * - it replaces the client set with MapLibre's `HttpRequestUtil.setOkHttpClient`. If your app
 *   sets its own client, do not also use this.
 *
 * @param hosts host names, e.g. `api.example.com`. Exact match, case-insensitive; no wildcards.
 * @param headers e.g. `mapOf("Authorization" to "Bearer ...")`.
 */
public class MapAuthentication(hosts: Set<String>, headers: Map<String, String>) {
    internal val hosts: Set<String> = hosts.mapTo(mutableSetOf()) { it.lowercase() }
    internal val headers: Map<String, String> = headers.toMap()

    override fun equals(other: Any?): Boolean =
        other is MapAuthentication && other.hosts == hosts && other.headers == headers

    override fun hashCode(): Int = 31 * hosts.hashCode() + headers.hashCode()

    /** Never prints the headers, which usually hold a key. */
    override fun toString(): String = "MapAuthentication(hosts=$hosts)"

    public companion object {
        /**
         * The key and app identity from [credentials], sent to the Remaply API's host: for a
         * style of your own that uses Remaply tiles or sprites. For Remaply's own style, use
         * [MapStyle.remaply].
         */
        @JvmStatic
        public fun remaply(credentials: RemaplyCredentials): MapAuthentication =
            MapAuthentication(setOfNotNull(credentials.host), credentials.requestHeaders)
    }
}
