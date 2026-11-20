# Configuration

Everything is set once, in `NavigatorConfig`. Only the route provider is required.

| Setting | Android | iOS | Default |
|---|---|---|---|
| Where routes come from | `routeProvider` | `routeProvider` | required |
| Travel mode | `profile` | `profile` | car |
| Progress and off-route distances | `thresholds` | `thresholds` | per profile |
| Rerouting | `rerouting` | `rerouting` | on, at most every 15 s |
| Spoken instructions | `voiceGuidance` | `voiceGuidance` | system voice, device language |
| Location source | `locationSource` | `locationSource` | device |
| Keep running in the background | `backgroundNavigation` | `backgroundNavigation` | on |
| Simulation speed | `simulationSpeedMultiplier` | `simulationSpeedMultiplier` | 2× |

## Routing servers

The SDK needs a routing server that returns **turn-by-turn instructions in OSRM format** with
banner and voice instructions. Free options:

| Server | Cost | Notes |
|---|---|---|
| Your own [Valhalla](https://github.com/valhalla/valhalla) | Free (your hosting) | Recommended for production. One Docker container: `ghcr.io/valhalla/valhalla-scripted`, fed an OpenStreetMap extract from [Geofabrik](https://download.geofabrik.de) |
| [Remaply API](https://api.remaply.com) | Per your Remaply plan | Valhalla behind API keys, with app restrictions |
| Public FOSSGIS Valhalla (`https://valhalla1.openstreetmap.de/route`) | Free | **Demos and development only.** Rate limited, no uptime guarantee |
| [Stadia Maps](https://stadiamaps.com), [GraphHopper](https://www.graphhopper.com) | Free tiers | Hosted, with API keys |

### Valhalla

```kotlin
ValhallaRouteProvider(
    endpointUrl = "https://valhalla.example.com/route",
    headers = mapOf("Authorization" to "Bearer ..."),            // optional
    options = mapOf("units" to "kilometers", "language" to "id-ID"), // merged into the request body
)
```

```swift
ValhallaRouteProvider(
    endpointURL: URL(string: "https://valhalla.example.com/route")!,
    headers: ["Authorization": "Bearer ..."],
    options: ["units": "kilometers", "language": "id-ID"]
)
```

`options` accepts any [Valhalla route option](https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/),
for example `costing_options` to avoid tolls. The SDK always asks for OSRM format with banner
and voice instructions.

### Remaply API

```kotlin
ValhallaRouteProvider.remaply(context, apiKey = "rmp_pk_...")
```

```swift
ValhallaRouteProvider.remaply(apiKey: "rmp_pk_...")
```

This calls `POST /v1/navigation/route` with the key in `X-Api-Key`. It also sends your app's
identity (Android package name and signing-certificate SHA-1, iOS bundle identifier), so keys
restricted to your app work. Use a **publishable** key (`rmp_pk_`); never ship a secret key in
an app.

### GraphHopper

```kotlin
GraphHopperRouteProvider("https://graphhopper.com/api/1/navigate/?key=YOUR_KEY", locale = "en")
```

### Your own backend

Implement `RouteProvider` and turn each OSRM-format route into a `NavigationRoute`:

```kotlin
class MyRouteProvider(private val api: MyApi) : RouteProvider {
    override suspend fun getRoutes(request: RouteRequest): List<NavigationRoute> {
        val response = api.route(request.origin.coordinate, request.waypoints.map { it.coordinate })
        return response.routes.map { routeJson ->
            NavigationRoute.fromOsrm(routeJson.toByteArray(), listOf(Waypoint(request.origin.coordinate)) + request.waypoints)
        }
    }
}
```

```swift
struct MyRouteProvider: RouteProvider {
    func routes(for request: RouteRequest) async throws -> [NavigationRoute] {
        let routeJSON: Data = try await myAPI.route(...)
        return [try NavigationRoute.fromOSRM(routeJSON: routeJSON, waypoints: [Waypoint(coordinate: request.origin.coordinate)] + request.waypoints)]
    }
}
```

`request.isReroute` tells you whether the user left the route. Throw a `NavigationException` /
`NavigationError` to report failures.

## Travel profiles

`CAR`, `MOTORCYCLE`, `TRUCK`, `BICYCLE`, `FOOT`. The profile picks:

- **the routing profile on the server.** For Valhalla these are `auto`, `motorcycle`, `truck`,
  `bicycle` and `pedestrian`; override them with `costing`.
- **the navigation thresholds:**

| Profile | Off route beyond | Stop reached within | Next step within |
|---|---|---|---|
| Car, motorcycle | 50 m | 100 m | 30 m |
| Truck | 60 m | 100 m | 40 m |
| Bicycle | 40 m | 40 m | 20 m |
| Foot | 40 m | 30 m | 15 m |

GPS fixes less accurate than 30 m never advance a step or count as off route. Override with
`thresholds = NavigationThresholds(...)`. Each call to `requestRoutes`, `navigateTo` or
`startNavigation` can also take its own profile.

## Rerouting

When the user leaves the route, the SDK asks the route provider for a new route from where they
are to the remaining stops. Each reroute is a request to your routing server, so it is limited:

- `minimumIntervalSeconds` / `minimumInterval` (default 15 s) between requests
- `minimumMovementMeters` / `minimumMovement` (default 50 m) moved since the last request

Set `enabled = false` to only get the off-route event and handle it yourself.

## Voice guidance

| Option | Android | iOS |
|---|---|---|
| System voice | `VoiceGuidance.Default(locale = Locale("id", "ID"))` | `.system(voiceLanguage: "id-ID")` |
| Off | `VoiceGuidance.Disabled` | `.disabled` |
| Your own (e.g. a cloud voice) | `VoiceGuidance.Custom(speaker)` | `.custom(speaker)` |

The voice language should match the language of the instructions, which the routing server
decides. With Valhalla, set `options = mapOf("language" to "id-ID")`. Users can mute and unmute
from the navigation view, or you can call `setMuted(...)`.

## Location

- **Android:** `LocationSource.Device` (default) uses Google Play Services' fused location when
  available, else the platform GPS. `LocationSource.PlatformGps` always uses the platform GPS
  (devices without Google services, F-Droid). `LocationSource.Custom(provider)` takes any
  source, e.g. an external GNSS receiver.
- **iOS:** `.device` uses Core Location tuned for driving. `.custom(provider)` takes any source.

## Background navigation

- **Android:** with `backgroundNavigation = true` (default), a foreground service keeps GPS and
  voice running and shows an ongoing notification with the next instruction and a Stop button.
  It needs location permission, and on Android 13+ the notification needs notification
  permission. Customize its texts by overriding the `remaply_navigation_*` string resources.
- **iOS:** add `location` (and `audio`, for voice) to `UIBackgroundModes`. The SDK enables
  background updates only when that mode is declared.

## Simulation

`navigateTo(..., simulate = true)` / `navigate(to:simulate:)` and `startNavigation(route, simulate = true)`
drive along the route instead of following the GPS, at `simulationSpeedMultiplier` times real
speed. Use it for development, demos, screenshots and app store review. No location permission
is needed, but you must pass an `origin` when the app has no location permission.
