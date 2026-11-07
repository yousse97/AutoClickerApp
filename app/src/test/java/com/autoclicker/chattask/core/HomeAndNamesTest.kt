package com.autoclicker.chattask.core

import com.autoclicker.chattask.R
import com.autoclicker.chattask.ui.QuickForm
import com.autoclicker.chattask.ui.menuItemOf
import com.autoclicker.chattask.ui.modeOfMenuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAndNamesTest {
    private val hours = CommandEntry("one", 60, allDay = false, startMinute = 60, endMinute = 120)
    private val saved = TaskConfig(
        commands = listOf(hours),
        jitterMinSec = 3,
        targetRoom = "first",
        restrictToRoom = true,
        reactive = ReactiveConfig(
            triggers = listOf("old"),
            command = "reply",
            room = "second",
            returnBeforeSend = false,
            hours = CommandEntry("", allDay = false, startMinute = 5, endMinute = 10),
        ),
    )

    private val heart = "❤"
    private val redHeart = "❤️"

    @Test
    fun `Home shows only the selected mode's settings`() {
        assertEquals(QuickForm.COMMANDS, QuickForm.of(OperatingMode.SCHEDULED))
        assertEquals(QuickForm.AUTO_REPLY, QuickForm.of(OperatingMode.AUTO_REPLY))
    }

    @Test
    fun `the menu chooses each mode and marks the selected one`() {
        OperatingMode.entries.forEach { assertEquals(it, modeOfMenuItem(menuItemOf(it))) }
        assertNull(modeOfMenuItem(R.id.menuCommands))
    }

    @Test
    fun `quick commands change only commands and channel`() {
        val edited = listOf(hours.copy(text = "two", intervalSec = 90))
        val result = saved.withQuickCommands(edited, " third ")
        assertEquals(saved.copy(commands = edited, targetRoom = "third"), result)
        assertEquals(hours.window, result.commands.single().window)
    }

    @Test
    fun `quick reply changes only triggers, reply and channel`() {
        val result = saved.withQuickReply("a\nb, a", " new ", " third ")
        val reply = saved.reactive.copy(triggers = listOf("a", "b"), command = "new", room = "third")
        assertEquals(saved.copy(reactive = reply), result)
    }

    @Test
    fun `Home and the pages read the same values`() {
        assertEquals(saved, saved.withQuickCommands(saved.commands, saved.targetRoom))
        assertEquals(saved, saved.withQuickReply("old", "reply", "second"))
    }

    @Test
    fun `unsaved edits show up as a difference from what is saved`() {
        assertTrue(saved.withQuickReply("old", "changed", "second") != saved)
    }

    @Test
    fun `invalid quick values are refused`() {
        val blank = saved.withQuickCommands(listOf(CommandEntry("", 60)), "first")
        assertTrue(ConfigError.EMPTY_COMMAND in blank.validate())
        val noChannel = saved.copy(mode = OperatingMode.AUTO_REPLY).withQuickReply("old", "reply", " ")
        assertEquals(listOf(ConfigError.REACTIVE_ROOM_REQUIRED), noChannel.validate())
    }

    @Test
    fun `a command added during a session waits a full interval, so Save sends nothing`() {
        val old = listOf(CommandEntry("a", 610))
        val carried = CommandTimers.carryOffsets(old, listOf(TimerOffset(0, 5_000, false)), old + CommandEntry("b", 90))
        assertEquals(TimerOffset(90_000, 90_000, false), carried[1])
    }

    @Test
    fun `an emoji-only name matches itself`() {
        assertTrue(WolfUi.isRoom("🔥", "🔥"))
    }

    @Test
    fun `an emoji name with and without a variation selector is the same name`() {
        assertTrue(WolfUi.isRoom(redHeart, heart))
        assertTrue(WolfUi.isRoom(heart, redHeart))
        assertTrue(WolfUi.isRoom("❤︎", redHeart))
    }

    @Test
    fun `text with emoji matches across the variation selector`() {
        assertTrue(WolfUi.isRoom("my $redHeart channel", "My $heart Channel"))
    }

    @Test
    fun `different emoji never match`() {
        assertFalse(WolfUi.isRoom("🔥", "💧"))
        assertFalse(WolfUi.isRoom("channel 🔥", "channel 💧"))
        assertFalse(WolfUi.isRoom("👋🏻", "👋🏿"))
    }

    @Test
    fun `an emoji joined by a joiner is a different emoji from the two apart`() {
        assertFalse(WolfUi.isRoom("👩‍💻", "👩💻"))
    }

    @Test
    fun `an emoji name is matched whole, never in part`() {
        assertFalse(WolfUi.isRoom("🔥🔥", "🔥"))
        assertFalse(WolfUi.isRoom("channel 🔥", "channel"))
        assertFalse(WolfUi.isRoom("🔥", ""))
    }

    @Test
    fun `a joiner between Arabic letters is still only formatting`() {
        assertTrue(WolfUi.isRoom("قن‍اة", "قناة"))
    }
}
