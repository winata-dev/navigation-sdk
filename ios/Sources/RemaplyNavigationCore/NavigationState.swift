import Foundation

/// Everything about the current trip, as one value. Observe ``RemaplyNavigator/state`` to follow it.
public struct NavigationState: Sendable {
    public enum Status: Hashable, Sendable {
        /// Not navigating.
        case idle
        /// Fetching the first route of a trip started with ``RemaplyNavigator/navigate(to:simulate:profile:origin:)``.
        case routing
        /// Following a route.
        case navigating
        /// The destination was reached. Call ``RemaplyNavigator/stopNavigation()`` to finish.
        case arrived
    }

    public internal(set) var status: Status = .idle
    /// The route being followed. Changes when the user is rerouted.
    public internal(set) var route: NavigationRoute?
    /// The last location fix, as reported by the GPS (or the simulator).
    public internal(set) var location: UserLocation?
    /// ``location`` snapped onto the route line. Use it for the puck while navigating.
    public internal(set) var snappedLocation: UserLocation?
    /// The next maneuver to show.
    public internal(set) var instruction: Instruction?
    public internal(set) var progress: TripProgress?
    /// The road the user is on, when known.
    public internal(set) var currentRoadName: String?
    /// The speed limit where the user is, when the routing server provides it (Valhalla's `maxspeed`).
    public internal(set) var speedLimit: Measurement<UnitSpeed>?
    /// Steps left, the current one first.
    public internal(set) var remainingSteps: [RouteStep] = []
    /// Stops left, the destination last.
    public internal(set) var remainingWaypoints: [Waypoint] = []
    /// True when the user is too far from the route.
    public internal(set) var isOffRoute = false
    /// True while a new route is being fetched because the user left the route.
    public internal(set) var isRerouting = false
    /// True while following a simulated drive rather than the GPS.
    public internal(set) var isSimulating = false
    public internal(set) var isMuted = false

    public init() {}

    public var isNavigating: Bool { status == .navigating }
}

/// Things that happen during a trip, for analytics, sounds or your own UI.
public enum NavigationEvent: Sendable {
    case navigationStarted(route: NavigationRoute, simulated: Bool)
    /// The user moved on to the next step; the instruction is the new one.
    case stepAdvanced(Instruction?, stepsRemaining: Int)
    /// The user left the route. A new route is requested if rerouting is on.
    case offRoute
    /// The user is back on the route without a reroute.
    case backOnRoute
    /// A new route replaced the old one after the user left it.
    case rerouted(NavigationRoute)
    /// An intermediate stop was reached. `index` counts stops from 0, excluding the start.
    case waypointReached(Waypoint, index: Int)
    /// The destination was reached.
    case arrived
    /// Navigation was stopped.
    case navigationStopped
    /// Something failed without stopping navigation, e.g. a reroute request.
    case error(NavigationError)
}
