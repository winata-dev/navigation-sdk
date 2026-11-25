import Foundation
import RemaplyNavigationCore

/// The map's look: a MapLibre style URL, plus the headers its servers need, if any. Pass it to
/// ``RemaplyNavigationView``.
///
/// For the Remaply map, ``remaply(_:styleID:)`` is all you need: the style, TileJSON, tiles and
/// sprites all come from the Remaply API, and they all get the key.
public struct MapStyle: Sendable, Hashable {
    /// A MapLibre style JSON URL.
    public let url: URL
    /// Headers for the map servers that need an API key.
    public let authentication: MapAuthentication?

    /// - Parameters:
    ///   - url: A MapLibre style JSON URL. Must include the attribution your tiles require
    ///     (e.g. "© OpenStreetMap contributors").
    ///   - authentication: Headers for map servers that need an API key.
    public init(url: URL, authentication: MapAuthentication? = nil) {
        self.url = url
        self.authentication = authentication
    }

    /// The Remaply map (`GET /v1/styles/{styleID}.json`), with the key and app identity from
    /// `credentials` added to every request the map makes to the Remaply API: the style, then the
    /// TileJSON (`/v1/tiles/{tileset}.json`), tiles (`/v1/tiles/{tileset}/{z}/{x}/{y}.mvt`) and
    /// sprites (`/v1/sprites/…`) the style names. Requests to other hosts get no key.
    ///
    /// With a blank key the map stays empty (the API answers 401); routes from the same
    /// credentials fail with `NavigationError.unauthorized`.
    ///
    /// - Parameter styleID: `RemaplyCredentials.defaultStyleID`, Remaply's map, or the id of your
    ///   organization's style from Map Studio.
    public static func remaply(
        _ credentials: RemaplyCredentials,
        styleID: String = RemaplyCredentials.defaultStyleID
    ) -> MapStyle {
        MapStyle(url: credentials.styleURL(styleID: styleID), authentication: .remaply(credentials))
    }
}
