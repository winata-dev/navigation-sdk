package com.remaply.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remaply.navigation.core.NavigationState
import com.remaply.navigation.core.RemaplyNavigator
import com.remaply.navigation.ui.internal.MapRequestHeaders
import com.remaply.navigation.ui.internal.NavigatorViewModel
import com.remaply.navigation.ui.internal.ThemeAdapter
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SignageStyle
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable

/**
 * A complete navigation screen: the map with the route and the user's position, the next
 * maneuver, distance and time remaining, speed limit, and mute, zoom, recenter and exit buttons.
 * Adapts to portrait and landscape.
 *
 * Show it while [navigator] is navigating (it also works idle, as a plain map). With the Remaply
 * API, the same credentials give routes and the whole map:
 *
 * ```
 * RemaplyNavigationView(
 *     navigator = navigator,
 *     style = MapStyle.remaply(credentials),
 * )
 * ```
 *
 * @param style the map: [MapStyle.remaply], or any MapLibre style with `MapStyle(url)`.
 * @param onExit called when the user taps the exit button. Defaults to stopping navigation.
 *   Pass null to hide the button.
 * @param overlay your own UI on top of the map, e.g. a "report" button. Receives the current state.
 * @param mapContent your own MapLibre layers (sources, symbols...), drawn above the base map.
 */
@Composable
public fun RemaplyNavigationView(
    navigator: RemaplyNavigator,
    style: MapStyle,
    modifier: Modifier = Modifier,
    options: NavigationViewOptions = NavigationViewOptions(),
    onExit: (() -> Unit)? = { navigator.stopNavigation() },
    overlay: @Composable BoxScope.(state: NavigationState) -> Unit = {},
    mapContent: @Composable @MaplibreComposable (state: NavigationState) -> Unit = {},
) {
    val mapAuthentication = style.authentication
    // During composition, so the headers are in place before the map requests its style.
    remember(mapAuthentication) { mapAuthentication?.let(MapRequestHeaders::register) }

    val state by navigator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val viewModel = remember(navigator, scope) { NavigatorViewModel(navigator, scope) }
    val theme = remember(options.theme) { ThemeAdapter(options.theme) }

    if (options.keepScreenOn && state.isNavigating) {
        KeepScreenOnDisposableEffect()
    }

    val config = VisualNavigationViewConfig(
        showMute = options.showMuteButton,
        showZoom = options.showZoomButtons,
        showRecenter = options.showRecenterButton,
        speedLimitStyle = when (options.speedLimitSignage) {
            SpeedLimitSignage.VIENNA_CONVENTION -> SignageStyle.ViennaConvention
            SpeedLimitSignage.MUTCD -> SignageStyle.MUTCD
            null -> null
        },
    )

    val views = NavigationViewComponentBuilder.Default(theme).withCustomOverlayView { overlayModifier ->
        Box(overlayModifier.fillMaxSize()) {
            if (options.showStatusBanner) {
                StatusBanner(state, options.theme, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
            }
            // Ferrostar hides its controls once the trip is complete; without this card the user
            // would be left on a bare map.
            if (state.status == NavigationState.Status.ARRIVED && onExit != null) {
                ArrivalCard(onDone = onExit, modifier = Modifier.align(Alignment.BottomCenter))
            }
            overlay(state)
        }
    }

    DynamicallyOrientingNavigationView(
        modifier = modifier,
        baseStyle = BaseStyle.Uri(style.url),
        viewModel = viewModel,
        theme = theme,
        config = config,
        views = views,
        onTapExit = onExit,
        mapContent = { mapContent(state) },
    )
}

/**
 * [RemaplyNavigationView] with a style URL and, optionally, headers for its servers. The same as
 * passing `style = MapStyle(styleUrl, mapAuthentication)`.
 *
 * @param styleUrl a MapLibre style JSON URL. Must include the attribution your tiles require
 *   (e.g. "© OpenStreetMap contributors").
 * @param mapAuthentication headers for map servers that need an API key.
 */
@Composable
public fun RemaplyNavigationView(
    navigator: RemaplyNavigator,
    styleUrl: String,
    modifier: Modifier = Modifier,
    options: NavigationViewOptions = NavigationViewOptions(),
    mapAuthentication: MapAuthentication? = null,
    onExit: (() -> Unit)? = { navigator.stopNavigation() },
    overlay: @Composable BoxScope.(state: NavigationState) -> Unit = {},
    mapContent: @Composable @MaplibreComposable (state: NavigationState) -> Unit = {},
) {
    RemaplyNavigationView(
        navigator = navigator,
        style = remember(styleUrl, mapAuthentication) { MapStyle(styleUrl, mapAuthentication) },
        modifier = modifier,
        options = options,
        onExit = onExit,
        overlay = overlay,
        mapContent = mapContent,
    )
}

@Composable
private fun ArrivalCard(onDone: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.remaply_navigation_arrived_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDone) { Text(stringResource(R.string.remaply_navigation_done)) }
        }
    }
}

/** "Finding a new route…" while rerouting, "Simulated drive" while simulating. */
@Composable
private fun StatusBanner(state: NavigationState, theme: NavigationTheme, modifier: Modifier) {
    val text = when {
        !state.isNavigating -> null
        state.isRerouting -> stringResource(R.string.remaply_navigation_rerouting)
        state.isOffRoute -> stringResource(R.string.remaply_navigation_off_route)
        state.isSimulating -> stringResource(R.string.remaply_navigation_simulating)
        else -> null
    } ?: return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = theme.statusBannerBackground ?: MaterialTheme.colorScheme.inverseSurface,
        contentColor = theme.statusBannerText ?: MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
