package com.remaply.navigation.core

import java.time.Instant

/** A point on Earth in WGS 84 degrees. */
public data class Coordinate(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be between -90 and 90, was $latitude" }
        require(longitude in -180.0..180.0) { "longitude must be between -180 and 180, was $longitude" }
    }
}

/** A place the route must visit. The last waypoint of a trip is its destination. */
public data class Waypoint(
    val coordinate: Coordinate,
    val kind: Kind = Kind.STOP,
    /** Optional label, e.g. for the notification or your own UI. Not sent to the routing server. */
    val name: String? = null,
) {
    public constructor(latitude: Double, longitude: Double, name: String? = null) :
        this(Coordinate(latitude, longitude), Kind.STOP, name)

    public enum class Kind {
        /** A stop: arriving there is reported with [NavigationEvent.WaypointReached]. */
        STOP,

        /** A point to pass through without stopping, used to shape the route. */
        VIA,
    }
}

/**
 * How the user travels. Picks the routing profile on the server and the navigation thresholds
 * (see [NavigationThresholds.forProfile]).
 */
public enum class TravelProfile {
    CAR,
    MOTORCYCLE,
    TRUCK,
    BICYCLE,
    FOOT,
}

/** A location fix, from the GPS or from the simulator. */
public data class UserLocation(
    val coordinate: Coordinate,
    /** Radius of the 68% confidence circle, in meters. */
    val horizontalAccuracyMeters: Double,
    /** Direction of travel in degrees clockwise from north, if known. */
    val courseDegrees: Double? = null,
    /** Speed in meters per second, if known. */
    val speedMetersPerSecond: Double? = null,
    val timestamp: Instant = Instant.now(),
)

/** The kind of maneuver at the end of the current step, for picking an icon or wording. */
public enum class ManeuverType {
    TURN,
    NEW_NAME,
    DEPART,
    ARRIVE,
    MERGE,
    ON_RAMP,
    OFF_RAMP,
    FORK,
    END_OF_ROAD,
    CONTINUE,
    ROUNDABOUT,
    ROTARY,
    ROUNDABOUT_TURN,
    NOTIFICATION,
    EXIT_ROUNDABOUT,
    EXIT_ROTARY,
}

/** The direction of a maneuver. */
public enum class ManeuverModifier {
    U_TURN,
    SHARP_RIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
}

/** What to show the user for the next maneuver. */
public data class Instruction(
    /** Main text, usually the road to take, e.g. "Victoria Parade". */
    val primaryText: String,
    /** Extra text, e.g. "Toward Suva", when the routing server provides it. */
    val secondaryText: String?,
    val maneuverType: ManeuverType?,
    val maneuverModifier: ManeuverModifier?,
    /** Full sentence, e.g. "Turn right onto Victoria Parade." */
    val fullText: String,
)

/** A step of a route: one maneuver and the road up to the next one. */
public data class RouteStep(
    val instruction: String,
    val roadName: String?,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

/** Distances and times remaining on the trip. */
public data class TripProgress(
    val distanceToNextManeuverMeters: Double,
    val distanceRemainingMeters: Double,
    val durationRemainingSeconds: Double,
) {
    /** When the user is expected to arrive, from now and the remaining duration. */
    public val estimatedArrival: Instant
        get() = Instant.now().plusMillis((durationRemainingSeconds * 1000).toLong())
}
