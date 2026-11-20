package com.remaply.navigation.core.internal

import android.location.Location
import android.os.SystemClock
import com.remaply.navigation.core.Coordinate
import com.remaply.navigation.core.Instruction
import com.remaply.navigation.core.ManeuverModifier
import com.remaply.navigation.core.ManeuverType
import com.remaply.navigation.core.RouteStep
import com.remaply.navigation.core.TripProgress
import com.remaply.navigation.core.UserLocation
import com.remaply.navigation.core.Waypoint
import java.time.Instant
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Speed
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.RouteStep as EngineStep
import uniffi.ferrostar.TripProgress as EngineProgress
import uniffi.ferrostar.UserLocation as EngineLocation
import uniffi.ferrostar.Waypoint as EngineWaypoint

// Conversions between the SDK's public types and Ferrostar's. Kept in one place so a Ferrostar
// upgrade that renames or reshapes a type only touches this file.

internal fun GeographicCoordinate.toCoordinate() = Coordinate(lat, lng)

internal fun Coordinate.toFerrostar() = GeographicCoordinate(latitude, longitude)

internal fun Waypoint.toFerrostar() = EngineWaypoint(
    coordinate.toFerrostar(),
    if (kind == Waypoint.Kind.VIA) WaypointKind.VIA else WaypointKind.BREAK,
    null,
)

internal fun EngineWaypoint.toWaypoint() = Waypoint(
    coordinate.toCoordinate(),
    if (kind == WaypointKind.VIA) Waypoint.Kind.VIA else Waypoint.Kind.STOP,
)

internal fun UserLocation.toFerrostar() = EngineLocation(
    coordinate.toFerrostar(),
    horizontalAccuracyMeters.coerceAtLeast(0.0),
    courseDegrees?.let { CourseOverGround(normalizeDegrees(it).toUInt().toUShort(), null) },
    timestamp,
    speedMetersPerSecond?.let { Speed(it, null) },
)

internal fun EngineLocation.toUserLocation() = UserLocation(
    coordinate = coordinates.toCoordinate(),
    horizontalAccuracyMeters = horizontalAccuracy,
    courseDegrees = courseOverGround?.degrees?.toDouble(),
    speedMetersPerSecond = speed?.value,
    timestamp = timestamp,
)

internal fun Location.toUserLocation() = UserLocation(
    coordinate = Coordinate(latitude, longitude),
    horizontalAccuracyMeters = if (hasAccuracy()) accuracy.toDouble() else 0.0,
    courseDegrees = if (hasBearing()) bearing.toDouble() else null,
    speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
    timestamp = Instant.ofEpochMilli(time),
)

internal fun UserLocation.toAndroidLocation(): Location = Location("RemaplyNavigation").also {
    it.latitude = coordinate.latitude
    it.longitude = coordinate.longitude
    it.accuracy = horizontalAccuracyMeters.toFloat()
    it.time = timestamp.toEpochMilli()
    courseDegrees?.let { degrees -> it.bearing = degrees.toFloat() }
    speedMetersPerSecond?.let { mps -> it.speed = mps.toFloat() }
    it.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
}

internal fun EngineStep.toRouteStep() = RouteStep(
    instruction = instruction,
    roadName = roadName,
    distanceMeters = distance,
    durationSeconds = duration,
)

internal fun EngineProgress.toTripProgress() = TripProgress(
    distanceToNextManeuverMeters = distanceToNextManeuver,
    distanceRemainingMeters = distanceRemaining,
    durationRemainingSeconds = durationRemaining,
)

internal fun VisualInstruction.toInstruction(fullText: String?) = Instruction(
    primaryText = primaryContent.text,
    secondaryText = secondaryContent?.text,
    maneuverType = primaryContent.maneuverType?.let { enumByName<ManeuverType>(it.name) },
    maneuverModifier = primaryContent.maneuverModifier?.let { enumByName<ManeuverModifier>(it.name) },
    fullText = fullText ?: primaryContent.text,
)

/** Matches enum constants by name, so a value Ferrostar adds later maps to null instead of crashing. */
internal inline fun <reified T : Enum<T>> enumByName(name: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }

internal fun normalizeDegrees(degrees: Double): Double {
    val d = degrees % 360.0
    return if (d < 0) d + 360.0 else if (d >= 360.0) 0.0 else d
}
