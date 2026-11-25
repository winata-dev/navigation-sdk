import Foundation

/// Everything the SDK throws or reports in ``NavigationEvent/error(_:)``.
public enum NavigationError: Error, LocalizedError, Sendable {
    /// The routing server found no route between the waypoints for this profile.
    case noRouteFound
    /// The routing server answered with an HTTP error other than 401 or 403 (those are
    /// ``unauthorized(_:statusCode:body:)``). `body` usually explains it.
    case http(statusCode: Int, body: String?)
    /// The routing server refused the credentials (HTTP 401 or 403), or there was no API key to
    /// send. `statusCode` is nil for ``AuthFailure/missingAPIKey``, which is found before any
    /// request is sent.
    case unauthorized(AuthFailure, statusCode: Int?, body: String?)
    /// The routing server could not be reached (no connection, timeout, DNS…).
    case network(underlying: String)
    /// The routing server's answer could not be understood.
    case invalidResponse(String)
    /// No location fix arrived in time. Check permissions, and that location is turned on.
    case locationUnavailable
    /// Location permission has not been granted (or was denied).
    case permissionDenied
    /// The SDK was used in a way it does not support.
    case illegalUse(String)

    public var errorDescription: String? {
        switch self {
        case .noRouteFound: "No route found between these points."
        case let .http(code, body): "Routing server returned HTTP \(code)" + (body.map { ": \($0.prefix(300))" } ?? "")
        case let .unauthorized(reason, code, _): reason.message + (code.map { " (HTTP \($0))" } ?? "")
        case let .network(underlying): "Could not reach the routing server: \(underlying)"
        case let .invalidResponse(message): message
        case .locationUnavailable: "No location fix available."
        case .permissionDenied: "Location permission has not been granted."
        case let .illegalUse(message): message
        }
    }
}

public extension NavigationError {
    /// Why credentials were refused, as far as the server says. The Remaply API always says.
    enum AuthFailure: String, Sendable, CaseIterable {
        /// The API key is blank, e.g. missing from this build's configuration.
        case missingAPIKey
        /// Wrong, revoked or expired key, or its organization is suspended (`invalid_api_key`).
        case invalidAPIKey
        /// The key lacks the scope this endpoint needs, e.g. `routing` (`scope_not_allowed`).
        case scopeNotAllowed
        /// The key is restricted to other apps, websites or IP addresses (`api_key_restricted`).
        /// Check the bundle identifier registered for the key.
        case apiKeyRestricted
        /// A 401 or 403 the SDK does not recognize, e.g. from another provider. See the body.
        case unknown

        var message: String {
            switch self {
            case .missingAPIKey: "No API key: pass a publishable key (rmp_pk_…) to RemaplyCredentials"
            case .invalidAPIKey: "The API key is wrong, revoked or expired"
            case .scopeNotAllowed: "The API key does not have the scope this request needs"
            case .apiKeyRestricted: "The API key is not allowed from this app (check its bundle identifier)"
            case .unknown: "The routing server refused the credentials"
            }
        }
    }
}
