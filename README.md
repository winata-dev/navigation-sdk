# Remaply Navigation SDK

Free, open-source turn-by-turn navigation for **Android** and **iOS**, with a ready-to-use
navigation screen. Built on [Ferrostar](https://github.com/stadiamaps/ferrostar) (navigation
engine) and [MapLibre](https://maplibre.org) (map rendering).

Add a dependency, give it a routing server and a map style, and you have working navigation:

```kotlin
// Android (Jetpack Compose)
val navigator = RemaplyNavigator(context, NavigatorConfig(
    routeProvider = ValhallaRouteProvider("https://valhalla.example.com/route"),
))
navigator.navigateTo(Waypoint(-18.1250, 178.4500))

RemaplyNavigationView(navigator, styleUrl = "https://tiles.openfreemap.org/styles/liberty")
```

```swift
// iOS (SwiftUI)
let navigator = RemaplyNavigator(config: NavigatorConfig(
    routeProvider: ValhallaRouteProvider(endpointURL: URL(string: "https://valhalla.example.com/route")!)
))
try await navigator.navigate(to: [Waypoint(latitude: -18.1250, longitude: 178.4500)])

RemaplyNavigationView(navigator: navigator, styleURL: URL(string: "https://tiles.openfreemap.org/styles/liberty")!)
```

With the [Remaply API](https://api.remaply.com), one key covers both the routes and the map
(style, tiles and sprites):

```kotlin
// Android
val remaply = RemaplyCredentials(context, apiKey = BuildConfig.REMAPLY_API_KEY)
val navigator = RemaplyNavigator(context, NavigatorConfig(routeProvider = ValhallaRouteProvider.remaply(remaply)))
RemaplyNavigationView(navigator, style = MapStyle.remaply(remaply))
```

```swift
// iOS
let remaply = RemaplyCredentials(apiKey: remaplyAPIKey)
let navigator = RemaplyNavigator(config: NavigatorConfig(routeProvider: ValhallaRouteProvider.remaply(remaply)))
RemaplyNavigationView(navigator: navigator, style: .remaply(remaply))
```

## What's included

| | |
|---|---|
| **Navigation Core** | Route requests, GPS tracking with route snapping, step-by-step progress, distance and time remaining, off-route detection and automatic rerouting, multi-stop trips, spoken instructions, speed limits, background navigation, simulated drives, a state stream and events |
| **Navigation UI** | A full-screen navigation view: map with route and position puck, maneuver banner, distance/ETA/arrival bar, speed limit sign, mute/zoom/recenter/exit buttons, rerouting banner, arrival card. Portrait and landscape, light and dark |
| **Routing backends** | Any Valhalla server (self-hosted, Stadia Maps, the Remaply API), GraphHopper, or your own `RouteProvider` |
| **Maps** | The Remaply map with just an API key, or any MapLibre style URL: OpenFreeMap, MapTiler, Stadia Maps, Protomaps, your own tiles |

Everything is Apache-2.0 / BSD licensed. There are no per-user fees or SDK keys. You only need
a routing server and map tiles, both of which can be free or self-hosted.

## Platforms

| | Minimum | Install |
|---|---|---|
| Android | API 25 (Android 7.1), Jetpack Compose | JitPack: `com.github.winata-dev.navigation-sdk:navigation-ui:<version>` |
| iOS | iOS 16, SwiftUI | Swift Package Manager: `https://github.com/winata-dev/navigation-sdk` |

## Documentation

- [Getting started on Android](docs/getting-started-android.md)
- [Getting started on iOS](docs/getting-started-ios.md)
- [Configuration](docs/configuration.md): routing servers, profiles, rerouting, voice, location, simulation
- [State and events](docs/state-and-events.md): building your own UI or analytics
- [Customizing the UI](docs/ui-customization.md)
- [Releasing and deploying the SDK](docs/deploying.md): for maintainers
- [Architecture](docs/architecture.md): how it's built and why
- [Troubleshooting](docs/troubleshooting.md)

## Sample apps

- Android: [`android/sample`](android/sample). Open `android/` in Android Studio and run `sample`.
- iOS: [`ios/Example`](ios/Example). Open `ios/Example/RemaplyNavigationExample.xcodeproj` and run.

Both default to a simulated drive in Suva, Fiji, routed by the public FOSSGIS Valhalla server and
drawn with OpenFreeMap tiles, so they work without any keys.

## Repository layout

```
Package.swift            iOS package manifest (must be at the root for SwiftPM)
ios/Sources/             RemaplyNavigationCore, RemaplyNavigationUI
ios/Tests/               iOS unit tests
ios/Example/             iOS example app
android/navigation-core  Android core library
android/navigation-ui    Android Compose UI library
android/sample           Android sample app
docs/                    Documentation
jitpack.yml              How JitPack builds the Android libraries
```

## License

[Apache-2.0](LICENSE). Map data from OpenStreetMap is © OpenStreetMap contributors (ODbL). Your
map style must show that attribution, and the navigation view keeps MapLibre's attribution
button visible for that reason.
