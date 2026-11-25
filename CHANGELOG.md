# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Each release is one git tag for both platforms.

## [Unreleased]

## [1.1.0-beta.1] - 2026-11-25

Pre-release for testing the Remaply API integration in apps. Not yet run against the live API or
on a device; do not use it in production. iOS is still unverified, as in 1.0.1.

### Added

- `RemaplyCredentials`: one Remaply API key plus the app's identity, shared by routes
  (`ValhallaRouteProvider.remaply(credentials)`) and the map (`MapStyle.remaply(credentials)`).
- `MapStyle`, and a `style` parameter on `RemaplyNavigationView`: a style URL plus the headers
  its servers need. `MapStyle.remaply(credentials)` is the Remaply map
  (`/v1/styles/default.json`, or an organization's style id), with the key on the style,
  TileJSON, tiles and sprites, so a Remaply key alone covers both navigation and the map.
- `RemaplyCredentials.styleUrl()` / `styleURL()`, for MapLibre maps of your own.
- `ValhallaRouteProvider.remaply(...)` falls back to `GET /v1/route` when the server has no
  `POST /v1/navigation/route` (404 `not_found`), converting its routes on the device, and tries
  the navigation endpoint again every 15 minutes. Those routes have English instructions written
  by the SDK, no speed limits, and reroutes without the direction of travel.
- `MapAuthentication`: adds headers to the map's own requests (style, TileJSON, tiles, sprites)
  to the given hosts only. Also a `mapAuthentication` parameter next to `styleUrl` / `styleURL`
  on `RemaplyNavigationView`, which still works and means `MapStyle(styleUrl, mapAuthentication)`.
- `NavigationException.Unauthorized` / `NavigationError.unauthorized` for 401, 403 and a missing
  key, with the reason the Remaply API gives (`invalid_api_key`, `scope_not_allowed`,
  `api_key_restricted`).

### Changed

- A blank API key no longer crashes (`require` on Android, `precondition` on iOS). Route
  requests fail with `Unauthorized` (missing API key) instead.
- **Source-breaking:** 401 and 403 responses are reported as `Unauthorized` instead of `Http`.
  Exhaustive `switch` statements over `NavigationError` (iOS) need the new case.

### Fixed

- Android: the signing-certificate SHA-1 sent for key restrictions is now the current
  certificate. After a signing-key rotation it used to be the original one.

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
