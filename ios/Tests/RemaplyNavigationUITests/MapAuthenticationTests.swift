import RemaplyNavigationCore
@testable import RemaplyNavigationUI
import XCTest

final class MapAuthenticationTests: XCTestCase {
    override func tearDown() {
        MapRequestHeaders.shared.removeAll()
    }

    func testHeadersAreAddedOnlyForTheGivenHosts() {
        let headers = MapRequestHeaders.shared
        headers.add(MapAuthentication(hosts: ["API.example.com"], headers: ["X-Api-Key": "k"]))

        func send(_ url: String) -> NSMutableURLRequest {
            headers.willSend(NSMutableURLRequest(url: URL(string: url)!))
        }

        XCTAssertEqual(send("https://api.example.com/v1/tiles/remaply/1/2/3.mvt").value(forHTTPHeaderField: "X-Api-Key"), "k")
        XCTAssertNil(send("https://tiles.openfreemap.org/planet").value(forHTTPHeaderField: "X-Api-Key"))
        XCTAssertNil(send("https://api.example.com.evil.test/x").value(forHTTPHeaderField: "X-Api-Key"))
    }

    func testRemaplyUsesTheAPIHost() {
        let auth = MapAuthentication.remaply(RemaplyCredentials(apiKey: "rmp_pk_live_abc"))
        XCTAssertEqual(auth.hosts, ["api.remaply.com"])
        XCTAssertEqual(auth.headers["X-Api-Key"], "rmp_pk_live_abc")
        XCTAssertFalse(auth.description.contains("rmp_pk_live_abc"))
    }

    func testRemaplyStyleUsesTheStylesEndpointWithTheKey() {
        let credentials = RemaplyCredentials(apiKey: "rmp_pk_live_abc")
        let style = MapStyle.remaply(credentials)
        XCTAssertEqual(style.url.absoluteString, "https://api.remaply.com/v1/styles/default.json")
        XCTAssertEqual(style.authentication, .remaply(credentials))

        let custom = MapStyle.remaply(credentials, styleID: "org-style")
        XCTAssertEqual(custom.url.absoluteString, "https://api.remaply.com/v1/styles/org-style.json")
    }

    func testPlainStylesHaveNoAuthentication() {
        let style = MapStyle(url: URL(string: "https://tiles.openfreemap.org/styles/liberty")!)
        XCTAssertNil(style.authentication)
    }
}
