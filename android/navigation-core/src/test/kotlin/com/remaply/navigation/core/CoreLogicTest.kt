package com.remaply.navigation.core

import com.remaply.navigation.core.internal.AppIdentity
import com.remaply.navigation.core.internal.HttpRouteFetcher
import com.remaply.navigation.core.internal.Json
import com.remaply.navigation.core.internal.enumByName
import com.remaply.navigation.core.internal.normalizeDegrees
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-Kotlin logic. Anything that calls Ferrostar's Rust core needs a device (the AAR only ships
// Android native libraries), so it is covered by the sample app instead.

class ThresholdsTest {
    @Test
    fun `profiles match the Remaply web app`() {
        assertEquals(NavigationThresholds(50, 100, 30), NavigationThresholds.forProfile(TravelProfile.CAR))
        assertEquals(NavigationThresholds(60, 100, 40), NavigationThresholds.forProfile(TravelProfile.TRUCK))
        assertEquals(NavigationThresholds(40, 40, 20), NavigationThresholds.forProfile(TravelProfile.BICYCLE))
        assertEquals(NavigationThresholds(40, 30, 15), NavigationThresholds.forProfile(TravelProfile.FOOT))
    }

    @Test
    fun `thresholds must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { NavigationThresholds(0, 10, 10) }
    }

    @Test
    fun `valhalla costing names`() {
        assertEquals("auto", ValhallaRouteProvider.defaultCosting(TravelProfile.CAR))
        assertEquals("pedestrian", ValhallaRouteProvider.defaultCosting(TravelProfile.FOOT))
        assertEquals("bike", GraphHopperRouteProvider.defaultProfileName(TravelProfile.BICYCLE))
    }

    @Test
    fun `coordinates are validated`() {
        assertThrows(IllegalArgumentException::class.java) { Coordinate(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Coordinate(0.0, -181.0) }
    }
}

class HttpErrorTest {
    @Test
    fun `remaply route_not_found means no route`() {
        val error = HttpRouteFetcher.httpError(404, """{"error":{"code":"route_not_found"}}""")
        assertTrue(error is NavigationException.NoRouteFound)
    }

    @Test
    fun `valhalla NoRoute means no route`() {
        val error = HttpRouteFetcher.httpError(400, """{"code":"NoRoute","message":"No path"}""")
        assertTrue(error is NavigationException.NoRouteFound)
    }

    @Test
    fun `other errors keep status and body`() {
        val error = HttpRouteFetcher.httpError(429, "slow down")
        assertTrue(error is NavigationException.Http)
        error as NavigationException.Http
        assertEquals(429, error.statusCode)
        assertEquals("slow down", error.body)
    }

    @Test
    fun `remaply credential errors name the reason`() {
        fun reason(code: Int, errorCode: String) =
            (HttpRouteFetcher.httpError(code, """{"error":{"code":"$errorCode","message":"x"}}""") as NavigationException.Unauthorized).reason

        assertEquals(NavigationException.Unauthorized.Reason.INVALID_API_KEY, reason(401, "invalid_api_key"))
        assertEquals(NavigationException.Unauthorized.Reason.SCOPE_NOT_ALLOWED, reason(403, "scope_not_allowed"))
        assertEquals(NavigationException.Unauthorized.Reason.API_KEY_RESTRICTED, reason(403, "api_key_restricted"))
    }

    @Test
    fun `other 401s are unauthorized with an unknown reason`() {
        val error = HttpRouteFetcher.httpError(401, "")
        assertTrue(error is NavigationException.Unauthorized)
        error as NavigationException.Unauthorized
        assertEquals(NavigationException.Unauthorized.Reason.UNKNOWN, error.reason)
        assertEquals(401, error.statusCode)
        assertNull(error.body)
    }

    @Test
    fun `a missing key fails without a request`() = runTest {
        val fetcher = HttpRouteFetcher(client = null, headers = emptyMap(), missingApiKey = true) {
            throw AssertionError("must not build a request")
        }
        val request = RouteRequest(
            origin = UserLocation(Coordinate(-18.14, 178.44), horizontalAccuracyMeters = 5.0),
            waypoints = listOf(Waypoint(Coordinate(-18.12, 178.45))),
            profile = TravelProfile.CAR,
            isReroute = false,
        )
        val error = runCatching { fetcher.fetch(request) }.exceptionOrNull()
        assertTrue(error is NavigationException.Unauthorized)
        error as NavigationException.Unauthorized
        assertEquals(NavigationException.Unauthorized.Reason.MISSING_API_KEY, error.reason)
        assertNull(error.statusCode)
    }
}

class JsonTest {
    @Test
    fun `empty options are omitted`() {
        assertNull(Json.encodeOrNull(emptyMap()))
    }

    @Test
    fun `nested values are encoded`() {
        val json = Json.encode(
            linkedMapOf(
                "units" to "kilometers",
                "costing_options" to mapOf("auto" to mapOf("use_tolls" to 0.5)),
                "avoid" to listOf(1, true, null),
            ),
        )
        assertEquals("""{"units":"kilometers","costing_options":{"auto":{"use_tolls":0.5}},"avoid":[1,true,null]}""", json)
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\n\"", Json.encode("a\"b\\c\n"))
    }
}

class MappingTest {
    @Test
    fun `degrees are normalized`() {
        assertEquals(10.0, normalizeDegrees(370.0), 1e-9)
        assertEquals(270.0, normalizeDegrees(-90.0), 1e-9)
        assertEquals(0.0, normalizeDegrees(360.0), 1e-9)
    }

    @Test
    fun `engine enum names map by name`() {
        assertEquals(ManeuverType.NEW_NAME, enumByName<ManeuverType>("NEW_NAME"))
        assertEquals(ManeuverModifier.SLIGHT_LEFT, enumByName<ManeuverModifier>("SLIGHT_LEFT"))
        assertNull(enumByName<ManeuverType>("SOMETHING_NEW"))
    }

    @Test
    fun `certificate fingerprint is upper case with colons`() {
        val sha1 = AppIdentity.sha1Hex("abc".toByteArray())
        assertEquals("A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D", sha1)
    }
}

class StyleUrlTest {
    @Test
    fun `style urls point at the styles endpoint`() {
        assertEquals(
            "https://api.remaply.com/v1/styles/default.json",
            RemaplyCredentials.styleUrl(RemaplyCredentials.DEFAULT_BASE_URL, RemaplyCredentials.DEFAULT_STYLE_ID),
        )
        assertEquals("https://staging.test/v1/styles/abc-123.json", RemaplyCredentials.styleUrl("https://staging.test/", " abc-123 "))
        assertEquals("https://a.test/v1/styles/my%20style%2Fx.json", RemaplyCredentials.styleUrl("https://a.test", "my style/x"))
    }

    @Test
    fun `a blank style id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RemaplyCredentials.styleUrl("https://a.test", " ") }
    }
}
