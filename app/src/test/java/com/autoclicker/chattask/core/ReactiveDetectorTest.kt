package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactiveDetectorTest {
    private val own = listOf(CommandEntry("my command", intervalSec = 610))

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
    fun `reactive settings are validated only in auto reply mode`() {
        val autoReply = OperatingMode.AUTO_REPLY
        assertTrue(TaskConfig(commands = own, reactive = ReactiveConfig(command = "")).validate().isEmpty())
        assertEquals(
            listOf(ConfigError.REACTIVE_NO_COMMAND, ConfigError.REACTIVE_ROOM_REQUIRED),
            TaskConfig(mode = autoReply, commands = own).validate(),
        )
        assertEquals(
            listOf(ConfigError.REACTIVE_NO_TRIGGER),
            TaskConfig(mode = autoReply, commands = own, reactive = ReactiveConfig(emptyList(), "!go", "room")).validate(),
        )
        assertEquals(
            listOf(ConfigError.REACTIVE_ROOM_REQUIRED),
            TaskConfig(mode = autoReply, commands = own, reactive = ReactiveConfig(command = "!go")).validate(),
        )
    }

    @Test
    fun `restricting task 1 needs a room`() {
        assertEquals(listOf(ConfigError.ROOM_REQUIRED), TaskConfig(commands = own, restrictToRoom = true).validate())
        assertTrue(TaskConfig(commands = own, restrictToRoom = true, targetRoom = "12345").validate().isEmpty())
    }
}
