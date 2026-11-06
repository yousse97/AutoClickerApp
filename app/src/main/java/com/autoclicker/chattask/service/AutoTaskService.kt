package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.StringRes
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CheckResult
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.core.GuardAction
import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.SessionGuard
import com.autoclicker.chattask.core.SessionSchedule
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.core.WolfUi
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.data.SessionSnapshot
import com.autoclicker.chattask.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private class Session(val config: TaskConfig)

    /** One failed child must not end the session, so failures stay inside their coroutine. */
    private val failureHandler = CoroutineExceptionHandler { _, _ ->
        showNotice(R.string.notice_engine_error)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failureHandler)
    private val nodeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private lateinit var store: SettingsStore
    private lateinit var scheduleDriver: ScheduleDriver
    private lateinit var cycle: CommandCycle
    private val bar by lazy { BarWatcher(this) }
    private lateinit var popups: PopupEngine
    private lateinit var sender: CommandSender
    private lateinit var navigator: RoomNavigator
    private lateinit var reactive: ReactiveEngine
    private lateinit var runner: TaskRunner
    private var session: Session? = null

    private var guard = SessionGuard()
    @Volatile private var relaunching = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(EventLog.TAG, "service connected, repeat=${::runner.isInitialized}")
        // Android can reconnect a live service. Rebuilding here would run two sessions at once.
        if (::runner.isInitialized) return onReconnected()
        store = SettingsStore(this)
        sender = CommandSender(this)
        popups = PopupEngine(this) { TaskBus.update { it.copy(popups = it.popups + 1) } }
        popups.launchIn(scope, nodeDispatcher)
        navigator = RoomNavigator(this, popups)
        reactive = ReactiveEngine(this, sender, navigator, ::onReactionFinished, ::onPopupSeen, ::navigateForPopup)
        reactive.launchIn(scope, nodeDispatcher)
        runner = TaskRunner(
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            random = Random.Default,
            action = ::performCycle,
            listener = this,
        )
        cycle = CommandCycle(this, sender, navigator, popups, ::isWorking) { relaunching }
        scheduleDriver = ScheduleDriver(
            service = this,
            store = store,
            hasSession = { session != null },
            startSession = { startNewSession(scheduled = true) },
            stopSession = {
                runner.stop()
                endSession(notice = 0)
            },
        )
        applyEventFilter(false)
        instance = this
        ScheduleKeeper.logProcessExits(this, store)
        // Restoring comes first, or the schedule would start a second session over the saved one.
        restoreSession()
        reloadSettings()
        scope.launch { watchSession() }
    }

    private fun onReconnected() {
        instance = this
        applyEventFilter(session != null)
        if (session != null && runner.phase == TaskPhase.IDLE) restoreSession()
    }

    /** Called after the settings screen saves, so schedule changes apply at once. */
    fun reloadSettings() {
        val config = store.loadConfig()
        scheduleDriver.schedule = config.schedule
        bar.vibrate = config.vibrateBeforeSend
        ScheduleKeeper.arm(this, config.schedule)
        tickSchedule()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (session == null || event.packageName?.toString() != WolfUi.PACKAGE) return
        val phase = runner.phase
        if (phase == TaskPhase.RUNNING) popups.onEvent(type)
        if (phase == TaskPhase.RUNNING || phase == TaskPhase.RESTING) reactive.onEvent()
    }

    override fun onInterrupt() = Unit

    /**
     * The snapshot is kept on purpose. A system unbind must not lose the session, and if the user
     * turned the service off, the watchdog tells them why nothing is running.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(EventLog.TAG, "service unbound")
        if (::runner.isInitialized) runner.stop()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(EventLog.TAG, "service destroyed")
        instance = null
        scope.cancel()
        nodeDispatcher.close()
        super.onDestroy()
    }

    fun startOrResume() {
        when (runner.phase) {
            TaskPhase.RUNNING, TaskPhase.RESTING -> Unit
            TaskPhase.PAUSED -> runner.resume()
            TaskPhase.IDLE -> startManually()
        }
    }

    /** A person pressing Start clears a stop, but the schedule still decides the hours. */
    private fun startManually() {
        if (scheduleDriver.isOutsideHours()) return showNotice(R.string.notice_idle_outside_hours)
        store.blockedUntilMs = 0
        startNewSession(scheduled = false)
    }

    fun pauseSession() = runner.pause()

    fun stopSession() {
        if (!::runner.isInitialized) return
        if (session != null) EventLog.add(this, EventType.SESSION_STOPPED)
        runner.stop()
        endSession(notice = 0)
        scheduleDriver.blockRestOfPeriod()
    }

    fun onOverlayClosed() = scheduleDriver.onOverlayClosed()

    fun tickSchedule() {
        if (::scheduleDriver.isInitialized) scheduleDriver.tick()
    }

    private fun startNewSession(scheduled: Boolean) {
        val config = store.loadConfig()
        val errors = config.validate()
        if (errors.isNotEmpty()) return showNotice(Notices.forErrors(errors))

        val inWolf = NodeTools.activePackage(this) == WolfUi.PACKAGE
        if (!inWolf && !scheduled) return showNotice(R.string.notice_open_chat)

        val cycles = ScheduleKeeper.cyclesOf(config, FIRST_SEND_DELAY_MS)
        activate(Session(config))
        TaskBus.update { it.copy(popups = 0, reactions = 0, notice = 0, idleUntilMs = 0) }
        runner.start(cycles.workPlan(SystemClock.elapsedRealtime()), jitterOf(config), cycles)
        // Never pulled out of another app: the channel is checked when they are back in WOLF.
        if (config.restrictToRoom && inWolf) relaunch()
        WatchdogReceiver.schedule(this)
        EventLog.add(this, EventType.SESSION_STARTED)
    }

    /** Resumes a session that was interrupted by the process dying, not by the user. */
    private fun restoreSession() {
        val snapshot = store.loadSession() ?: return
        val elapsed = if (snapshot.paused) {
            0
        } else {
            max(0, System.currentTimeMillis() - snapshot.savedAtWallMs)
        }
        val config = store.loadConfig()
        val cycles = ScheduleKeeper.cyclesOf(config, FIRST_SEND_DELAY_MS)
        if (snapshot.offsets.endInMs - elapsed <= 0 && !cycles.loops) return store.clearSession()

        // The bar is retried by the watch loop, so a refused start does not stop the session.
        OverlayService.start(this)
        val now = SystemClock.elapsedRealtime()
        activate(Session(config))
        TaskBus.update { it.copy(popups = snapshot.popups, notice = 0) }
        runner.start(
            plan = SessionSchedule.fromOffsets(snapshot.offsets, now - elapsed),
            jitterPolicy = jitterOf(config),
            workRest = cycles,
            sentSoFar = snapshot.sent,
            failedSoFar = snapshot.failed,
            paused = snapshot.paused,
            isResting = snapshot.resting,
            workCycleSoFar = snapshot.workCycle,
        )
        WatchdogReceiver.schedule(this)
        EventLog.add(this, EventType.SESSION_RESTORED)
    }

    fun ensureOverlay() {
        if (session != null) OverlayService.start(this)
    }

    private fun activate(newSession: Session) {
        session = newSession
        guard = SessionGuard()
        relaunching = false
        applyEventFilter(true)
        popups.configure(
            target = WolfUi.PACKAGE,
            keywords = newSession.config.popupKeywords,
            skipLists = newSession.config.skipListItems,
        )
        reactive.configure(WolfUi.PACKAGE, newSession.config.reactive)
        TaskBus.update {
            it.copy(target = WolfUi.PACKAGE, keepScreenOn = newSession.config.keepScreenOn)
        }
    }

    private fun endSession(@StringRes notice: Int) {
        session = null
        store.clearSession()
        WatchdogReceiver.cancel(this)
        popups.disable()
        reactive.disable()
        applyEventFilter(false)
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

    /** Events only ever come from WOLF, and are switched off entirely when idle. */
    private fun applyEventFilter(active: Boolean) {
        val info = serviceInfo ?: return
        info.packageNames = arrayOf(WolfUi.PACKAGE)
        info.eventTypes = if (active) WATCHED_EVENTS else 0
        serviceInfo = info
    }

    /** Runs once a second for as long as the service lives. */
    private suspend fun watchSession() {
        var ticks = 0L
        while (true) {
            delay(WATCH_INTERVAL_MS)
            tickSchedule()
            val phase = runner.phase
            val active = phase == TaskPhase.RUNNING || phase == TaskPhase.RESTING
            if (session != null && ++ticks % OVERLAY_RETRY_TICKS == 0L) OverlayService.start(this)
            if (session == null || !active) continue
            val foreground = NodeTools.foreground(this)
            bar.update(runner.view(), phase == TaskPhase.RUNNING, foreground)
            if (relaunching) continue
            val now = SystemClock.elapsedRealtime()
            handle(guard.observe(foreground, phase == TaskPhase.RESTING, now))
        }
    }

    private fun handle(action: GuardAction) {
        when (action) {
            GuardAction.NONE -> Unit
            GuardAction.RELAUNCH -> relaunch()
            GuardAction.STOP_LOCKED ->
                stopWithReason(EventType.STOPPED_LOCKED, R.string.alert_stopped_locked)
            GuardAction.STOP_RELAUNCH_FAILED ->
                stopWithReason(EventType.STOPPED_RELAUNCH, R.string.alert_stopped_relaunch)
            GuardAction.STOP_SEND_FAILURES ->
                stopWithReason(EventType.STOPPED_FAILURES, R.string.alert_stopped_failures, offerRetry = true)
        }
    }

    /** Ends the session for good: the user has to start it again by hand. */
    private fun stopWithReason(type: EventType, @StringRes message: Int, offerRetry: Boolean = false) {
        runner.stop()
        endSession(notice = message)
        scheduleDriver.blockRestOfPeriod()
        EventLog.add(this, type)
        Alerts.post(this, message, offerRetry)
    }

    private fun onPopupSeen() {
        // WOLF gives the popup no sender field that could be read, so this cannot be confirmed.
        EventLog.add(this, EventType.POPUP_TRIGGER)
        EventLog.add(this, EventType.POPUP_SENDER_UNVERIFIED)
    }

    private fun navigateForPopup(room: String) {
        scope.launch {
            val now = SystemClock.elapsedRealtime()
            val inWolf = NodeTools.foreground(this@AutoTaskService) == Foreground.WOLF
            if (session != null && inWolf && !relaunching && guard.mayNavigate(now)) relaunch(room)
        }
    }

    private fun relaunch(targetRoom: String? = null) {
        val current = session ?: return
        relaunching = true
        scope.launch {
            showNotice(R.string.notice_recovering)
            val room = targetRoom ?: current.config.targetRoom.takeIf { current.config.restrictToRoom }.orEmpty()
            val result = withContext(nodeDispatcher) { navigator.recover(room, ::isWorking) }
            relaunching = false
            if (session == null) return@launch
            val success = result == RecoveryResult.READY
            if (result != RecoveryResult.ABORTED) {
                EventLog.add(this@AutoTaskService, Notices.eventFor(result))
                showNotice(Notices.forRecovery(result))
                handle(guard.onRelaunchResult(success, SystemClock.elapsedRealtime()))
            }
        }
    }

    private suspend fun performCycle(): CycleResult {
        val current = session ?: return CycleResult.HELD
        return try {
            sendCommands(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            showNotice(R.string.notice_engine_error)
            recordFailure()
        }
    }

    private fun recordFailure(): CycleResult {
        EventLog.add(this, EventType.SEND_FAILED)
        handle(guard.onSendResult(false))
        return CycleResult.FAILED
    }

    /**
     * Sends the cycle's commands. A command that cannot go out yet holds the slot, which sends it
     * once when the screen is valid again. Commands already sent this cycle are not repeated.
     */
    private suspend fun sendCommands(current: Session): CycleResult {
        val outcome = withContext(nodeDispatcher) { cycle.run(current.config) }
        val hold = outcome.heldBy
        return when {
            hold != null && !outcome.sentAny -> {
                navigateIfNeeded(current.config, hold)
                cycle.recordHold(hold)
                CycleResult.HELD
            }
            outcome.completed || outcome.sentAny -> {
                cycle.clearHold()
                showNotice(0)
                handle(guard.onSendResult(true))
                CycleResult.SENT
            }
            else -> {
                showNotice(outcome.sendNotice)
                recordFailure()
            }
        }
    }

    /**
     * Runs only when a send is due, so someone browsing WOLF between sends is left alone. Without
     * the restriction the app never changes channels.
     */
    private fun navigateIfNeeded(config: TaskConfig, hold: EventType) {
        val wrongPlace = hold == EventType.HELD_WRONG_ROOM || hold == EventType.HELD_NOT_CHANNEL
        val now = SystemClock.elapsedRealtime()
        if (config.restrictToRoom && wrongPlace && !relaunching && guard.mayNavigate(now)) relaunch()
    }

    /** Tries the path to a channel without sending anything. Refused during a session. */
    fun testNavigation(room: String, onDone: (RecoveryResult) -> Unit) {
        if (session != null || relaunching) return
        relaunching = true
        scope.launch {
            val result = withContext(nodeDispatcher) { navigator.recover(room) { true } }
            relaunching = false
            EventLog.add(this@AutoTaskService, Notices.eventFor(result))
            onDone(result)
        }
    }

    /** Opens WOLF only after a tap on the check dialog. */
    fun checkWithoutSending(openWolf: Boolean, onDone: (List<CheckResult>) -> Unit) {
        scope.launch {
            if (openWolf) {
                val intent = packageManager.getLaunchIntentForPackage(WolfUi.PACKAGE)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(intent) }
                delay(CHECK_OPEN_DELAY_MS)
            }
            val config = store.loadConfig()
            onDone(withContext(nodeDispatcher) { ReadOnlyChecker.run(this@AutoTaskService, navigator, config) })
        }
    }

    private fun isWorking() = runner.phase == TaskPhase.RUNNING

    private fun onReactionFinished(success: Boolean) {
        EventLog.add(this, if (success) EventType.REACTION_SENT else EventType.REACTION_FAILED)
        if (success) {
            TaskBus.update { it.copy(reactions = it.reactions + 1) }
        } else {
            showNotice(R.string.notice_reaction_failed)
        }
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
                workCycle = view.workCycle,
            )
        }
    }

    override fun onRestFinished() {
        guard.onRestFinished()
    }

    override fun onPlanChanged() {
        val current = session ?: return
        val offsets = runner.offsets() ?: return
        val view = runner.view()
        store.saveSession(
            SessionSnapshot(
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
        val scheduled = scheduleDriver.schedule.enabled
        endSession(if (scheduled) 0 else R.string.notice_finished)
        if (scheduled) EventLog.add(this, EventType.SCHEDULE_STOP)
    }

    companion object {
        private const val FIRST_SEND_DELAY_MS = 3_000L
        private const val WATCH_INTERVAL_MS = 1_000L
        private const val OVERLAY_RETRY_TICKS = 30L
        private const val CHECK_OPEN_DELAY_MS = 4_000L
        private const val WATCHED_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        /** The connected service, or null while the user has it turned off. */
        @Volatile
        var instance: AutoTaskService? = null
            private set
    }
}
