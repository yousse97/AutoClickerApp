package com.autoclicker.chattask.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import com.autoclicker.chattask.BuildConfig
import com.autoclicker.chattask.core.LicenseKeys
import com.autoclicker.chattask.core.PasscodeVerifier
import java.security.MessageDigest

/** The one check that decides whether this install may do anything. */
object AppGate {
    val licenseKeys: LicenseKeys by lazy { LicenseKeys(BuildConfig.LICENSE_HMAC_KEY) }

    fun isUnlocked(context: Context): Boolean =
        isGenuine(context) && SecureStore(context).role != LicenseRole.NONE

    fun isAdmin(context: Context): Boolean =
        isGenuine(context) && SecureStore(context).role == LicenseRole.ADMIN

    fun licenseId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return LicenseKeys.licenseIdFor(androidId.orEmpty())
    }

    /** What [entered] unlocks on this device. Slow for a passcode, so call it off the main thread. */
    fun roleFor(context: Context, entered: String): LicenseRole = when {
        licenseKeys.isValid(licenseId(context), entered) -> LicenseRole.STANDARD
        PasscodeVerifier.MASTER.matches(entered) -> LicenseRole.ADMIN
        else -> LicenseRole.NONE
    }

    /** False if the APK was re-signed or rebuilt as debuggable. */
    fun isGenuine(context: Context): Boolean {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) return false
        val signers = runCatching { signersOf(context) }.getOrNull().orEmpty()
        return signers.isNotEmpty() && signers.all { sha256(it) == BuildConfig.SIGNER_SHA256 }
    }

    @Suppress("DEPRECATION")
    private fun signersOf(context: Context): List<Signature> {
        val manager = context.packageManager
        val name = context.packageName
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val info = manager.getPackageInfo(name, PackageManager.GET_SIGNATURES)
            return info.signatures?.toList().orEmpty()
        }
        val info = manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        return info.signingInfo?.apkContentsSigners?.toList().orEmpty()
    }

    private fun sha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
