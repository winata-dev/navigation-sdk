import FerrostarCore
import FerrostarMapLibreUI
import FerrostarSwiftUI
import MapLibreSwiftDSL
import MapLibreSwiftUI
import RemaplyNavigationCore
import SwiftUI
import UIKit

/// A complete navigation screen: the map with the route and the user's position, the next
/// maneuver, distance and time remaining, speed limit, and mute, zoom, recenter and exit buttons.
/// Adapts to portrait and landscape.
///
/// ```swift
/// RemaplyNavigationView(
///     navigator: navigator,
///     styleURL: URL(string: "https://tiles.example.com/styles/streets.json")!
/// )
/// ```
public struct RemaplyNavigationView<Overlay: View>: View {
    @ObservedObject private var navigator: RemaplyNavigator
    private let styleURL: URL
    private let options: NavigationViewOptions
    private let onExit: (() -> Void)?
    private let overlay: (RemaplyNavigationCore.NavigationState) -> Overlay
    private let mapContent: [StyleLayerDefinition]

    @State private var camera: MapViewCamera = .automotiveNavigation()

    /// - Parameters:
    ///   - styleURL: A MapLibre style JSON URL. Must include the attribution your tiles require
    ///     (e.g. "© OpenStreetMap contributors").
    ///   - onExit: Called when the user taps the exit button. Defaults to stopping navigation.
    ///   - overlay: Your own UI on top of the map. Receives the current state.
    ///   - mapContent: Your own MapLibre layers, drawn above the base map.
    public init(
        navigator: RemaplyNavigator,
        styleURL: URL,
        options: NavigationViewOptions = NavigationViewOptions(),
        onExit: (() -> Void)? = nil,
        @ViewBuilder overlay: @escaping (RemaplyNavigationCore.NavigationState) -> Overlay,
        @MapViewContentBuilder mapContent: () -> [StyleLayerDefinition] = { [] }
    ) {
        self.navigator = navigator
        self.styleURL = styleURL
        self.options = options
        self.onExit = onExit
        self.overlay = overlay
        self.mapContent = mapContent()
    }

    public var body: some View {
        let state = navigator.state

        DynamicallyOrientingNavigationView(
            styleURL: styleURL,
            camera: $camera,
            navigationState: navigator.engineState,
            isMuted: state.isMuted,
            onTapMute: { navigator.setMuted(!navigator.state.isMuted) },
            onTapExit: { (onExit ?? { navigator.stopNavigation() })() },
            makeMapContent: { mapContent }
        )
        .navigationSpeedLimit(
            speedLimit: options.speedLimitSignage == nil ? nil : state.speedLimit,
            speedLimitStyle: options.speedLimitSignage == .mutcd ? .mutcdStyle : .viennaConvention
        )
        .navigationViewInnerGrid(topCenter: {
            if options.showStatusBanner {
                StatusBanner(state: state, background: options.theme.statusBannerBackground)
            }
        })
        .navigationUITheme(options.theme.ferrostarTheme)
        // Ferrostar hides its controls once the trip is complete; without this card the user
        // would be left on a bare map.
        .overlay(alignment: .bottom) {
            if state.status == .arrived {
                ArrivalCard { (onExit ?? { navigator.stopNavigation() })() }
            }
        }
        .overlay { overlay(state) }
        .onChange(of: state.status) { status in
            if status == .navigating { camera = .automotiveNavigation() }
            updateIdleTimer(navigating: status == .navigating)
        }
        .onAppear { updateIdleTimer(navigating: state.isNavigating) }
        .onDisappear { updateIdleTimer(navigating: false) }
    }

    private func updateIdleTimer(navigating: Bool) {
        guard options.keepScreenOn else { return }
        UIApplication.shared.isIdleTimerDisabled = navigating
    }
}

public extension RemaplyNavigationView where Overlay == EmptyView {
    init(
        navigator: RemaplyNavigator,
        styleURL: URL,
        options: NavigationViewOptions = NavigationViewOptions(),
        onExit: (() -> Void)? = nil,
        @MapViewContentBuilder mapContent: () -> [StyleLayerDefinition] = { [] }
    ) {
        self.init(navigator: navigator, styleURL: styleURL, options: options, onExit: onExit, overlay: { _ in EmptyView() }, mapContent: mapContent)
    }
}

private struct ArrivalCard: View {
    let onDone: () -> Void

    var body: some View {
        HStack {
            Text("remaply.navigation.arrived", bundle: .module)
                .font(.headline)
            Spacer()
            Button(action: onDone) {
                Text("remaply.navigation.done", bundle: .module).bold()
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
        .padding()
    }
}

/// "Finding a new route…" while rerouting, "Simulated drive" while simulating.
private struct StatusBanner: View {
    let state: RemaplyNavigationCore.NavigationState
    let background: Color?

    var body: some View {
        if state.isNavigating {
            if state.isRerouting {
                banner(.loading, "remaply.navigation.rerouting")
            } else if state.isOffRoute {
                banner(.error, "remaply.navigation.offRoute")
            } else if state.isSimulating {
                banner(.info, "remaply.navigation.simulating")
            }
        }
    }

    private func banner(_ severity: NavigationUIBanner<Text>.Severity, _ key: String) -> some View {
        NavigationUIBanner(severity: severity, backgroundColor: background ?? Color(.systemBackground)) {
            Text(LocalizedStringKey(key), bundle: .module)
        }
    }
}

extension NavigationTheme {
    var ferrostarTheme: any NavigationUITheme {
        var primary = DefaultInstructionRowTheme()
        var secondary = DefaultSecondaryInstructionRowTheme()
        var progress = DefaultTripProgressViewTheme()
        progress.style = .informational
        if let instructionBackground { primary.backgroundColor = instructionBackground }
        if let instructionPrimaryText {
            primary.distanceColor = instructionPrimaryText
            primary.iconTintColor = instructionPrimaryText
            secondary.distanceColor = instructionPrimaryText
            secondary.iconTintColor = instructionPrimaryText
        }
        if let instructionSecondaryText {
            primary.instructionColor = instructionSecondaryText
            secondary.instructionColor = instructionSecondaryText
        }
        if let progressBackground { progress.backgroundColor = progressBackground }
        if let progressText {
            progress.measurementColor = progressText
            progress.secondaryColor = progressText.opacity(0.7)
        }
        return DefaultNavigationUITheme(primaryInstructionsRow: primary, secondaryInstructionsRow: secondary, tripProgress: progress)
    }
}
