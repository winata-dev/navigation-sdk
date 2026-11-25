package com.remaply.navigation.ui

import com.remaply.navigation.core.RemaplyCredentials

/**
 * The map's look: a MapLibre style URL, plus the headers its servers need, if any. Pass it to
 * [RemaplyNavigationView].
 *
 * For the Remaply map, [remaply] is all you need: the style, TileJSON, tiles and sprites all come
 * from the Remaply API, and they all get the key.
 *
 * @param url a MapLibre style JSON URL. Must include the attribution your tiles require (e.g.
 *   "© OpenStreetMap contributors").
 * @param authentication headers for map servers that need an API key.
 */
public class MapStyle @JvmOverloads constructor(
    public val url: String,
    public val authentication: MapAuthentication? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is MapStyle && other.url == url && other.authentication == authentication

    override fun hashCode(): Int = 31 * url.hashCode() + (authentication?.hashCode() ?: 0)

    override fun toString(): String = "MapStyle(url=$url, authentication=$authentication)"

    public companion object {
        /**
         * The Remaply map (`GET /v1/styles/{styleId}.json`), with the key and app identity from
         * [credentials] added to every request the map makes to the Remaply API: the style, then
         * the TileJSON (`/v1/tiles/{tileset}.json`), tiles (`/v1/tiles/{tileset}/{z}/{x}/{y}.mvt`)
         * and sprites (`/v1/sprites/...`) the style names. Requests to other hosts get no key.
         *
         * With a blank key the map stays empty (the API answers 401); routes from the same
         * credentials fail with [com.remaply.navigation.core.NavigationException.Unauthorized].
         *
         * @param styleId [RemaplyCredentials.DEFAULT_STYLE_ID], Remaply's map, or the id of your
         *   organization's style from Map Studio.
         */
        @JvmStatic
        @JvmOverloads
        public fun remaply(
            credentials: RemaplyCredentials,
            styleId: String = RemaplyCredentials.DEFAULT_STYLE_ID,
        ): MapStyle = MapStyle(credentials.styleUrl(styleId), MapAuthentication.remaply(credentials))
    }
}
