package com.autoclicker.chattask.data

import android.content.Context
import android.util.Log
import com.autoclicker.chattask.core.EventRing
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.LogEvent
import org.json.JSONArray

/** The local event log. It stays on this device and holds only event types and times. */
object EventLog {
    const val TAG = "AutoClicker"
    private const val PREFS = "event_log"
    private const val KEY_EVENTS = "events"

    private var ring: EventRing? = null
    private var snapshot: EventRing? = null

    fun add(context: Context, type: EventType) = addAt(context, System.currentTimeMillis(), type)

    @Synchronized
    fun addAt(context: Context, timeMs: Long, type: EventType) {
        Log.i(TAG, type.name)
        val events = load(context)
        events.add(LogEvent(timeMs, type))
        snapshot = null
        val json = JSONArray()
        events.all.forEach { json.put(it.timeMs).put(it.type.name) }
        prefs(context).edit().putString(KEY_EVENTS, json.toString()).apply()
    }

    @Synchronized
    fun read(context: Context): EventRing =
        snapshot ?: EventRing(load(context).all).also { snapshot = it }

    /** Everything here is already on disk, so the memory copy can go under pressure. */
    @Synchronized
    fun releaseCache() {
        ring = null
        snapshot = null
    }

    private fun load(context: Context): EventRing = ring ?: EventRing(parse(context)).also { ring = it }

    /** Stored as time, type, time, type. Entries that no longer parse are skipped. */
    private fun parse(context: Context): List<LogEvent> = runCatching {
        val json = JSONArray(prefs(context).getString(KEY_EVENTS, null) ?: "[]")
        (0 until json.length() step 2).mapNotNull { index ->
            EventType.entries.firstOrNull { it.name == json.getString(index + 1) }
                ?.let { LogEvent(json.getLong(index), it) }
        }
    }.getOrDefault(emptyList())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
