import SwiftUI

/// What ``RemaplyNavigationView`` shows.
public struct NavigationViewOptions {
    /// Style of the speed limit sign, or nil to hide it. Needs speed limits from the routing server.
    public var speedLimitSignage: SpeedLimitSignage?
    /// Banner while a new route is fetched, and a "Simulated drive" label while simulating.
    public var showStatusBanner: Bool
    /// Keeps the screen on while navigating.
    public var keepScreenOn: Bool
    /// Colors. Anything left nil uses the system colors (light and dark mode).
    public var theme: NavigationTheme

    public init(
        speedLimitSignage: SpeedLimitSignage? = .viennaConvention,
        showStatusBanner: Bool = true,
        keepScreenOn: Bool = true,
        theme: NavigationTheme = NavigationTheme()
    ) {
        self.speedLimitSignage = speedLimitSignage
        self.showStatusBanner = showStatusBanner
        self.keepScreenOn = keepScreenOn
        self.theme = theme
    }
}

public enum SpeedLimitSignage: Hashable, Sendable {
    /// Round red-bordered sign, used in most of the world.
    case viennaConvention
    /// Rectangular "SPEED LIMIT" sign, used in the United States.
    case mutcd
}

/// Color overrides for the navigation UI. Anything left nil uses the system colors.
public struct NavigationTheme {
    /// Background of the instruction banner at the top.
    public var instructionBackground: Color?
    /// Distance and maneuver icon in the instruction banner.
    public var instructionPrimaryText: Color?
    /// Road name in the instruction banner.
    public var instructionSecondaryText: Color?
    /// Background of the distance / ETA bar at the bottom.
    public var progressBackground: Color?
    public var progressText: Color?
    /// The rerouting / simulation banner.
    public var statusBannerBackground: Color?

    public init(
        instructionBackground: Color? = nil,
        instructionPrimaryText: Color? = nil,
        instructionSecondaryText: Color? = nil,
        progressBackground: Color? = nil,
        progressText: Color? = nil,
        statusBannerBackground: Color? = nil
    ) {
        self.instructionBackground = instructionBackground
        self.instructionPrimaryText = instructionPrimaryText
        self.instructionSecondaryText = instructionSecondaryText
        self.progressBackground = progressBackground
        self.progressText = progressText
        self.statusBannerBackground = statusBannerBackground
    }
}
