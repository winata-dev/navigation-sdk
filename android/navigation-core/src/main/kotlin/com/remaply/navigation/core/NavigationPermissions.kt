package com.remaply.navigation.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions navigation needs. The manifest entries are merged in by the SDK; your app
 * only has to ask the user, e.g. with `ActivityResultContracts.RequestMultiplePermissions`:
 *
 * ```
 * launcher.launch(NavigationPermissions.required)
 * ```
 */
public object NavigationPermissions {
    /** Location, plus notifications on Android 13+ for the background navigation notification. */
    @JvmStatic
    public val required: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    /** True when precise or approximate location was granted. Precise is strongly recommended. */
    @JvmStatic
    public fun hasLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** True when precise location was granted. Approximate location (about 2 km) is too coarse to navigate. */
    @JvmStatic
    public fun hasPreciseLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
