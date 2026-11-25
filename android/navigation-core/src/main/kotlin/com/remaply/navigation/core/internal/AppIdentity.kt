package com.remaply.navigation.core.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The app's package name and signing certificate SHA-1, as the headers API-key restrictions use
 * (the same convention as Google Maps Platform keys).
 */
internal object AppIdentity {
    fun headers(context: Context): Map<String, String> {
        val packageName = context.packageName
        val sha1 = runCatching { signingCertSha1(context) }.getOrNull()
        return buildMap {
            put("X-Android-Package", packageName)
            if (sha1 != null) put("X-Android-Cert", sha1)
        }
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signingCertSha1(context: Context): String? {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            // The certificate the APK is signed with now. signingCertificateHistory starts with the
            // original certificate, so after a key rotation it would name the old one.
            signingInfo.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull() ?: return null
        return sha1Hex(cert.toByteArray())
    }

    /** "AB:CD:...", upper case. */
    internal fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString(":") { "%02X".format(it) }
}
