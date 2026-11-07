package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.StringRes
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CheckResult
import com.autoclicker.chattask.core.CommandTimers
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.core.GuardAction
import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.SessionGuard
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.core.TimerOffset
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
import java.time.LocalDateTime
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
    private lateinit var anr: AnrHandler
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
        reactive = ReactiveEngine(this, sender, navigator, ::onReactionFinished, ::navigateForPopup)
        reactive.launchIn(scope, nodeDispatcher)
        runner = TaskRunner(
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            random = Random.Default,
            action = ::performCycle,
            listener = this,
        )
        cycle = CommandCycle(this, sender, navigator, popups, ::isWorking) { relaunching }
        anr = AnrHandler(this, { relaunch() }) {
            stopWithReason(EventType.STOPPED_ANR, R.string.alert_stopped_anr)
        }
        scheduleDriver = ScheduleDriver(
            service = this,
            store = store,
            hasSession = { session != null },
            startSession = { startNewSession(scheduled = true) },
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
        scheduleDriver.config = config
        bar.vibrate = config.vibrateBeforeSend
        bar.returnBeforeSend = config.returnBeforeSend
        ScheduleKeeper.arm(this, config)
        session?.let { applyToSession(it, config) }
        tickSchedule()
    }

    /** The session follows what was saved. Unchanged commands keep their place in the interval. */
    private fun applyToSession(current: Session, config: TaskConfig) {
        if (current.config == config) return
        val updated = Session(config)
        session = updated
        configureEngines(updated)
        val oldCommands = current.config.commands
        runner.replacePlan(jitterOf(config)) { offsets, baseMs ->
            CommandTimers.of(config.commands, FIRST_SEND_DELAY_MS).apply {
                restore(CommandTimers.carryOffsets(oldCommands, offsets, config.commands), baseMs)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (session == null || event.packageName?.toString() != WolfUi.PACKAGE) return
        if (runner.phase != TaskPhase.RUNNING) return
        popups.onEvent(type)
        reactive.onEvent()
    }

    override fun onInterrupt() = Unit

    /** The snapshot stays: a system unbind must not lose the session, and the watchdog reports it. */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(EventLog.TAG, "service unbound")
        if (::runner.isInitialized) runner.stop()
        instance = null
        stopSelf()
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
            TaskPhase.RUNNING -> Unit
            TaskPhase.PAUSED -> runner.resume()
            TaskPhase.IDLE -> startManually()
        }
    }

    /** A person pressing Start clears a stop, but the schedule still decides the hours. */
    private fun startManually() {
        store.blockedUntilMs = 0
        startNewSession(scheduled = false)
    }

    /** Home can start from anywhere: WOLF is opened 5 seconds before the first send is due. */
    fun startFromHome() {
        if (runner.phase != TaskPhase.IDLE) return
        store.blockedUntilMs = 0
        startNewSession(scheduled = true)
    }

    fun pauseSession() = runner.pause()

    fun stopSession() {
        if (!::runner.isInitialized) return
        if (session != null) EventLog.add(this, EventType.SESSION_STOPPED)
        runner.stop()
        endSession(notice = 0)
        scheduleDriver.blockAfterStop()
    }

    fun onOverlayClosed() = scheduleDriver.onOverlayClosed()

    fun tickSchedule() {
        if (::scheduleDriver.isInitialized && session == null) scheduleDriver.tickStopped()
    }

    private fun startNewSession(scheduled: Boolean) {
        val config = store.loadConfig()
        val errors = config.validate()
        if (errors.isNotEmpty()) return showNotice(Notices.forErrors(errors))

        val inWolf = NodeTools.activePackage(this) == WolfUi.PACKAGE
        if (!inWolf && !scheduled) return showNotice(R.string.notice_open_chat)

        activate(Session(config))
        TaskBus.update {
            it.copy(popups = 0, reactions = 0, notice = 0, idleUntilMs = 0, stoppedByError = false)
        }
        val timers = CommandTimers.of(config.commands, FIRST_SEND_DELAY_MS)
        timers.refresh(SystemClock.elapsedRealtime(), LocalDateTime.now())
        runner.start(timers, jitterOf(config))
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
        // The bar is retried by the watch loop, so a refused start does not stop the session.
        OverlayService.start(this)
        val now = SystemClock.elapsedRealtime()
        val timers = CommandTimers.of(config.commands, FIRST_SEND_DELAY_MS)
        timers.restore(snapshot.timers, now - elapsed)
        activate(Session(config))
        TaskBus.update { it.copy(popups = snapshot.popups, notice = 0) }
        runner.start(timers, jitterOf(config), snapshot.sent, snapshot.failed, snapshot.paused)
        WatchdogReceiver.schedule(this)
        EventLog.add(this, EventType.SESSION_RESTORED)
    }

    private fun activate(newSession: Session) {
        session = newSession
        guard = SessionGuard()
        relaunching = false
        applyEventFilter(true)
        configureEngines(newSession)
    }

    private fun configureEngines(newSession: Session) {
        popups.configure(
            target = WolfUi.PACKAGE,
            keywords = newSession.config.popupKeywords,
            skipLists = newSession.config.skipListItems,
        )
        reactive.configure(WolfUi.PACKAGE, newSession.config.reactive)
        TaskBus.update {
            it.copy(
                target = WolfUi.PACKAGE,
                keepScreenOn = newSession.config.keepScreenOn,
                commandNames = newSession.config.commands.map { command -> command.text },
            )
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
            val active = phase == TaskPhase.RUNNING
            if (session != null && ++ticks % OVERLAY_RETRY_TICKS == 0L) OverlayService.start(this)
            if (session == null || !active) continue
            val foreground = NodeTools.foreground(this)
            val view = runner.view()
            val idle = view.allIdle && !reactive.hasPending
            if (anr.tick(SystemClock.elapsedRealtime())) continue
            val returnNow = bar.update(view, !idle, foreground)
            scheduleDriver.tickSession(idle)
            if (relaunching) continue
            if (returnNow && (foreground != Foreground.WOLF || session?.config?.restrictToRoom == true)) {
                Log.i(EventLog.TAG, "return to WOLF, send in ${view.nextInMs}ms from $foreground")
                relaunch()
            }
            val now = SystemClock.elapsedRealtime()
            handle(guard.observe(foreground, idle, now))
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
        TaskBus.update { it.copy(stoppedByError = true) }
        scheduleDriver.blockAfterStop()
        EventLog.add(this, type)
        Alerts.post(this, message, offerRetry)
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

    private suspend fun performCycle(index: Int): CycleResult {
        val current = session ?: return CycleResult.HELD
        return try {
            sendCommand(current, index)
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

    private suspend fun sendCommand(current: Session, index: Int): CycleResult {
        val command = current.config.commands[index].text
        val outcome = withContext(nodeDispatcher) { cycle.send(current.config, command) }
        val hold = outcome.heldBy
        return when {
            outcome.sent -> {
                Log.i(EventLog.TAG, "sent command=${index + 1}")
                cycle.clearHold()
                anr.onRecovered()
                showNotice(0)
                handle(guard.onSendResult(true))
                CycleResult.SENT
            }
            hold != null -> {
                navigateIfNeeded(current.config, hold)
                cycle.recordHold(hold)
                CycleResult.HELD
            }
            else -> {
                showNotice(outcome.sendNotice)
                recordFailure()
            }
        }
    }

    /** Only when a send is due, so browsing is left alone. Unrestricted, channels never change. */
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

    fun checkWithoutSending(openWolf: Boolean, onDone: (List<CheckResult>) -> Unit) {
        scope.launch {
            if (openWolf) ReadOnlyChecker.openWolf(this@AutoTaskService)
            val config = store.loadConfig()
            onDone(withContext(nodeDispatcher) { ReadOnlyChecker.run(this@AutoTaskService, navigator, config) })
        }
    }

    private fun isWorking() = runner.phase == TaskPhase.RUNNING

    private fun onReactionFinished(success: Boolean) {
        EventLog.add(this, if (success) EventType.REACTION_SENT else EventType.REACTION_FAILED)
        TaskBus.update {
            if (success) it.copy(reactions = it.reactions + 1) else it.copy(notice = R.string.notice_reaction_failed)
        }
    }

    private fun showNotice(@StringRes notice: Int) = TaskBus.update { it.copy(notice = notice) }

    private fun jitterOf(config: TaskConfig) = JitterPolicy(config.jitterMinSec, config.jitterMaxSec)

    override fun onTick(view: RunnerView) = TaskBus.show(view)

    override fun onCommandSkipped(index: Int) {
        EventLog.add(this, EventType.COMMAND_SKIPPED)
    }

    override fun onPlanChanged() {
        val current = session ?: return
        val view = runner.view()
        store.saveSession(
            SessionSnapshot(
                paused = view.phase == TaskPhase.PAUSED,
                sent = view.sent,
                failed = view.failed,
                popups = TaskBus.status.value.popups,
                savedAtWallMs = System.currentTimeMillis(),
                timers = runner.timerOffsets(),
            ),
        )
    }

    companion object {
        private const val FIRST_SEND_DELAY_MS = 3_000L
        private const val WATCH_INTERVAL_MS = 1_000L
        private const val OVERLAY_RETRY_TICKS = 30L
        private const val WATCHED_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        /** The connected service, or null while the user has it turned off. */
        @Volatile
        var instance: AutoTaskService? = null
            private set
    }
}
