package com.autoclicker.chattask.core

/** User-tunable settings for one automation session. */
data class TaskConfig(
    val command: String = "!س ميدان",
    val intervalSec: Int = 10 * 60 + 10,
    val durationSec: Int = 22 * 60 * 60,
    val jitterMinSec: Int = 2,
    val jitterMaxSec: Int = 4,
    val popupKeywords: List<String> = DEFAULT_POPUP_KEYWORDS,
    /** Fixed target app. Blank means "whichever app is on screen when Start is pressed". */
    val targetPackage: String = "",
    /** Ignore matches inside scrolling lists, so chat messages reading "تم" are never clicked. */
    val skipListItems: Boolean = true,
    val keepScreenOn: Boolean = true,
) {
    fun validate(): List<ConfigError> = buildList {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) add(ConfigError.EMPTY_COMMAND)
        if (trimmed.length > MAX_COMMAND_LENGTH) add(ConfigError.COMMAND_TOO_LONG)
        if (intervalSec < MIN_INTERVAL_SEC) add(ConfigError.INTERVAL_TOO_SHORT)
        if (durationSec < intervalSec) add(ConfigError.DURATION_TOO_SHORT)
        if (jitterMinSec < 0 || jitterMaxSec < jitterMinSec) add(ConfigError.JITTER_RANGE)
        if (jitterMaxSec + MIN_JITTER_MARGIN_SEC > intervalSec) add(ConfigError.JITTER_TOO_LARGE)
        if (targetPackage.isNotBlank() && !PACKAGE_REGEX.matches(targetPackage.trim())) {
            add(ConfigError.BAD_PACKAGE)
        }
    }

    companion object {
        const val MIN_INTERVAL_SEC = 10
        const val MIN_JITTER_MARGIN_SEC = 5
        const val MAX_COMMAND_LENGTH = 1000
        private val PACKAGE_REGEX = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

        val DEFAULT_POPUP_KEYWORDS = listOf("تم", "إغلاق", "اغلاق", "close", "done", "×", "✕")

        /** Splits on Latin commas, Arabic commas and new lines. */
        fun parseKeywords(raw: String): List<String> =
            raw.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

enum class ConfigError {
    EMPTY_COMMAND,
    COMMAND_TOO_LONG,
    INTERVAL_TOO_SHORT,
    DURATION_TOO_SHORT,
    JITTER_RANGE,
    JITTER_TOO_LARGE,
    BAD_PACKAGE,
}
