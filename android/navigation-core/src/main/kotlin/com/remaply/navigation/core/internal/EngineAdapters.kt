package com.remaply.navigation.core.internal

import android.content.Context
import android.location.Location
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.remaply.navigation.core.InstructionSpeaker
import com.remaply.navigation.core.LocationProvider
import com.remaply.navigation.core.LocationSource
import com.remaply.navigation.core.NavigationThresholds
import com.remaply.navigation.core.VoiceGuidance
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import com.stadiamaps.ferrostar.core.location.AndroidLocationProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import com.stadiamaps.ferrostar.googleplayservices.FusedNavigationLocationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.DeviationCalculationPolicy
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.stepAdvanceDistanceEntryAndSnappedExit
import uniffi.ferrostar.stepAdvanceDistanceFromStep
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import uniffi.ferrostar.stepAdvanceOr

private const val TAG = "RemaplyNavigation"

/**
 * Ferrostar's controller settings for [NavigationThresholds], the same as the Remaply web app:
 *
 * A step normally ends when the user reaches its end and then leaves it. That alone gets stuck
 * where a stop needs a U-turn (the route doubles back over the step), so the step also ends once
 * the user is clearly away from it while still on the route.
 */
internal fun NavigationThresholds.toEngineConfig(): NavigationControllerConfig {
    val accuracy = minimumAccuracyMeters.toUShort()
    return NavigationControllerConfig(
        WaypointAdvanceMode.WaypointWithinRange(waypointReachedMeters.toDouble()),
        stepAdvanceOr(
            listOf(
                stepAdvanceDistanceEntryAndSnappedExit(stepEndMeters.toUShort(), 5u, accuracy),
                stepAdvanceDistanceFromStep(offRouteMeters.toUShort(), accuracy, DeviationCalculationPolicy.WHILE_ON_ROUTE),
            ),
        ),
        stepAdvanceDistanceToEndOfStep(stepEndMeters.toUShort(), accuracy),
        RouteDeviationTracking.StaticThreshold(accuracy, offRouteMeters.toDouble()),
        CourseFiltering.SNAP_TO_ROUTE,
    )
}

internal object LocationProviders {
    fun create(context: Context, source: LocationSource): NavigationLocationProviding = when (source) {
        LocationSource.Device ->
            if (hasPlayServices(context)) FusedNavigationLocationProvider(context) else AndroidLocationProvider(context)
        LocationSource.PlatformGps -> AndroidLocationProvider(context)
        is LocationSource.Custom -> CustomLocationAdapter(source.provider)
    }

    private fun hasPlayServices(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
}

private class CustomLocationAdapter(private val provider: LocationProvider) : NavigationLocationProviding {
    override suspend fun lastLocation(): Location? = provider.lastLocation()?.toAndroidLocation()

    override fun locationUpdates(intervalMillis: Long): Flow<Location> =
        provider.locationUpdates().map { it.toAndroidLocation() }
}

/** The speech side of [VoiceGuidance], as Ferrostar's observer. Owns its engine's lifecycle. */
internal interface Speech : SpokenInstructionObserver {
    fun release()
}

internal fun createSpeech(context: Context, voice: VoiceGuidance): Speech? = when (voice) {
    VoiceGuidance.Disabled -> null
    is VoiceGuidance.Default -> SystemSpeech(context, voice.locale)
    is VoiceGuidance.Custom -> CustomSpeech(voice.speaker)
}

private class SystemSpeech(context: Context, locale: java.util.Locale?) : Speech {
    private val observer = AndroidTtsObserver(context.applicationContext).also { tts ->
        tts.statusObserver = object : AndroidTtsStatusListener {
            override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "Text-to-speech is not available (status $status); instructions will not be spoken")
                    return
                }
                if (locale != null && tts?.setLanguage(locale) == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Text-to-speech has no voice for $locale; using the default voice")
                }
            }

            override fun onTtsShutdownAndRelease() = Unit

            override fun onTtsSpeakError(utteranceId: String, status: Int) {
                Log.w(TAG, "Could not speak instruction $utteranceId (status $status)")
            }
        }
        tts.start()
    }

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) =
        observer.onSpokenInstructionTrigger(spokenInstruction)

    override fun stopAndClearQueue() = observer.stopAndClearQueue()

    override fun setMuted(isMuted: Boolean) = observer.setMuted(isMuted)

    override val muteState: StateFlow<Boolean> get() = observer.muteState

    override val isMuted: Boolean get() = observer.isMuted

    override fun release() = observer.shutdown()
}

private class CustomSpeech(private val speaker: InstructionSpeaker) : Speech {
    private val mute = MutableStateFlow(false)

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        if (!mute.value) {
            runCatching { speaker.speak(spokenInstruction.text, spokenInstruction.ssml) }
                .onFailure { Log.w(TAG, "InstructionSpeaker.speak failed", it) }
        }
    }

    override fun stopAndClearQueue() {
        runCatching { speaker.stop() }
    }

    override fun setMuted(isMuted: Boolean) {
        mute.value = isMuted
        if (isMuted) stopAndClearQueue()
    }

    override val muteState: StateFlow<Boolean> = mute.asStateFlow()

    override val isMuted: Boolean get() = mute.value

    override fun release() {
        runCatching { speaker.release() }
    }
}
