package com.autoclicker.chattask.service

import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.LanguageChoice
import com.autoclicker.chattask.ui.Destination
import com.autoclicker.chattask.ui.HomeWarning
import com.autoclicker.chattask.ui.PermissionState
import com.autoclicker.chattask.ui.SettingsPage
import com.autoclicker.chattask.ui.SettingsSectionKind
import com.autoclicker.chattask.ui.destinationOf
import com.autoclicker.chattask.ui.homeWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    private val allGranted = PermissionState(true, true, true, true)
    private val ready = TaskConfig(commands = listOf(CommandEntry("a", 610)))

    @Test
    fun `every menu item leads to its page`() {
        assertEquals(Destination.Page(SettingsPage.COMMANDS), destinationOf(R.id.menuCommands))
        assertEquals(Destination.Page(SettingsPage.AUTO_REPLY), destinationOf(R.id.menuAutoReply))
        assertEquals(Destination.Page(SettingsPage.CHANNEL), destinationOf(R.id.menuChannel))
        assertEquals(Destination.Page(SettingsPage.TIMER), destinationOf(R.id.menuTimer))
        assertEquals(Destination.Page(SettingsPage.HELP), destinationOf(R.id.menuHelp))
        assertEquals(Destination.Log, destinationOf(R.id.menuLog))
        assertEquals(Destination.Page(SettingsPage.ALL), destinationOf(R.id.menuAll))
        assertNull(destinationOf(0))
    }

    @Test
    fun `all settings holds each section exactly once`() {
        assertEquals(SettingsSectionKind.entries, SettingsPage.ALL.sections)
        val single = SettingsPage.entries.filter { it != SettingsPage.ALL }.flatMap { it.sections }
        assertEquals(SettingsSectionKind.entries, single)
    }

    @Test
    fun `each validation error belongs to one section`() {
        val owners = SettingsSectionKind.entries.flatMap { it.errors }
        assertEquals(ConfigError.entries.toSet(), owners.toSet())
        assertEquals(owners.size, owners.toSet().size)
    }

    @Test
    fun `language rows map to their language`() {
        assertEquals(LanguageChoice.ARABIC, LanguageChoice.fromItem(R.id.languageArabic))
        assertEquals(LanguageChoice.ENGLISH, LanguageChoice.fromItem(R.id.languageEnglish))
        assertNull(LanguageChoice.fromItem(0))
        assertEquals(LanguageChoice.ARABIC, LanguageChoice.fromLanguage("ar"))
        assertEquals(LanguageChoice.ENGLISH, LanguageChoice.fromLanguage("en"))
        assertEquals(LanguageChoice.ENGLISH, LanguageChoice.fromLanguage("fr"))
    }

    @Test
    fun `home warns about missing permissions and settings`() {
        assertEquals(emptyList<HomeWarning>(), homeWarnings(ready, allGranted))
        val noOverlay = homeWarnings(ready, allGranted.copy(overlay = false))
        assertEquals(listOf(HomeWarning.OVERLAY), noOverlay)
        val blank = homeWarnings(TaskConfig(), allGranted)
        assertEquals(listOf(HomeWarning.COMMANDS), blank)
        val noRoom = homeWarnings(ready.copy(restrictToRoom = true), allGranted)
        assertEquals(listOf(HomeWarning.CHANNEL), noRoom)
    }

    @Test
    fun `a daily command without times gets the schedule warning`() {
        val config = TaskConfig(commands = listOf(CommandEntry("a", 610, allDay = false)))
        val warnings = homeWarnings(config, allGranted)
        assertEquals(listOf(HomeWarning.SCHEDULE), warnings)
        assertEquals(SettingsPage.COMMANDS, warnings.first().page)
    }

    @Test
    fun `waiting for a schedule shows as idle, not running`() {
        val status = TaskStatus(phase = TaskPhase.RUNNING, scheduleNeeded = true)
        assertTrue(homeStateOf(status, false) == HomeState.IDLE)
    }
}
