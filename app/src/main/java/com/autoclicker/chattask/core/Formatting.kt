package com.autoclicker.chattask.core

import java.util.Locale

/** Formats a duration as H:MM:SS, or M:SS when under an hour. Rounds up to whole seconds. */
fun formatDuration(totalMs: Long): String {
    val total = (totalMs.coerceAtLeast(0) + 999) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
