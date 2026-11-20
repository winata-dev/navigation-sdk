# Architecture

## Overview

```
          Your app
             │  public API: RemaplyNavigator, NavigatorConfig, NavigationState,
             │              NavigationEvent, RouteProvider, RemaplyNavigationView
┌────────────┴──────────────────────────────────────────────┐
│ navigation-ui / RemaplyNavigationUI                        │
│   RemaplyNavigationView, options, theme, status banner,    │
│   arrival card                                             │
├────────────────────────────────────────────────────────────┤
│ navigation-core / RemaplyNavigationCore                    │
│   RemaplyNavigator ── route providers (Valhalla, GraphHopper, custom)
│        │          ── location (device / custom / simulated)
│        │          ── voice (system TTS / custom)
│        │          ── state mapping + event detection
│        ▼                                                    │
│   engine glue (internal): Ferrostar ⇄ SDK type conversions │
├────────────────────────────────────────────────────────────┤
│ Ferrostar (Rust core via UniFFI + platform layers)         │
│ MapLibre Native (via maplibre-compose / swiftui-dsl)       │
└────────────────────────────────────────────────────────────┘
```

Two native SDKs share one design and one engine. Ferrostar's navigation logic is written in Rust
and shipped as Kotlin and Swift bindings, so the hard part (route snapping, step advancing,
deviation detection, instruction triggering) is the same code on both platforms. What remains
per platform is the UI, GPS, text-to-speech, and background execution, which are native APIs
anyway.

## Decisions

### Native per platform, not Kotlin Multiplatform

- Ferrostar already shares the logic, so KMP would share only thin glue.
- iOS developers expect a Swift package with SwiftUI views. A KMP framework would put a Kotlin
  runtime and Objective-C-style headers in front of them.
- Ferrostar does not support KMP; its UI layers are Compose and SwiftUI.

### Ferrostar is an implementation detail

Ferrostar is pre-1.0 and changes its API between minor versions. No Ferrostar (or `uniffi`) type
appears in the SDK's public API:

- The SDK has its own `Coordinate`, `Waypoint`, `UserLocation`, `NavigationState`,
  `NavigationRoute`, `Instruction`, and so on.
- All conversions live in one file per platform (`internal/FerrostarMapping.kt`,
  `Internal/FerrostarMapping.swift`).
- On Android, the UI module reaches the engine through members marked `@InternalNavigationApi`.
  That opt-in annotation fails the build with an error if an app touches them. On iOS, the
  same members use Swift's `package` access level, which apps cannot see.
- `explicitApi()` is on for the Android modules, so nothing becomes public by accident.

The one deliberate exception is `mapContent`, which takes MapLibre layers. MapLibre is stable,
and drawing custom layers has no reasonable abstraction.

### The SDK makes the HTTP calls, Ferrostar parses

Ferrostar's built-in route adapters cannot add headers (API keys, app restrictions), and its core
drops the error body. The SDK uses Ferrostar's request generator and OSRM parser, but sends the
request itself with OkHttp / URLSession. Ferrostar receives routes through its
`CustomRouteProvider` hook, so reroutes also go through the app's `RouteProvider`.

### Defaults come from the Remaply web app

Thresholds per profile, the step-advance strategy (end of step, *or* clearly away from the step
while still on the route, which avoids getting stuck at U-turn stops), the 30 m accuracy floor,
and the 15 s reroute interval all match `remaply-web-app`'s tuned Ferrostar config. Web and
mobile therefore behave the same.

### Distribution: JitPack and SwiftPM from one tag

Both platforms need no hosted artifact repository: SwiftPM reads git tags, and JitPack builds
Android from them. See [deploying.md](deploying.md), including how to move to Maven Central.

## Data flow during navigation

1. A GPS fix (or a simulated one) reaches Ferrostar's core through its location provider.
2. The Rust core updates the trip state: snapped location, current step, progress, and deviation.
3. The platform layer of Ferrostar (`FerrostarCore`) speaks instructions that are due and, on
   deviation, asks for new routes, throttled by the rerouting options.
4. `RemaplyNavigator` maps each engine state to a `NavigationState`, compares it with the previous
   one to emit `NavigationEvent`s, and publishes both.
5. `RemaplyNavigationView` draws Ferrostar's map and overlays from the same engine state, plus
   the SDK's own banner and arrival card from `NavigationState`.

## Where things live

| Concern | Android | iOS |
|---|---|---|
| Public models | `navigation-core/.../Models.kt`, `NavigationState.kt` | `Models.swift`, `NavigationState.swift` |
| Navigator | `RemaplyNavigator.kt` | `RemaplyNavigator.swift` |
| Route providers | `RouteProvider.kt`, `internal/HttpRouteFetcher.kt` | `RouteProvider.swift` |
| Engine glue | `internal/FerrostarMapping.kt`, `internal/EngineAdapters.kt` | `Internal/*.swift` |
| Background | `internal/BackgroundNavigation.kt` (foreground service) | `UIBackgroundModes` + Core Location |
| UI | `navigation-ui/.../RemaplyNavigationView.kt` | `RemaplyNavigationUI/RemaplyNavigationView.swift` |

## Not in v1

CarPlay and Android Auto (Ferrostar has building blocks for both), offline maps and routing,
a route preview screen with alternatives, lane guidance in the UI, and traffic. The architecture
leaves room for each. For example, offline routing is a `RouteProvider`, and car screens would be
a third module next to `navigation-ui`.
