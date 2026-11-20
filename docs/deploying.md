# Releasing and deploying the SDK

This page is for the people who maintain the SDK. App developers who use it only need the
[getting started guides](../README.md#documentation).

Both platforms are released together from a single **git tag**:

```
git tag 1.2.0 ──push──► GitHub
                          ├─ Swift Package Manager reads the tag directly (iOS: nothing to upload)
                          ├─ JitPack builds the Android libraries from the tag on first request
                          └─ GitHub Actions (release.yml) tests both, warms up JitPack, writes the GitHub release
```

There is no artifact repository to run, no signing keys and no accounts to pay for.

## One-time setup

1. **Publish the repository on GitHub as public**, at `github.com/winata-dev/navigation-sdk`.
   If the owner or name differs, search and replace `winata-dev/navigation-sdk` across the repo
   (docs, `android/*/build.gradle.kts` POM URLs, `README.md`).

   ```bash
   git remote add origin git@github.com:winata-dev/navigation-sdk.git
   git push -u origin main
   ```

2. **Allow GitHub Actions to create releases:** *Settings › Actions › General › Workflow
   permissions › Read and write permissions*.
3. **JitPack:** nothing to do. Optionally sign in at [jitpack.io](https://jitpack.io) with GitHub
   to see build logs.
4. **Protect `main`** (recommended): *Settings › Branches*, require the CI workflow to pass.

## Versioning

[Semantic versioning](https://semver.org): `MAJOR.MINOR.PATCH`, tags **without** a `v` prefix
(`1.2.0`, not `v1.2.0`), because SwiftPM resolves plain version tags.

- **PATCH**: bug fixes, no API change.
- **MINOR**: new features; existing code keeps compiling.
- **MAJOR**: anything that breaks an app's code. Upgrading Ferrostar is usually *not* a major
  change, because Ferrostar types are not part of the SDK's API.
- **Pre-releases**: `1.3.0-beta.1`. These are marked as pre-releases on GitHub, and SwiftPM
  does not pick them for `from: "1.2.0"`.

## Releasing a version

1. Make sure `main` is green in CI.
2. Update the version in **`android/gradle.properties`**:
   ```properties
   sdkVersion=1.2.0
   ```
3. In **`CHANGELOG.md`**, move the *Unreleased* entries under a new `## [1.2.0] - YYYY-MM-DD`
   heading.
4. Update the version in the install snippets of `docs/getting-started-android.md` and
   `docs/getting-started-ios.md` if the major version changed.
5. Commit, tag and push:
   ```bash
   git commit -am "Release 1.2.0"
   git tag 1.2.0
   git push origin main 1.2.0
   ```
6. Watch the **Release** workflow in the *Actions* tab. It:
   - checks that the tag matches `sdkVersion` and has a CHANGELOG section,
   - builds and tests Android and iOS,
   - requests the version from JitPack so it gets built (up to ~10 minutes),
   - creates the GitHub release with the CHANGELOG section as notes.
7. Check that the release works the way apps get it:
   - Android: [`https://jitpack.io/#winata-dev/navigation-sdk/1.2.0`](https://jitpack.io/#winata-dev/navigation-sdk)
     shows a green build. In any test app, depend on
     `com.github.winata-dev.navigation-sdk:navigation-ui:1.2.0`.
   - iOS: in any test app, *File › Packages › Update to Latest Package Versions*.

### If something goes wrong

- **The Release workflow failed before "Publish":** nothing was published. Fix the problem, then
  move the tag: `git tag -d 1.2.0 && git push origin :1.2.0`, then tag and push again.
- **JitPack failed:** open the log at `https://jitpack.io/#winata-dev/navigation-sdk/1.2.0`.
  JitPack runs the commands in [`jitpack.yml`](../jitpack.yml), which you can reproduce locally:
  ```bash
  cd android && GROUP=com.github.winata-dev.navigation-sdk VERSION=1.2.0 \
    ./gradlew :navigation-core:publishToMavenLocal :navigation-ui:publishToMavenLocal --no-configuration-cache
  ```
  After fixing, delete the tag as above, press *Delete* on the JitPack build page (JitPack caches
  failures), and release again.
- **A broken version is already public:** never move a tag that apps may have resolved. SwiftPM
  and Gradle cache it. Release a new patch version instead.

## Building locally

| | Command |
|---|---|
| Android libraries + sample | `cd android && ./gradlew assembleRelease` |
| Android unit tests | `cd android && ./gradlew :navigation-core:testReleaseUnitTest` |
| Install Android sample on a device | `cd android && ./gradlew :sample:installDebug` |
| iOS package + tests | `xcodebuild test -scheme RemaplyNavigation-Package -destination 'platform=iOS Simulator,name=iPhone 17'` |
| iOS example | open `ios/Example/RemaplyNavigationExample.xcodeproj` and run |

Requirements: JDK 17, Android SDK 36, and Xcode 16 or newer. The first iOS build in Xcode asks you to
*Trust & Enable* MapLibre's macros. On the command line, add `-skipMacroValidation`.

The Android sample reads optional settings from `android/local.properties`:

```properties
sample.styleUrl=https://tiles.openfreemap.org/styles/liberty
sample.valhallaUrl=https://valhalla.example.com/route
sample.remaplyApiKey=rmp_pk_...
```

## Upgrading Ferrostar (or MapLibre)

The engine version is pinned in two places, and they must match:

- `android/gradle/libs.versions.toml` → `ferrostar = "0.59.0"`
- `Package.swift` → `.package(url: "https://github.com/stadiamaps/ferrostar.git", exact: "0.59.0")`

Steps:

1. Read the [Ferrostar release notes](https://github.com/stadiamaps/ferrostar/releases) for
   every version you skip. It is pre-1.0, so minor versions may break its API.
2. Bump both versions (and `maplibre-compose` / `swiftui-dsl` if Ferrostar's own requirements moved).
3. Build. Compile errors are confined to the engine glue: `internal/` on Android,
   `Internal/` and `RemaplyNavigator.swift` on iOS, plus `RemaplyNavigationView` in the UI
   modules. Public types should not need to change.
4. Run both sample apps through a simulated trip to arrival, including a reroute (simulate
   from a point off the road).
5. Release as a MINOR version, noting the engine version in the CHANGELOG.

## Moving to Maven Central later

JitPack was chosen because it needs no domain verification. If you later control DNS for
`remaply.com`, Maven Central gives apps faster, more reliable downloads with no extra repository
line:

1. Register the `com.remaply` namespace at [central.sonatype.com](https://central.sonatype.com),
   verified with a DNS TXT record.
2. Create a GPG signing key and add it, with a Central user token, to GitHub secrets.
3. Add the [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
   plugin to both library modules and a publish step to `release.yml`.
4. Keep publishing to JitPack for a few releases, and announce the new coordinates
   (`com.remaply.navigation:navigation-ui`).

## Deploying a routing server for production

The SDK works with any compatible server (see [Configuration](configuration.md#routing-servers)).
The simplest self-hosted setup is Valhalla in Docker, fed an OpenStreetMap extract:

```bash
docker run -d --name valhalla -p 8002:8002 \
  -v $PWD/valhalla:/custom_files \
  -e tile_urls=https://download.geofabrik.de/australia-oceania/fiji-latest.osm.pbf \
  ghcr.io/valhalla/valhalla-scripted:3.9.2
```

The first start builds the routing graph (minutes for a country, hours for a continent). Then
point the SDK at `https://<your-host>/route`, behind HTTPS and, ideally, an API gateway with
keys and rate limits. Rebuild the graph periodically to pick up map edits.
