package com.remaply.navigation.core

import com.remaply.navigation.core.internal.Polyline6
import com.remaply.navigation.core.internal.RemaplyRouteFallback
import com.remaply.navigation.core.internal.RemaplyToOsrm
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same answer as in the iOS tests, where the engine also parses the result. */
internal const val REMAPLY_ROUTE = """
{"routes":[{"distance_m":601,"duration_s":75,"geometry":"nxgra@gjfjsINzz@Rjz@scBfE_|BfE","legs":[{"distance_m":601,"duration_s":75,"steps":[
 {"type":"depart","instruction":"Drive west on Nacuva Street.","distance_m":201,"duration_s":30,"road_names":["Nacuva Street"],"location":{"lat":-18.141592,"lon":178.441908}},
 {"type":"turn_right","instruction":"Turn right onto Milverton Road.","distance_m":400,"duration_s":45,"road_names":["Milverton Road"],"location":{"lat":-18.14161,"lon":178.44}},
 {"type":"arrive","instruction":"You have arrived at your destination.","distance_m":0,"duration_s":0,"road_names":[],"location":{"lat":-18.138,"lon":178.4398}}
]}]}]}
"""

class RemaplyRouteFallbackTest {
    private val request = RouteRequest(
        origin = UserLocation(Coordinate(-18.141592, 178.441908), horizontalAccuracyMeters = 5.0),
        waypoints = listOf(Waypoint(Coordinate(-18.138, 178.4398))),
        profile = TravelProfile.CAR,
        isReroute = false,
    )

    @Test
    fun `only a missing endpoint falls back, never a missing route`() {
        assertTrue(RemaplyRouteFallback.isMissingEndpoint(404, """{"error":{"code":"not_found","message":"Not Found"}}"""))
        assertTrue(RemaplyRouteFallback.isMissingEndpoint(405, ""))
        assertFalse(RemaplyRouteFallback.isMissingEndpoint(404, """{"error":{"code":"route_not_found"}}"""))
        assertFalse(RemaplyRouteFallback.isMissingEndpoint(401, """{"error":{"code":"invalid_api_key"}}"""))
        assertFalse(RemaplyRouteFallback.isMissingEndpoint(503, ""))
    }

    @Test
    fun `the fallback is used for a while, then the navigation endpoint is tried again`() {
        val fallback = RemaplyRouteFallback("https://api.remaply.com")
        assertFalse(fallback.isActive(now = 0))
        fallback.activate(now = 0)
        assertTrue(fallback.isActive(now = 60L * 1_000_000_000))
        assertFalse(fallback.isActive(now = 16L * 60 * 1_000_000_000))
    }

    @Test
    fun `the url asks for steps and a polyline6 line`() {
        val url = RemaplyRouteFallback("https://api.remaply.com/").url(request)
        assertEquals("/v1/route", url.encodedPath)
        assertEquals("-18.141592,178.441908;-18.138000,178.439800", url.queryParameter("points"))
        assertEquals("car", url.queryParameter("profile"))
        assertEquals("true", url.queryParameter("steps"))
        assertEquals("polyline6", url.queryParameter("geometry"))
    }

    @Test
    fun `polylines round trip`() {
        val line = Polyline6.decode("nxgra@gjfjsINzz@Rjz@scBfE_|BfE")
        assertEquals(5, line.size)
        assertArrayEquals(intArrayOf(-18141592, 178441908), line[0])
        assertEquals("nxgra@gjfjsINzz@Rjz@scBfE_|BfE", Polyline6.encode(line))
    }

    @Test
    fun `steps get their part of the line, maneuvers and instructions`() {
        val osrm = JSONObject(
            RemaplyToOsrm.convert(REMAPLY_ROUTE, listOf(request.origin.coordinate, request.waypoints[0].coordinate), TravelProfile.CAR),
        )
        assertEquals("Ok", osrm.getString("code"))
        assertEquals(2, osrm.getJSONArray("waypoints").length())
        val route = osrm.getJSONArray("routes").getJSONObject(0)
        assertEquals(601.0, route.getDouble("distance"), 0.0)
        val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        assertEquals(3, steps.length())

        // Each step's line starts where the previous one ends.
        val lines = (0 until 3).map { Polyline6.decode(steps.getJSONObject(it).getString("geometry")) }
        assertEquals(3, lines[0].size)
        assertEquals(3, lines[1].size)
        assertArrayEquals(lines[0].last(), lines[1].first())
        assertEquals(2, lines[2].size)

        val depart = steps.getJSONObject(0)
        assertEquals("depart", depart.getJSONObject("maneuver").getString("type"))
        assertEquals("Nacuva Street", depart.getString("name"))
        val turnRight = steps.getJSONObject(1).getJSONObject("maneuver")
        assertEquals("turn", turnRight.getString("type"))
        assertEquals("right", turnRight.getString("modifier"))
        assertEquals(270.0, depart.getJSONObject("maneuver").getDouble("bearing_after"), 1.0)
        assertEquals(357.0, turnRight.getDouble("bearing_after"), 3.0)

        // The banner during the first step shows the turn after it.
        val banner = depart.getJSONArray("bannerInstructions").getJSONObject(0).getJSONObject("primary")
        assertEquals("Milverton Road", banner.getString("text"))
        assertEquals("right", banner.getString("modifier"))

        val voice = depart.getJSONArray("voiceInstructions")
        assertEquals("Drive west on Nacuva Street.", voice.getJSONObject(0).getString("announcement"))
        assertEquals("Turn right onto Milverton Road.", voice.getJSONObject(voice.length() - 1).getString("announcement"))
        assertEquals(100.0, voice.getJSONObject(voice.length() - 1).getDouble("distanceAlongGeometry"), 0.0)

        val beforeArrival = steps.getJSONObject(1).getJSONArray("voiceInstructions")
        assertEquals("You have arrived at your destination.", beforeArrival.getJSONObject(beforeArrival.length() - 1).getString("announcement"))
        assertEquals(0, steps.getJSONObject(2).getJSONArray("voiceInstructions").length())
    }

    @Test
    fun `every remaply step type has a maneuver the engine knows`() {
        val known = setOf("turn", "new name", "depart", "arrive", "merge", "on ramp", "off ramp", "fork", "end of road", "continue", "roundabout", "rotary", "roundabout turn", "notification", "exit roundabout", "exit rotary")
        val modifiers = setOf(null, "uturn", "sharp right", "right", "slight right", "straight", "slight left", "left", "sharp left")
        listOf(
            "depart", "arrive", "continue", "turn_slight_right", "turn_right", "turn_sharp_right", "uturn", "turn_sharp_left",
            "turn_left", "turn_slight_left", "ramp", "ramp_right", "ramp_left", "exit_right", "exit_left", "keep_straight",
            "keep_right", "keep_left", "merge", "roundabout_enter", "roundabout_exit", "ferry_enter", "ferry_exit",
            "merge_right", "merge_left", "other", "something_new",
        ).forEach {
            val (type, modifier) = RemaplyToOsrm.maneuver(it)
            assertTrue(it, type in known)
            assertTrue(it, modifier in modifiers)
        }
    }
}
