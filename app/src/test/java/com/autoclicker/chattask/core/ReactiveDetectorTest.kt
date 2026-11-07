package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactiveDetectorTest {
    private val own = listOf(CommandEntry("my command", intervalSec = 610))

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
        assertTrue(TaskConfig(commands = own, reactive = ReactiveConfig(enabled = false, command = "")).validate().isEmpty())
        assertEquals(
            listOf(ConfigError.REACTIVE_NO_COMMAND),
            TaskConfig(commands = own, reactive = ReactiveConfig(enabled = true)).validate(),
        )
        assertEquals(
            listOf(ConfigError.REACTIVE_NO_TRIGGER),
            TaskConfig(commands = own, reactive = ReactiveConfig(true, emptyList(), "!go")).validate(),
        )
        assertEquals(
            listOf(ConfigError.REACTIVE_ROOM_REQUIRED),
            TaskConfig(commands = own, reactive = ReactiveConfig(true, command = "!go", restrictToRoom = true)).validate(),
        )
    }

    @Test
    fun `restricting task 1 needs a room`() {
        assertEquals(listOf(ConfigError.ROOM_REQUIRED), TaskConfig(commands = own, restrictToRoom = true).validate())
        assertTrue(TaskConfig(commands = own, restrictToRoom = true, targetRoom = "12345").validate().isEmpty())
    }
}
