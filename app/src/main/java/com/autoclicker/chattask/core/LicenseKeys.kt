package com.autoclicker.chattask.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Device-bound activation keys: a key is the truncated HMAC-SHA256 of a License ID.
 *
 * HMAC is symmetric, so the same secret both makes and checks keys and has to ship in the APK.
 */
class LicenseKeys(secretHex: String) {
    private val secret = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun keyFor(licenseId: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret, ALGORITHM))
        val digest = mac.doFinal(canonical(licenseId).toByteArray(Charsets.UTF_8))
        return grouped(digest.copyOf(KEY_BYTES))
    }

    fun isValid(licenseId: String, key: String): Boolean {
        val expected = canonical(keyFor(licenseId)).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, canonical(key).toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val ID_SALT = "autoclicker-license-id:"
        private const val ID_BYTES = 6
        private const val KEY_BYTES = 8
        private const val GROUP = 4

        /** A one-way ID, so the Android ID itself is never shown or shared. */
        fun licenseIdFor(androidId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((ID_SALT + androidId).toByteArray(Charsets.UTF_8))
            return grouped(digest.copyOf(ID_BYTES))
        }

        private fun canonical(value: String): String =
            value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }

        private fun grouped(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it) }.chunked(GROUP).joinToString("-")
    }
}
