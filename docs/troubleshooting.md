# Troubleshooting

## Both platforms

**`NoRouteFound` / "No route found"**
The points are not connected by roads for this profile: a point at sea, an island without a
ferry in the data, or a footpath-only place with the car profile. Try another profile or a point
on a road.

**`Http` 401 or 403**
The API key is wrong, lacks the routing scope, or is restricted to another app. For the Remaply
API, check that the key's Android package and SHA-1 (or iOS bundle ID) match the app, and
remember that debug and release builds are usually signed with different keys.

**The map is blank**
The style URL is unreachable or not a MapLibre style (Mapbox `mapbox://` styles do not work).
Open it in a browser: it must return JSON with `"version": 8`.

**Instructions are in the wrong language**
They come from the routing server. For Valhalla, pass `options = { "language": "id-ID" }` and
set the voice to the same language.

**Navigation never advances / says "off route" while on the road**
The GPS is less accurate than the minimum accuracy (default 30 m), typically indoors or in
emulators with a coarse location. Test outdoors, or with `simulate = true`.

## Android

**Gradle cannot find `com.github.winata-dev.navigation-sdk:...`**
Add `maven("https://jitpack.io")` to `dependencyResolutionManagement.repositories` in
`settings.gradle.kts`. A version that was never requested before takes a few minutes to build on
JitPack; sync again.

**"Dependency ... requires core library desugaring to be enabled"**
Add `isCoreLibraryDesugaringEnabled = true` and
`coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")` to your app module.

**"Module was compiled with an incompatible version of Kotlin"**
Use Kotlin 2.3 or newer in your app.

**`PermissionDenied` when starting**
Request `NavigationPermissions.required` first, or use `simulate = true`.

**No notification while navigating / stops in background**
On Android 13+, the user must grant notification permission. Without it, navigation still runs
but the notification is hidden. Some manufacturers' battery savers also kill foreground services;
see [dontkillmyapp.com](https://dontkillmyapp.com).

**Crash with `UnsatisfiedLinkError` in release builds**
R8 removed the JNA bindings the engine needs. The SDK ships keep rules that apply
automatically. If your build ignores library consumer rules, copy the rules from
`android/navigation-core/consumer-rules.pro` into your own `proguard-rules.pro`.

## iOS

**"Macro 'MapLibreSwiftMacrosImpl' must be enabled before it can be used"**
Click the error in Xcode and choose *Trust & Enable*. On CI, add `-skipMacroValidation`.

**Navigation pauses in the background**
Add `location` (and `audio`) to `UIBackgroundModes` in Info.plist.

**Location permission prompt never appears**
`NSLocationWhenInUseUsageDescription` is missing from Info.plist. iOS silently refuses to ask
without it.

**No voice**
Check the ringer/silent switch isn't hiding speech on a device. Also check that the voice for the
chosen `voiceLanguage` is installed (*Settings › Accessibility › Spoken Content › Voices*).
