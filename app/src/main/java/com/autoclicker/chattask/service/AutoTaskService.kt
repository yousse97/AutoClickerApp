package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.StringRes
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.SessionSchedule
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.SessionSnapshot
import com.autoclicker.chattask.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.random.Random

/**
 * Owns the automation session.
 *
 * The system binds accessibility services and restarts them after a crash, which makes this the
 * most durable place in the app for the timer. Node access runs on one worker thread, because
 * each call is a blocking IPC into the target app and must not stall the main thread.
 */
class AutoTaskService : AccessibilityService(), TaskRunner.Listener {
    private class Session(val target: String, val config: TaskConfig)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nodeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private lateinit var store: SettingsStore
    private lateinit var popups: PopupEngine
    private lateinit var sender: CommandSender
    private lateinit var runner: TaskRunner
    private var session: Session? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = SettingsStore(this)
        sender = CommandSender(this)
        popups = PopupEngine(this) { TaskBus.update { it.copy(popups = it.popups + 1) } }
        popups.launchIn(scope, nodeDispatcher)
        runner = TaskRunner(
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            random = Random.Default,
            action = ::performCycle,
            listener = this,
        )
        applyEventFilter(null)
        instance = this
        restoreSession()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        val current = session ?: return
        if (runner.phase != TaskPhase.RUNNING) return
        if (event.packageName?.toString() == current.target) popups.onEvent(type)
    }

    override fun onInterrupt() = Unit

    /** Only reached when the user turns the service off, so the session should end with it. */
    override fun onUnbind(intent: Intent?): Boolean {
        stopSession()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        nodeDispatcher.close()
        super.onDestroy()
    }

    fun startOrResume() {
        when (runner.phase) {
            TaskPhase.RUNNING -> Unit
            TaskPhase.PAUSED -> runner.resume()
            TaskPhase.IDLE -> startNewSession()
        }
    }

    fun pauseSession() = runner.pause()

    fun stopSession() {
        if (!::runner.isInitialized) return
        runner.stop()
        endSession(notice = 0)
    }

    private fun startNewSession() {
        val config = store.loadConfig()
        if (config.validate().isNotEmpty()) return showNotice(R.string.notice_invalid_settings)

        val fixedTarget = config.targetPackage.trim()
        val target = fixedTarget.ifEmpty { NodeTools.foregroundPackage(this).orEmpty() }
        val hasNoChat = target.isEmpty() || target == packageName ||
            (fixedTarget.isEmpty() && sender.findInput(target) == null)
        if (hasNoChat) return showNotice(R.string.notice_open_chat)

        val plan = SessionSchedule.create(
            now = SystemClock.elapsedRealtime(),
            firstDelayMs = FIRST_SEND_DELAY_MS,
            intervalMs = config.intervalSec * 1000L,
            durationMs = config.durationSec * 1000L,
        )
        activate(Session(target, config))
        TaskBus.update { it.copy(popups = 0, notice = 0) }
        runner.start(plan, jitterOf(config))
    }

    /** Resumes a session that was interrupted by the process dying, not by the user. */
    private fun restoreSession() {
        val snapshot = store.loadSession() ?: return
        val elapsed = if (snapshot.paused) {
            0
        } else {
            max(0, System.currentTimeMillis() - snapshot.savedAtWallMs)
        }
        if (snapshot.offsets.endInMs - elapsed <= 0) return store.clearSession()

        // Without the overlay there would be no visible way to stop it, so it must not run.
        val overlayStarted = OverlayService.start(this)
        val config = store.loadConfig()
        val now = SystemClock.elapsedRealtime()
        activate(Session(snapshot.target, config))
        TaskBus.update { it.copy(popups = snapshot.popups, notice = 0) }
        runner.start(
            plan = SessionSchedule.fromOffsets(snapshot.offsets, now - elapsed),
            jitterPolicy = jitterOf(config),
            sentSoFar = snapshot.sent,
            failedSoFar = snapshot.failed,
            paused = snapshot.paused || !overlayStarted,
        )
    }

    private fun activate(newSession: Session) {
        session = newSession
        applyEventFilter(newSession.target)
        popups.configure(
            target = newSession.target,
            keywords = newSession.config.popupKeywords,
            skipLists = newSession.config.skipListItems,
        )
        TaskBus.update {
            it.copy(target = newSession.target, keepScreenOn = newSession.config.keepScreenOn)
        }
    }

    private fun endSession(@StringRes notice: Int) {
        session = null
        store.clearSession()
        popups.disable()
        applyEventFilter(null)
        TaskBus.update {
            it.copy(
                phase = TaskPhase.IDLE,
                target = "",
                nextInMs = 0,
                sessionLeftMs = 0,
                keepScreenOn = false,
                notice = notice,
            )
        }
    }

    /** Events are limited to the target app, and switched off entirely when idle. */
    private fun applyEventFilter(target: String?) {
        val info = serviceInfo ?: return
        info.packageNames = target?.let { arrayOf(it) }
        info.eventTypes = if (target == null) 0 else WATCHED_EVENTS
        serviceInfo = info
    }

    private suspend fun performCycle(): Boolean {
        val current = session ?: return false
        val result = withContext(nodeDispatcher) {
            var last = SendResult.NOT_CONFIRMED
            for (attempt in 1..MAX_SEND_ATTEMPTS) {
                popups.scan(full = true)
                last = sender.send(current.target, current.config.command.trim())
                if (last == SendResult.SENT || last == SendResult.SCREEN_LOCKED) break
                if (attempt < MAX_SEND_ATTEMPTS) delay(RETRY_DELAY_MS)
                // The user may have paused to use the chat, so do not type over them.
                if (runner.phase != TaskPhase.RUNNING) break
            }
            last
        }
        showNotice(noticeFor(result))
        return result == SendResult.SENT
    }

    private fun noticeFor(result: SendResult): Int = when (result) {
        SendResult.SENT -> 0
        SendResult.SCREEN_LOCKED -> R.string.notice_screen_locked
        SendResult.TARGET_NOT_VISIBLE -> R.string.notice_target_hidden
        SendResult.NO_INPUT -> R.string.notice_no_input
        SendResult.TYPE_FAILED -> R.string.notice_type_failed
        SendResult.NOT_CONFIRMED -> R.string.notice_not_confirmed
    }

    private fun showNotice(@StringRes notice: Int) = TaskBus.update { it.copy(notice = notice) }

    private fun jitterOf(config: TaskConfig) = JitterPolicy(config.jitterMinSec, config.jitterMaxSec)

    override fun onTick(view: RunnerView) {
        TaskBus.update {
            it.copy(
                phase = view.phase,
                nextInMs = view.nextInMs,
                sessionLeftMs = view.sessionLeftMs,
                sent = view.sent,
                failed = view.failed,
            )
        }
    }

    override fun onPlanChanged() {
        val current = session ?: return
        val offsets = runner.offsets() ?: return
        val view = runner.view()
        store.saveSession(
            SessionSnapshot(
                target = current.target,
                paused = view.phase == TaskPhase.PAUSED,
                offsets = offsets,
                sent = view.sent,
                failed = view.failed,
                popups = TaskBus.status.value.popups,
                savedAtWallMs = System.currentTimeMillis(),
            ),
        )
    }

    override fun onFinished(view: RunnerView) {
        onTick(view)
        endSession(R.string.notice_finished)
    }

    companion object {
        private const val FIRST_SEND_DELAY_MS = 3_000L
        private const val MAX_SEND_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_500L
        private const val WATCHED_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        /** The connected service, or null while the user has it turned off. */
        @Volatile
        var instance: AutoTaskService? = null
            private set
    }
}
