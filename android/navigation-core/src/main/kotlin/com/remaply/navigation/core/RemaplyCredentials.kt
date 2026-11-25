package com.remaply.navigation.core

import android.content.Context
import com.remaply.navigation.core.internal.AppIdentity
import java.net.URI
import java.net.URLEncoder

/**
 * A Remaply API key and this app's identity, for everything that calls the Remaply API: routes
 * ([ValhallaRouteProvider.remaply]) and the map (`MapStyle.remaply` in navigation-ui: style,
 * tiles and sprites).
 *
 * ```
 * val remaply = RemaplyCredentials(context, BuildConfig.REMAPLY_API_KEY)
 * val navigator = RemaplyNavigator(context, NavigatorConfig(routeProvider = ValhallaRouteProvider.remaply(remaply)))
 * RemaplyNavigationView(navigator, style = MapStyle.remaply(remaply))
 * ```
 *
 * Every request carries the key in `X-Api-Key`, plus this app's package name and signing
 * certificate SHA-1, so keys restricted to your Android app work.
 *
 * Use a **publishable** key (`rmp_pk_...`), never a secret one: anything shipped in an app can be
 * read. A blank key does not throw here. Route requests then fail with
 * [NavigationException.Unauthorized] ([NavigationException.Unauthorized.Reason.MISSING_API_KEY]),
 * so a key missing from a build's configuration shows up as an error, not a crash.
 *
 * @param apiKey a key allowed to use routing, and styles and tiles if the map uses Remaply's style.
 * @param baseUrl the API's base URL.
 */
public class RemaplyCredentials @JvmOverloads constructor(
    context: Context,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
) {
    /** The key, without leading or trailing spaces. */
    public val apiKey: String = apiKey.trim()

    /** The API's base URL, without a trailing slash. */
    public val baseUrl: String = baseUrl.trimEnd('/')

    /** False when the key is blank. */
    public val hasApiKey: Boolean get() = apiKey.isNotEmpty()

    /** The headers sent with every request to the API. */
    @InternalNavigationApi
    public val requestHeaders: Map<String, String> = buildMap {
        if (hasApiKey) put("X-Api-Key", this@RemaplyCredentials.apiKey)
        putAll(AppIdentity.headers(context))
    }

    /** The API's host name, lower case, or null when [baseUrl] is not a valid URL. */
    @InternalNavigationApi
    public val host: String? = runCatching { URI(this.baseUrl).host?.lowercase() }.getOrNull()

    /**
     * The URL of a Remaply map style (`GET /v1/styles/{styleId}.json`). The style points MapLibre
     * at the Remaply tiles (`/v1/tiles/...`) and sprites (`/v1/sprites/...`), all on this API, so
     * the map's requests need the same headers: use `MapStyle.remaply` in navigation-ui, which
     * does both. Only call this for a MapLibre map of your own, and add [requestHeaders] to its
     * requests to [host].
     *
     * @param styleId [DEFAULT_STYLE_ID], Remaply's map, or the id of your organization's style
     *   from Map Studio.
     */
    @JvmOverloads
    public fun styleUrl(styleId: String = DEFAULT_STYLE_ID): String = styleUrl(baseUrl, styleId)

    /** Never prints the whole key, so it does not end up in logs or crash reports. */
    override fun toString(): String =
        "RemaplyCredentials(apiKey=${if (hasApiKey) apiKey.take(12) + "…" else "<none>"}, baseUrl=$baseUrl)"

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.remaply.com"

        /** Remaply's default map style, the same for every organization. */
        public const val DEFAULT_STYLE_ID: String = "default"

        internal fun styleUrl(baseUrl: String, styleId: String): String {
            val id = styleId.trim()
            require(id.isNotEmpty()) { "styleId must not be blank" }
            val segment = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
            return "${baseUrl.trimEnd('/')}/v1/styles/$segment.json"
        }
    }
}
