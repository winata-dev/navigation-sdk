# Getting started on iOS

This guide adds turn-by-turn navigation to an existing SwiftUI app. It takes about 15 minutes.

**You need:** Xcode 16 or newer, an app targeting iOS 16 or higher, a routing server URL (see
[Configuration › Routing servers](configuration.md#routing-servers)) and a MapLibre style URL, or
just a Remaply API key, which covers both.

## 1. Add the package

In Xcode: **File › Add Package Dependencies…**, enter

```
https://github.com/winata-dev/navigation-sdk
```

choose **Up to Next Major Version** from the [latest release](https://github.com/winata-dev/navigation-sdk/releases),
and add the **RemaplyNavigationUI** product to your app target. (Add **RemaplyNavigationCore**
instead if you build your own UI.)

Or, in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/winata-dev/navigation-sdk", from: "1.0.1"),
],
targets: [
    .target(name: "MyApp", dependencies: [
        .product(name: "RemaplyNavigationUI", package: "navigation-sdk"),
    ]),
]
```

**Trust the macros.** The map library (MapLibre's SwiftUI DSL) uses Swift macros. The first time
you build, Xcode shows *"Macro 'MapLibreSwiftMacrosImpl' must be enabled"*. Click the error and
choose **Trust & Enable**. On CI, pass `-skipMacroValidation` to `xcodebuild`.

## 2. Info.plist

Add to your app's Info.plist:

| Key | Value | Why |
|---|---|---|
| `NSLocationWhenInUseUsageDescription` | e.g. "Your location is used for turn-by-turn directions." | Required for GPS. iOS shows this text in the permission prompt |
| `UIBackgroundModes` | `location`, `audio` | Optional. Keeps navigation and voice running with the screen off or another app in front |

Without `location` in `UIBackgroundModes`, navigation pauses when the app goes to the
background. The SDK only turns on background updates when this mode is declared, because
turning them on without it crashes the app.

## 3. Create a navigator

`RemaplyNavigator` is an `ObservableObject`. Own it with `@StateObject`, in a view or at the app
level:

```swift
import RemaplyNavigationCore
import RemaplyNavigationUI
import SwiftUI

// Only with the Remaply API. Read the key from your build configuration, not from source.
// let credentials = RemaplyCredentials(apiKey: remaplyAPIKey)

struct NavigationScreen: View {
    @StateObject private var navigator = RemaplyNavigator(config: NavigatorConfig(
        routeProvider: ValhallaRouteProvider(endpointURL: URL(string: "https://valhalla.example.com/route")!)
        // Or, with the Remaply API (pass the same credentials to the map, below):
        // routeProvider: ValhallaRouteProvider.remaply(credentials)
    ))
    @State private var error: String?

    var body: some View {
        switch navigator.state.status {
        case .navigating, .arrived:
            RemaplyNavigationView(
                navigator: navigator,
                style: MapStyle(url: URL(string: "https://tiles.openfreemap.org/styles/liberty")!)
                // Or the Remaply map, with the key on the style, tiles and sprites:
                // style: .remaply(credentials)
            )
            .ignoresSafeArea()
        case .idle, .routing:
            Button("Navigate to Suva Harbour") {
                Task {
                    do {
                        try await navigator.navigate(to: [Waypoint(latitude: -18.1250, longitude: 178.4500)])
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
            }
        }
    }
}
```

`navigate(to:)` asks for location permission if needed, gets the device's location, asks the
routing server for a route, and starts following it. To let the user choose between routes
first, call `requestRoutes(to:)` and then `startNavigation(route:)`.

The exit button and the "Done" button on arrival stop navigation by default; pass
`onExit: { ... }` to do something else, such as dismissing the screen.

## 4. Try it in the Simulator

Pass `simulate: true` to drive along the route without GPS. No permission is needed:

```swift
try await navigator.navigate(
    to: [Waypoint(latitude: -18.1250, longitude: 178.4500)],
    simulate: true,
    origin: UserLocation(coordinate: Coordinate(latitude: -18.1416, longitude: 178.4419), horizontalAccuracy: 5)
)
```

## Next steps

- [Configuration](configuration.md): profiles, rerouting, voice language
- [State and events](state-and-events.md): react to arrival, rerouting and errors
- [Customizing the UI](ui-customization.md): colors, your own overlays and map layers
