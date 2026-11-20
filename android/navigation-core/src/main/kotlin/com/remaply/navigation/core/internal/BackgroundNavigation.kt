package com.remaply.navigation.core.internal

import android.app.Notification
import android.content.Context
import android.util.Log
import com.remaply.navigation.core.NavigationPermissions
import com.remaply.navigation.core.R
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.core.service.ForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.service.ForegroundServiceManager
import kotlin.math.roundToInt
import uniffi.ferrostar.TripState

/**
 * Starts Ferrostar's foreground service only when it is allowed to run: a location-type
 * foreground service started without location permission crashes the app on Android 14+.
 * That is the case for simulated drives before the user granted permission.
 */
internal class PermissionGatedForegroundService(private val context: Context) : ForegroundServiceManager {
    private val delegate = FerrostarForegroundServiceManager(context, NavigationNotificationBuilder(context))
    private var running = false

    override fun startService(stopNavigation: () -> Unit) {
        if (!NavigationPermissions.hasLocationPermission(context)) {
            Log.i("RemaplyNavigation", "No location permission: navigating without the background service")
            return
        }
        try {
            delegate.startService(stopNavigation)
            running = true
        } catch (e: RuntimeException) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.w("RemaplyNavigation", "Could not start the background navigation service", e)
        }
    }

    override fun stopService() {
        if (!running) return
        running = false
        delegate.stopService()
    }

    override fun onNavigationStateUpdated(state: NavigationState) {
        if (running) delegate.onNavigationStateUpdated(state)
    }
}

/** The ongoing notification while navigating: next instruction, and distance and time left. */
internal class NavigationNotificationBuilder(context: Context) : ForegroundNotificationBuilder(context) {
    override fun build(tripState: TripState?): Notification {
        val builder = Notification.Builder(context, channelId ?: error("channelId must be set before building"))
            .setSmallIcon(R.drawable.remaply_navigation_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_NAVIGATION)

        when (tripState) {
            is TripState.Navigating -> {
                builder.setContentTitle(tripState.visualInstruction?.primaryContent?.text ?: context.getString(R.string.remaply_navigation_navigating))
                builder.setContentText(
                    context.getString(
                        R.string.remaply_navigation_remaining,
                        formatDistance(tripState.progress.distanceRemaining),
                        formatDuration(tripState.progress.durationRemaining),
                    ),
                )
                builder.addAction(
                    Notification.Action.Builder(null, context.getString(R.string.remaply_navigation_stop), stopPendingIntent).build(),
                )
            }
            is TripState.Complete -> builder.setContentTitle(context.getString(R.string.remaply_navigation_arrived))
            else -> builder.setContentTitle(context.getString(R.string.remaply_navigation_preparing))
        }
        return builder.build()
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1f km".format(meters / 1000) else "${meters.roundToInt()} m"

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
}
