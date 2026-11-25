@testable import RemaplyNavigationCore
import XCTest

/// The same `/v1/route` answer as in the Android tests.
private let remaplyRoute = #"""
{"routes":[{"distance_m":601,"duration_s":75,"geometry":"nxgra@gjfjsINzz@Rjz@scBfE_|BfE","legs":[{"distance_m":601,"duration_s":75,"steps":[
 {"type":"depart","instruction":"Drive west on Nacuva Street.","distance_m":201,"duration_s":30,"road_names":["Nacuva Street"],"location":{"lat":-18.141592,"lon":178.441908}},
 {"type":"turn_right","instruction":"Turn right onto Milverton Road.","distance_m":400,"duration_s":45,"road_names":["Milverton Road"],"location":{"lat":-18.14161,"lon":178.44}},
 {"type":"arrive","instruction":"You have arrived at your destination.","distance_m":0,"duration_s":0,"road_names":[],"location":{"lat":-18.138,"lon":178.4398}}
]}]}]}
"""#

/// Answers requests from a table, and records them.
final class StubProtocol: URLProtocol {
    struct Stub {
        let status: Int
        let body: String
    }

    nonisolated(unsafe) static var stubs: [String: Stub] = [:]
    nonisolated(unsafe) static var requested: [String] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let key = "\(request.httpMethod ?? "GET") \(url.path)"
        Self.requested.append(key)
        let stub = Self.stubs[key] ?? Stub(status: 404, body: #"{"error":{"code":"not_found"}}"#)
        let response = HTTPURLResponse(url: url, statusCode: stub.status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(stub.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class RemaplyRouteFallbackTests: XCTestCase {
    private let request = RouteRequest(
        origin: UserLocation(coordinate: Coordinate(latitude: -18.141592, longitude: 178.441908), horizontalAccuracy: 5),
        waypoints: [Waypoint(latitude: -18.138, longitude: 178.4398)],
        profile: .car,
        isReroute: false
    )

    private func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: configuration)
    }

    override func setUp() {
        StubProtocol.stubs = [:]
        StubProtocol.requested = []
    }

    func testOnlyAMissingEndpointFallsBack() {
        XCTAssertTrue(RemaplyRouteFallback.isMissingEndpoint(statusCode: 404, body: #"{"error":{"code":"not_found","message":"Not Found"}}"#))
        XCTAssertTrue(RemaplyRouteFallback.isMissingEndpoint(statusCode: 405, body: ""))
        XCTAssertFalse(RemaplyRouteFallback.isMissingEndpoint(statusCode: 404, body: #"{"error":{"code":"route_not_found"}}"#))
        XCTAssertFalse(RemaplyRouteFallback.isMissingEndpoint(statusCode: 401, body: #"{"error":{"code":"invalid_api_key"}}"#))
    }

    func testTheFallbackIsUsedForAWhile() {
        let fallback = RemaplyRouteFallback(baseURL: RemaplyCredentials.defaultBaseURL)
        let now = Date()
        XCTAssertFalse(fallback.isActive(now: now))
        fallback.activate(now: now)
        XCTAssertTrue(fallback.isActive(now: now.addingTimeInterval(60)))
        XCTAssertFalse(fallback.isActive(now: now.addingTimeInterval(16 * 60)))
    }

    func testTheURLAsksForStepsAndPolyline6() throws {
        let url = try XCTUnwrap(RemaplyRouteFallback(baseURL: RemaplyCredentials.defaultBaseURL).url(for: request))
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(url.path, "/v1/route")
        XCTAssertEqual(items.first { $0.name == "points" }?.value, "-18.141592,178.441908;-18.138000,178.439800")
        XCTAssertEqual(items.first { $0.name == "profile" }?.value, "car")
        XCTAssertEqual(items.first { $0.name == "steps" }?.value, "true")
        XCTAssertEqual(items.first { $0.name == "geometry" }?.value, "polyline6")
    }

    func testPolylinesRoundTrip() throws {
        let line = try Polyline6.decode("nxgra@gjfjsINzz@Rjz@scBfE_|BfE")
        XCTAssertEqual(line.count, 5)
        XCTAssertEqual(line[0], Polyline6.Point(lat: -18_141_592, lon: 178_441_908))
        XCTAssertEqual(Polyline6.encode(line), "nxgra@gjfjsINzz@Rjz@scBfE_|BfE")
    }

    /// The whole path: the navigation endpoint is missing, /v1/route answers, and the engine
    /// parses the converted route.
    func testFallsBackToV1RouteWhenTheNavigationEndpointIsMissing() async throws {
        StubProtocol.stubs["GET /v1/route"] = .init(status: 200, body: remaplyRoute)
        let provider = ValhallaRouteProvider.remaply(RemaplyCredentials(apiKey: "rmp_pk_test"), session: session())

        let routes = try await provider.routes(for: request)
        XCTAssertEqual(StubProtocol.requested, ["POST /v1/navigation/route", "GET /v1/route"])
        let route = try XCTUnwrap(routes.first)
        XCTAssertEqual(route.distance, 601, accuracy: 0.1)
        XCTAssertEqual(route.geometry.count, 5)
        XCTAssertEqual(route.steps.map(\.instruction), [
            "Drive west on Nacuva Street.",
            "Turn right onto Milverton Road.",
            "You have arrived at your destination.",
        ])
        XCTAssertEqual(route.steps.first?.roadName, "Nacuva Street")
        XCTAssertEqual(route.waypoints.count, 2)

        // Known missing now: the next request goes straight to /v1/route.
        _ = try await provider.routes(for: request)
        XCTAssertEqual(StubProtocol.requested.last, "GET /v1/route")
        XCTAssertEqual(StubProtocol.requested.count, 3)
    }

    func testNoRouteIsNotAMissingEndpoint() async {
        StubProtocol.stubs["POST /v1/navigation/route"] = .init(status: 404, body: #"{"error":{"code":"route_not_found","message":"x"}}"#)
        let provider = ValhallaRouteProvider.remaply(RemaplyCredentials(apiKey: "rmp_pk_test"), session: session())
        do {
            _ = try await provider.routes(for: request)
            XCTFail("expected noRouteFound")
        } catch {
            guard case .noRouteFound? = error as? NavigationError else { return XCTFail("expected noRouteFound, got \(error)") }
        }
        XCTAssertEqual(StubProtocol.requested, ["POST /v1/navigation/route"])
    }

    func testEveryRemaplyStepTypeHasAManeuverTheEngineKnows() {
        let known: Set = ["turn", "new name", "depart", "arrive", "merge", "on ramp", "off ramp", "fork", "end of road", "continue", "roundabout", "rotary", "roundabout turn", "notification", "exit roundabout", "exit rotary"]
        let modifiers: Set<String?> = [nil, "uturn", "sharp right", "right", "slight right", "straight", "slight left", "left", "sharp left"]
        for type in ["depart", "arrive", "continue", "turn_slight_right", "turn_right", "turn_sharp_right", "uturn", "turn_sharp_left",
                     "turn_left", "turn_slight_left", "ramp", "ramp_right", "ramp_left", "exit_right", "exit_left", "keep_straight",
                     "keep_right", "keep_left", "merge", "roundabout_enter", "roundabout_exit", "ferry_enter", "ferry_exit",
                     "merge_right", "merge_left", "other", "something_new"] {
            let (maneuver, modifier) = RemaplyToOSRM.maneuver(type)
            XCTAssertTrue(known.contains(maneuver), type)
            XCTAssertTrue(modifiers.contains(modifier), type)
        }
    }
}
