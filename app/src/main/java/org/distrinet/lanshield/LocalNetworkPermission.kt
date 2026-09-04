package org.distrinet.lanshield

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Apps targeting API 37 must hold ACCESS_LOCAL_NETWORK to reach local addresses. LANShield forwards
 * every other app's LAN traffic through its own sockets, so without this grant the tunnel would
 * silently drop all LAN traffic on the device. Below API 37 the permission does not exist and
 * INTERNET implies local network access, so it is treated as granted there.
 */
object LocalNetworkPermission {
    const val PERMISSION: String = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        !isRequired(sdkInt) ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Opens this app's details page, where the user can flip "Nearby devices" back on. */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }
}
