import Foundation

/// A Remaply API key and this app's identity, for everything that calls the Remaply API: routes
/// (``ValhallaRouteProvider/remaply(_:session:)``) and the map (`MapStyle.remaply(_:styleID:)` in
/// RemaplyNavigationUI: style, tiles and sprites).
///
/// ```swift
/// let remaply = RemaplyCredentials(apiKey: remaplyAPIKey)
/// let navigator = RemaplyNavigator(config: NavigatorConfig(routeProvider: ValhallaRouteProvider.remaply(remaply)))
/// RemaplyNavigationView(navigator: navigator, style: .remaply(remaply))
/// ```
///
/// Every request carries the key in `X-Api-Key`, plus the app's bundle identifier, so keys
/// restricted to your iOS app work.
///
/// Use a **publishable** key (`rmp_pk_…`), never a secret one: anything shipped in an app can be
/// read. A blank key does not crash. Route requests then fail with
/// ``NavigationError/unauthorized(_:statusCode:body:)`` (``NavigationError/AuthFailure/missingAPIKey``),
/// so a key missing from a build's configuration shows up as an error.
public struct RemaplyCredentials: Sendable, Hashable, CustomStringConvertible, CustomDebugStringConvertible {
    public static let defaultBaseURL = URL(string: "https://api.remaply.com")!
    /// Remaply's default map style, the same for every organization.
    public static let defaultStyleID = "default"

    /// The key, without leading or trailing spaces.
    public let apiKey: String
    /// The API's base URL.
    public let baseURL: URL

    /// - Parameters:
    ///   - apiKey: A key allowed to use routing, and styles and tiles if the map uses Remaply's style.
    ///   - baseURL: The API's base URL.
    public init(apiKey: String, baseURL: URL = RemaplyCredentials.defaultBaseURL) {
        self.apiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.baseURL = baseURL
    }

    /// False when the key is blank.
    public var hasAPIKey: Bool { !apiKey.isEmpty }

    /// The headers sent with every request to the API.
    package var requestHeaders: [String: String] {
        var headers: [String: String] = [:]
        if hasAPIKey { headers["X-Api-Key"] = apiKey }
        if let bundleID = Bundle.main.bundleIdentifier { headers["X-Ios-Bundle-Identifier"] = bundleID }
        return headers
    }

    /// The URL of a Remaply map style (`GET /v1/styles/{styleID}.json`). The style points MapLibre
    /// at the Remaply tiles (`/v1/tiles/…`) and sprites (`/v1/sprites/…`), all on this API, so the
    /// map's requests need the same headers: use `MapStyle.remaply(_:styleID:)` in
    /// RemaplyNavigationUI, which does both. Only call this for a MapLibre map of your own.
    ///
    /// - Parameter styleID: ``defaultStyleID``, Remaply's map, or the id of your organization's
    ///   style from Map Studio. Must not be blank.
    public func styleURL(styleID: String = RemaplyCredentials.defaultStyleID) -> URL {
        let id = styleID.trimmingCharacters(in: .whitespacesAndNewlines)
        precondition(!id.isEmpty, "styleID must not be blank")
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/")
        let segment = id.addingPercentEncoding(withAllowedCharacters: allowed) ?? id
        var base = baseURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        return URL(string: "\(base)/v1/styles/\(segment).json")!
    }

    /// The API's host name, lower case.
    package var host: String? { baseURL.host?.lowercased() }

    /// Never prints the whole key, so it does not end up in logs or crash reports.
    public var description: String {
        "RemaplyCredentials(apiKey: \(hasAPIKey ? apiKey.prefix(12) + "…" : "<none>"), baseURL: \(baseURL.absoluteString))"
    }

    public var debugDescription: String { description }
}
