import Combine
import FerrostarCore
import FerrostarCoreFFI
import Foundation
import RemaplyFerrostarBridge

/// Runs turn-by-turn navigation: fetches routes, follows the GPS (or a simulated drive), tracks
/// progress, speaks instructions and reroutes when the user leaves the route.
///
/// Create one per screen or per app (it is cheap while idle) and show it with
/// `RemaplyNavigationView` from RemaplyNavigationUI:
///
/// ```swift
/// let navigator = RemaplyNavigator(config: NavigatorConfig(
///     routeProvider: ValhallaRouteProvider.remaply(apiKey: "rmp_pk_...")
/// ))
/// try await navigator.navigate(to: [Waypoint(latitude: -18.1416, longitude: 178.4419)])
/// ```
@MainActor
public final class RemaplyNavigator: ObservableObject {
    /// The current trip.
    @Published public private(set) var state = NavigationState()

    public let config: NavigatorConfig

    /// Events as they happen, for any number of subscribers.
    public var eventPublisher: AnyPublisher<NavigationEvent, Never> { eventSubject.eraseToAnyPublisher() }

    // For RemaplyNavigationUI only.
    package let engine: FerrostarCore
    package let speech: SpokenInstructionObserver
    package let annotations: AnnotationPublisher<ValhallaExtendedOSRMAnnotation>
    @Published package private(set) var engineState: EngineNavigationState?

    private let locationProvider: NavigatorLocationProvider
    private let routeBridge: EngineRouteProvider
    private let coreDelegate: EngineDelegate
    private let eventSubject = PassthroughSubject<NavigationEvent, Never>()
    private var cancellables = Set<AnyCancellable>()

    private var profile: TravelProfile
    private var activeRoute: NavigationRoute?
    private var simulating = false
    private var stopsReached = 0
    /// Bumped whenever a new route is followed, so its steps are not compared with the old route's.
    private var routeGeneration = 0
    private var previous: (trip: TripState, generation: Int)?

    public init(config: NavigatorConfig) {
        self.config = config
        profile = config.profile

        let backgroundModes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        locationProvider = NavigatorLocationProvider(
            source: config.locationSource,
            allowsBackgroundUpdates: config.backgroundNavigation && backgroundModes.contains("location"),
            simulationSpeedMultiplier: config.simulationSpeedMultiplier
        )
        speech = config.voiceGuidance.makeObserver()
        annotations = AnnotationPublisher<ValhallaExtendedOSRMAnnotation>.valhallaExtendedOSRM()
        routeBridge = EngineRouteProvider(provider: config.routeProvider)
        coreDelegate = EngineDelegate()

        engine = FerrostarCore(
            customRouteProvider: routeBridge,
            locationProvider: locationProvider,
            navigationControllerConfig: (config.thresholds ?? .forProfile(config.profile)).engineConfig,
            annotation: annotations,
            spokenInstructionObserver: speech
        )
        engine.minimumTimeBeforeRecalculation = config.rerouting.minimumInterval
        engine.minimumMovementBeforeRecalculation = config.rerouting.minimumMovement
        engine.delegate = coreDelegate

        routeBridge.profile = { [unowned self] in self.profile }
        routeBridge.onRerouteError = { [weak self] error in
            Task { @MainActor in self?.eventSubject.send(.error(error)) }
        }
        coreDelegate.reroutingEnabled = config.rerouting.enabled
        coreDelegate.onNewRoutes = { [weak self] routes in
            Task { @MainActor in self?.reroute(to: routes) }
        }

        engine.$state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.engineDidUpdate($0) }
            .store(in: &cancellables)
        annotations.$speedLimit
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state.speedLimit = $0 }
            .store(in: &cancellables)
        speech.$isMuted
            .receive(on: DispatchQueue.main)
            .sink { [weak self] muted in
                guard let self else { return }
                state.isMuted = muted || { if case .disabled = config.voiceGuidance { true } else { false } }()
            }
            .store(in: &cancellables)
    }

    /// Events as an async sequence. Each call returns a new stream; it ends when the task is cancelled.
    public func events() -> AsyncStream<NavigationEvent> {
        AsyncStream { continuation in
            let cancellable = eventSubject.sink { continuation.yield($0) }
            continuation.onTermination = { _ in cancellable.cancel() }
        }
    }

    /// Fetches routes to `waypoints` (in order, the destination last), best first.
    ///
    /// - Parameters:
    ///   - origin: Where to start; defaults to the device's location (see ``currentLocation(timeout:)``).
    ///   - profile: Defaults to ``NavigatorConfig/profile``.
    public func requestRoutes(to waypoints: [Waypoint], from origin: UserLocation? = nil, profile: TravelProfile? = nil) async throws -> [NavigationRoute] {
        guard !waypoints.isEmpty else { throw NavigationError.illegalUse("waypoints must contain at least the destination") }
        let start: UserLocation = if let origin { origin } else { try await currentLocation() }
        let request = RouteRequest(origin: start, waypoints: waypoints, profile: profile ?? config.profile, isReroute: false)
        let routes: [NavigationRoute]
        do {
            routes = try await config.routeProvider.routes(for: request)
        } catch let error as NavigationError {
            throw error
        } catch {
            throw NavigationError.invalidResponse("Route provider failed: \(error)")
        }
        guard !routes.isEmpty else { throw NavigationError.noRouteFound }
        return routes
    }

    /// Starts following `route`, replacing any trip in progress.
    ///
    /// - Parameters:
    ///   - simulate: Drive along the route instead of following the GPS. For demos, development
    ///     and App Review; needs no location permission.
    ///   - profile: The profile the route was requested with, used for rerouting.
    public func startNavigation(route: NavigationRoute, simulate: Bool = false, profile: TravelProfile? = nil) throws {
        if !simulate, case .device = config.locationSource,
           [.denied, .restricted].contains(locationProvider.authorizationStatus)
        {
            throw NavigationError.permissionDenied
        }

        self.profile = profile ?? config.profile
        activeRoute = route
        simulating = simulate
        stopsReached = 0
        routeGeneration += 1

        if simulate {
            do { try locationProvider.simulate(route.engineRoute) } catch {
                throw NavigationError.illegalUse("Could not simulate this route: \(error)")
            }
        } else {
            locationProvider.stopSimulating()
        }

        do {
            try engine.startNavigation(route: route.engineRoute, config: thresholds.engineConfig)
        } catch {
            throw NavigationError.illegalUse("Could not start navigation: \(error)")
        }
        eventSubject.send(.navigationStarted(route: route, simulated: simulate))
    }

    /// Fetches the best route to `waypoints` and starts following it. ``state`` is
    /// ``NavigationState/Status/routing`` meanwhile.
    ///
    /// - Parameter origin: Where to start. Defaults to the device's location; required when
    ///   simulating without location permission.
    /// - Returns: The route being followed.
    @discardableResult
    public func navigate(to waypoints: [Waypoint], simulate: Bool = false, profile: TravelProfile? = nil, origin: UserLocation? = nil) async throws -> NavigationRoute {
        var routing = NavigationState()
        routing.status = .routing
        routing.isMuted = state.isMuted
        state = routing
        do {
            let routes = try await requestRoutes(to: waypoints, from: origin, profile: profile)
            try startNavigation(route: routes[0], simulate: simulate, profile: profile)
            return routes[0]
        } catch {
            if state.status == .routing { state.status = .idle }
            throw error
        }
    }

    /// Ends the trip. Safe to call when not navigating.
    public func stopNavigation() {
        engine.stopNavigation()
        locationProvider.stopSimulating()
        simulating = false
        activeRoute = nil
    }

    /// Silences (or restores) spoken instructions.
    public func setMuted(_ muted: Bool) {
        if speech.isMuted != muted { speech.toggleMute() }
    }

    /// The device's location: the last known fix if any, else the next one.
    /// Asks for location permission if it was never asked.
    public func currentLocation(timeout: TimeInterval = 15) async throws -> UserLocation {
        guard let location = try await locationProvider.firstFix(timeout: timeout) else {
            throw NavigationError.locationUnavailable
        }
        return location
    }

    private var thresholds: NavigationThresholds { config.thresholds ?? .forProfile(profile) }

    private func reroute(to routes: [FerrostarCoreFFI.Route]) {
        guard let route = routes.first, engine.state != nil else { return }
        let navigationRoute = NavigationRoute(engineRoute: route)
        activeRoute = navigationRoute
        routeGeneration += 1
        do {
            try engine.startNavigation(route: route, config: thresholds.engineConfig)
            eventSubject.send(.rerouted(navigationRoute))
        } catch {
            eventSubject.send(.error(.illegalUse("Could not follow the new route: \(error)")))
        }
    }

    // MARK: Engine state

    private func engineDidUpdate(_ engineState: EngineNavigationState?) {
        self.engineState = engineState
        let trip = engineState?.tripState
        var next = NavigationState()
        next.isMuted = state.isMuted
        next.speedLimit = state.speedLimit

        switch trip {
        case .none, .idle:
            next.status = state.status == .routing ? .routing : .idle
            if case let .idle(location) = trip { next.location = location?.userLocation }
            next.speedLimit = nil
        case let .navigating(_, userLocation, snappedUserLocation, remainingSteps, remainingWaypoints, progress, _, deviation, visualInstruction, _, _):
            next.status = .navigating
            next.route = activeRoute
            next.location = userLocation.userLocation
            next.snappedLocation = snappedUserLocation.userLocation
            // A step's banner describes the maneuver at its end, which the next step's text spells out.
            next.instruction = visualInstruction?.instruction(fullText: remainingSteps.dropFirst().first?.instruction)
            next.progress = progress.tripProgress
            next.currentRoadName = remainingSteps.first?.roadName.flatMap { $0.isEmpty ? nil : $0 }
            next.remainingSteps = remainingSteps.map(\.routeStep)
            next.remainingWaypoints = remainingWaypoints.map(\.waypoint)
            next.isOffRoute = if case .deviation = deviation { true } else { false }
            next.isRerouting = engineState?.isCalculatingNewRoute ?? false
            next.isSimulating = simulating
        case let .complete(userLocation, _):
            next.status = .arrived
            next.route = activeRoute
            next.location = userLocation.userLocation
            next.isSimulating = simulating
        }
        state = next
        emitTransitions(to: trip)
    }

    private func emitTransitions(to trip: TripState?) {
        defer { previous = trip.map { ($0, routeGeneration) } }
        guard let before = previous else { return }

        switch (before.trip, trip) {
        case let (.navigating(_, _, _, stepsBefore, stopsBefore, _, _, deviationBefore, _, _, _),
                  .navigating(_, _, _, steps, stops, _, _, deviation, visualInstruction, _, _)):
            guard before.generation == routeGeneration else { return }
            if steps.count < stepsBefore.count {
                let instruction = visualInstruction?.instruction(fullText: steps.dropFirst().first?.instruction)
                eventSubject.send(.stepAdvanced(instruction, stepsRemaining: steps.count))
            }
            let wasOff = if case .deviation = deviationBefore { true } else { false }
            let isOff = if case .deviation = deviation { true } else { false }
            if !wasOff, isOff { eventSubject.send(.offRoute) }
            if wasOff, !isOff { eventSubject.send(.backOnRoute) }
            if stops.count < stopsBefore.count, !stops.isEmpty, let reached = stopsBefore.first {
                eventSubject.send(.waypointReached(reached.waypoint, index: stopsReached))
                stopsReached += 1
            }
        case (.complete, .none), (.complete, .idle):
            eventSubject.send(.navigationStopped)
        case (.complete, _):
            break
        case (_, .complete):
            eventSubject.send(.arrived)
        case (.navigating, .none), (.navigating, .idle):
            eventSubject.send(.navigationStopped)
        default:
            break
        }
    }
}

// MARK: - Engine glue

extension NavigationThresholds {
    /// The same controller settings as the Remaply web app. A step normally ends when the user
    /// reaches its end and then leaves it. That alone gets stuck where a stop needs a U-turn, so
    /// the step also ends once the user is clearly away from it while still on the route.
    var engineConfig: SwiftNavigationControllerConfig {
        SwiftNavigationControllerConfig(
            waypointAdvance: .waypointWithinRange(Double(waypointReached)),
            stepAdvanceCondition: stepAdvanceOr(conditions: [
                stepAdvanceDistanceEntryAndSnappedExit(
                    distanceToEndOfStep: stepEnd,
                    distanceAfterEndOfStep: 5,
                    minimumHorizontalAccuracy: minimumAccuracy
                ),
                stepAdvanceDistanceFromStep(
                    distance: offRoute,
                    minimumHorizontalAccuracy: minimumAccuracy,
                    calculationPolicy: .whileOnRoute
                ),
            ]),
            arrivalStepAdvanceCondition: stepAdvanceDistanceToEndOfStep(distance: stepEnd, minimumHorizontalAccuracy: minimumAccuracy),
            routeDeviationTracking: .staticThreshold(minimumHorizontalAccuracy: minimumAccuracy, maxAcceptableDeviation: Double(offRoute)),
            snappedLocationCourseFiltering: .snapToRoute
        )
    }
}

/// Lets FerrostarCore reroute through the app's ``RouteProvider``.
private final class EngineRouteProvider: CustomRouteProvider, @unchecked Sendable {
    let provider: any RouteProvider
    var profile: () -> TravelProfile = { .car }
    var onRerouteError: (NavigationError) -> Void = { _ in }

    init(provider: any RouteProvider) {
        self.provider = provider
    }

    func getRoutes(userLocation: FerrostarCoreFFI.UserLocation, waypoints: [FerrostarCoreFFI.Waypoint]) async throws -> [FerrostarCoreFFI.Route] {
        let currentProfile = await MainActor.run { profile() }
        let request = RouteRequest(origin: userLocation.userLocation, waypoints: waypoints.map(\.waypoint), profile: currentProfile, isReroute: true)
        do {
            return try await provider.routes(for: request).map(\.engineRoute)
        } catch {
            let navigationError = error as? NavigationError ?? .invalidResponse("Route provider failed: \(error)")
            onRerouteError(navigationError)
            throw navigationError
        }
    }
}

private final class EngineDelegate: FerrostarCoreDelegate {
    var reroutingEnabled = true
    var onNewRoutes: ([FerrostarCoreFFI.Route]) -> Void = { _ in }

    func core(_: FerrostarCore, didStartWith _: FerrostarCoreFFI.Route) {}

    func core(_: FerrostarCore, correctiveActionForDeviation _: DeviationKind, remainingWaypoints waypoints: [FerrostarCoreFFI.Waypoint]) -> CorrectiveAction {
        reroutingEnabled ? .getNewRoutes(waypoints: waypoints) : .doNothing
    }

    func core(_: FerrostarCore, loadedAlternateRoutes routes: [FerrostarCoreFFI.Route]) {
        onNewRoutes(routes)
    }
}
