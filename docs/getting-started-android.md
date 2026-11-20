# Getting started on Android

This guide adds turn-by-turn navigation to an existing Jetpack Compose app. It takes about 15
minutes.

**You need:** Android Studio, an app with `minSdk` 25 or higher using Jetpack Compose, a routing
server URL (see [Configuration › Routing servers](configuration.md#routing-servers)), and a
MapLibre style URL.

## 1. Add the JitPack repository

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

## 2. Add the dependency

In your app module's `build.gradle.kts`:

```kotlin
android {
    compileOptions {
        // Required: the navigation engine uses java.time.
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    // Core + UI. Use "navigation-core" instead if you build your own UI.
    implementation("com.github.winata-dev.navigation-sdk:navigation-ui:1.0.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

Replace `1.0.0` with the [latest release](https://github.com/winata-dev/navigation-sdk/releases).
The first time anyone requests a new version, JitPack builds it, which takes a few minutes. If
your Gradle sync times out, sync again.

> Requirements that come with the dependency: `compileSdk` 36, Kotlin 2.3 or newer (the
> libraries are compiled with Kotlin 2.3.20), and Android Gradle Plugin 8.13 or newer.

## 3. Permissions

The SDK's manifest already declares location, foreground-service and notification permissions.
You only have to **ask the user** before navigating with the real GPS:

```kotlin
val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
LaunchedEffect(Unit) { launcher.launch(NavigationPermissions.required) }
```

`NavigationPermissions.required` is precise and approximate location, plus notifications on
Android 13+, which show the ongoing navigation notification. Simulated drives need no permission.

## 4. Create a navigator

Create one `RemaplyNavigator` and keep it for as long as the navigation screen lives. A
`ViewModel` is a good owner because it survives rotation:

```kotlin
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    val navigator = RemaplyNavigator(
        application,
        NavigatorConfig(
            routeProvider = ValhallaRouteProvider("https://valhalla.example.com/route"),
            // Or, with the Remaply API:
            // routeProvider = ValhallaRouteProvider.remaply(application, apiKey = "rmp_pk_..."),
        ),
    )

    fun navigateTo(lat: Double, lon: Double) = viewModelScope.launch {
        try {
            navigator.navigateTo(Waypoint(lat, lon))
        } catch (e: NavigationException) {
            // Show e.message: no route, no connection, no location permission...
        }
    }

    override fun onCleared() = navigator.close()
}
```

`navigateTo` gets the device's location, asks the routing server for a route, and starts
following it. To let the user choose between routes first, call `requestRoutes(...)` and then
`startNavigation(route)`.

## 5. Show the navigation screen

```kotlin
@Composable
fun NavigationScreen(model: NavigationViewModel) {
    val state by model.navigator.state.collectAsStateWithLifecycle()

    if (state.status == NavigationState.Status.NAVIGATING || state.status == NavigationState.Status.ARRIVED) {
        RemaplyNavigationView(
            navigator = model.navigator,
            styleUrl = "https://tiles.openfreemap.org/styles/liberty",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // Your own screen: search, destination picker...
    }
}
```

That's it. The exit button and the "Done" button on arrival stop navigation by default; pass
`onExit = { ... }` to do something else, such as navigating back.

## 6. Try it without leaving your desk

Pass `simulate = true` to drive along the route without GPS:

```kotlin
navigator.navigateTo(
    waypoints = listOf(Waypoint(-18.1250, 178.4500)),
    simulate = true,
    origin = UserLocation(Coordinate(-18.1416, 178.4419), horizontalAccuracyMeters = 5.0),
)
```

## Release builds

The SDK ships its R8/ProGuard rules (`consumer-rules.pro`), so `isMinifyEnabled = true` works
without extra configuration.

## Next steps

- [Configuration](configuration.md): profiles, rerouting, voice language, background navigation
- [State and events](state-and-events.md): react to arrival, rerouting and errors
- [Customizing the UI](ui-customization.md): colors, buttons, your own overlays and map layers
