import CoreLocation
import FerrostarCore
import FerrostarCoreFFI
import Foundation

/// The location provider FerrostarCore sees: the device (or a custom source), or a simulated
/// drive. The device provider is created lazily, so creating a navigator does not prompt for
/// location permission; the first route request or non-simulated start does.
final class NavigatorLocationProvider: NSObject, LocationProviding, LocationManagingDelegate {
    weak var delegate: LocationManagingDelegate?

    private let source: LocationSource
    private let allowsBackgroundUpdates: Bool
    private var deviceProvider: CoreLocationProvider?
    private var customProvider: CustomLocationAdapter?
    private let simulated = SimulatedLocationProvider()
    private(set) var isSimulating = false
    private var isUpdating = false

    init(source: LocationSource, allowsBackgroundUpdates: Bool, simulationSpeedMultiplier: Int) {
        self.source = source
        self.allowsBackgroundUpdates = allowsBackgroundUpdates
        super.init()
        simulated.warpFactor = UInt64(simulationSpeedMultiplier)
        simulated.delegate = self
    }

    private var live: LocationProviding {
        switch source {
        case .device:
            if let deviceProvider { return deviceProvider }
            let provider = CoreLocationProvider(
                activityType: .automotiveNavigation,
                allowBackgroundLocationUpdates: allowsBackgroundUpdates
            )
            provider.delegate = self
            deviceProvider = provider
            return provider
        case let .custom(provider):
            if let customProvider { return customProvider }
            let adapter = CustomLocationAdapter(provider)
            adapter.delegate = self
            customProvider = adapter
            return adapter
        }
    }

    private var current: LocationProviding { isSimulating ? simulated : live }

    var authorizationStatus: CLAuthorizationStatus {
        if isSimulating { return .authorizedWhenInUse }
        if case .custom = source { return .authorizedWhenInUse }
        return deviceProvider?.authorizationStatus ?? CLLocationManager().authorizationStatus
    }

    var lastLocation: FerrostarCoreFFI.UserLocation? { current.lastLocation }

    var lastHeading: Heading? { current.lastHeading }

    func startUpdating() {
        isUpdating = true
        current.startUpdating()
    }

    func stopUpdating() {
        isUpdating = false
        simulated.stopUpdating()
        if let deviceProvider { deviceProvider.stopUpdating() }
        customProvider?.stopUpdating()
    }

    func simulate(_ route: FerrostarCoreFFI.Route) throws {
        if !isSimulating { live.stopUpdating() }
        try simulated.setSimulatedRoute(route, resampleDistance: 10)
        isSimulating = true
        if isUpdating { simulated.startUpdating() }
    }

    func stopSimulating() {
        guard isSimulating else { return }
        simulated.stopUpdating()
        isSimulating = false
        if isUpdating { live.startUpdating() }
    }

    /// The live provider's last fix, or the next one within `timeout`.
    @MainActor
    func firstFix(timeout: TimeInterval) async throws -> UserLocation? {
        let provider = live
        if let location = provider.lastLocation { return location.userLocation }
        if provider.authorizationStatus == .denied || provider.authorizationStatus == .restricted {
            throw NavigationError.permissionDenied
        }
        let startedHere = !isUpdating || isSimulating
        if startedHere { provider.startUpdating() }
        defer { if startedHere && !(isUpdating && !isSimulating) { provider.stopUpdating() } }

        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try await Task.sleep(nanoseconds: 250_000_000)
            if let location = provider.lastLocation { return location.userLocation }
            if provider.authorizationStatus == .denied || provider.authorizationStatus == .restricted {
                throw NavigationError.permissionDenied
            }
        }
        return nil
    }

    // MARK: LocationManagingDelegate (from the sub-providers)

    func locationManager(_ manager: LocationProviding, didUpdateLocations locations: [FerrostarCoreFFI.UserLocation]) {
        guard manager === current else { return }
        delegate?.locationManager(self, didUpdateLocations: locations)
    }

    func locationManager(_ manager: LocationProviding, didUpdateHeading newHeading: Heading) {
        guard manager === current else { return }
        delegate?.locationManager(self, didUpdateHeading: newHeading)
    }

    func locationManager(_ manager: LocationProviding, didFailWithError error: Error) {
        guard manager === current else { return }
        delegate?.locationManager(self, didFailWithError: error)
    }
}

/// A public ``LocationProvider`` as Ferrostar's `LocationProviding`.
private final class CustomLocationAdapter: LocationProviding {
    weak var delegate: LocationManagingDelegate?
    private let provider: any LocationProvider

    init(_ provider: any LocationProvider) {
        self.provider = provider
        provider.onLocation = { [weak self] location in
            guard let self else { return }
            self.delegate?.locationManager(self, didUpdateLocations: [location.engineLocation])
        }
    }

    var authorizationStatus: CLAuthorizationStatus { .authorizedWhenInUse }
    var lastLocation: FerrostarCoreFFI.UserLocation? { provider.lastLocation?.engineLocation }
    var lastHeading: Heading? { nil }
    func startUpdating() { provider.startUpdating() }
    func stopUpdating() { provider.stopUpdating() }
}
