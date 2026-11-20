package com.remaply.navigation.core

import com.remaply.navigation.core.internal.AppIdentity
import com.remaply.navigation.core.internal.HttpRouteFetcher
import com.remaply.navigation.core.internal.Json
import com.remaply.navigation.core.internal.enumByName
import com.remaply.navigation.core.internal.normalizeDegrees
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
        val error = HttpRouteFetcher.httpError(401, "invalid key")
        assertTrue(error is NavigationException.Http)
        error as NavigationException.Http
        assertEquals(401, error.statusCode)
        assertEquals("invalid key", error.body)
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
