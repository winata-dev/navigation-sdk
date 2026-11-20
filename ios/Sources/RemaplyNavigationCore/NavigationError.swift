import Foundation

/// Everything the SDK throws or reports in ``NavigationEvent/error(_:)``.
public enum NavigationError: Error, LocalizedError, Sendable {
    /// The routing server found no route between the waypoints for this profile.
    case noRouteFound
    /// The routing server answered with an HTTP error. `body` usually explains it.
    case http(statusCode: Int, body: String?)
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
        case let .network(underlying): "Could not reach the routing server: \(underlying)"
        case let .invalidResponse(message): message
        case .locationUnavailable: "No location fix available."
        case .permissionDenied: "Location permission has not been granted."
        case let .illegalUse(message): message
        }
    }
}
