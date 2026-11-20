package com.remaply.navigation.ui

import androidx.compose.ui.graphics.Color

/** What [RemaplyNavigationView] shows. */
public data class NavigationViewOptions(
    /** Mute button. Hidden anyway when voice guidance is disabled. */
    val showMuteButton: Boolean = true,
    val showZoomButtons: Boolean = true,
    /** Button to recenter the camera on the user after they panned the map. */
    val showRecenterButton: Boolean = true,
    /** Style of the speed limit sign, or null to hide it. Needs speed limits from the routing server. */
    val speedLimitSignage: SpeedLimitSignage? = SpeedLimitSignage.VIENNA_CONVENTION,
    /** Banner while a new route is fetched, and a "Simulated drive" label while simulating. */
    val showStatusBanner: Boolean = true,
    /** Keeps the screen on while navigating. */
    val keepScreenOn: Boolean = true,
    /** Colors. Anything left null follows your app's MaterialTheme. */
    val theme: NavigationTheme = NavigationTheme(),
)

public enum class SpeedLimitSignage {
    /** Round red-bordered sign, used in most of the world. */
    VIENNA_CONVENTION,

    /** Rectangular "SPEED LIMIT" sign, used in the United States. */
    MUTCD,
}

/**
 * Color overrides for the navigation UI. By default every element follows your app's
 * `MaterialTheme` (surface, onSurface, secondary...), so wrapping the view in your theme is often
 * enough.
 */
public data class NavigationTheme(
    /** Background of the instruction banner at the top. */
    val instructionBackground: Color? = null,
    /** Distance and maneuver icon in the instruction banner. */
    val instructionPrimaryText: Color? = null,
    /** Road name in the instruction banner. */
    val instructionSecondaryText: Color? = null,
    /** Background of the distance / ETA bar at the bottom. */
    val progressBackground: Color? = null,
    val progressText: Color? = null,
    val exitButtonBackground: Color? = null,
    val exitButtonIcon: Color? = null,
    /** The current road name pill. */
    val roadNameBackground: Color? = null,
    val roadNameText: Color? = null,
    /** The rerouting / simulation banner. */
    val statusBannerBackground: Color? = null,
    val statusBannerText: Color? = null,
)
