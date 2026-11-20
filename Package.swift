// swift-tools-version: 5.9
//
// Remaply Navigation SDK for iOS. Lives at the repository root so apps can add the package by its
// git URL. Sources are under ios/. The Android SDK is under android/.

import PackageDescription

let package = Package(
    name: "RemaplyNavigation",
    defaultLocalization: "en",
    platforms: [
        .iOS(.v16),
    ],
    products: [
        /// Routing, GPS tracking, progress, rerouting, voice guidance and events. No UI.
        .library(name: "RemaplyNavigationCore", targets: ["RemaplyNavigationCore"]),
        /// The ready-to-use SwiftUI navigation screen. Includes RemaplyNavigationCore.
        .library(name: "RemaplyNavigationUI", targets: ["RemaplyNavigationUI"]),
    ],
    dependencies: [
        // The navigation engine. Pinned exactly: Ferrostar is pre-1.0 and changes its API between
        // minor versions. Keep in sync with android/gradle/libs.versions.toml.
        .package(url: "https://github.com/stadiamaps/ferrostar.git", exact: "0.59.0"),
        .package(url: "https://github.com/maplibre/swiftui-dsl.git", from: "0.26.1"),
    ],
    targets: [
        .target(
            name: "RemaplyNavigationCore",
            dependencies: [
                "RemaplyFerrostarBridge",
                .product(name: "FerrostarCore", package: "ferrostar"),
            ],
            path: "ios/Sources/RemaplyNavigationCore"
        ),
        .target(
            name: "RemaplyNavigationUI",
            dependencies: [
                "RemaplyNavigationCore",
                "RemaplyFerrostarBridge",
                .product(name: "FerrostarCore", package: "ferrostar"),
                .product(name: "FerrostarSwiftUI", package: "ferrostar"),
                .product(name: "FerrostarMapLibreUI", package: "ferrostar"),
                .product(name: "MapLibreSwiftUI", package: "swiftui-dsl"),
                .product(name: "MapLibreSwiftDSL", package: "swiftui-dsl"),
            ],
            path: "ios/Sources/RemaplyNavigationUI",
            resources: [.process("Resources")]
        ),
        // Implementation detail: names Ferrostar types that the SDK's own types shadow.
        .target(
            name: "RemaplyFerrostarBridge",
            dependencies: [.product(name: "FerrostarCore", package: "ferrostar")],
            path: "ios/Sources/RemaplyFerrostarBridge"
        ),
        .testTarget(
            name: "RemaplyNavigationCoreTests",
            dependencies: ["RemaplyNavigationCore"],
            path: "ios/Tests/RemaplyNavigationCoreTests"
        ),
    ]
)
