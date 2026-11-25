# Troubleshooting

## Both platforms

**`NoRouteFound` / "No route found"**
The points are not connected by roads for this profile: a point at sea, an island without a
ferry in the data, or a footpath-only place with the car profile. Try another profile or a point
on a road.

**`Unauthorized` (401 or 403)**
The error's `reason` says why:

- `MISSING_API_KEY` / `.missingAPIKey`: the key is blank, usually missing from this build's
  configuration. No request was sent.
- `INVALID_API_KEY` / `.invalidAPIKey`: wrong, revoked or expired key.
- `SCOPE_NOT_ALLOWED` / `.scopeNotAllowed`: the key lacks the `routing` scope.
- `API_KEY_RESTRICTED` / `.apiKeyRestricted`: the key is restricted to another app. Check that
  its Android package and SHA-1 (or iOS bundle ID) match the app. Debug and release builds are
  usually signed with different keys, and Play Store builds are signed with Play's app signing
  key, not your upload key.
- `UNKNOWN` / `.unknown`: another provider's 401 or 403. Read `body`.

**With the Remaply API, there are no speed limits and instructions are always in English**
The server has no `/v1/navigation/route`, so routes come from `/v1/route`, converted on the
device. See [When the server has no navigation endpoint](configuration.md#when-the-server-has-no-navigation-endpoint).

**The map is blank, or roads, labels or icons are missing, with the Remaply map**
The style, tiles and sprites need the key too. Pass `style = MapStyle.remaply(credentials)` /
`style: .remaply(credentials)` to the view, rather than the style URL alone, and allow the key to
use styles and tiles. With a style of your own that uses Remaply tiles, pass
`MapAuthentication.remaply(credentials)` in its `MapStyle`. See
[The Remaply map](ui-customization.md#the-remaply-map).

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
