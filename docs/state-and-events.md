# State and events

The navigator publishes two things:

- **`state`**: a snapshot of the trip, always current. Use it to draw UI.
- **`events`**: things that just happened. Use them for side effects such as analytics, sounds,
  or showing a message.

## State

| Field | Meaning |
|---|---|
| `status` | `IDLE`, `ROUTING` (fetching the first route), `NAVIGATING`, `ARRIVED` |
| `route` | The route being followed. Changes on reroute |
| `location` / `snappedLocation` | Raw GPS fix / the same fix snapped onto the route |
| `instruction` | Next maneuver: `primaryText`, `secondaryText`, `maneuverType`, `maneuverModifier`, `fullText` |
| `progress` | `distanceToNextManeuver`, `distanceRemaining`, `durationRemaining`, `estimatedArrival` |
| `currentRoadName` | The road the user is on |
| `speedLimitKmh` (Android) / `speedLimit` (iOS) | When the routing server provides it |
| `remainingSteps`, `remainingWaypoints` | What's left of the trip |
| `isOffRoute`, `isRerouting`, `isSimulating`, `isMuted` | Flags |

Distances are in meters and durations in seconds.

```kotlin
// Android
val state by navigator.state.collectAsStateWithLifecycle()
Text("${state.progress?.distanceRemainingMeters?.div(1000)} km to go")
```

```swift
// iOS: RemaplyNavigator is an ObservableObject
@ObservedObject var navigator: RemaplyNavigator
Text("\(Int((navigator.state.progress?.distanceRemaining ?? 0) / 1000)) km to go")
```

## Events

| Event | When |
|---|---|
| `NavigationStarted(route, simulated)` | `startNavigation` / `navigateTo` began a trip |
| `StepAdvanced(instruction, stepsRemaining)` | The user moved on to the next maneuver |
| `OffRoute` / `BackOnRoute` | The user left / came back to the route |
| `Rerouted(route)` | A new route replaced the old one |
| `WaypointReached(waypoint, index)` | An intermediate stop was reached |
| `Arrived` | The destination was reached |
| `NavigationStopped` | The trip ended: from the app, the exit button, or the notification's Stop button |
| `Error(error)` | Something failed without stopping navigation, e.g. a reroute request |

```kotlin
// Android: collect before starting; events are not replayed
lifecycleScope.launch {
    navigator.events.collect { event ->
        when (event) {
            is NavigationEvent.Arrived -> analytics.log("arrived")
            is NavigationEvent.Error -> showSnackbar(event.error.message)
            else -> Unit
        }
    }
}
```

```swift
// iOS: async sequence (one per caller) or Combine publisher
.task {
    for await event in navigator.events() {
        if case .arrived = event { analytics.log("arrived") }
    }
}
```

## Errors

Methods that can fail throw `NavigationException` (Android) / `NavigationError` (iOS):

| Case | Typical cause | What to show |
|---|---|---|
| `NoRouteFound` | Points not connected by road (islands without ferries, off-road points) | "No route to this place" |
| `Http(statusCode, body)` | 401/403 wrong or restricted API key; 422 invalid points; 429 rate limit | Log the body; it explains the error |
| `Network` | No connection, timeout | "Check your connection" |
| `PermissionDenied` | Location not granted | Ask for permission, or explain in settings |
| `LocationUnavailable` | No GPS fix within 15 s (indoors, location off) | "Waiting for GPS…" |
| `InvalidResponse` | Server is not OSRM-compatible, or a bug in your custom provider | Log it |

## Building a fully custom UI

Depend on `navigation-core` / `RemaplyNavigationCore` only, and draw everything from `state`.
You will need a map view: MapLibre ([Android](https://github.com/maplibre/maplibre-compose),
[iOS](https://github.com/maplibre/swiftui-dsl)) with `state.route.geometry` as a line and
`state.snappedLocation` as the puck.
