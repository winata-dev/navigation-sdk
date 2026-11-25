package com.remaply.navigation.core.internal

import com.remaply.navigation.core.Coordinate
import com.remaply.navigation.core.RouteRequest
import com.remaply.navigation.core.TravelProfile
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes from the Remaply API's `GET /v1/route`, for when `POST /v1/navigation/route` is not
 * available on the server (it answers 404 `not_found`).
 *
 * `/v1/route` answers in Remaply's own format: one line per route and, per step, a type, an
 * English instruction, road names and where the step starts. This turns that into the OSRM
 * format the navigation engine reads, cutting the line into steps and writing the banner and
 * voice instructions from the steps' instructions. Compared to the navigation endpoint, there is
 * no heading (a reroute may start with a U-turn), no speed limits and no lane information, and
 * via points are treated as stops.
 */
internal class RemaplyRouteFallback(baseUrl: String) {
    private val routeUrl: HttpUrl? = (baseUrl.trimEnd('/') + "/v1/route").toHttpUrlOrNull()

    /** System.nanoTime() until which the navigation endpoint is skipped. */
    @Volatile
    private var activeUntil: Long = 0
    private val lock = Any()

    /** True while the navigation endpoint is known to be missing. */
    fun isActive(now: Long = System.nanoTime()): Boolean = synchronized(lock) { activeUntil != 0L && now - activeUntil < 0 }

    /** Skips the navigation endpoint for a while, then tries it again, in case it was deployed since. */
    fun activate(now: Long = System.nanoTime()) = synchronized(lock) { activeUntil = now + RECHECK_AFTER_NANOS }

    fun url(request: RouteRequest): HttpUrl {
        val base = routeUrl ?: throw IllegalArgumentException("Invalid Remaply base URL")
        val points = (listOf(request.origin.coordinate) + request.waypoints.map { it.coordinate })
            .joinToString(";") { "${format(it.latitude)},${format(it.longitude)}" }
        return base.newBuilder()
            .addQueryParameter("points", points)
            .addQueryParameter("profile", profileName(request.profile))
            .addQueryParameter("steps", "true")
            .addQueryParameter("geometry", "polyline6")
            .build()
    }

    /** The `/v1/route` answer as an OSRM response. Throws on anything it cannot read. */
    fun toOsrm(remaplyJson: String, request: RouteRequest): ByteArray =
        RemaplyToOsrm.convert(remaplyJson, listOf(request.origin.coordinate) + request.waypoints.map { it.coordinate }, request.profile)
            .toByteArray()

    companion object {
        private const val RECHECK_AFTER_NANOS = 15L * 60 * 1_000_000_000

        /** 404 or 405 without `route_not_found`: the endpoint itself does not exist. */
        fun isMissingEndpoint(code: Int, body: String): Boolean =
            (code == 404 || code == 405) && !body.contains("route_not_found")

        fun profileName(profile: TravelProfile): String = when (profile) {
            TravelProfile.CAR -> "car"
            TravelProfile.MOTORCYCLE -> "motorcycle"
            TravelProfile.TRUCK -> "truck"
            TravelProfile.BICYCLE -> "bicycle"
            TravelProfile.FOOT -> "foot"
        }

        private fun format(degrees: Double): String = String.format(Locale.ROOT, "%.6f", degrees)
    }
}

/** Remaply `/v1/route` JSON → OSRM JSON. Pure, for tests. */
internal object RemaplyToOsrm {
    fun convert(remaplyJson: String, points: List<Coordinate>, profile: TravelProfile): String {
        val root = JSONObject(remaplyJson)
        val routes = root.getJSONArray("routes")
        val osrmRoutes = (0 until routes.length()).map { route(routes.getJSONObject(it), profile) }
        val waypoints = points.map { mapOf("location" to listOf(it.longitude, it.latitude)) }
        return Json.encode(mapOf("code" to "Ok", "routes" to osrmRoutes, "waypoints" to waypoints))
    }

    private class Step(
        val type: String,
        val instruction: String,
        val distance: Double,
        val duration: Double,
        val roadNames: List<String>,
        val location: Pair<Double, Double>,
        val legIndex: Int,
        val isLastLeg: Boolean,
    )

    private fun route(route: JSONObject, profile: TravelProfile): Map<String, Any?> {
        val line = Polyline6.decode(route.getString("geometry")).let { if (it.size == 1) it + it else it }
        require(line.size >= 2) { "route without geometry" }

        val legsJson = route.getJSONArray("legs")
        val steps = mutableListOf<Step>()
        for (l in 0 until legsJson.length()) {
            val stepsJson = legsJson.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(s)
                val location = step.getJSONObject("location")
                val names = step.optJSONArray("road_names") ?: JSONArray()
                steps += Step(
                    type = step.optString("type", "other"),
                    instruction = step.getString("instruction"),
                    distance = step.getDouble("distance_m"),
                    duration = step.getDouble("duration_s"),
                    roadNames = (0 until names.length()).map { names.getString(it) }.filter { it.isNotBlank() },
                    location = location.getDouble("lat") to location.getDouble("lon"),
                    legIndex = l,
                    isLastLeg = l == legsJson.length() - 1,
                )
            }
        }
        require(steps.isNotEmpty()) { "route without steps; was steps=true sent?" }

        // Where each step starts on the line. Steps start on a vertex, in order.
        val starts = IntArray(steps.size)
        var cursor = 0
        steps.forEachIndexed { i, step ->
            cursor = nearestIndex(line, step.location, cursor)
            starts[i] = cursor
        }

        val osrmSteps = steps.mapIndexed { i, step ->
            val start = starts[i]
            val end = if (i + 1 < steps.size && steps[i + 1].legIndex == step.legIndex) starts[i + 1] else lastIndexOfLeg(starts, steps, i, line)
            val geometry = if (end > start) line.subList(start, end + 1) else listOf(line[start], line[start])
            val next = steps.getOrNull(i + 1)?.takeIf { it.legIndex == step.legIndex }
            osrmStep(step, next, geometry, line, start, isFirstOfLeg = i == 0 || steps[i - 1].legIndex != step.legIndex, profile)
        }

        val legs = (0 until legsJson.length()).map { l ->
            val leg = legsJson.getJSONObject(l)
            mapOf(
                "distance" to leg.getDouble("distance_m"),
                "duration" to leg.getDouble("duration_s"),
                "summary" to "",
                "steps" to osrmSteps.filterIndexed { i, _ -> steps[i].legIndex == l },
            )
        }

        return mapOf(
            "distance" to route.getDouble("distance_m"),
            "duration" to route.getDouble("duration_s"),
            "geometry" to Polyline6.encode(line),
            "legs" to legs,
        )
    }

    /** A leg's last step (arrive) ends where it starts; any other last step runs to the end of the line. */
    private fun lastIndexOfLeg(starts: IntArray, steps: List<Step>, i: Int, line: List<IntArray>): Int {
        val nextLegStart = starts.getOrNull(i + 1)
        return if (steps[i].type == "arrive") starts[i] else nextLegStart ?: line.lastIndex
    }

    private fun nearestIndex(line: List<IntArray>, location: Pair<Double, Double>, from: Int): Int {
        val lat = (location.first * 1e6).roundToLong()
        val lon = (location.second * 1e6).roundToLong()
        var best = from
        var bestDistance = Long.MAX_VALUE
        for (i in from until line.size) {
            val dLat = line[i][0] - lat
            val dLon = line[i][1] - lon
            val d = dLat * dLat + dLon * dLon
            if (d == 0L) return i
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun osrmStep(
        step: Step,
        next: Step?,
        geometry: List<IntArray>,
        line: List<IntArray>,
        start: Int,
        isFirstOfLeg: Boolean,
        profile: TravelProfile,
    ): Map<String, Any?> {
        val (type, modifier) = maneuver(step.type)
        val bearingBefore = if (start > 0) bearing(line[start - 1], line[start]) else 0
        val bearingAfter = geometry.zipWithNext().firstOrNull { (a, b) -> !a.contentEquals(b) }?.let { (a, b) -> bearing(a, b) } ?: bearingBefore
        val location = listOf(line[start][1] / 1e6, line[start][0] / 1e6)

        return buildMap {
            put("distance", step.distance)
            put("duration", step.duration)
            put("geometry", Polyline6.encode(geometry))
            if (step.roadNames.isNotEmpty()) put("name", step.roadNames.joinToString(" / "))
            put("mode", if (step.type.startsWith("ferry")) "ferry" else mode(profile))
            put(
                "maneuver",
                buildMap {
                    put("location", location)
                    put("bearing_before", bearingBefore)
                    put("bearing_after", bearingAfter)
                    put("type", type)
                    if (modifier != null) put("modifier", modifier)
                    put("instruction", step.instruction)
                },
            )
            put("intersections", emptyList<Any>())
            put("bannerInstructions", listOf(banner(next ?: step, step.distance)))
            put("voiceInstructions", Voice.instructions(step, next, isFirstOfLeg, profile))
        }
    }

    private fun banner(target: Step, distance: Double): Map<String, Any?> {
        val (type, modifier) = maneuver(target.type)
        val text = target.roadNames.joinToString(" / ").ifEmpty { target.instruction.trimEnd('.') }
        return mapOf(
            "distanceAlongGeometry" to distance,
            "primary" to buildMap {
                put("text", text)
                put("type", type)
                if (modifier != null) put("modifier", modifier)
                put("components", listOf(mapOf("type" to "text", "text" to text)))
            },
        )
    }

    /** Remaply step type → OSRM maneuver type and modifier. */
    fun maneuver(type: String): Pair<String, String?> = when (type) {
        "depart" -> "depart" to null
        "arrive" -> "arrive" to null
        "continue" -> "continue" to "straight"
        "turn_slight_right" -> "turn" to "slight right"
        "turn_right" -> "turn" to "right"
        "turn_sharp_right" -> "turn" to "sharp right"
        "turn_slight_left" -> "turn" to "slight left"
        "turn_left" -> "turn" to "left"
        "turn_sharp_left" -> "turn" to "sharp left"
        "uturn" -> "turn" to "uturn"
        "ramp" -> "on ramp" to "straight"
        "ramp_right" -> "on ramp" to "right"
        "ramp_left" -> "on ramp" to "left"
        "exit_right" -> "off ramp" to "right"
        "exit_left" -> "off ramp" to "left"
        "keep_straight" -> "fork" to "straight"
        "keep_right" -> "fork" to "right"
        "keep_left" -> "fork" to "left"
        "merge" -> "merge" to "straight"
        "merge_right" -> "merge" to "slight right"
        "merge_left" -> "merge" to "slight left"
        "roundabout_enter" -> "roundabout" to null
        "roundabout_exit" -> "exit roundabout" to null
        "ferry_enter", "ferry_exit" -> "notification" to "straight"
        else -> "continue" to "straight"
    }

    private fun mode(profile: TravelProfile) = when (profile) {
        TravelProfile.BICYCLE -> "cycling"
        TravelProfile.FOOT -> "walking"
        else -> "driving"
    }

    /** Initial bearing from a to b, whole degrees clockwise from north. */
    private fun bearing(a: IntArray, b: IntArray): Int {
        val lat1 = Math.toRadians(a[0] / 1e6)
        val lat2 = Math.toRadians(b[0] / 1e6)
        val dLon = Math.toRadians((b[1] - a[1]) / 1e6)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360).roundToInt()) % 360
    }

    /** Spoken instructions, from the steps' written ones. English, like the instructions. */
    private object Voice {
        fun instructions(step: Step, next: Step?, isFirstOfLeg: Boolean, profile: TravelProfile): List<Map<String, Any>> {
            // The arrive step itself says nothing: the step before it announces the arrival.
            if (next == null) return emptyList()
            val (early, near) = when (profile) {
                TravelProfile.CAR, TravelProfile.MOTORCYCLE, TravelProfile.TRUCK -> 500.0 to 100.0
                TravelProfile.BICYCLE -> 150.0 to 30.0
                TravelProfile.FOOT -> 60.0 to 15.0
            }
            val distance = step.distance
            val upcoming = if (next.type == "arrive") arrival(step) else next.instruction
            val result = mutableListOf<Map<String, Any>>()

            if (isFirstOfLeg && distance <= near * 2) {
                // The next maneuver comes right away: say both at once.
                return listOf(say("${step.instruction} Then ${lowerFirst(upcoming)}", distance))
            }
            if (isFirstOfLeg) result += say(step.instruction, distance)
            if (distance >= 2_000 && profile.isMotorized()) result += say("In ${spoken(1_000.0)}, ${lowerFirst(upcoming)}", 1_000.0)
            if (distance >= early * 1.5) result += say("In ${spoken(early)}, ${lowerFirst(upcoming)}", early)
            result += if (next.type == "arrive") {
                // "You have arrived" only once there, not 100 m before.
                say(next.instruction, minOf(distance, near / 4))
            } else {
                say(upcoming, minOf(distance, near))
            }
            return result
        }

        private fun arrival(step: Step) =
            if (step.isLastLeg) "You will arrive at your destination." else "You will arrive at your stop."

        private fun say(text: String, distance: Double) = mapOf("announcement" to text, "distanceAlongGeometry" to distance)

        private fun TravelProfile.isMotorized() = this == TravelProfile.CAR || this == TravelProfile.MOTORCYCLE || this == TravelProfile.TRUCK

        fun spoken(meters: Double): String = when {
            meters >= 1_000 -> {
                val km = (meters / 500).roundToInt() / 2.0
                val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()
                if (km == 1.0) "1 kilometer" else "$text kilometers"
            }
            else -> "${meters.roundToInt()} meters"
        }

        private fun lowerFirst(text: String) = text.replaceFirstChar { it.lowercase(Locale.ROOT) }
    }
}

/** Encoded polylines with 6 decimals, as integer [lat, lon] in millionths of a degree. */
internal object Polyline6 {
    fun decode(encoded: String): List<IntArray> {
        val out = mutableListOf<IntArray>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            val (dLat, afterLat) = next(encoded, index)
            val (dLon, afterLon) = next(encoded, afterLat)
            lat += dLat
            lon += dLon
            index = afterLon
            out += intArrayOf(lat, lon)
        }
        return out
    }

    private fun next(encoded: String, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var index = start
        while (true) {
            require(index < encoded.length) { "truncated polyline" }
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return value to index
    }

    fun encode(points: List<IntArray>): String {
        val out = StringBuilder()
        var lat = 0
        var lon = 0
        for (p in points) {
            append(out, p[0] - lat)
            append(out, p[1] - lon)
            lat = p[0]
            lon = p[1]
        }
        return out.toString()
    }

    private fun append(out: StringBuilder, delta: Int) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v ushr 5
        }
        out.append((v + 63).toChar())
    }
}
