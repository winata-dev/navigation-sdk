import FerrostarCoreFFI
import Foundation

/// What a ``RouteProvider`` is asked for.
public struct RouteRequest: Sendable {
    /// Where the user is. Its course, when known, lets the server avoid routes that start with a U-turn.
    public var origin: UserLocation
    /// Where to go, in order. The last one is the destination. Never empty.
    public var waypoints: [Waypoint]
    public var profile: TravelProfile
    /// True when the user left the route and a new one is needed.
    public var isReroute: Bool

    public init(origin: UserLocation, waypoints: [Waypoint], profile: TravelProfile, isReroute: Bool) {
        self.origin = origin
        self.waypoints = waypoints
        self.profile = profile
        self.isReroute = isReroute
    }
}

/// Where routes come from. Use ``ValhallaRouteProvider`` or ``GraphHopperRouteProvider`` for those
/// servers, or conform to this protocol for anything else (an in-house API, offline routing…)
/// and build the routes with ``NavigationRoute/fromOSRM(routeJSON:waypoints:polylinePrecision:)``.
///
/// The first route returned is the one followed when rerouting.
public protocol RouteProvider: Sendable {
    /// Returns routes for `request`, best first, or throws a ``NavigationError``.
    func routes(for request: RouteRequest) async throws -> [NavigationRoute]
}

/// Routes from a [Valhalla](https://github.com/valhalla/valhalla) server, or any API that accepts
/// Valhalla's route request and answers in OSRM format (Stadia Maps, the Remaply API…).
public struct ValhallaRouteProvider: RouteProvider {
    private let fetcher: HTTPRouteFetcher

    /// - Parameters:
    ///   - endpointURL: The full route URL, e.g. `https://valhalla.example.com/route`.
    ///   - headers: Sent with every request, e.g. an API key header.
    ///   - options: Extra fields merged into the request body, e.g. `["units": "kilometers", "language": "id-ID"]`.
    ///     Must be JSON-serializable.
    ///   - costing: The Valhalla costing for each profile. Defaults to ``defaultCosting(_:)``.
    ///   - session: To share a session, add a delegate or change timeouts.
    public init(
        endpointURL: URL,
        headers: [String: String] = [:],
        options: [String: Any] = [:],
        costing: @escaping @Sendable (TravelProfile) -> String = ValhallaRouteProvider.defaultCosting,
        session: URLSession = .shared
    ) {
        let optionsJSON = JSONOptions.encode(options)
        fetcher = HTTPRouteFetcher(session: session, headers: headers) { profile in
            .valhalla(endpointUrl: endpointURL.absoluteString, profile: costing(profile), optionsJson: optionsJSON)
        }
    }

    public func routes(for request: RouteRequest) async throws -> [NavigationRoute] {
        try await fetcher.fetch(request)
    }

    /// Valhalla's standard costing names.
    @Sendable public static func defaultCosting(_ profile: TravelProfile) -> String {
        switch profile {
        case .car: "auto"
        case .motorcycle: "motorcycle"
        case .truck: "truck"
        case .bicycle: "bicycle"
        case .foot: "pedestrian"
        }
    }

    /// Routes from the Remaply Platform API (`POST /v1/navigation/route`).
    ///
    /// Sends the key in `X-Api-Key`, plus the app's bundle identifier, so keys restricted to your
    /// iOS app work.
    ///
    /// - Parameters:
    ///   - apiKey: A key with the `routing` scope.
    ///   - baseURL: The API's base URL.
    public static func remaply(
        apiKey: String,
        baseURL: URL = URL(string: "https://api.remaply.com")!,
        session: URLSession = .shared
    ) -> ValhallaRouteProvider {
        precondition(!apiKey.trimmingCharacters(in: .whitespaces).isEmpty, "apiKey must not be blank")
        var headers = ["X-Api-Key": apiKey]
        if let bundleID = Bundle.main.bundleIdentifier {
            headers["X-Ios-Bundle-Identifier"] = bundleID
        }
        return ValhallaRouteProvider(
            endpointURL: baseURL.appendingPathComponent("v1/navigation/route"),
            headers: headers,
            session: session
        )
    }
}

/// Routes from [GraphHopper](https://www.graphhopper.com/)'s navigate endpoint.
public struct GraphHopperRouteProvider: RouteProvider {
    private let fetcher: HTTPRouteFetcher

    /// - Parameters:
    ///   - endpointURL: e.g. `https://graphhopper.com/api/1/navigate/?key=YOUR_KEY`.
    ///   - locale: Language of the instructions, e.g. "en" or "id".
    ///   - profileName: The GraphHopper profile for each travel profile.
    public init(
        endpointURL: URL,
        locale: String = "en",
        useMetricUnits: Bool = true,
        headers: [String: String] = [:],
        options: [String: Any] = [:],
        profileName: @escaping @Sendable (TravelProfile) -> String = GraphHopperRouteProvider.defaultProfileName,
        session: URLSession = .shared
    ) {
        let optionsJSON = JSONOptions.encode(options)
        fetcher = HTTPRouteFetcher(session: session, headers: headers) { profile in
            .graphHopper(
                endpointUrl: endpointURL.absoluteString,
                profile: profileName(profile),
                locale: locale,
                voiceUnits: useMetricUnits ? .metric : .imperial,
                optionsJson: optionsJSON
            )
        }
    }

    public func routes(for request: RouteRequest) async throws -> [NavigationRoute] {
        try await fetcher.fetch(request)
    }

    @Sendable public static func defaultProfileName(_ profile: TravelProfile) -> String {
        switch profile {
        case .car: "car"
        case .motorcycle: "motorcycle"
        case .truck: "truck"
        case .bicycle: "bike"
        case .foot: "foot"
        }
    }
}

enum JSONOptions {
    static func encode(_ options: [String: Any]) -> String? {
        guard !options.isEmpty else { return nil }
        precondition(JSONSerialization.isValidJSONObject(options), "options must be JSON-serializable")
        guard let data = try? JSONSerialization.data(withJSONObject: options, options: [.sortedKeys]) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// Builds the request with Ferrostar's request generator, sends it with URLSession (adding our
/// headers, which Ferrostar's generators cannot), and parses the OSRM answer with Ferrostar's
/// parser. Doing the HTTP call ourselves also keeps the error body, which FerrostarCore drops.
final class HTTPRouteFetcher: @unchecked Sendable {
    private let session: URLSession
    private let headers: [String: String]
    private let providerFor: @Sendable (TravelProfile) -> WellKnownRouteProvider
    private let lock = NSLock()
    private var adapters: [TravelProfile: RouteAdapter] = [:]

    init(session: URLSession, headers: [String: String], providerFor: @escaping @Sendable (TravelProfile) -> WellKnownRouteProvider) {
        self.session = session
        self.headers = headers
        self.providerFor = providerFor
    }

    private func adapter(for profile: TravelProfile) throws -> RouteAdapter {
        lock.lock()
        defer { lock.unlock() }
        if let adapter = adapters[profile] { return adapter }
        do {
            let adapter = try RouteAdapter.fromWellKnownRouteProvider(wellKnownRouteProvider: providerFor(profile))
            adapters[profile] = adapter
            return adapter
        } catch {
            throw NavigationError.illegalUse("Invalid route provider configuration: \(error)")
        }
    }

    func fetch(_ request: RouteRequest) async throws -> [NavigationRoute] {
        let adapter = try adapter(for: request.profile)

        let engineRequest: FerrostarCoreFFI.RouteRequest
        do {
            engineRequest = try adapter.generateRequest(
                userLocation: request.origin.engineLocation,
                waypoints: request.waypoints.map(\.engineWaypoint)
            )
        } catch {
            throw NavigationError.illegalUse("Could not build the route request: \(error)")
        }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: urlRequest(engineRequest))
        } catch let error as NavigationError {
            throw error
        } catch {
            throw NavigationError.network(underlying: error.localizedDescription)
        }

        if let http = response as? HTTPURLResponse, !(200 ..< 300).contains(http.statusCode) {
            throw Self.httpError(statusCode: http.statusCode, body: String(data: data, encoding: .utf8) ?? "")
        }

        let routes: [FerrostarCoreFFI.Route]
        do {
            routes = try adapter.parseResponse(response: data)
        } catch {
            if "\(error)".localizedCaseInsensitiveContains("NoRoute") { throw NavigationError.noRouteFound }
            throw NavigationError.invalidResponse("Could not parse the route response: \(error)")
        }
        guard !routes.isEmpty else { throw NavigationError.noRouteFound }
        return routes.map { NavigationRoute(engineRoute: $0) }
    }

    private func urlRequest(_ request: FerrostarCoreFFI.RouteRequest) throws -> URLRequest {
        var urlRequest: URLRequest
        let requestHeaders: [String: String]
        switch request {
        case let .httpPost(url, headers, body):
            guard let url = URL(string: url) else { throw NavigationError.illegalUse("Invalid route URL: \(url)") }
            urlRequest = URLRequest(url: url)
            urlRequest.httpMethod = "POST"
            urlRequest.httpBody = body
            requestHeaders = headers
        case let .httpGet(url, headers):
            guard let url = URL(string: url) else { throw NavigationError.illegalUse("Invalid route URL: \(url)") }
            urlRequest = URLRequest(url: url)
            urlRequest.httpMethod = "GET"
            requestHeaders = headers
        }
        urlRequest.timeoutInterval = 20
        for (name, value) in requestHeaders.merging(headers, uniquingKeysWith: { _, ours in ours }) {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        return urlRequest
    }

    /// Both Valhalla's `NoRoute` (HTTP 400) and the Remaply API's `route_not_found` (404) mean the same.
    static func httpError(statusCode: Int, body: String) -> NavigationError {
        if body.contains("route_not_found") || body.contains("\"NoRoute\"") {
            return .noRouteFound
        }
        return .http(statusCode: statusCode, body: body.isEmpty ? nil : body)
    }
}
