import Foundation

/// Routes from the Remaply API's `GET /v1/route`, for when `POST /v1/navigation/route` is not
/// available on the server (it answers 404 `not_found`).
///
/// `/v1/route` answers in Remaply's own format: one line per route and, per step, a type, an
/// English instruction, road names and where the step starts. This turns that into the OSRM
/// format the navigation engine reads, cutting the line into steps and writing the banner and
/// voice instructions from the steps' instructions. Compared to the navigation endpoint, there is
/// no heading (a reroute may start with a U-turn), no speed limits and no lane information, and
/// via points are treated as stops.
final class RemaplyRouteFallback: @unchecked Sendable {
    private static let recheckAfter: TimeInterval = 15 * 60

    private let routeURL: URL
    private let lock = NSLock()
    private var activeUntil: Date?

    init(baseURL: URL) {
        routeURL = baseURL.appendingPathComponent("v1/route")
    }

    /// True while the navigation endpoint is known to be missing.
    func isActive(now: Date = Date()) -> Bool {
        lock.withLock { activeUntil.map { now < $0 } ?? false }
    }

    /// Skips the navigation endpoint for a while, then tries it again, in case it was deployed since.
    func activate(now: Date = Date()) {
        lock.withLock { activeUntil = now.addingTimeInterval(Self.recheckAfter) }
    }

    func url(for request: RouteRequest) -> URL? {
        let points = ([request.origin.coordinate] + request.waypoints.map(\.coordinate))
            .map { String(format: "%.6f,%.6f", locale: Locale(identifier: "en_US_POSIX"), $0.latitude, $0.longitude) }
            .joined(separator: ";")
        var components = URLComponents(url: routeURL, resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "points", value: points),
            URLQueryItem(name: "profile", value: Self.profileName(request.profile)),
            URLQueryItem(name: "steps", value: "true"),
            URLQueryItem(name: "geometry", value: "polyline6"),
        ]
        return components?.url
    }

    /// The `/v1/route` answer as an OSRM response. Throws on anything it cannot read.
    func osrmResponse(_ data: Data, for request: RouteRequest) throws -> Data {
        try RemaplyToOSRM.convert(data, points: [request.origin.coordinate] + request.waypoints.map(\.coordinate), profile: request.profile)
    }

    /// 404 or 405 without `route_not_found`: the endpoint itself does not exist.
    static func isMissingEndpoint(statusCode: Int, body: String) -> Bool {
        (statusCode == 404 || statusCode == 405) && !body.contains("route_not_found")
    }

    static func profileName(_ profile: TravelProfile) -> String {
        switch profile {
        case .car: "car"
        case .motorcycle: "motorcycle"
        case .truck: "truck"
        case .bicycle: "bicycle"
        case .foot: "foot"
        }
    }
}

/// Remaply `/v1/route` JSON → OSRM JSON. Pure, for tests.
enum RemaplyToOSRM {
    struct ConversionError: Error, CustomStringConvertible {
        let description: String
    }

    private struct Response: Decodable {
        let routes: [Route]
    }

    private struct Route: Decodable {
        let distance_m: Double
        let duration_s: Double
        let geometry: String
        let legs: [Leg]
    }

    private struct Leg: Decodable {
        let distance_m: Double
        let duration_s: Double
        let steps: [Step]
    }

    private struct Step: Decodable {
        let type: String?
        let instruction: String
        let distance_m: Double
        let duration_s: Double
        let road_names: [String]?
        let location: LatLon
    }

    private struct LatLon: Decodable {
        let lat: Double
        let lon: Double
    }

    /// A step with where it sits in the route.
    private struct FlatStep {
        let step: Step
        let leg: Int
        let isLastLeg: Bool
        var type: String { step.type ?? "other" }
        var roadNames: [String] { (step.road_names ?? []).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty } }
    }

    static func convert(_ data: Data, points: [Coordinate], profile: TravelProfile) throws -> Data {
        let response = try JSONDecoder().decode(Response.self, from: data)
        let routes = try response.routes.map { try route($0, profile: profile) }
        let waypoints = points.map { ["location": [$0.longitude, $0.latitude]] }
        return try JSONSerialization.data(withJSONObject: ["code": "Ok", "routes": routes, "waypoints": waypoints] as [String: Any])
    }

    private static func route(_ route: Route, profile: TravelProfile) throws -> [String: Any] {
        var line = try Polyline6.decode(route.geometry)
        if line.count == 1 { line.append(line[0]) }
        guard line.count >= 2 else { throw ConversionError(description: "route without geometry") }

        var steps: [FlatStep] = []
        for (l, leg) in route.legs.enumerated() {
            for step in leg.steps {
                steps.append(FlatStep(step: step, leg: l, isLastLeg: l == route.legs.count - 1))
            }
        }
        guard !steps.isEmpty else { throw ConversionError(description: "route without steps; was steps=true sent?") }

        // Where each step starts on the line. Steps start on a vertex, in order.
        var starts: [Int] = []
        var cursor = 0
        for step in steps {
            cursor = nearestIndex(line, step.step.location, from: cursor)
            starts.append(cursor)
        }

        var osrmSteps: [[String: Any]] = []
        for (i, step) in steps.enumerated() {
            let start = starts[i]
            let sameLegNext = i + 1 < steps.count && steps[i + 1].leg == step.leg
            let end: Int
            if sameLegNext {
                end = starts[i + 1]
            } else if step.type == "arrive" {
                end = start
            } else {
                end = i + 1 < starts.count ? starts[i + 1] : line.count - 1
            }
            let geometry = end > start ? Array(line[start ... end]) : [line[start], line[start]]
            let next = sameLegNext ? steps[i + 1] : nil
            let isFirstOfLeg = i == 0 || steps[i - 1].leg != step.leg
            osrmSteps.append(osrmStep(step, next: next, geometry: geometry, line: line, start: start, isFirstOfLeg: isFirstOfLeg, profile: profile))
        }

        let legs: [[String: Any]] = route.legs.enumerated().map { l, leg in
            [
                "distance": leg.distance_m,
                "duration": leg.duration_s,
                "summary": "",
                "steps": zip(steps, osrmSteps).filter { $0.0.leg == l }.map(\.1),
            ]
        }
        return [
            "distance": route.distance_m,
            "duration": route.duration_s,
            "geometry": Polyline6.encode(line),
            "legs": legs,
        ]
    }

    private static func nearestIndex(_ line: [Polyline6.Point], _ location: LatLon, from: Int) -> Int {
        let lat = Int64((location.lat * 1e6).rounded())
        let lon = Int64((location.lon * 1e6).rounded())
        var best = from
        var bestDistance = Int64.max
        for i in from ..< line.count {
            let dLat = Int64(line[i].lat) - lat
            let dLon = Int64(line[i].lon) - lon
            let d = dLat * dLat + dLon * dLon
            if d == 0 { return i }
            if d < bestDistance {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private static func osrmStep(
        _ step: FlatStep,
        next: FlatStep?,
        geometry: [Polyline6.Point],
        line: [Polyline6.Point],
        start: Int,
        isFirstOfLeg: Bool,
        profile: TravelProfile
    ) -> [String: Any] {
        let (type, modifier) = maneuver(step.type)
        let bearingBefore = start > 0 ? bearing(line[start - 1], line[start]) : 0
        let bearingAfter = zip(geometry, geometry.dropFirst()).first { $0 != $1 }.map { bearing($0, $1) } ?? bearingBefore

        var maneuverJSON: [String: Any] = [
            "location": [Double(line[start].lon) / 1e6, Double(line[start].lat) / 1e6],
            "bearing_before": bearingBefore,
            "bearing_after": bearingAfter,
            "type": type,
            "instruction": step.step.instruction,
        ]
        if let modifier { maneuverJSON["modifier"] = modifier }

        var json: [String: Any] = [
            "distance": step.step.distance_m,
            "duration": step.step.duration_s,
            "geometry": Polyline6.encode(geometry),
            "mode": step.type.hasPrefix("ferry") ? "ferry" : mode(profile),
            "maneuver": maneuverJSON,
            "intersections": [Any](),
            "bannerInstructions": [banner(next ?? step, distance: step.step.distance_m)],
            "voiceInstructions": Voice.instructions(step, next: next, isFirstOfLeg: isFirstOfLeg, profile: profile),
        ]
        if !step.roadNames.isEmpty { json["name"] = step.roadNames.joined(separator: " / ") }
        return json
    }

    private static func banner(_ target: FlatStep, distance: Double) -> [String: Any] {
        let (type, modifier) = maneuver(target.type)
        var text = target.roadNames.joined(separator: " / ")
        if text.isEmpty {
            text = target.step.instruction
            while text.hasSuffix(".") { text.removeLast() }
        }
        var primary: [String: Any] = ["text": text, "type": type, "components": [["type": "text", "text": text]]]
        if let modifier { primary["modifier"] = modifier }
        return ["distanceAlongGeometry": distance, "primary": primary]
    }

    /// Remaply step type → OSRM maneuver type and modifier.
    static func maneuver(_ type: String) -> (String, String?) {
        switch type {
        case "depart": ("depart", nil)
        case "arrive": ("arrive", nil)
        case "continue": ("continue", "straight")
        case "turn_slight_right": ("turn", "slight right")
        case "turn_right": ("turn", "right")
        case "turn_sharp_right": ("turn", "sharp right")
        case "turn_slight_left": ("turn", "slight left")
        case "turn_left": ("turn", "left")
        case "turn_sharp_left": ("turn", "sharp left")
        case "uturn": ("turn", "uturn")
        case "ramp": ("on ramp", "straight")
        case "ramp_right": ("on ramp", "right")
        case "ramp_left": ("on ramp", "left")
        case "exit_right": ("off ramp", "right")
        case "exit_left": ("off ramp", "left")
        case "keep_straight": ("fork", "straight")
        case "keep_right": ("fork", "right")
        case "keep_left": ("fork", "left")
        case "merge": ("merge", "straight")
        case "merge_right": ("merge", "slight right")
        case "merge_left": ("merge", "slight left")
        case "roundabout_enter": ("roundabout", nil)
        case "roundabout_exit": ("exit roundabout", nil)
        case "ferry_enter", "ferry_exit": ("notification", "straight")
        default: ("continue", "straight")
        }
    }

    private static func mode(_ profile: TravelProfile) -> String {
        switch profile {
        case .bicycle: "cycling"
        case .foot: "walking"
        default: "driving"
        }
    }

    /// Initial bearing from a to b, whole degrees clockwise from north.
    private static func bearing(_ a: Polyline6.Point, _ b: Polyline6.Point) -> Int {
        let lat1 = Double(a.lat) / 1e6 * .pi / 180
        let lat2 = Double(b.lat) / 1e6 * .pi / 180
        let dLon = Double(b.lon - a.lon) / 1e6 * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return Int((atan2(y, x) * 180 / .pi + 360).rounded()) % 360
    }

    /// Spoken instructions, from the steps' written ones. English, like the instructions.
    private enum Voice {
        static func instructions(_ step: FlatStep, next: FlatStep?, isFirstOfLeg: Bool, profile: TravelProfile) -> [[String: Any]] {
            // The arrive step itself says nothing: the step before it announces the arrival.
            guard let next else { return [] }
            let motorized = profile == .car || profile == .motorcycle || profile == .truck
            let (early, near): (Double, Double) = switch profile {
            case .car, .motorcycle, .truck: (500, 100)
            case .bicycle: (150, 30)
            case .foot: (60, 15)
            }
            let distance = step.step.distance_m
            let instruction = step.step.instruction
            let upcoming = next.type == "arrive"
                ? (step.isLastLeg ? "You will arrive at your destination." : "You will arrive at your stop.")
                : next.step.instruction

            if isFirstOfLeg, distance <= near * 2 {
                // The next maneuver comes right away: say both at once.
                return [say("\(instruction) Then \(lowerFirst(upcoming))", distance)]
            }
            var result: [[String: Any]] = []
            if isFirstOfLeg { result.append(say(instruction, distance)) }
            if distance >= 2000, motorized { result.append(say("In \(spoken(1000)), \(lowerFirst(upcoming))", 1000)) }
            if distance >= early * 1.5 { result.append(say("In \(spoken(early)), \(lowerFirst(upcoming))", early)) }
            if next.type == "arrive" {
                // "You have arrived" only once there, not 100 m before.
                result.append(say(next.step.instruction, min(distance, near / 4)))
            } else {
                result.append(say(upcoming, min(distance, near)))
            }
            return result
        }

        private static func say(_ text: String, _ distance: Double) -> [String: Any] {
            ["announcement": text, "distanceAlongGeometry": distance]
        }

        static func spoken(_ meters: Double) -> String {
            if meters >= 1000 {
                let km = (meters / 500).rounded() / 2
                if km == 1 { return "1 kilometer" }
                return km == km.rounded() ? "\(Int(km)) kilometers" : "\(km) kilometers"
            }
            return "\(Int(meters.rounded())) meters"
        }

        private static func lowerFirst(_ text: String) -> String {
            guard let first = text.first else { return text }
            return first.lowercased() + text.dropFirst()
        }
    }
}

/// Encoded polylines with 6 decimals, as integer latitude and longitude in millionths of a degree.
enum Polyline6 {
    struct Point: Equatable {
        let lat: Int
        let lon: Int
    }

    struct DecodingError: Error {}

    static func decode(_ encoded: String) throws -> [Point] {
        let bytes = Array(encoded.utf8)
        var points: [Point] = []
        var index = 0
        var lat = 0
        var lon = 0
        func next() throws -> Int {
            var result = 0
            var shift = 0
            while true {
                guard index < bytes.count else { throw DecodingError() }
                let b = Int(bytes[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20 { break }
            }
            return (result & 1) != 0 ? ~(result >> 1) : result >> 1
        }
        while index < bytes.count {
            lat += try next()
            lon += try next()
            points.append(Point(lat: lat, lon: lon))
        }
        return points
    }

    static func encode(_ points: [Point]) -> String {
        var out = ""
        var lat = 0
        var lon = 0
        func append(_ delta: Int) {
            var v = delta < 0 ? ~(delta << 1) : delta << 1
            while v >= 0x20 {
                out.append(Character(UnicodeScalar(UInt8((0x20 | (v & 0x1F)) + 63))))
                v >>= 5
            }
            out.append(Character(UnicodeScalar(UInt8(v + 63))))
        }
        for p in points {
            append(p.lat - lat)
            append(p.lon - lon)
            lat = p.lat
            lon = p.lon
        }
        return out
    }
}
