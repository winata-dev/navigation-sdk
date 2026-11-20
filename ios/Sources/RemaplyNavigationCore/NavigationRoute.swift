import FerrostarCoreFFI
import Foundation

/// A route that can be followed with ``RemaplyNavigator/startNavigation(route:simulate:profile:)``.
///
/// Get one from ``RemaplyNavigator/requestRoutes(to:from:profile:)``, or build one from an
/// OSRM-format response with ``fromOSRM(routeJSON:waypoints:polylinePrecision:)`` when you write
/// your own ``RouteProvider``.
public final class NavigationRoute: @unchecked Sendable {
    package let engineRoute: FerrostarCoreFFI.Route

    package init(engineRoute: FerrostarCoreFFI.Route) {
        self.engineRoute = engineRoute
    }

    /// Length of the route in meters.
    public var distance: Double { engineRoute.distance }

    /// Expected travel time in seconds, the sum of the steps.
    public var duration: TimeInterval { engineRoute.steps.reduce(0) { $0 + $1.duration } }

    /// The route line, from the start to the destination.
    public private(set) lazy var geometry: [Coordinate] = engineRoute.geometry.map(\.coordinate)

    public private(set) lazy var steps: [RouteStep] = engineRoute.steps.map(\.routeStep)

    /// The waypoints this route visits, including the start.
    public private(set) lazy var waypoints: [Waypoint] = engineRoute.waypoints.map(\.waypoint)

    /// Parses one route object from an OSRM-format response (an element of its `routes` array),
    /// e.g. from Valhalla with `format=osrm`, OSRM, or any compatible server.
    ///
    /// - Parameters:
    ///   - routeJSON: The route object as UTF-8 JSON.
    ///   - waypoints: The waypoints of the request, including the start.
    ///   - polylinePrecision: 6 for `polyline6` geometry (Valhalla, Mapbox), 5 for `polyline`.
    /// - Throws: ``NavigationError/invalidResponse(_:)`` if the JSON is not a valid OSRM route.
    public static func fromOSRM(routeJSON: Data, waypoints: [Waypoint], polylinePrecision: UInt32 = 6) throws -> NavigationRoute {
        do {
            let route = try createRouteFromOsrmRoute(
                routeData: routeJSON,
                waypoints: waypoints.map(\.engineWaypoint),
                polylinePrecision: polylinePrecision
            )
            return NavigationRoute(engineRoute: route)
        } catch {
            throw NavigationError.invalidResponse("Not a valid OSRM route: \(error)")
        }
    }
}

extension NavigationRoute: CustomStringConvertible {
    public var description: String {
        "NavigationRoute(distance: \(Int(distance)) m, duration: \(Int(duration)) s, steps: \(engineRoute.steps.count))"
    }
}
