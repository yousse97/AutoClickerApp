package com.autoclicker.chattask.core

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Checks a passcode against a salted PBKDF2 hash, so the passcode itself never ships. */
class PasscodeVerifier(
    private val saltHex: String,
    private val iterations: Int,
    private val hashHex: String,
) {
    /** Slow by design, so call it off the main thread. */
    fun matches(candidate: String): Boolean {
        val expected = hashHex.hexToBytes()
        val spec = PBEKeySpec(
            candidate.trim().toCharArray(),
            saltHex.hexToBytes(),
            iterations,
            expected.size * 8,
        )
        return try {
            val actual = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            MessageDigest.isEqual(actual, expected)
        } finally {
            spec.clearPassword()
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"

        val MASTER = PasscodeVerifier(
            saltHex = "57c8deffcf1d10cb73f3a1f074eb0ebb",
            iterations = 120_000,
            hashHex = "116a6f82132e7b32f168bf03562a5cd30c76c8f41d82b589ee40768583098441",
        )
    }
}
