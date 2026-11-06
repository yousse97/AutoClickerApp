package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactiveAndLicenseTest {
    private val keys = LicenseKeys("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")

    @Test
    fun `license id is stable, formatted and differs per device`() {
        val id = LicenseKeys.licenseIdFor("abc123")
        assertEquals(id, LicenseKeys.licenseIdFor("abc123"))
        assertTrue(Regex("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}").matches(id))
        assertNotEquals(id, LicenseKeys.licenseIdFor("abc124"))
    }

    @Test
    fun `a key unlocks only its own device`() {
        val id = LicenseKeys.licenseIdFor("device-a")
        val key = keys.keyFor(id)
        assertTrue(keys.isValid(id, key))
        assertTrue(keys.isValid(id.lowercase(), " ${key.lowercase().replace("-", "")} "))
        assertFalse(keys.isValid(LicenseKeys.licenseIdFor("device-b"), key))
        assertFalse(keys.isValid(id, ""))
    }

    @Test
    fun `a key from another secret is rejected`() {
        val other = LicenseKeys("ff".repeat(32))
        val id = LicenseKeys.licenseIdFor("device-a")
        assertFalse(keys.isValid(id, other.keyFor(id)))
    }

    @Test
    fun `old messages set the baseline and do not fire`() {
        val detector = ReactiveDetector()
        assertFalse(detector.observe(3, 0))
        assertFalse(detector.observe(3, 1_000))
    }

    @Test
    fun `a new message fires once and a scrolled away one does not`() {
        val detector = ReactiveDetector()
        detector.observe(1, 0)
        assertTrue(detector.observe(2, 20_000))
        assertFalse(detector.observe(2, 21_000))
        assertFalse(detector.observe(1, 40_000))
        assertTrue(detector.observe(2, 60_000))
    }

    @Test
    fun `the cooldown absorbs a second match right after the first`() {
        val detector = ReactiveDetector(cooldownMs = 10_000)
        detector.observe(0, 0)
        assertTrue(detector.observe(1, 1_000))
        assertFalse(detector.observe(2, 2_000))
        assertTrue(detector.observe(3, 12_000))
    }

    @Test
    fun `entering another chat starts a fresh baseline`() {
        val detector = ReactiveDetector()
        detector.observe(0, 0)
        detector.forgetBaseline()
        assertFalse(detector.observe(4, 60_000))
    }

    @Test
    fun `message box is recognised by editable flag, class or hint`() {
        assertTrue(InputMatcher.isMessageBox(null, editable = true, null, null))
        assertTrue(InputMatcher.isMessageBox("android.widget.EditText", false, null, null))
        assertTrue(InputMatcher.isMessageBox("androidx.appcompat.widget.AppCompatEditText", false, null, null))
        assertTrue(InputMatcher.isMessageBox("android.view.View", false, "اكتب رسالتك", null))
        assertTrue(InputMatcher.isMessageBox("android.widget.TextView", false, null, "Type a message..."))
        assertFalse(InputMatcher.isMessageBox("android.widget.TextView", false, null, "Hello"))
    }

    @Test
    fun `a long chat message that mentions the hint is not a message box`() {
        val text = "someone wrote that you should type a message to the admin of this channel today"
        assertFalse(InputMatcher.isMessageBox("android.widget.TextView", false, null, text))
    }

    @Test
    fun `reactive settings are validated only while enabled`() {
        assertTrue(TaskConfig(reactive = ReactiveConfig(enabled = false, command = "")).validate().isEmpty())
        assertEquals(
            listOf(ConfigError.REACTIVE_NO_COMMAND),
            TaskConfig(reactive = ReactiveConfig(enabled = true)).validate(),
        )
        assertEquals(
            listOf(ConfigError.REACTIVE_NO_TRIGGER),
            TaskConfig(reactive = ReactiveConfig(true, emptyList(), "!go")).validate(),
        )
        assertEquals(
            listOf(ConfigError.REACTIVE_ROOM_REQUIRED),
            TaskConfig(reactive = ReactiveConfig(true, command = "!go", restrictToRoom = true)).validate(),
        )
    }

    @Test
    fun `restricting task 1 needs a room`() {
        assertEquals(listOf(ConfigError.ROOM_REQUIRED), TaskConfig(restrictToRoom = true).validate())
        assertTrue(TaskConfig(restrictToRoom = true, targetRoom = "41110").validate().isEmpty())
    }
}
