# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Each release is one git tag for both platforms.

## [Unreleased]

## [1.0.1] - 2026-11-20

First release (1.0.0 was tagged but its JitPack build failed because of a `jitpack.yml` bug and was never published). **Android is verified and supported. The iOS code is included but has not been
built or tested end to end yet; do not use it in production until a later release says so.**

### Added

- **Navigation Core:** `RemaplyNavigator` with route requests, GPS tracking and route snapping,
  progress, off-route detection and automatic rerouting, multi-stop trips, spoken instructions,
  speed limits, background navigation, simulated drives, `NavigationState` and
  `NavigationEvent` streams.
- **Route providers:** Valhalla (including the Remaply API preset with app-restriction
  headers), GraphHopper, and custom providers via `NavigationRoute.fromOsrm`.
- **Navigation UI:** `RemaplyNavigationView` for Jetpack Compose and SwiftUI, with maneuver
  banner, trip progress, speed limit sign, mute/zoom/recenter/exit controls, rerouting and
  simulation banner, and arrival card; color theming, custom overlays and map layers.
- Sample apps for both platforms.

### Engine

- Ferrostar 0.59.0, maplibre-compose 0.13.0, MapLibre swiftui-dsl 0.26.
