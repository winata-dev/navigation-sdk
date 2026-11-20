import Foundation

/// A point on Earth in WGS 84 degrees.
public struct Coordinate: Hashable, Sendable {
    public var latitude: Double
    public var longitude: Double

    public init(latitude: Double, longitude: Double) {
        precondition((-90 ... 90).contains(latitude), "latitude must be between -90 and 90, was \(latitude)")
        precondition((-180 ... 180).contains(longitude), "longitude must be between -180 and 180, was \(longitude)")
        self.latitude = latitude
        self.longitude = longitude
    }
}

/// A place the route must visit. The last waypoint of a trip is its destination.
public struct Waypoint: Hashable, Sendable {
    public enum Kind: Hashable, Sendable {
        /// A stop: arriving there is reported with ``NavigationEvent/waypointReached(_:index:)``.
        case stop
        /// A point to pass through without stopping, used to shape the route.
        case via
    }

    public var coordinate: Coordinate
    public var kind: Kind
    /// Optional label for your own UI. Not sent to the routing server.
    public var name: String?

    public init(coordinate: Coordinate, kind: Kind = .stop, name: String? = nil) {
        self.coordinate = coordinate
        self.kind = kind
        self.name = name
    }

    public init(latitude: Double, longitude: Double, name: String? = nil) {
        self.init(coordinate: Coordinate(latitude: latitude, longitude: longitude), name: name)
    }
}

/// How the user travels. Picks the routing profile on the server and the navigation thresholds
/// (see ``NavigationThresholds/forProfile(_:)``).
public enum TravelProfile: String, CaseIterable, Hashable, Sendable {
    case car
    case motorcycle
    case truck
    case bicycle
    case foot
}

/// A location fix, from the GPS or from the simulator.
public struct UserLocation: Hashable, Sendable {
    public var coordinate: Coordinate
    /// Radius of the 68% confidence circle, in meters.
    public var horizontalAccuracy: Double
    /// Direction of travel in degrees clockwise from north, if known.
    public var course: Double?
    /// Speed in meters per second, if known.
    public var speed: Double?
    public var timestamp: Date

    public init(coordinate: Coordinate, horizontalAccuracy: Double, course: Double? = nil, speed: Double? = nil, timestamp: Date = Date()) {
        self.coordinate = coordinate
        self.horizontalAccuracy = horizontalAccuracy
        self.course = course
        self.speed = speed
        self.timestamp = timestamp
    }
}

/// The kind of maneuver at the end of the current step, for picking an icon or wording.
public enum ManeuverType: String, CaseIterable, Hashable, Sendable {
    case turn, newName, depart, arrive, merge, onRamp, offRamp, fork, endOfRoad, `continue`
    case roundabout, rotary, roundaboutTurn, notification, exitRoundabout, exitRotary
}

/// The direction of a maneuver.
public enum ManeuverModifier: String, CaseIterable, Hashable, Sendable {
    case uTurn, sharpRight, right, slightRight, straight, slightLeft, left, sharpLeft
}

/// What to show the user for the next maneuver.
public struct Instruction: Hashable, Sendable {
    /// Main text, usually the road to take, e.g. "Victoria Parade".
    public var primaryText: String
    /// Extra text, e.g. "Toward Suva", when the routing server provides it.
    public var secondaryText: String?
    public var maneuverType: ManeuverType?
    public var maneuverModifier: ManeuverModifier?
    /// Full sentence, e.g. "Turn right onto Victoria Parade."
    public var fullText: String
}

/// A step of a route: one maneuver and the road up to the next one.
public struct RouteStep: Hashable, Sendable {
    public var instruction: String
    public var roadName: String?
    public var distance: Double
    public var duration: TimeInterval
}

/// Distances (meters) and times (seconds) remaining on the trip.
public struct TripProgress: Hashable, Sendable {
    public var distanceToNextManeuver: Double
    public var distanceRemaining: Double
    public var durationRemaining: TimeInterval

    /// When the user is expected to arrive, from now and the remaining duration.
    public var estimatedArrival: Date { Date().addingTimeInterval(durationRemaining) }
}
