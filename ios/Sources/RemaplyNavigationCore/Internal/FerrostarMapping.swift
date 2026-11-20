import CoreLocation
import FerrostarCoreFFI
import Foundation

// Conversions between the SDK's public types and Ferrostar's. Kept in one place so a Ferrostar
// upgrade that renames or reshapes a type only touches this file.

extension GeographicCoordinate {
    var coordinate: Coordinate { Coordinate(latitude: lat, longitude: lng) }
}

extension Coordinate {
    var engineCoordinate: GeographicCoordinate { GeographicCoordinate(lat: latitude, lng: longitude) }
    var clCoordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}

extension Waypoint {
    var engineWaypoint: FerrostarCoreFFI.Waypoint {
        FerrostarCoreFFI.Waypoint(coordinate: coordinate.engineCoordinate, kind: kind == .via ? .via : .break, properties: nil)
    }
}

extension FerrostarCoreFFI.Waypoint {
    var waypoint: Waypoint {
        Waypoint(coordinate: coordinate.coordinate, kind: kind == .via ? .via : .stop)
    }
}

extension UserLocation {
    var engineLocation: FerrostarCoreFFI.UserLocation {
        FerrostarCoreFFI.UserLocation(
            coordinates: coordinate.engineCoordinate,
            horizontalAccuracy: max(horizontalAccuracy, 0),
            courseOverGround: course.map { CourseOverGround(degrees: UInt16(normalizedDegrees($0)), accuracy: nil) },
            timestamp: timestamp,
            speed: speed.map { Speed(value: $0, accuracy: nil) }
        )
    }
}

extension FerrostarCoreFFI.UserLocation {
    var userLocation: UserLocation {
        UserLocation(
            coordinate: coordinates.coordinate,
            horizontalAccuracy: horizontalAccuracy,
            course: courseOverGround.map { Double($0.degrees) },
            speed: speed?.value,
            timestamp: timestamp
        )
    }
}

extension FerrostarCoreFFI.RouteStep {
    var routeStep: RouteStep {
        RouteStep(instruction: instruction, roadName: roadName, distance: distance, duration: duration)
    }
}

extension FerrostarCoreFFI.TripProgress {
    var tripProgress: TripProgress {
        TripProgress(
            distanceToNextManeuver: distanceToNextManeuver,
            distanceRemaining: distanceRemaining,
            durationRemaining: durationRemaining
        )
    }
}

extension VisualInstruction {
    func instruction(fullText: String?) -> Instruction {
        Instruction(
            primaryText: primaryContent.text,
            secondaryText: secondaryContent?.text,
            maneuverType: primaryContent.maneuverType.flatMap { ManeuverType(engineName: "\($0)") },
            maneuverModifier: primaryContent.maneuverModifier.flatMap { ManeuverModifier(engineName: "\($0)") },
            fullText: fullText ?? primaryContent.text
        )
    }
}

// Matched by case name, so a value Ferrostar adds later maps to nil instead of failing to compile.
extension ManeuverType {
    init?(engineName: String) {
        guard let match = Self.allCases.first(where: { $0.rawValue.caseInsensitiveCompare(engineName) == .orderedSame }) else { return nil }
        self = match
    }
}

extension ManeuverModifier {
    init?(engineName: String) {
        guard let match = Self.allCases.first(where: { $0.rawValue.caseInsensitiveCompare(engineName) == .orderedSame }) else { return nil }
        self = match
    }
}

func normalizedDegrees(_ degrees: Double) -> Double {
    let d = degrees.truncatingRemainder(dividingBy: 360)
    let positive = d < 0 ? d + 360 : d
    return positive >= 360 ? 0 : positive
}
