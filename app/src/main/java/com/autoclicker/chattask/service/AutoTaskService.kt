package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.StringRes
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CommandSequence
import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.SessionSchedule
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.core.WorkRestScheduler
import com.autoclicker.chattask.data.SessionSnapshot
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.security.AppGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /** One failed child must not end the session, so failures stay inside their coroutine. */
    private val failureHandler = CoroutineExceptionHandler { _, _ ->
        showNotice(R.string.notice_engine_error)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failureHandler)
    private val nodeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private lateinit var store: SettingsStore
    private lateinit var popups: PopupEngine
    private lateinit var sender: CommandSender
    private lateinit var navigator: RoomNavigator
    private lateinit var reactive: ReactiveEngine
    private lateinit var runner: TaskRunner
    private var session: Session? = null

    /** Set when a rest ends, and consumed by the first cycle of the new work period. */
    @Volatile private var relaunchPending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = SettingsStore(this)
        sender = CommandSender(this)
        popups = PopupEngine(this) { TaskBus.update { it.copy(popups = it.popups + 1) } }
        popups.launchIn(scope, nodeDispatcher)
        navigator = RoomNavigator(this, sender, popups)
        reactive = ReactiveEngine(this, sender, navigator, ::onReactionFinished)
        reactive.launchIn(scope, nodeDispatcher)
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
        if (event.packageName?.toString() != current.target) return
        val phase = runner.phase
        if (phase == TaskPhase.RUNNING) popups.onEvent(type)
        if (phase == TaskPhase.RUNNING || phase == TaskPhase.RESTING) reactive.onEvent()
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
            TaskPhase.RUNNING, TaskPhase.RESTING -> Unit
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
        if (!AppGate.isUnlocked(this)) return showNotice(R.string.notice_locked)
        val config = store.loadConfig()
        if (config.validate().isNotEmpty()) return showNotice(R.string.notice_invalid_settings)

        val fixedTarget = config.targetPackage.trim()
        val target = fixedTarget.ifEmpty { NodeTools.foregroundPackage(this).orEmpty() }
        // A saved room means recovery can reach the chat, so the box need not be on screen yet.
        val canReachChat = fixedTarget.isNotEmpty() || config.targetRoom.isNotBlank() ||
            sender.findInput(target) != null
        val hasNoChat = target.isEmpty() || target == packageName || !canReachChat
        if (hasNoChat) return showNotice(R.string.notice_open_chat)

        val cycles = cyclesOf(config)
        activate(Session(target, config))
        TaskBus.update { it.copy(popups = 0, reactions = 0, notice = 0) }
        runner.start(cycles.workPlan(SystemClock.elapsedRealtime()), jitterOf(config), cycles)
    }

    /** Resumes a session that was interrupted by the process dying, not by the user. */
    private fun restoreSession() {
        val snapshot = store.loadSession() ?: return
        if (!AppGate.isUnlocked(this)) return store.clearSession()
        val elapsed = if (snapshot.paused) {
            0
        } else {
            max(0, System.currentTimeMillis() - snapshot.savedAtWallMs)
        }
        val config = store.loadConfig()
        val cycles = cyclesOf(config)
        if (snapshot.offsets.endInMs - elapsed <= 0 && !cycles.loops) return store.clearSession()

        // Without the overlay there would be no visible way to stop it, so it must not run.
        val overlayStarted = OverlayService.start(this)
        val now = SystemClock.elapsedRealtime()
        activate(Session(snapshot.target, config))
        TaskBus.update { it.copy(popups = snapshot.popups, notice = 0) }
        runner.start(
            plan = SessionSchedule.fromOffsets(snapshot.offsets, now - elapsed),
            jitterPolicy = jitterOf(config),
            workRest = cycles,
            sentSoFar = snapshot.sent,
            failedSoFar = snapshot.failed,
            paused = snapshot.paused || !overlayStarted,
            isResting = snapshot.resting,
            workCycleSoFar = snapshot.workCycle,
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
        reactive.configure(newSession.target, newSession.config.reactive)
        TaskBus.update {
            it.copy(target = newSession.target, keepScreenOn = newSession.config.keepScreenOn)
        }
    }

    private fun endSession(@StringRes notice: Int) {
        session = null
        relaunchPending = false
        store.clearSession()
        popups.disable()
        reactive.disable()
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
        return try {
            sendCommands(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            showNotice(R.string.notice_engine_error)
            false
        }
    }

    private suspend fun sendCommands(current: Session): Boolean {
        var mayRecover = true
        var recoveryNotice = 0
        var sendNotice = 0
        val success = withContext(nodeDispatcher) {
            if (relaunchPending) {
                relaunchPending = false
                mayRecover = false
                recoveryNotice = recover(current)
            }
            CommandSequence.run(current.config.commands, ::isWorking, { delay(it) }) { command ->
                var result = sendInRoom(current, command)
                if (result in RECOVERABLE && mayRecover) {
                    mayRecover = false
                    recoveryNotice = recover(current)
                    if (recoveryNotice == 0) result = sendInRoom(current, command)
                }
                sendNotice = noticeFor(result)
                result == SendResult.SENT
            }
        }
        showNotice(if (success) 0 else recoveryNotice.takeIf { it != 0 } ?: sendNotice)
        return success
    }

    private fun isWorking() = runner.phase == TaskPhase.RUNNING

    private suspend fun sendInRoom(current: Session, command: String): SendResult {
        val config = current.config
        if (config.restrictToRoom && !navigator.isInRoom(current.target, config.targetRoom)) {
            return SendResult.WRONG_ROOM
        }
        return sendWithRetries(current, command)
    }

    private fun onReactionFinished(success: Boolean) {
        if (success) {
            TaskBus.update { it.copy(reactions = it.reactions + 1) }
        } else {
            showNotice(R.string.notice_reaction_failed)
        }
    }

    private suspend fun sendWithRetries(current: Session, command: String): SendResult {
        var last = SendResult.NOT_CONFIRMED
        for (attempt in 1..MAX_SEND_ATTEMPTS) {
            popups.scan(full = true)
            last = sender.send(current.target, command)
            if (last == SendResult.SENT || last == SendResult.SCREEN_LOCKED) break
            if (attempt < MAX_SEND_ATTEMPTS) delay(RETRY_DELAY_MS)
            // The user may have paused to use the chat, so do not type over them.
            if (!isWorking()) break
        }
        return last
    }

    /** Reopens the target and returns to its chat. Returns a notice, or 0 on success. */
    private suspend fun recover(current: Session): Int {
        showNotice(R.string.notice_recovering)
        val config = current.config
        return when (navigator.recover(current.target, config.targetRoom, config.restrictToRoom, ::isWorking)) {
            RecoveryResult.READY, RecoveryResult.ABORTED -> 0
            RecoveryResult.NOT_LAUNCHABLE -> R.string.notice_not_launchable
            RecoveryResult.NO_CHAT -> R.string.notice_no_chat
            RecoveryResult.ROOM_NOT_FOUND -> R.string.notice_room_not_found
        }
    }

    private fun noticeFor(result: SendResult): Int = when (result) {
        SendResult.SENT -> 0
        SendResult.SCREEN_LOCKED -> R.string.notice_screen_locked
        SendResult.TARGET_NOT_VISIBLE -> R.string.notice_target_hidden
        SendResult.WRONG_ROOM -> R.string.notice_wrong_room
        SendResult.NO_INPUT -> R.string.notice_no_input
        SendResult.TYPE_FAILED -> R.string.notice_type_failed
        SendResult.NOT_CONFIRMED -> R.string.notice_not_confirmed
    }

    private fun showNotice(@StringRes notice: Int) = TaskBus.update { it.copy(notice = notice) }

    private fun jitterOf(config: TaskConfig) = JitterPolicy(config.jitterMinSec, config.jitterMaxSec)

    private fun cyclesOf(config: TaskConfig) = WorkRestScheduler(
        workMs = config.workDurationSec * 1000L,
        restMs = config.restDurationSec * 1000L,
        intervalMs = config.intervalSec * 1000L,
        firstDelayMs = FIRST_SEND_DELAY_MS,
    )

    override fun onTick(view: RunnerView) {
        TaskBus.update {
            it.copy(
                phase = view.phase,
                nextInMs = view.nextInMs,
                sessionLeftMs = view.sessionLeftMs,
                sent = view.sent,
                failed = view.failed,
                workCycle = view.workCycle,
            )
        }
    }

    override fun onRestFinished() {
        relaunchPending = true
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
                resting = view.resting,
                workCycle = view.workCycle,
            ),
        )
    }

    override fun onFinished(view: RunnerView) {
        onTick(view)
        endSession(R.string.notice_finished)
    }

    companion object {
        private const val FIRST_SEND_DELAY_MS = 3_000L
        private val RECOVERABLE =
            setOf(SendResult.TARGET_NOT_VISIBLE, SendResult.NO_INPUT, SendResult.WRONG_ROOM)
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
