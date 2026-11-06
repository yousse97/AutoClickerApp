package com.autoclicker.chattask.service

import android.annotation.SuppressLint
import android.util.TypedValue
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.formatDuration
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
import kotlin.math.abs

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
    private lateinit var store: SettingsStore
    private lateinit var wakeLock: PowerManager.WakeLock
    private val texts: Context get() = LanguageManager.localize(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!overlayAttached) return
        clampToScreen()
        windowManager.updateViewLayout(binding.root, params)
    }

    override fun onDestroy() {
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
            gravity = Gravity.TOP or Gravity.START
            x = resources.getDimensionPixelOffset(R.dimen.overlay_start_x)
            y = resources.getDimensionPixelOffset(R.dimen.overlay_start_y)
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
                stopSelf()
            } else {
                AutoTaskService.instance?.stopSession()
            }
        }
        binding.buttonShrink.setOnClickListener { setCompact(true) }
        binding.buttonSize.setOnClickListener {
            store.overlaySizeIndex = (store.overlaySizeIndex + 1) % SIZE_SCALES.size
            applyOverlaySize()
        }
        applyOverlaySize()
        val drag = DragListener()
        binding.root.setOnTouchListener(drag)
        binding.statusArea.setOnTouchListener(drag)

        windowManager.addView(binding.root, params)
        overlayAttached = true
    }

    /** The tiny badge shows only the countdown, and a tap on it brings the controls back. */
    private fun setCompact(value: Boolean) {
        compact = value
        val controls = if (value) View.GONE else View.VISIBLE
        binding.textDetail.visibility = controls
        listOf(
            binding.buttonStart,
            binding.buttonPause,
            binding.buttonStop,
            binding.buttonShrink,
            binding.buttonSize,
        ).forEach { it.visibility = controls }
        applyOverlaySize()
    }

    private fun applyOverlaySize() {
        val scale = SIZE_SCALES[store.overlaySizeIndex.coerceIn(SIZE_SCALES.indices)]
        val titleSp = if (compact) BADGE_TEXT_SP else TITLE_TEXT_SP
        binding.textCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp * scale)
        binding.textDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, DETAIL_TEXT_SP * scale)
        val minWidth = resources.getDimensionPixelSize(R.dimen.overlay_status_min_width)
        binding.statusArea.minimumWidth = if (compact) 0 else (minWidth * scale).toInt()
        val buttonSize = (resources.getDimensionPixelSize(R.dimen.overlay_button_size) * scale).toInt()
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
        if (overlayAttached) windowManager.updateViewLayout(binding.root, params)
    }

    private fun render(status: TaskStatus) {
        binding.textCountdown.text = when (status.phase) {
            TaskPhase.RUNNING -> formatDuration(status.nextInMs)
            TaskPhase.RESTING -> texts.getString(R.string.overlay_resting, formatDuration(status.nextInMs))
            TaskPhase.PAUSED -> texts.getString(R.string.overlay_paused)
            TaskPhase.IDLE -> texts.getString(R.string.overlay_ready)
        }
        binding.textDetail.text = if (status.notice != 0) {
            texts.getString(status.notice)
        } else {
            texts.getString(
                R.string.overlay_detail,
                formatDuration(status.sessionLeftMs),
                status.sent,
                status.failed,
                status.popups,
                status.workCycle,
                status.reactions,
            )
        }
        val active = status.phase == TaskPhase.RUNNING || status.phase == TaskPhase.RESTING
        binding.buttonStart.isEnabled = !active
        binding.buttonPause.isEnabled = active
        applyKeepScreenOn(active && status.keepScreenOn)
        applyWakeLock(active)
        if (status.phase != shownPhase) {
            shownPhase = status.phase
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(status.phase))
        }
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

    private fun clampToScreen() {
        val metrics = resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - binding.root.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - binding.root.height).coerceAtLeast(0))
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
            TaskPhase.RESTING -> R.string.notification_resting
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
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchX
                    val deltaY = event.rawY - touchY
                    if (abs(deltaX) > slop || abs(deltaY) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + deltaX.toInt()
                        params.y = startY + deltaY.toInt()
                        clampToScreen()
                        windowManager.updateViewLayout(binding.root, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (compact && !dragging) setCompact(false)
            }
            return true
        }
    }

    companion object {
        private const val CHANNEL_ID = "automation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_SHUTDOWN = "com.autoclicker.chattask.action.SHUTDOWN"
        private val SIZE_SCALES = listOf(0.8f, 1f, 1.25f)
        private const val BADGE_TEXT_SP = 12f
        private const val TITLE_TEXT_SP = 16f
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
