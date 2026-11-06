package com.autoclicker.chattask.core

/**
 * Stops a popup that refuses to close from being clicked in a tight loop.
 *
 * Each distinct target gets a cooldown, and a global limit bounds clicks in a sliding window.
 * Not thread-safe; use it from one thread.
 */
class ClickGuard(
    private val cooldownMs: Long = 2_000,
    private val maxClicks: Int = 6,
    private val windowMs: Long = 10_000,
) {
    private val lastClickByKey = HashMap<String, Long>()
    private val recentClicks = ArrayDeque<Long>()

    fun allow(key: String, now: Long): Boolean {
        while (recentClicks.isNotEmpty() && now - recentClicks.first() >= windowMs) {
            recentClicks.removeFirst()
        }
        lastClickByKey.entries.removeAll { now - it.value >= cooldownMs }
        if (key in lastClickByKey || recentClicks.size >= maxClicks) return false
        lastClickByKey[key] = now
        recentClicks.addLast(now)
        return true
    }
}
