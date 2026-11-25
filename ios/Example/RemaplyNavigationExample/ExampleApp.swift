import RemaplyNavigationCore
import RemaplyNavigationUI
import SwiftUI

/// Settings for the example. Edit these, or use your own servers.
enum ExampleConfig {
    /// A MapLibre style. OpenFreeMap is free and needs no key.
    static let styleURL = URL(string: "https://tiles.openfreemap.org/styles/liberty")!

    /// Leave empty to use the public FOSSGIS Valhalla server and OpenFreeMap (fine for trying
    /// things out, not for production). Put a Remaply API key here to use the Remaply API for
    /// routes and the map instead.
    static let remaplyAPIKey = ""

    static let valhallaURL = URL(string: "https://valhalla1.openstreetmap.de/route")!

    /// Nil unless `remaplyAPIKey` is set. Used for routes and for the map.
    static var credentials: RemaplyCredentials? {
        remaplyAPIKey.isEmpty ? nil : RemaplyCredentials(apiKey: remaplyAPIKey)
    }

    static var mapStyle: MapStyle {
        credentials.map { MapStyle.remaply($0) } ?? MapStyle(url: styleURL)
    }

    static var routeProvider: any RouteProvider {
        credentials.map { ValhallaRouteProvider.remaply($0) } ?? ValhallaRouteProvider(endpointURL: valhallaURL)
    }
}

@main
struct ExampleApp: App {
    @StateObject private var navigator = RemaplyNavigator(config: NavigatorConfig(routeProvider: ExampleConfig.routeProvider))

    var body: some Scene {
        WindowGroup {
            ContentView(navigator: navigator)
        }
    }
}
