@testable import RemaplyNavigationCore
import XCTest

final class ThresholdsTests: XCTestCase {
    func testProfilesMatchTheWebApp() {
        XCTAssertEqual(NavigationThresholds.forProfile(.car), NavigationThresholds(offRoute: 50, waypointReached: 100, stepEnd: 30))
        XCTAssertEqual(NavigationThresholds.forProfile(.truck), NavigationThresholds(offRoute: 60, waypointReached: 100, stepEnd: 40))
        XCTAssertEqual(NavigationThresholds.forProfile(.foot), NavigationThresholds(offRoute: 40, waypointReached: 30, stepEnd: 15))
    }

    func testValhallaCosting() {
        XCTAssertEqual(ValhallaRouteProvider.defaultCosting(.car), "auto")
        XCTAssertEqual(ValhallaRouteProvider.defaultCosting(.foot), "pedestrian")
    }
}

final class HTTPErrorTests: XCTestCase {
    func testRemaplyRouteNotFound() {
        let error = HTTPRouteFetcher.httpError(statusCode: 404, body: #"{"error":{"code":"route_not_found"}}"#)
        guard case .noRouteFound = error else { return XCTFail("got \(error)") }
    }

    func testValhallaNoRoute() {
        let error = HTTPRouteFetcher.httpError(statusCode: 400, body: #"{"code":"NoRoute","message":"x"}"#)
        guard case .noRouteFound = error else { return XCTFail("got \(error)") }
    }

    func testOtherErrorsKeepTheBody() {
        let error = HTTPRouteFetcher.httpError(statusCode: 401, body: "bad key")
        guard case let .http(code, body) = error else { return XCTFail("got \(error)") }
        XCTAssertEqual(code, 401)
        XCTAssertEqual(body, "bad key")
    }
}

final class MappingTests: XCTestCase {
    func testDegreesAreNormalized() {
        XCTAssertEqual(normalizedDegrees(370), 10, accuracy: 0.001)
        XCTAssertEqual(normalizedDegrees(-90), 270, accuracy: 0.001)
        XCTAssertEqual(normalizedDegrees(360), 0, accuracy: 0.001)
    }

    func testUserLocationRoundTrip() {
        let location = UserLocation(coordinate: Coordinate(latitude: -18.14, longitude: 178.44), horizontalAccuracy: 5, course: 90, speed: 10)
        let back = location.engineLocation.userLocation
        XCTAssertEqual(back.coordinate, location.coordinate)
        XCTAssertEqual(back.course, 90)
        XCTAssertEqual(back.speed, 10)
    }

    func testManeuverNamesMatchTheEngine() {
        XCTAssertEqual(ManeuverType(engineName: "newName"), .newName)
        XCTAssertEqual(ManeuverModifier(engineName: "slightLeft"), .slightLeft)
        XCTAssertNil(ManeuverType(engineName: "somethingNew"))
    }

    func testOptionsAreEncodedAsJSON() {
        XCTAssertNil(JSONOptions.encode([:]))
        XCTAssertEqual(JSONOptions.encode(["units": "kilometers"]), #"{"units":"kilometers"}"#)
    }

    func testParsesAValhallaOSRMRoute() throws {
        let waypoints = [Waypoint(latitude: -18.1416, longitude: 178.4419), Waypoint(latitude: -18.1250, longitude: 178.4500)]
        let route = try NavigationRoute.fromOSRM(routeJSON: Data(Fixtures.osrmRoute.utf8), waypoints: waypoints)
        XCTAssertEqual(route.distance, 2000, accuracy: 0.1)
        XCTAssertEqual(route.steps.count, 2)
        XCTAssertEqual(route.steps.first?.roadName, "Victoria Parade")
        XCTAssertEqual(route.geometry.count, 2)
    }

    func testRejectsInvalidRoutes() {
        XCTAssertThrowsError(try NavigationRoute.fromOSRM(routeJSON: Data("{}".utf8), waypoints: []))
    }
}

enum Fixtures {
    /// A minimal OSRM route: two points, a depart step and an arrive step. polyline6 of
    /// (-18.1416, 178.4419) → (-18.1250, 178.4500).
    static let osrmRoute = """
    {
      "distance": 2000, "duration": 300, "weight": 300, "weight_name": "auto",
      "geometry": "\(polyline6([(-18.1416, 178.4419), (-18.1250, 178.4500)]))",
      "legs": [{
        "distance": 2000, "duration": 300, "weight": 300, "summary": "",
        "steps": [
          {
            "distance": 2000, "duration": 300, "weight": 300, "name": "Victoria Parade", "mode": "driving",
            "geometry": "\(polyline6([(-18.1416, 178.4419), (-18.1250, 178.4500)]))",
            "maneuver": {"location": [178.4419, -18.1416], "bearing_before": 0, "bearing_after": 30, "type": "depart", "instruction": "Drive north on Victoria Parade."},
            "intersections": []
          },
          {
            "distance": 0, "duration": 0, "weight": 0, "name": "Victoria Parade", "mode": "driving",
            "geometry": "\(polyline6([(-18.1250, 178.4500), (-18.1250, 178.4500)]))",
            "maneuver": {"location": [178.4500, -18.1250], "bearing_before": 30, "bearing_after": 0, "type": "arrive", "instruction": "You have arrived."},
            "intersections": []
          }
        ]
      }]
    }
    """

    /// Google's encoded polyline algorithm with 6 decimals.
    static func polyline6(_ points: [(Double, Double)]) -> String {
        var result = ""
        var last = (0, 0)
        for (lat, lon) in points {
            let current = (Int((lat * 1e6).rounded()), Int((lon * 1e6).rounded()))
            for delta in [current.0 - last.0, current.1 - last.1] {
                var value = delta < 0 ? ~(delta << 1) : delta << 1
                while value >= 0x20 {
                    result.append(Character(UnicodeScalar(UInt8((0x20 | (value & 0x1F)) + 63))))
                    value >>= 5
                }
                result.append(Character(UnicodeScalar(UInt8(value + 63))))
            }
            last = current
        }
        return result.replacingOccurrences(of: "\\", with: "\\\\")
    }
}
