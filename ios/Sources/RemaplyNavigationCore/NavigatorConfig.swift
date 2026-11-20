import Foundation

/// How a ``RemaplyNavigator`` behaves. Only ``routeProvider`` is required; the defaults suit driving.
public struct NavigatorConfig {
    /// Where routes come from, e.g. ``ValhallaRouteProvider/remaply(apiKey:baseURL:session:)``.
    public var routeProvider: any RouteProvider
    /// Default profile for requesting routes.
    public var profile: TravelProfile
    /// Distances that decide progress and leaving the route. `nil` picks ``NavigationThresholds/forProfile(_:)``.
    public var thresholds: NavigationThresholds?
    public var rerouting: ReroutingOptions
    public var voiceGuidance: VoiceGuidance
    public var locationSource: LocationSource
    /// Keeps GPS and voice running while the app is in the background. Takes effect only when the
    /// app declares the `location` background mode (`UIBackgroundModes` in Info.plist).
    public var backgroundNavigation: Bool
    /// How much faster than real time a simulated drive runs. 1 is real time.
    public var simulationSpeedMultiplier: Int

    public init(
        routeProvider: any RouteProvider,
        profile: TravelProfile = .car,
        thresholds: NavigationThresholds? = nil,
        rerouting: ReroutingOptions = ReroutingOptions(),
        voiceGuidance: VoiceGuidance = .system(),
        locationSource: LocationSource = .device,
        backgroundNavigation: Bool = true,
        simulationSpeedMultiplier: Int = 2
    ) {
        precondition(simulationSpeedMultiplier >= 1, "simulationSpeedMultiplier must be at least 1")
        self.routeProvider = routeProvider
        self.profile = profile
        self.thresholds = thresholds
        self.rerouting = rerouting
        self.voiceGuidance = voiceGuidance
        self.locationSource = locationSource
        self.backgroundNavigation = backgroundNavigation
        self.simulationSpeedMultiplier = simulationSpeedMultiplier
    }
}

/// Distances, in meters, that decide when a step, a stop or the route itself is left behind.
///
/// The defaults from ``forProfile(_:)`` match the Remaply web app's tuned values.
public struct NavigationThresholds: Hashable, Sendable {
    /// Distance from the route line beyond which the user is off route.
    public var offRoute: UInt16
    /// Distance from a stop at which it counts as reached.
    public var waypointReached: UInt16
    /// Distance from the end of a step at which the next step may begin.
    public var stepEnd: UInt16
    /// GPS fixes less accurate than this neither advance a step nor count as off route.
    public var minimumAccuracy: UInt16

    public init(offRoute: UInt16, waypointReached: UInt16, stepEnd: UInt16, minimumAccuracy: UInt16 = 30) {
        precondition(offRoute > 0 && waypointReached > 0 && stepEnd > 0 && minimumAccuracy > 0, "All thresholds must be positive")
        self.offRoute = offRoute
        self.waypointReached = waypointReached
        self.stepEnd = stepEnd
        self.minimumAccuracy = minimumAccuracy
    }

    /// Walking and cycling are slower and closer to the line, and paths are often missing from the
    /// map, so their distances are shorter.
    public static func forProfile(_ profile: TravelProfile) -> NavigationThresholds {
        switch profile {
        case .car, .motorcycle: NavigationThresholds(offRoute: 50, waypointReached: 100, stepEnd: 30)
        case .truck: NavigationThresholds(offRoute: 60, waypointReached: 100, stepEnd: 40)
        case .bicycle: NavigationThresholds(offRoute: 40, waypointReached: 40, stepEnd: 20)
        case .foot: NavigationThresholds(offRoute: 40, waypointReached: 30, stepEnd: 15)
        }
    }
}

/// What happens when the user leaves the route.
public struct ReroutingOptions: Hashable, Sendable {
    /// Fetch a new route automatically. When false, only ``NavigationEvent/offRoute`` is reported.
    public var enabled: Bool
    /// Minimum time between two reroute requests. Each one is a call to your routing server.
    public var minimumInterval: TimeInterval
    /// Minimum distance moved since the last reroute request before another one is made.
    public var minimumMovement: Double

    public init(enabled: Bool = true, minimumInterval: TimeInterval = 15, minimumMovement: Double = 50) {
        self.enabled = enabled
        self.minimumInterval = minimumInterval
        self.minimumMovement = minimumMovement
    }
}

/// Spoken turn-by-turn instructions.
public enum VoiceGuidance {
    /// No speech.
    case disabled
    /// The system voice (AVSpeechSynthesizer). `voiceLanguage` is a BCP 47 code such as "en-US"
    /// or "id-ID", and should match the language of the route instructions; `nil` uses the
    /// device language.
    case system(voiceLanguage: String? = nil)
    /// Your own speech, e.g. a cloud voice.
    case custom(any InstructionSpeaker)
}

/// Speaks instructions for ``VoiceGuidance/custom(_:)``.
public protocol InstructionSpeaker: AnyObject {
    /// Speak `text` now. `ssml` is the same instruction as SSML when the server provides it.
    /// Not called while muted.
    func speak(text: String, ssml: String?)
    /// Stop speaking and drop anything queued.
    func stop()
}

/// Where location fixes come from when not simulating.
public enum LocationSource {
    /// Core Location, tuned for navigation.
    case device
    /// Your own location, e.g. an external GNSS receiver or a replayed recording.
    case custom(any LocationProvider)
}

/// Supplies location fixes for ``LocationSource/custom(_:)``. Call `onLocation` on the main thread.
public protocol LocationProvider: AnyObject {
    /// The most recent fix, if any. Used as the origin of a route request.
    var lastLocation: UserLocation? { get }
    /// Set by the SDK; call it with every new fix.
    var onLocation: ((UserLocation) -> Void)? { get set }
    func startUpdating()
    func stopUpdating()
}
