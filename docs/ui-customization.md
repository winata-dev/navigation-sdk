# Customizing the UI

`RemaplyNavigationView` is built to be used as-is, with a few layers of customization.

## Map style

`styleUrl` / `styleURL` takes any [MapLibre style](https://maplibre.org/maplibre-style-spec/).
Free options include [OpenFreeMap](https://openfreemap.org) (`https://tiles.openfreemap.org/styles/liberty`,
no key) and [Protomaps](https://protomaps.com), which you can self-host from one file. Hosted
options with free tiers include MapTiler and Stadia Maps. The style must credit its data, e.g.
"© OpenStreetMap contributors"; the view shows MapLibre's attribution button for that.

Use a style with good road contrast and road labels. Navigation styles usually dim POIs.

## Options

```kotlin
// Android
RemaplyNavigationView(
    navigator = navigator,
    styleUrl = styleUrl,
    options = NavigationViewOptions(
        showMuteButton = true,
        showZoomButtons = false,
        showRecenterButton = true,
        speedLimitSignage = SpeedLimitSignage.VIENNA_CONVENTION, // or MUTCD (US), or null to hide
        showStatusBanner = true,   // "Finding a new route…", "Simulated drive"
        keepScreenOn = true,
    ),
    onExit = { navController.popBackStack() }, // null hides the exit button
)
```

```swift
// iOS
RemaplyNavigationView(
    navigator: navigator,
    styleURL: styleURL,
    options: NavigationViewOptions(speedLimitSignage: .viennaConvention, showStatusBanner: true, keepScreenOn: true),
    onExit: { dismiss() }
)
```

On iOS, the mute, zoom and recenter buttons are always shown while navigating.

## Colors

By default the view follows your app's theme: Material 3 colors on Android, system colors
(including dark mode) on iOS. Override individual colors with `NavigationTheme`:

```kotlin
options = NavigationViewOptions(
    theme = NavigationTheme(
        instructionBackground = Color(0xFF0B3D2E),
        instructionPrimaryText = Color.White,
        instructionSecondaryText = Color(0xFFCCE8DD),
        exitButtonBackground = Color(0xFFD32F2F),
    ),
)
```

```swift
options: NavigationViewOptions(theme: NavigationTheme(
    instructionBackground: Color(red: 0.04, green: 0.24, blue: 0.18),
    instructionPrimaryText: .white
))
```

## Your own controls on top

`overlay` draws above the map and the navigation UI and receives the current state:

```kotlin
RemaplyNavigationView(navigator, styleUrl, overlay = { state ->
    if (state.isNavigating) {
        FloatingActionButton(
            onClick = { reportIncident(state.location) },
            modifier = Modifier.align(Alignment.CenterEnd), // overlay runs in a BoxScope
        ) { Icon(Icons.Default.Warning, null) }
    }
})
```

```swift
RemaplyNavigationView(navigator: navigator, styleURL: styleURL) { state in
    if state.isNavigating {
        Button("Report") { report(state.location) }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
    }
}
```

## Your own map layers

`mapContent` adds MapLibre layers, for example markers for stops or live incidents. These use the
MapLibre APIs directly: [maplibre-compose](https://github.com/maplibre/maplibre-compose) on
Android and [swiftui-dsl](https://github.com/maplibre/swiftui-dsl) on iOS.

```kotlin
RemaplyNavigationView(navigator, styleUrl, mapContent = { state ->
    val stops = rememberGeoJsonSource(GeoJsonData.Features(stopsFeatureCollection))
    CircleLayer(id = "stops", source = stops, color = const(Color.Red), radius = const(8.dp))
})
```

```swift
RemaplyNavigationView(navigator: navigator, styleURL: styleURL) {
    let stops = ShapeSource(identifier: "stops") { MLNPointFeature(coordinate: stop) }
    CircleStyleLayer(identifier: "stops", source: stops).radius(8).color(.systemRed)
}
```

## Text and translations

Texts the SDK adds:

- **Android:** redefine the string resources in your app (`values/strings.xml`, or
  `values-id/strings.xml` for Indonesian): `remaply_navigation_rerouting`,
  `remaply_navigation_off_route`, `remaply_navigation_simulating`,
  `remaply_navigation_arrived_title`, `remaply_navigation_done`, and the notification's
  `remaply_navigation_navigating`, `remaply_navigation_preparing`, `remaply_navigation_arrived`,
  `remaply_navigation_stop`, `remaply_navigation_remaining`.
- **iOS:** apps cannot override a package's strings, so translations live in the SDK. To add a
  language, add `ios/Sources/RemaplyNavigationUI/Resources/<code>.lproj/Localizable.strings`
  with the same keys as `en.lproj` and send a pull request.

Distances and times are formatted for the device's locale (km or miles, 12- or 24-hour clock).
Instruction texts come from the routing server.
