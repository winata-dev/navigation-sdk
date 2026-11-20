import RemaplyNavigationCore
import RemaplyNavigationUI
import SwiftUI

/// Settings for the example. Edit these, or use your own servers.
enum ExampleConfig {
    /// A MapLibre style. OpenFreeMap is free and needs no key.
    static let styleURL = URL(string: "https://tiles.openfreemap.org/styles/liberty")!

    /// Leave empty to use the public FOSSGIS Valhalla server (fine for trying things out, not for
    /// production). Put a Remaply API key here to route with the Remaply API instead.
    static let remaplyAPIKey = ""

    static let valhallaURL = URL(string: "https://valhalla1.openstreetmap.de/route")!

    static var routeProvider: any RouteProvider {
        remaplyAPIKey.isEmpty
            ? ValhallaRouteProvider(endpointURL: valhallaURL)
            : ValhallaRouteProvider.remaply(apiKey: remaplyAPIKey)
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
