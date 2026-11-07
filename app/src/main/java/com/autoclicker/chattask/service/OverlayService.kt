package com.autoclicker.chattask.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.util.Log
import android.util.TypedValue
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.BarState
import com.autoclicker.chattask.core.TapTracker
import com.autoclicker.chattask.core.WolfUi
import com.autoclicker.chattask.core.formatDuration
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.data.LanguageManager
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.OverlayControlsBinding
import com.autoclicker.chattask.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Date

/**
 * Foreground service hosting the floating controls.
 *
 * The foreground notification keeps the process at high priority. The overlay window is not
 * focusable, so the chat stays the active window and its keyboard stays open.
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var binding: OverlayControlsBinding
    private lateinit var params: WindowManager.LayoutParams
    private var overlayAttached = false
    private var shownPhase: TaskPhase? = null
    private var compact = false
    private var shownColor = 0
    private var shownLook = -1
    private var shownSize = -1
    private lateinit var store: SettingsStore
    private lateinit var wakeLock: PowerManager.WakeLock
    private val texts: Context get() = LanguageManager.localize(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        store = SettingsStore(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoClicker:session")
            .apply { setReferenceCounted(false) }
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(TaskPhase.IDLE), foregroundType())
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        attachOverlay()
        TaskBus.status.onEach(::render).launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) {
            AutoTaskService.instance?.stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RETRY) AutoTaskService.instance?.startOrResume()
        return START_STICKY
    }

    /** Android asks for memory back: drop what can be reloaded, as the session is on disk. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(EventLog.TAG, "trim memory level=$level")
        EventLog.releaseCache()
        LanguageManager.releaseCache()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!overlayAttached) return
        clampToScreen()
        windowManager.updateViewLayout(binding.root, params)
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        applyWakeLock(false)
        if (overlayAttached) windowManager.removeView(binding.root)
        overlayAttached = false
        super.onDestroy()
    }

    private fun attachOverlay() {
        val themed = ContextThemeWrapper(texts, R.style.Theme_AutoClicker)
        binding = OverlayControlsBinding.inflate(LayoutInflater.from(themed))
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val savedX = store.overlayX
            x = if (savedX == SettingsStore.NO_POSITION) defaultX() else savedX
            val savedY = store.overlayY
            y = if (savedY == SettingsStore.NO_POSITION) defaultY() else savedY
        }

        binding.buttonStart.setOnClickListener {
            val service = AutoTaskService.instance
            if (service == null) {
                TaskBus.update { it.copy(notice = R.string.notice_enable_service) }
            } else {
                service.startOrResume()
            }
        }
        binding.buttonPause.setOnClickListener { AutoTaskService.instance?.pauseSession() }
        binding.buttonStop.setOnClickListener {
            if (TaskBus.status.value.phase == TaskPhase.IDLE) {
                AutoTaskService.instance?.onOverlayClosed()
                stopSelf()
            } else {
                AutoTaskService.instance?.stopSession()
            }
        }
        binding.buttonShrink.setOnClickListener { setCompact(true) }
        binding.buttonSize.setOnClickListener {
            store.overlaySizeIndex = (store.overlaySizeIndex + 1) % SIZE_SCALES.size
            TaskBus.update { it.copy(overlaySize = store.overlaySizeIndex) }
            applyOverlaySize()
        }
        applyOverlaySize()
        val drag = DragListener()
        binding.root.setOnTouchListener(drag)
        binding.statusArea.setOnTouchListener(drag)

        clampToScreen()
        windowManager.addView(binding.root, params)
        overlayAttached = true
    }

    private fun defaultX() = resources.getDimensionPixelOffset(R.dimen.overlay_start_x)

    private fun defaultY() = resources.getDimensionPixelOffset(R.dimen.overlay_start_y)

    private val controls: List<View>
        get() = listOf(binding.textDetail, binding.textCommand, binding.progressNext, binding.rowButtons)

    /** The badge shows the countdown and state dot, and a tap on it brings the controls back. */
    private fun setCompact(value: Boolean) {
        compact = value
        TransitionManager.beginDelayedTransition(binding.content, AutoTransition().setDuration(TRANSITION_MS))
        controls.forEach { it.visibility = if (value) View.GONE else View.VISIBLE }
        binding.textState.visibility = if (value) View.GONE else View.VISIBLE
        binding.statusArea.orientation = if (value) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.statusArea.gravity = Gravity.CENTER_VERTICAL
        binding.content.minimumWidth = if (value) 0 else resources.getDimensionPixelSize(R.dimen.overlay_card_min_width)
        applyOverlaySize()
        render(TaskBus.status.value)
    }

    /** Text scales with the size setting. Buttons never go under their 48dp touch target. */
    private fun applyOverlaySize() {
        shownSize = store.overlaySizeIndex.coerceIn(SIZE_SCALES.indices)
        val scale = SIZE_SCALES[shownSize]
        val countdownSp = if (compact) BADGE_TEXT_SP else COUNTDOWN_TEXT_SP
        binding.textCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, countdownSp * scale)
        binding.textState.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATE_TEXT_SP * scale)
        binding.textCommand.setTextSize(TypedValue.COMPLEX_UNIT_SP, COMMAND_TEXT_SP * scale)
        binding.textDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, DETAIL_TEXT_SP * scale)
        val smallest = resources.getDimensionPixelSize(R.dimen.overlay_button_size)
        val buttonSize = (smallest * scale).toInt().coerceAtLeast(smallest)
        listOf(
            binding.buttonStart,
            binding.buttonPause,
            binding.buttonStop,
            binding.buttonShrink,
            binding.buttonSize,
        ).forEach { button ->
            button.layoutParams = button.layoutParams.apply {
                width = buttonSize
                height = buttonSize
            }
        }
        if (!overlayAttached) return
        clampToScreen()
        windowManager.updateViewLayout(binding.root, params)
    }

    private fun render(status: TaskStatus) {
        // The bar lives through a language change, so its direction has to follow it.
        val direction = texts.resources.configuration.layoutDirection
        if (binding.root.layoutDirection != direction) binding.root.layoutDirection = direction
        if (status.overlaySize >= 0 && status.overlaySize != shownSize) applyOverlaySize()
        val idle = status.idleUntilMs > 0
        val state = homeStateOf(status, status.stoppedByError)
        binding.textCountdown.show(
            when {
                idle -> idleTime(status)
                status.phase != TaskPhase.IDLE -> formatDuration(status.nextInMs)
                else -> "--:--"
            },
        )
        binding.textState.show(texts.getString(labelOf(state)))
        val name = status.nextIndex?.let(status.commandNames::getOrNull)
        val showName = !compact && !name.isNullOrBlank() && status.phase == TaskPhase.RUNNING && !idle
        binding.textCommand.visibility = if (showName) View.VISIBLE else View.GONE
        binding.textCommand.show(name.orEmpty())
        binding.textDetail.show(detailOf(status, idle))
        val active = status.phase == TaskPhase.RUNNING
        binding.buttonStart.isEnabled = !active
        binding.buttonPause.isEnabled = active
        applyKeepScreenOn(if (idle) status.idleKeepScreen else active && status.keepScreenOn)
        applyWakeLock(active || idle)
        applyState(state, status)
        if (status.phase != shownPhase) {
            shownPhase = status.phase
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(status.phase))
        }
    }

    /** Setting identical text still redraws, and this runs every second for hours. */
    private fun TextView.show(value: String) {
        if (text.toString() != value) text = value
    }

    private fun detailOf(status: TaskStatus, idle: Boolean): String = when {
        idle -> idleText(status)
        status.barState == BarState.COMMAND_READY -> texts.getString(R.string.overlay_command_ready)
        status.notice != 0 -> texts.getString(status.notice)
        status.phase == TaskPhase.IDLE -> ""
        else -> texts.getString(
            R.string.overlay_detail,
            status.sent,
            status.failed,
            status.popups,
            status.reactions,
        )
    }

    private fun labelOf(state: HomeState) = when (state) {
        HomeState.RUNNING -> R.string.status_running
        HomeState.ATTENTION -> R.string.status_attention
        HomeState.PAUSED -> R.string.status_paused
        HomeState.IDLE -> R.string.status_idle
        HomeState.ERROR -> R.string.status_error_short
        HomeState.STOPPED -> R.string.status_stopped
    }

    private fun colorOf(state: HomeState) = when (state) {
        HomeState.RUNNING -> R.color.overlay_running
        HomeState.ATTENTION -> R.color.overlay_attention
        HomeState.PAUSED -> R.color.overlay_paused
        HomeState.IDLE -> R.color.overlay_idle
        HomeState.ERROR -> R.color.overlay_error
        HomeState.STOPPED -> R.color.overlay_stopped
    }

    private fun idleTime(status: TaskStatus): String =
        DateFormat.getTimeFormat(texts).format(Date(status.idleUntilMs))

    private fun idleText(status: TaskStatus): String =
        texts.getString(R.string.status_idle_until, idleTime(status))

    /** The state always has a text label too, so color is never the only signal. */
    private fun applyState(state: HomeState, status: TaskStatus) {
        val ready = status.barState == BarState.COMMAND_READY
        val soon = status.barState == BarState.SEND_SOON
        val color = getColor(if (ready) R.color.overlay_error else colorOf(state))
        if (color != shownColor) {
            val from = shownColor
            shownColor = color
            ValueAnimator.ofArgb(from, color).apply {
                duration = TRANSITION_MS
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Int
                    binding.stateDot.background.setTint(value)
                    binding.progressNext.progressTintList = ColorStateList.valueOf(value)
                }
                start()
            }
        }
        val look = if (ready) 2 else if (soon) 1 else 0
        if (look != shownLook) {
            shownLook = look
            val countdown = if (look == 0) getColor(R.color.overlay_text) else color
            binding.textCountdown.setTextColor(countdown)
            binding.root.setCardBackgroundColor(getColor(if (ready) R.color.overlay_card_alert else R.color.overlay_card))
        }
        binding.progressNext.progress = if (status.idleUntilMs > 0) 0 else progressOf(status)
    }

    /** The share of the interval still left for the next command. */
    private fun progressOf(status: TaskStatus): Int {
        val total = status.nextTotalMs
        if (total <= 0) return 0
        return (status.nextInMs.coerceIn(0, total) * PROGRESS_MAX / total).toInt()
    }

    /** Only ever called from a tap by the user. */
    private fun openWolf() {
        val intent = packageManager.getLaunchIntentForPackage(WolfUi.PACKAGE) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /** Keeps timers and detection alive while the user-started session is active. */
    @SuppressLint("WakelockTimeout")
    private fun applyWakeLock(active: Boolean) {
        if (active && !wakeLock.isHeld) wakeLock.acquire()
        if (!active && wakeLock.isHeld) wakeLock.release()
    }

    /** A locked phone has no chat window to type into or reopen, so this covers rest as well. */
    private fun applyKeepScreenOn(keepOn: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if ((params.flags and flag != 0) == keepOn) return
        params.flags = if (keepOn) params.flags or flag else params.flags and flag.inv()
        windowManager.updateViewLayout(binding.root, params)
    }

    /** Stays on screen, and stops short of the message bar at the bottom of a WOLF chat. */
    private fun clampToScreen() {
        val metrics = resources.displayMetrics
        binding.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = binding.root.measuredWidth
        val height = binding.root.measuredHeight
        val reserved = resources.getDimensionPixelSize(R.dimen.overlay_bottom_reserve)
        params.x = params.x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - height - reserved).coerceAtLeast(0))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            texts.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(phase: TaskPhase): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val shutdownIntent = Intent(this, OverlayService::class.java).setAction(ACTION_SHUTDOWN)
        val shutdown = PendingIntent.getService(this, 1, shutdownIntent, flags)
        val text = when (phase) {
            TaskPhase.RUNNING -> R.string.notification_running
            TaskPhase.PAUSED -> R.string.notification_paused
            TaskPhase.IDLE -> R.string.notification_idle
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(texts.getString(R.string.app_name))
            .setContentText(texts.getString(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, texts.getString(R.string.notification_exit), shutdown)
            .build()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private inner class DragListener : View.OnTouchListener {
        private val tracker = TapTracker(ViewConfiguration.get(this@OverlayService).scaledTouchSlop.toFloat())
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    tracker.down(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker.move(event.rawX, event.rawY)
                    if (tracker.isDrag) {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        clampToScreen()
                        windowManager.updateViewLayout(binding.root, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (tracker.up(event.rawX, event.rawY)) {
                    // On the badge a tap keeps its expand action, and the full bar then opens WOLF.
                    if (compact) setCompact(false) else openWolf()
                } else {
                    store.overlayX = params.x
                    store.overlayY = params.y
                }
            }
            return true
        }
    }

    companion object {
        private const val CHANNEL_ID = "automation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_SHUTDOWN = "com.autoclicker.chattask.action.SHUTDOWN"
        const val ACTION_RETRY = "com.autoclicker.chattask.action.RETRY"
        private val SIZE_SCALES = listOf(0.85f, 1f, 1.2f)
        private const val TRANSITION_MS = 180L
        private const val PROGRESS_MAX = 1000L

        @Volatile
        var isRunning = false
            private set

        private const val BADGE_TEXT_SP = 15f
        private const val COUNTDOWN_TEXT_SP = 28f
        private const val STATE_TEXT_SP = 12f
        private const val COMMAND_TEXT_SP = 12f
        private const val DETAIL_TEXT_SP = 11f

        /** Returns false if Android refused, for example a background start on a recent version. */
        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
            }.isSuccess
        }
    }
}
