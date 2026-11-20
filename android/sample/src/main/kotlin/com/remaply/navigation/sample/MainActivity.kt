package com.remaply.navigation.sample

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.remaply.navigation.core.NavigationEvent
import com.remaply.navigation.core.NavigationException
import com.remaply.navigation.core.NavigationPermissions
import com.remaply.navigation.core.NavigationState
import com.remaply.navigation.core.NavigatorConfig
import com.remaply.navigation.core.RemaplyNavigator
import com.remaply.navigation.core.RouteProvider
import com.remaply.navigation.core.UserLocation
import com.remaply.navigation.core.Coordinate
import com.remaply.navigation.core.ValhallaRouteProvider
import com.remaply.navigation.core.Waypoint
import com.remaply.navigation.ui.RemaplyNavigationView
import kotlinx.coroutines.launch

/** Keeps the navigator across rotations, and closes it with the screen. */
class SampleViewModel(application: Application) : AndroidViewModel(application) {
    private val routeProvider: RouteProvider =
        if (BuildConfig.REMAPLY_API_KEY.isNotBlank()) {
            ValhallaRouteProvider.remaply(application, apiKey = BuildConfig.REMAPLY_API_KEY)
        } else {
            ValhallaRouteProvider(BuildConfig.VALHALLA_URL)
        }

    val navigator = RemaplyNavigator(application, NavigatorConfig(routeProvider = routeProvider))

    var error by mutableStateOf<String?>(null)
        private set

    val eventLog = mutableStateListOf<String>()

    init {
        viewModelScope.launch {
            navigator.events.collect { event ->
                eventLog.add(0, event.describe())
                if (eventLog.size > 20) eventLog.removeAt(eventLog.lastIndex)
            }
        }
    }

    fun start(origin: Coordinate?, destination: Coordinate, simulate: Boolean) {
        error = null
        viewModelScope.launch {
            try {
                navigator.navigateTo(
                    waypoints = listOf(Waypoint(destination)),
                    simulate = simulate,
                    origin = origin?.let { UserLocation(it, horizontalAccuracyMeters = 5.0) },
                )
            } catch (e: NavigationException) {
                error = e.message
            }
        }
    }

    override fun onCleared() = navigator.close()
}

private fun NavigationEvent.describe(): String = when (this) {
    is NavigationEvent.NavigationStarted -> "Started (${route.distanceMeters.toInt()} m${if (simulated) ", simulated" else ""})"
    is NavigationEvent.StepAdvanced -> "Next: ${instruction?.primaryText ?: "-"}"
    NavigationEvent.OffRoute -> "Off route"
    NavigationEvent.BackOnRoute -> "Back on route"
    is NavigationEvent.Rerouted -> "Rerouted (${route.distanceMeters.toInt()} m)"
    is NavigationEvent.WaypointReached -> "Reached stop ${index + 1}"
    NavigationEvent.Arrived -> "Arrived"
    NavigationEvent.NavigationStopped -> "Stopped"
    is NavigationEvent.Error -> "Error: ${error.message}"
}

class MainActivity : ComponentActivity() {
    private val model: SampleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val state by model.navigator.state.collectAsStateWithLifecycle()
                if (state.status == NavigationState.Status.NAVIGATING || state.status == NavigationState.Status.ARRIVED) {
                    RemaplyNavigationView(
                        navigator = model.navigator,
                        styleUrl = BuildConfig.STYLE_URL,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TripForm(model, routing = state.status == NavigationState.Status.ROUTING)
                }
            }
        }
    }
}

/** Pick a trip. The defaults are a short drive in Suva, Fiji. */
@Composable
private fun TripForm(model: SampleViewModel, routing: Boolean) {
    var origin by rememberSaveable { mutableStateOf("-18.1416, 178.4419") }
    var destination by rememberSaveable { mutableStateOf("-18.1250, 178.4500") }
    var simulate by rememberSaveable { mutableStateOf(true) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (!permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(NavigationPermissions.required)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Remaply Navigation SDK", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Routes: " + if (BuildConfig.REMAPLY_API_KEY.isNotBlank()) "Remaply API" else BuildConfig.VALHALLA_URL,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = simulate, onCheckedChange = { simulate = it })
                Spacer(Modifier.width(8.dp))
                Text(if (simulate) "Simulate the drive from the origin below" else "Use my GPS location")
            }
            if (simulate) {
                OutlinedTextField(origin, { origin = it }, label = { Text("Origin (lat, lon)") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(destination, { destination = it }, label = { Text("Destination (lat, lon)") }, modifier = Modifier.fillMaxWidth())

            Button(
                enabled = !routing,
                onClick = {
                    val to = parseCoordinate(destination) ?: return@Button
                    model.start(origin = if (simulate) parseCoordinate(origin) else null, destination = to, simulate = simulate)
                },
            ) {
                if (routing) CircularProgressIndicator(Modifier.padding(end = 8.dp).width(16.dp), strokeWidth = 2.dp)
                Text(if (routing) "Finding route…" else "Start navigation")
            }

            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (model.eventLog.isNotEmpty()) {
                Text("Last trip's events", style = MaterialTheme.typography.titleSmall)
                model.eventLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun parseCoordinate(text: String): Coordinate? {
    val parts = text.split(',').map { it.trim().toDoubleOrNull() }
    if (parts.size != 2 || parts.any { it == null }) return null
    return runCatching { Coordinate(parts[0]!!, parts[1]!!) }.getOrNull()
}
