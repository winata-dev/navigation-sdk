import Foundation
import MapLibre
import RemaplyNavigationCore

/// Headers the map adds to its own requests (style, TileJSON, tiles, sprites, glyphs) to the
/// given hosts, and only to them, for map servers that need an API key. Requests to any other
/// host (another tile provider in the same style, a font server…) are sent unchanged, so the key
/// is not leaked to them.
///
/// Pass it to ``RemaplyNavigationView`` in a ``MapStyle``. For the Remaply map, use
/// ``MapStyle/remaply(_:styleID:)``, which sets this up. MapLibre's network configuration is shared by every map
/// in the app, so:
/// - once a view has used it, the headers keep being added to requests to those hosts for the
///   rest of the process, by any MapLibre map;
/// - it becomes the delegate of `MLNNetworkConfiguration.sharedManager`. A delegate your app set
///   before is kept and still called; one set after replaces this.
public struct MapAuthentication: Sendable, Hashable, CustomStringConvertible {
    /// Host names, lower case. Exact match; no wildcards.
    public let hosts: Set<String>
    let headers: [String: String]

    /// - Parameters:
    ///   - hosts: Host names, e.g. `api.example.com`. Case-insensitive.
    ///   - headers: E.g. `["Authorization": "Bearer …"]`.
    public init(hosts: Set<String>, headers: [String: String]) {
        self.hosts = Set(hosts.map { $0.lowercased() })
        self.headers = headers
    }

    /// The key and app identity from `credentials`, sent to the Remaply API's host: for a style of
    /// your own that uses Remaply tiles or sprites. For Remaply's own style, use
    /// ``MapStyle/remaply(_:styleID:)``.
    public static func remaply(_ credentials: RemaplyCredentials) -> MapAuthentication {
        MapAuthentication(hosts: credentials.host.map { [$0] } ?? [], headers: credentials.requestHeaders)
    }

    /// Never prints the headers, which usually hold a key.
    public var description: String { "MapAuthentication(hosts: \(hosts.sorted()))" }
}

/// Adds ``MapAuthentication`` headers to MapLibre's requests, through the one process-wide
/// `MLNNetworkConfiguration` delegate.
final class MapRequestHeaders: NSObject, MLNNetworkConfigurationDelegate, @unchecked Sendable {
    static let shared = MapRequestHeaders()

    private let lock = NSLock()
    private var headersByHost: [String: [String: String]] = [:]
    private var installed = false
    /// The app's own delegate, if it set one before us. Weak, as MapLibre holds it.
    private weak var previous: MLNNetworkConfigurationDelegate?

    func register(_ authentication: MapAuthentication) {
        add(authentication)
        let install = lock.withLock {
            defer { installed = true }
            return !installed
        }
        if install {
            let configuration = MLNNetworkConfiguration.sharedManager
            if configuration.delegate !== self {
                previous = configuration.delegate
                configuration.delegate = self
            }
        }
    }

    /// Without installing the delegate: for tests.
    func add(_ authentication: MapAuthentication) {
        lock.withLock {
            for host in authentication.hosts {
                headersByHost[host] = authentication.headers
            }
        }
    }

    func headers(for url: URL?) -> [String: String]? {
        guard let host = url?.host?.lowercased() else { return nil }
        return lock.withLock { headersByHost[host] }
    }

    /// For tests.
    func removeAll() {
        lock.withLock { headersByHost.removeAll() }
    }

    // Called by MapLibre on background threads, for every request.
    func willSend(_ request: NSMutableURLRequest) -> NSMutableURLRequest {
        let request = previous?.willSend?(request) ?? request
        for (name, value) in headers(for: request.url) ?? [:] {
            request.setValue(value, forHTTPHeaderField: name)
        }
        return request
    }

    // The other optional delegate methods go to the app's delegate, if it has them. Implementing
    // them here would change MapLibre's behavior for apps that do not.
    private static let forwarded: Set<Selector> = [
        NSSelectorFromString("sessionForNetworkConfiguration:"),
        NSSelectorFromString("didReceiveResponse:"),
    ]

    override func responds(to aSelector: Selector!) -> Bool {
        if Self.forwarded.contains(aSelector) { return previous?.responds(to: aSelector) ?? false }
        return super.responds(to: aSelector)
    }

    override func forwardingTarget(for aSelector: Selector!) -> Any? {
        if Self.forwarded.contains(aSelector) { return previous }
        return super.forwardingTarget(for: aSelector)
    }
}
