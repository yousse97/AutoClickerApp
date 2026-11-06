package com.autoclicker.chattask.core

import kotlin.math.min

enum class SendMode { SIMULTANEOUS, DELAYED }

/**
 * One command in a cycle. The mode describes how it follows the previous command, so it has no
 * effect on the first entry.
 */
data class CommandEntry(
    val text: String,
    val mode: SendMode = SendMode.SIMULTANEOUS,
    val delaySec: Int = 0,
) {
    val waitMs: Long get() = if (mode == SendMode.DELAYED) delaySec * 1000L else 0
}

object CommandSequence {
    private const val SLICE_MS = 250L

    /** Time a cycle spends waiting between commands, not counting the sends themselves. */
    fun totalWaitMs(commands: List<CommandEntry>): Long = commands.drop(1).sumOf { it.waitMs }

    /**
     * Sends [commands] in order and stops at the first failure. Returns false only if a send
     * failed: when [isActive] turns false the rest is abandoned, which is not a failure.
     *
     * "Simultaneous" means straight after the previous command, as a chat box takes one message
     * at a time. Waits are sliced so that a pause takes effect during a long delay.
     */
    suspend fun run(
        commands: List<CommandEntry>,
        isActive: () -> Boolean,
        wait: suspend (Long) -> Unit,
        send: suspend (String) -> Boolean,
    ): Boolean {
        commands.forEachIndexed { index, command ->
            var left = if (index == 0) 0 else command.waitMs
            while (left > 0 && isActive()) {
                val slice = min(left, SLICE_MS)
                wait(slice)
                left -= slice
            }
            if (!isActive()) return true
            if (!send(command.text.trim())) return false
        }
        return true
    }
}
