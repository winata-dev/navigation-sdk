package com.remaply.navigation.core

import java.util.Locale
import kotlinx.coroutines.flow.Flow

/**
 * How a [RemaplyNavigator] behaves. Only [routeProvider] is required; the defaults suit driving.
 */
public data class NavigatorConfig @JvmOverloads constructor(
    /** Where routes come from, e.g. [ValhallaRouteProvider.remaply] or [ValhallaRouteProvider]. */
    val routeProvider: RouteProvider,
    /** Default profile for [RemaplyNavigator.requestRoutes] and [RemaplyNavigator.navigateTo]. */
    val profile: TravelProfile = TravelProfile.CAR,
    /** Distances that decide progress and leaving the route. `null` picks [NavigationThresholds.forProfile]. */
    val thresholds: NavigationThresholds? = null,
    val rerouting: ReroutingOptions = ReroutingOptions(),
    val voiceGuidance: VoiceGuidance = VoiceGuidance.Default(),
    val locationSource: LocationSource = LocationSource.Device,
    /**
     * Keeps GPS and voice running while the app is in the background, with an ongoing
     * notification (a foreground service). Needs location permission; on Android 13+ the
     * notification also needs POST_NOTIFICATIONS. See [NavigationPermissions].
     */
    val backgroundNavigation: Boolean = true,
    /** How much faster than real time a simulated drive runs. 1 is real time. */
    val simulationSpeedMultiplier: Int = 2,
) {
    init {
        require(simulationSpeedMultiplier >= 1) { "simulationSpeedMultiplier must be at least 1" }
    }
}

/**
 * Distances, in meters, that decide when a step, a stop or the route itself is left behind.
 *
 * The defaults from [forProfile] match the Remaply web app's tuned values.
 */
public data class NavigationThresholds(
    /** Distance from the route line beyond which the user is off route. */
    val offRouteMeters: Int,
    /** Distance from a stop at which it counts as reached. */
    val waypointReachedMeters: Int,
    /** Distance from the end of a step at which the next step may begin. */
    val stepEndMeters: Int,
    /** GPS fixes less accurate than this neither advance a step nor count as off route. */
    val minimumAccuracyMeters: Int = 30,
) {
    init {
        require(offRouteMeters > 0 && waypointReachedMeters > 0 && stepEndMeters > 0 && minimumAccuracyMeters > 0) {
            "All thresholds must be positive"
        }
        require(maxOf(offRouteMeters, waypointReachedMeters, stepEndMeters, minimumAccuracyMeters) <= 5_000) {
            "Thresholds above 5000 m are not supported"
        }
    }

    public companion object {
        /**
         * Walking and cycling are slower and closer to the line, and paths are often missing from
         * the map, so their distances are shorter.
         */
        @JvmStatic
        public fun forProfile(profile: TravelProfile): NavigationThresholds = when (profile) {
            TravelProfile.CAR, TravelProfile.MOTORCYCLE -> NavigationThresholds(50, 100, 30)
            TravelProfile.TRUCK -> NavigationThresholds(60, 100, 40)
            TravelProfile.BICYCLE -> NavigationThresholds(40, 40, 20)
            TravelProfile.FOOT -> NavigationThresholds(40, 30, 15)
        }
    }
}

/** What happens when the user leaves the route. */
public data class ReroutingOptions(
    /** Fetch a new route automatically. When false, only [NavigationEvent.OffRoute] is reported. */
    val enabled: Boolean = true,
    /** Minimum time between two reroute requests. Each one is a call to your routing server. */
    val minimumIntervalSeconds: Double = 15.0,
    /** Minimum distance moved since the last reroute request before another one is made. */
    val minimumMovementMeters: Double = 50.0,
)

/** Spoken turn-by-turn instructions. */
public sealed interface VoiceGuidance {
    /** No speech. */
    public data object Disabled : VoiceGuidance

    /**
     * The device's text-to-speech engine.
     *
     * @param locale language of the voice; should match the language of the route instructions.
     *   `null` uses the device language.
     */
    public data class Default(val locale: Locale? = null) : VoiceGuidance

    /** Your own speech, e.g. a cloud voice. */
    public data class Custom(val speaker: InstructionSpeaker) : VoiceGuidance
}

/** Speaks instructions for [VoiceGuidance.Custom]. Methods are called on a background thread. */
public interface InstructionSpeaker {
    /**
     * Speak [text] now. [ssml] is the same instruction as SSML when the server provides it.
     * Muting is handled by the SDK: this is not called while muted.
     */
    public fun speak(text: String, ssml: String?)

    /** Stop speaking and drop anything queued. */
    public fun stop()

    /** Release resources. Called once, from [RemaplyNavigator.close]. */
    public fun release() {}
}

/** Where location fixes come from when not simulating. */
public sealed interface LocationSource {
    /**
     * The best available: Google Play Services' fused location when installed (better in cities
     * and tunnels), else the platform GPS.
     */
    public data object Device : LocationSource

    /** Always the platform's LocationManager, e.g. for devices or app stores without Google Play Services. */
    public data object PlatformGps : LocationSource

    /** Your own location, e.g. an external GNSS receiver or a replayed recording. */
    public data class Custom(val provider: LocationProvider) : LocationSource
}

/** Supplies location fixes for [LocationSource.Custom]. */
public interface LocationProvider {
    /** The most recent fix, if any. Used as the origin of a route request. */
    public suspend fun lastLocation(): UserLocation? = null

    /** Fixes as they arrive. Collected only while navigating or waiting for a first fix. */
    public fun locationUpdates(): Flow<UserLocation>
}
