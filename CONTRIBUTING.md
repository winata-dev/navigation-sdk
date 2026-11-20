# Contributing

Thanks for helping. Issues and pull requests are welcome.

## Ground rules

- **Keep both platforms in step.** A feature or public API change lands on Android and iOS in the
  same pull request, with the same names where the languages allow it.
- **No engine types in the public API.** Ferrostar and `uniffi` types stay behind
  `@InternalNavigationApi` (Android) or `package` access (iOS). See
  [docs/architecture.md](docs/architecture.md).
- **Document what you add.** Public declarations get KDoc / DocC comments. User-facing behaviour
  goes into `docs/`, and a line goes under *Unreleased* in `CHANGELOG.md`.

## Development

| | |
|---|---|
| Android | Open `android/` in Android Studio. `./gradlew :navigation-core:testReleaseUnitTest :sample:assembleRelease` |
| iOS | Open the repository folder in Xcode (it reads `Package.swift`), or the example project in `ios/Example/`. |

Before opening a pull request, run a simulated trip to arrival in both sample apps.

## Releasing

See [docs/deploying.md](docs/deploying.md).
