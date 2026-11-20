package com.remaply.navigation.ui.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import com.remaply.navigation.core.RemaplyNavigator
import com.remaply.navigation.core.VoiceGuidance
import com.remaply.navigation.ui.NavigationTheme
import com.stadiamaps.ferrostar.composeui.theme.DefaultInstructionRowTheme
import com.stadiamaps.ferrostar.composeui.theme.DefaultNavigationUITheme
import com.stadiamaps.ferrostar.composeui.theme.DefaultRoadNameViewTheme
import com.stadiamaps.ferrostar.composeui.theme.DefaultTripProgressViewTheme
import com.stadiamaps.ferrostar.composeui.theme.InstructionRowTheme
import com.stadiamaps.ferrostar.composeui.theme.NavigationUITheme
import com.stadiamaps.ferrostar.composeui.theme.RoadNameViewTheme
import com.stadiamaps.ferrostar.composeui.theme.TripProgressViewStyle
import com.stadiamaps.ferrostar.composeui.theme.TripProgressViewTheme
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.NavigationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Ferrostar's views read a [NavigationViewModel]. This one is fed by a [RemaplyNavigator], so the
 * navigator stays the single source of truth for apps.
 */
internal class NavigatorViewModel(
    private val navigator: RemaplyNavigator,
    scope: CoroutineScope,
) : NavigationViewModel {
    private val destination = MutableStateFlow<String?>(null)
    private val hasVoice = navigator.config.voiceGuidance !is VoiceGuidance.Disabled

    override val navigationUiState: StateFlow<NavigationUiState> =
        combine(
            navigator.engine.state,
            navigator.state.map { it.isMuted }.distinctUntilChanged(),
            destination,
        ) { core, muted, destination ->
            uiState(core, muted).copy(destination = destination)
        }.stateIn(scope, SharingStarted.Eagerly, uiState(navigator.engine.state.value, navigator.state.value.isMuted))

    private fun uiState(core: com.stadiamaps.ferrostar.core.NavigationState, muted: Boolean) =
        NavigationUiState.fromFerrostar(
            core,
            // Ferrostar hides the mute button when this is null.
            isMuted = if (hasVoice) muted else null,
            annotation = navigator.annotationPublisher.map(core),
        )

    override fun setDestination(destination: String?) {
        this.destination.value = destination
    }

    override fun toggleMute() {
        navigator.setMuted(!navigator.state.value.isMuted)
    }

    override fun stopNavigation() {
        navigator.stopNavigation()
    }
}

/** [NavigationTheme] on top of Ferrostar's Material-based defaults. */
internal class ThemeAdapter(private val theme: NavigationTheme) : NavigationUITheme {
    override val instructionRowTheme: InstructionRowTheme
        @Composable get() = object : InstructionRowTheme {
            override val distanceTextStyle: TextStyle
                @Composable get() = DefaultInstructionRowTheme.distanceTextStyle.withColor(theme.instructionPrimaryText)
            override val instructionTextStyle: TextStyle
                @Composable get() = DefaultInstructionRowTheme.instructionTextStyle.withColor(theme.instructionSecondaryText)
            override val iconTintColor: Color
                @Composable get() = theme.instructionPrimaryText ?: DefaultInstructionRowTheme.iconTintColor
            override val backgroundColor: Color
                @Composable get() = theme.instructionBackground ?: DefaultInstructionRowTheme.backgroundColor
        }

    override val roadNameViewTheme: RoadNameViewTheme
        @Composable get() = object : RoadNameViewTheme {
            override val textStyle: TextStyle
                @Composable get() = DefaultRoadNameViewTheme.textStyle.withColor(theme.roadNameText)
            override val backgroundColor: Color
                @Composable get() = theme.roadNameBackground ?: DefaultRoadNameViewTheme.backgroundColor
            override val borderColor: Color
                @Composable get() = DefaultRoadNameViewTheme.borderColor
        }

    override val tripProgressViewTheme: TripProgressViewTheme
        @Composable get() = object : TripProgressViewTheme {
            override val style: TripProgressViewStyle
                @Composable get() = TripProgressViewStyle.INFORMATIONAL
            override val measurementTextStyle: TextStyle
                @Composable get() = DefaultTripProgressViewTheme.measurementTextStyle.withColor(theme.progressText)
            override val secondaryTextStyle: TextStyle
                @Composable get() = DefaultTripProgressViewTheme.secondaryTextStyle.withColor(theme.progressText?.copy(alpha = 0.7f))
            override val exitIconColor: Color
                @Composable get() = theme.exitButtonIcon ?: DefaultTripProgressViewTheme.exitIconColor
            override val exitButtonBackgroundColor: Color
                @Composable get() = theme.exitButtonBackground ?: DefaultTripProgressViewTheme.exitButtonBackgroundColor
            override val backgroundColor: Color
                @Composable get() = theme.progressBackground ?: DefaultTripProgressViewTheme.backgroundColor
        }

    override val buttonSize: DpSize
        @Composable get() = DefaultNavigationUITheme.buttonSize
}

private fun TextStyle.withColor(color: Color?): TextStyle = if (color == null) this else copy(color = color)
