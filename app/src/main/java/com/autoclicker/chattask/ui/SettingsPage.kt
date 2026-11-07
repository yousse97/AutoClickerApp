package com.autoclicker.chattask.ui

import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.OperatingMode
import com.autoclicker.chattask.core.TaskConfig

/** One group of settings. Its own page and All Settings show the same section. */
enum class SettingsSectionKind(@StringRes val title: Int, val errors: Set<ConfigError>) {
    COMMANDS(
        R.string.page_commands,
        setOf(
            ConfigError.EMPTY_COMMAND,
            ConfigError.COMMAND_TOO_LONG,
            ConfigError.TOO_MANY_COMMANDS,
            ConfigError.INTERVAL_TOO_SHORT,
            ConfigError.JITTER_RANGE,
            ConfigError.JITTER_TOO_LARGE,
        ),
    ),
    AUTO_REPLY(
        R.string.page_auto_reply,
        setOf(
            ConfigError.REACTIVE_NO_TRIGGER,
            ConfigError.REACTIVE_NO_COMMAND,
            ConfigError.REACTIVE_ROOM_REQUIRED,
        ),
    ),
    CHANNEL(R.string.page_channel, setOf(ConfigError.ROOM_TOO_LONG, ConfigError.ROOM_REQUIRED)),
    TIMER(R.string.page_timer, emptySet()),
    HELP(R.string.page_help, emptySet()),
}

/** The pages reached from the menu. */
enum class SettingsPage(@StringRes val title: Int, val sections: List<SettingsSectionKind>) {
    COMMANDS(R.string.page_commands, listOf(SettingsSectionKind.COMMANDS)),
    AUTO_REPLY(R.string.page_auto_reply, listOf(SettingsSectionKind.AUTO_REPLY)),
    CHANNEL(R.string.page_channel, listOf(SettingsSectionKind.CHANNEL)),
    TIMER(R.string.page_timer, listOf(SettingsSectionKind.TIMER)),
    HELP(R.string.page_help, listOf(SettingsSectionKind.HELP)),
    ALL(R.string.page_all, SettingsSectionKind.entries),
}

sealed interface Destination {
    data class Page(val page: SettingsPage) : Destination
    data object Log : Destination
    data object About : Destination
}

/** Where each menu item goes. */
fun destinationOf(@IdRes item: Int): Destination? = when (item) {
    R.id.menuCommands -> Destination.Page(SettingsPage.COMMANDS)
    R.id.menuAutoReply -> Destination.Page(SettingsPage.AUTO_REPLY)
    R.id.menuChannel -> Destination.Page(SettingsPage.CHANNEL)
    R.id.menuTimer -> Destination.Page(SettingsPage.TIMER)
    R.id.menuHelp -> Destination.Page(SettingsPage.HELP)
    R.id.menuLog -> Destination.Log
    R.id.menuAll -> Destination.Page(SettingsPage.ALL)
    R.id.menuAbout -> Destination.About
    else -> null
}

/** What the home screen warns about, each with the page that fixes it. */
enum class HomeWarning(@StringRes val text: Int, val page: SettingsPage) {
    ACCESSIBILITY(R.string.warning_accessibility, SettingsPage.HELP),
    OVERLAY(R.string.warning_overlay, SettingsPage.HELP),
    NOTIFICATIONS(R.string.warning_notifications, SettingsPage.HELP),
    BATTERY(R.string.warning_battery, SettingsPage.HELP),
    COMMANDS(R.string.warning_commands, SettingsPage.COMMANDS),
    SCHEDULE(R.string.warning_schedule, SettingsPage.COMMANDS),
    REPLY_SCHEDULE(R.string.warning_reply_schedule, SettingsPage.AUTO_REPLY),
    CHANNEL(R.string.warning_channel, SettingsPage.CHANNEL),
    AUTO_REPLY(R.string.warning_auto_reply, SettingsPage.AUTO_REPLY),
    ;

    /** The others are worth showing, but none of them stops a session from running. */
    val blocksStart: Boolean get() = this !in OPTIONAL

    private companion object {
        val OPTIONAL = setOf(NOTIFICATIONS, BATTERY, SCHEDULE, REPLY_SCHEDULE)
    }
}

@get:StringRes
val OperatingMode.title: Int
    get() = if (this == OperatingMode.AUTO_REPLY) R.string.mode_auto_reply else R.string.mode_scheduled

@get:StringRes
val OperatingMode.shortTitle: Int
    get() = if (this == OperatingMode.AUTO_REPLY) R.string.mode_auto_reply_short else R.string.mode_scheduled_short

data class PermissionState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
)

fun homeWarnings(config: TaskConfig, permissions: PermissionState): List<HomeWarning> = buildList {
    if (!permissions.accessibility) add(HomeWarning.ACCESSIBILITY)
    if (!permissions.overlay) add(HomeWarning.OVERLAY)
    if (!permissions.notifications) add(HomeWarning.NOTIFICATIONS)
    if (!permissions.battery) add(HomeWarning.BATTERY)
    val errors = config.validate()
    fun inSection(kind: SettingsSectionKind) = errors.any { it in kind.errors }
    if (inSection(SettingsSectionKind.COMMANDS)) add(HomeWarning.COMMANDS)
    if (config.needsSchedule) {
        add(if (config.mode == OperatingMode.AUTO_REPLY) HomeWarning.REPLY_SCHEDULE else HomeWarning.SCHEDULE)
    }
    if (inSection(SettingsSectionKind.CHANNEL)) add(HomeWarning.CHANNEL)
    if (inSection(SettingsSectionKind.AUTO_REPLY)) add(HomeWarning.AUTO_REPLY)
}
