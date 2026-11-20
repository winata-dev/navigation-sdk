package com.remaply.navigation.core

import android.content.Context
import android.util.Log
import com.remaply.navigation.core.internal.LocationProviders
import com.remaply.navigation.core.internal.PermissionGatedForegroundService
import com.remaply.navigation.core.internal.Speech
import com.remaply.navigation.core.internal.createSpeech
import com.remaply.navigation.core.internal.toEngineConfig
import com.remaply.navigation.core.internal.toInstruction
import com.remaply.navigation.core.internal.toRouteStep
import com.remaply.navigation.core.internal.toTripProgress
import com.remaply.navigation.core.internal.toUserLocation
import com.remaply.navigation.core.internal.toWaypoint
import com.stadiamaps.ferrostar.core.AlternativeRouteProcessor
import com.stadiamaps.ferrostar.core.CorrectiveAction
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.RouteDeviationHandler
import com.stadiamaps.ferrostar.core.annotation.AnnotationPublisher
import com.stadiamaps.ferrostar.core.annotation.Speed
import com.stadiamaps.ferrostar.core.annotation.SpeedUnit
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import com.stadiamaps.ferrostar.core.http.IResponse
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation as EngineLocation
import uniffi.ferrostar.Waypoint as EngineWaypoint

/**
 * Runs turn-by-turn navigation: fetches routes, follows the GPS (or a simulated drive), tracks
 * progress, speaks instructions and reroutes when the user leaves the route.
 *
 * Create one per screen or per app (it is cheap while idle), show it with the navigation UI's
 * `RemaplyNavigationView`, and [close] it when done.
 *
 * ```
 * val navigator = RemaplyNavigator(context, NavigatorConfig(
 *     routeProvider = ValhallaRouteProvider.remaply(context, apiKey = "rmp_pk_..."),
 * ))
 * navigator.navigateTo(Waypoint(-18.1416, 178.4419))
 * ```
 *
 * All methods may be called from any thread. [state] and [events] emit on a background thread;
 * collect them on the main thread for UI work (Compose's `collectAsState` does).
 */
public class RemaplyNavigator(
    context: Context,
    public val config: NavigatorConfig,
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val liveLocation = LocationProviders.create(appContext, config.locationSource)
    private val simulatedLocation = SimulatedLocationProvider(warpFactor = config.simulationSpeedMultiplier.toUInt())
    private val locationProvider = NavigationLocationProvider(liveLocation, simulatedLocation)
    private val speech: Speech? = createSpeech(appContext, config.voiceGuidance)

    @Volatile private var profile: TravelProfile = config.profile
    @Volatile private var activeRoute: NavigationRoute? = null
    @Volatile private var simulating = false
    @Volatile private var closed = false
    private var stopsReached = 0

    private val _state = MutableStateFlow(NavigationState())
    private val _events = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 64)

    /** The current trip. Starts [NavigationState.Status.IDLE]. */
    public val state: StateFlow<NavigationState> = _state.asStateFlow()

    /**
     * What happens during a trip, as it happens. Not replayed: collect it before starting.
     * A slow collector misses events rather than slowing navigation down.
     */
    public val events: SharedFlow<NavigationEvent> = _events.asSharedFlow()

    /** Reads speed limits from Valhalla-style route annotations. For the SDK's UI module. */
    @InternalNavigationApi
    public val annotationPublisher: AnnotationPublisher<*> = valhallaExtendedOSRMAnnotationPublisher()

    /** The engine, for the SDK's UI module. */
    @InternalNavigationApi
    public val engine: FerrostarCore = FerrostarCore(
        customRouteProvider = CustomRouteProvider { location, waypoints -> engineReroute(location, waypoints) },
        httpClient = UnusedHttpClient,
        locationProvider = locationProvider,
        navigationControllerConfig = thresholds().toEngineConfig(),
        foregroundServiceManager = if (config.backgroundNavigation) PermissionGatedForegroundService(appContext) else null,
    ).apply {
        minimumTimeBeforeRecalculation = config.rerouting.minimumIntervalSeconds.seconds
        minimumMovementBeforeRecalculation = config.rerouting.minimumMovementMeters
        spokenInstructionObserver = speech
        if (!config.rerouting.enabled) {
            deviationHandler = RouteDeviationHandler { _, _, _ -> CorrectiveAction.DoNothing }
        }
        alternativeRouteProcessor = AlternativeRouteProcessor { core, routes ->
            val route = routes.firstOrNull() ?: return@AlternativeRouteProcessor
            val navigationRoute = NavigationRoute(route)
            activeRoute = navigationRoute
            core.replaceRoute(route, thresholds().toEngineConfig())
            _events.tryEmit(NavigationEvent.Rerouted(navigationRoute))
        }
    }

    init {
        scope.launch { followEngine() }
        speech?.let { s -> scope.launch { s.muteState.collect { muted -> _state.update { it.copy(isMuted = muted) } } } }
    }

    /**
     * Fetches routes to [waypoints] (in order, the destination last), best first.
     *
     * @param origin where to start; defaults to the device's location (see [currentLocation]).
     * @param profile defaults to [NavigatorConfig.profile].
     * @throws NavigationException when no route is found or the server cannot be reached.
     */
    @JvmOverloads
    public suspend fun requestRoutes(
        waypoints: List<Waypoint>,
        origin: UserLocation? = null,
        profile: TravelProfile = config.profile,
    ): List<NavigationRoute> {
        checkOpen()
        require(waypoints.isNotEmpty()) { "waypoints must contain at least the destination" }
        val start = origin ?: currentLocation()
        val routes = withContext(Dispatchers.IO) {
            try {
                config.routeProvider.getRoutes(RouteRequest(start, waypoints, profile, isReroute = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NavigationException) {
                throw e
            } catch (e: Exception) {
                throw NavigationException.InvalidResponse("Route provider failed: ${e.message}", e)
            }
        }
        if (routes.isEmpty()) throw NavigationException.NoRouteFound()
        return routes
    }

    /**
     * Starts following [route], replacing any trip in progress.
     *
     * @param simulate drive along the route instead of following the GPS. For demos, development
     *   and store review; needs no location permission.
     * @param profile the profile the route was requested with, used for rerouting.
     * @throws NavigationException.PermissionDenied when not simulating and location was not granted.
     */
    @JvmOverloads
    public fun startNavigation(
        route: NavigationRoute,
        simulate: Boolean = false,
        profile: TravelProfile = config.profile,
    ) {
        checkOpen()
        if (!simulate && config.locationSource !is LocationSource.Custom &&
            !NavigationPermissions.hasLocationPermission(appContext)
        ) {
            throw NavigationException.PermissionDenied()
        }

        this.profile = profile
        activeRoute = route
        simulating = simulate
        stopsReached = 0
        if (simulate) locationProvider.enableSimulationOn(route.engineRoute) else locationProvider.disableSimulation()

        engine.startNavigation(route.engineRoute, thresholds().toEngineConfig())
        _events.tryEmit(NavigationEvent.NavigationStarted(route, simulate))
    }

    /**
     * Fetches the best route to [waypoints] from the current location and starts following it.
     * [state] is [NavigationState.Status.ROUTING] meanwhile.
     *
     * @return the route being followed.
     * @throws NavigationException when no route can be fetched; the state goes back to idle.
     */
    @JvmOverloads
    public suspend fun navigateTo(
        waypoints: List<Waypoint>,
        simulate: Boolean = false,
        profile: TravelProfile = config.profile,
        origin: UserLocation? = null,
    ): NavigationRoute {
        checkOpen()
        _state.update { NavigationState(status = NavigationState.Status.ROUTING, isMuted = it.isMuted) }
        try {
            val start = origin ?: if (simulate) currentLocationOrNull() else currentLocation()
            val route = requestRoutes(waypoints, start ?: throw NavigationException.LocationUnavailable(), profile).first()
            startNavigation(route, simulate, profile)
            return route
        } catch (e: Throwable) {
            _state.update { if (it.status == NavigationState.Status.ROUTING) NavigationState(isMuted = it.isMuted) else it }
            throw e
        }
    }

    /** Shorthand for [navigateTo] with a single destination. */
    @JvmOverloads
    public suspend fun navigateTo(
        destination: Waypoint,
        simulate: Boolean = false,
        profile: TravelProfile = config.profile,
    ): NavigationRoute = navigateTo(listOf(destination), simulate, profile)

    /** Ends the trip. Safe to call when not navigating. */
    public fun stopNavigation() {
        engine.stopNavigation()
        locationProvider.disableSimulation()
        simulating = false
        activeRoute = null
    }

    /** Silences (or restores) spoken instructions. */
    public fun setMuted(muted: Boolean) {
        speech?.setMuted(muted)
    }

    /**
     * The device's location: the last known fix if any, else the next one.
     *
     * @throws NavigationException.PermissionDenied without location permission.
     * @throws NavigationException.LocationUnavailable when no fix arrives within [timeoutMillis].
     */
    @JvmOverloads
    public suspend fun currentLocation(timeoutMillis: Long = 15_000): UserLocation {
        if (config.locationSource !is LocationSource.Custom && !NavigationPermissions.hasLocationPermission(appContext)) {
            throw NavigationException.PermissionDenied()
        }
        return currentLocationOrNull(timeoutMillis) ?: throw NavigationException.LocationUnavailable()
    }

    private suspend fun currentLocationOrNull(timeoutMillis: Long = 15_000): UserLocation? {
        if (config.locationSource !is LocationSource.Custom && !NavigationPermissions.hasLocationPermission(appContext)) {
            return null
        }
        return try {
            liveLocation.lastLocation()?.toUserLocation()
                ?: withTimeoutOrNull(timeoutMillis.milliseconds) { liveLocation.locationUpdates().first() }?.toUserLocation()
        } catch (e: SecurityException) {
            null
        }
    }

    /** Stops navigation and releases the voice engine. The navigator cannot be used afterwards. */
    override fun close() {
        if (closed) return
        closed = true
        stopNavigation()
        speech?.release()
        scope.cancel()
    }

    private fun checkOpen() {
        if (closed) throw NavigationException.IllegalUse("This RemaplyNavigator was closed")
    }

    private fun thresholds() = NavigationThresholds.forProfileOr(config.thresholds, profile)

    /** Called by the engine when the user left the route. */
    private suspend fun engineReroute(location: EngineLocation, waypoints: List<EngineWaypoint>): List<Route> {
        val request = RouteRequest(location.toUserLocation(), waypoints.map { it.toWaypoint() }, profile, isReroute = true)
        return try {
            config.routeProvider.getRoutes(request).map { it.engineRoute }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e as? NavigationException ?: NavigationException.InvalidResponse("Route provider failed: ${e.message}", e)
            Log.w(TAG, "Reroute failed", error)
            _events.tryEmit(NavigationEvent.Error(error))
            throw error
        }
    }

    private suspend fun followEngine() {
        var previous: com.stadiamaps.ferrostar.core.NavigationState? = null
        engine.state.collect { current ->
            val muted = _state.value.isMuted
            _state.value = toState(current, muted)
            emitTransitions(previous, current)
            previous = current
        }
    }

    private fun toState(core: com.stadiamaps.ferrostar.core.NavigationState, muted: Boolean): NavigationState =
        when (val trip = core.tripState) {
            is TripState.Idle -> {
                // Keep ROUTING while navigateTo fetches the first route.
                val routing = _state.value.status == NavigationState.Status.ROUTING
                NavigationState(
                    status = if (routing) NavigationState.Status.ROUTING else NavigationState.Status.IDLE,
                    location = trip.userLocation?.toUserLocation(),
                    isMuted = muted,
                )
            }
            is TripState.Navigating -> {
                val steps = trip.remainingSteps
                NavigationState(
                    status = NavigationState.Status.NAVIGATING,
                    route = activeRoute,
                    location = trip.userLocation.toUserLocation(),
                    snappedLocation = trip.snappedUserLocation.toUserLocation(),
                    // A step's banner describes the maneuver at its end, which the next step's text spells out.
                    instruction = trip.visualInstruction?.toInstruction(steps.getOrNull(1)?.instruction),
                    progress = trip.progress.toTripProgress(),
                    currentRoadName = steps.firstOrNull()?.roadName?.takeIf { it.isNotBlank() },
                    speedLimitKmh = annotationPublisher.map(core).speed.toKmh(),
                    remainingSteps = steps.map { it.toRouteStep() },
                    remainingWaypoints = trip.remainingWaypoints.map { it.toWaypoint() },
                    isOffRoute = trip.deviation is RouteDeviation.Deviation,
                    isRerouting = core.isCalculatingNewRoute,
                    isSimulating = simulating,
                    isMuted = muted,
                )
            }
            is TripState.Complete -> NavigationState(
                status = NavigationState.Status.ARRIVED,
                route = activeRoute,
                location = trip.userLocation.toUserLocation(),
                isSimulating = simulating,
                isMuted = muted,
            )
        }

    private fun emitTransitions(
        previous: com.stadiamaps.ferrostar.core.NavigationState?,
        current: com.stadiamaps.ferrostar.core.NavigationState,
    ) {
        val before = previous?.tripState
        val now = current.tripState
        when {
            now is TripState.Navigating && before is TripState.Navigating -> {
                // A reroute replaces the route: compare steps and stops only within one route.
                if (previous.routeGeometry !== current.routeGeometry) return

                if (now.remainingSteps.size < before.remainingSteps.size) {
                    _events.tryEmit(
                        NavigationEvent.StepAdvanced(
                            now.visualInstruction?.toInstruction(now.remainingSteps.getOrNull(1)?.instruction),
                            now.remainingSteps.size,
                        ),
                    )
                }
                val wasOff = before.deviation is RouteDeviation.Deviation
                val isOff = now.deviation is RouteDeviation.Deviation
                if (!wasOff && isOff) _events.tryEmit(NavigationEvent.OffRoute)
                if (wasOff && !isOff) _events.tryEmit(NavigationEvent.BackOnRoute)

                if (now.remainingWaypoints.size < before.remainingWaypoints.size && now.remainingWaypoints.isNotEmpty()) {
                    _events.tryEmit(NavigationEvent.WaypointReached(before.remainingWaypoints.first().toWaypoint(), stopsReached++))
                }
            }
            now is TripState.Complete && before !is TripState.Complete -> _events.tryEmit(NavigationEvent.Arrived)
            now is TripState.Idle && (before is TripState.Navigating || before is TripState.Complete) ->
                _events.tryEmit(NavigationEvent.NavigationStopped)
        }
    }

    private companion object {
        const val TAG = "RemaplyNavigation"
    }
}

private fun Speed?.toKmh(): Double? = when (this) {
    is Speed.Value -> when (unit) {
        SpeedUnit.KILOMETERS_PER_HOUR -> value
        SpeedUnit.MILES_PER_HOUR -> value * 1.609344
        SpeedUnit.KNOTS -> value * 1.852
    }
    else -> null
}

private fun NavigationThresholds.Companion.forProfileOr(custom: NavigationThresholds?, profile: TravelProfile) =
    custom ?: forProfile(profile)

/** Routes come from the [RouteProvider] through a custom provider, so Ferrostar never makes HTTP calls. */
private object UnusedHttpClient : HttpClientProvider {
    override suspend fun call(request: uniffi.ferrostar.RouteRequest): IResponse =
        error("Ferrostar's HTTP client is not used by the Remaply Navigation SDK")
}
