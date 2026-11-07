package com.autoclicker.chattask.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CheckItem
import com.autoclicker.chattask.core.CheckResult
import com.autoclicker.chattask.core.CheckStatus
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.ReadOnlyCheck
import com.autoclicker.chattask.core.WolfScreen
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.LanguageManager
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.ActivityMainBinding
import com.autoclicker.chattask.databinding.RowPermissionBinding
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.Notices
import com.autoclicker.chattask.service.OverlayService
import com.autoclicker.chattask.service.ScheduleKeeper
import com.autoclicker.chattask.service.TaskBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore
    private lateinit var commandEditor: CommandListEditor
    private lateinit var statusCard: StatusCardView
    private var detachedTicks = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        commandEditor = CommandListEditor(binding.commandList, binding.buttonAddCommand)
        if (savedInstanceState == null) {
            val config = store.loadConfig()
            showConfig(config)
            commandEditor.show(config.commands)
        } else {
            val saved = savedInstanceState.getString(STATE_COMMANDS)?.let(SettingsStore::decodeCommands)
            commandEditor.show(saved ?: store.loadConfig().commands)
        }

        binding.tabsTask.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        val tab = savedInstanceState?.getInt(STATE_TAB) ?: 0
        binding.tabsTask.getTabAt(tab)?.select()
        showTab(tab)

        statusCard = StatusCardView(this, binding)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    statusCard.render(TaskBus.status.value, commandEditor.read())
                    showRecovery()
                    delay(STATUS_REFRESH_MS)
                }
            }
        }
        binding.buttonCheck.setOnClickListener { runCheck(openWolf = false) }
        showOverlaySize()
        binding.textSchedulePrompt.visibility = if (store.schedulePromptPending) View.VISIBLE else View.GONE
        binding.switchReturn.setOnCheckedChangeListener { _, checked ->
            store.returnBeforeSend = checked
            AutoTaskService.instance?.reloadSettings()
        }
        binding.buttonRecover.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.topBar.buttonLanguage.setOnClickListener { LanguageManager.toggle(this) }
        binding.buttonTestChannel.setOnClickListener { testChannel() }
        binding.buttonGuide.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        binding.buttonLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.rowAccessibility.title.setText(R.string.permission_accessibility)
        binding.rowOverlay.title.setText(R.string.permission_overlay)
        binding.rowNotifications.title.setText(R.string.permission_notifications)
        binding.rowBattery.title.setText(R.string.permission_battery)

        binding.rowAccessibility.action.setOnClickListener { showAccessibilityDisclosure() }
        binding.rowOverlay.action.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
        binding.rowNotifications.action.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.rowBattery.action.setOnClickListener { requestBatteryExemption() }
        binding.buttonLaunch.setOnClickListener { launchOverlay() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_COMMANDS, SettingsStore.encodeCommands(commandEditor.read()))
        outState.putInt(STATE_TAB, binding.tabsTask.selectedTabPosition)
    }

    /** Always shown first, so nobody turns the service on without knowing what it does. */
    private fun showAccessibilityDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setPositiveButton(R.string.disclosure_continue) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * After a crash Android can leave the service switched on but not attached. Only the user can
     * fix that, by turning it off and on, so the app says so instead of showing it as granted.
     */
    private fun showRecovery() {
        val setting = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val expected = ComponentName(this, AutoTaskService::class.java).flattenToString()
        val switchedOn = setting.orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
        // A normal rebind takes a second or two, and the card must not flash during one.
        detachedTicks = if (switchedOn && AutoTaskService.instance == null) detachedTicks + 1 else 0
        val detached = detachedTicks >= RECOVERY_DELAY_TICKS
        binding.cardRecovery.visibility = if (detached) View.VISIBLE else View.GONE
    }

    private fun runCheck(openWolf: Boolean) {
        val service = AutoTaskService.instance ?: return showCheck(
            ReadOnlyCheck.evaluate(false, Settings.canDrawOverlays(this), false, WolfScreen(), false, "", false),
        )
        service.checkWithoutSending(openWolf) { results ->
            if (openWolf) startActivity(Intent(this, MainActivity::class.java))
            showCheck(results)
        }
    }

    private fun showCheck(results: List<CheckResult>) {
        val wolfClosed = results.any { it.item == CheckItem.WOLF_OPEN && it.status == CheckStatus.FAIL }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.button_check)
            .setMessage(results.joinToString("\n\n") { CheckText.lineFor(this, it) })
            .setPositiveButton(android.R.string.ok, null)
        if (wolfClosed && AutoTaskService.instance != null) {
            dialog.setNeutralButton(R.string.check_open_wolf) { _, _ -> runCheck(openWolf = true) }
        }
        dialog.show()
    }

    /** Walks to the channel and verifies it. It never types or sends. */
    private fun testChannel() {
        val room = binding.inputRoom.text?.toString().orEmpty().trim()
        val service = AutoTaskService.instance
        when {
            room.isEmpty() -> binding.layoutRoom.error = getString(R.string.error_room_required)
            service == null -> Toast.makeText(this, R.string.toast_permissions_needed, Toast.LENGTH_LONG).show()
            else -> {
                binding.layoutRoom.error = null
                service.testNavigation(room) { result ->
                    val message = Notices.forRecovery(result).takeIf { it != 0 } ?: R.string.toast_channel_ok
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Applies at once: the bar is told straight away, and the choice stays on this device. */
    private fun showOverlaySize() = with(binding) {
        val choices = mapOf(
            R.id.radioSizeSmall to SettingsStore.OVERLAY_SIZE_SMALL,
            R.id.radioSizeMedium to SettingsStore.OVERLAY_SIZE_MEDIUM,
            R.id.radioSizeLarge to SettingsStore.OVERLAY_SIZE_LARGE,
        )
        choices.entries.first { it.value == store.overlaySizeIndex }.let { groupOverlaySize.check(it.key) }
        groupOverlaySize.setOnCheckedChangeListener { _, id ->
            val size = choices[id] ?: return@setOnCheckedChangeListener
            store.overlaySizeIndex = size
            TaskBus.update { it.copy(overlaySize = size) }
        }
    }

    private fun showTab(position: Int) {
        binding.panelTask1.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.panelReactive.visibility = if (position == 1) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun showConfig(config: TaskConfig) = with(binding) {
        inputJitterMin.setText(config.jitterMinSec.toString())
        inputJitterMax.setText(config.jitterMaxSec.toString())
        inputKeywords.setText(config.popupKeywords.joinToString(", "))
        inputRoom.setText(config.targetRoom)
        checkRestrictRoom.isChecked = config.restrictToRoom
        switchReactive.isChecked = config.reactive.enabled
        inputTriggers.setText(config.reactive.triggers.joinToString("\n"))
        inputReaction.setText(config.reactive.command)
        checkRestrictReactive.isChecked = config.reactive.restrictToRoom
        inputReactiveRoom.setText(config.reactive.room)
        switchSkipLists.isChecked = config.skipListItems
        switchKeepScreen.isChecked = config.keepScreenOn
        switchIdleScreen.isChecked = config.keepScreenWhenIdle
        switchVibrate.isChecked = config.vibrateBeforeSend
        switchReturn.isChecked = config.returnBeforeSend
    }

    private fun readConfig() = with(binding) {
        TaskConfig(
            commands = commandEditor.read(),
            jitterMinSec = inputJitterMin.intValue(),
            jitterMaxSec = inputJitterMax.intValue(),
            popupKeywords = TaskConfig.parseKeywords(inputKeywords.text?.toString().orEmpty()),
            targetRoom = inputRoom.text?.toString().orEmpty().trim(),
            restrictToRoom = checkRestrictRoom.isChecked,
            keepScreenWhenIdle = switchIdleScreen.isChecked,
            vibrateBeforeSend = switchVibrate.isChecked,
            returnBeforeSend = switchReturn.isChecked,
            reactive = ReactiveConfig(
                enabled = switchReactive.isChecked,
                triggers = TaskConfig.parseKeywords(inputTriggers.text?.toString().orEmpty()),
                command = inputReaction.text?.toString().orEmpty().trim(),
                restrictToRoom = checkRestrictReactive.isChecked,
                room = inputReactiveRoom.text?.toString().orEmpty().trim(),
            ),
            skipListItems = switchSkipLists.isChecked,
            keepScreenOn = switchKeepScreen.isChecked,
        )
    }

    private fun android.widget.EditText.intValue(): Int = text?.toString()?.toIntOrNull() ?: 0

    private fun showErrors(errors: List<ConfigError>) = with(binding) {
        fun message(vararg matching: ConfigError): String? =
            errors.firstOrNull { it in matching }?.let { getString(messageFor(it)) }

        commandEditor.showBlankError(message(ConfigError.EMPTY_COMMAND))
        val listError = message(
            ConfigError.COMMAND_TOO_LONG,
            ConfigError.TOO_MANY_COMMANDS,
            ConfigError.INTERVAL_TOO_SHORT,
            ConfigError.JITTER_TOO_LARGE,
            ConfigError.SCHEDULE_TIMES,
        )
        textCommandsError.text = listError
        textCommandsError.visibility = if (listError == null) View.GONE else View.VISIBLE
        layoutJitterMin.error = message(ConfigError.JITTER_RANGE)
        layoutRoom.error = message(ConfigError.ROOM_TOO_LONG, ConfigError.ROOM_REQUIRED)
        layoutTriggers.error = message(ConfigError.REACTIVE_NO_TRIGGER)
        layoutReaction.error = message(ConfigError.REACTIVE_NO_COMMAND)
        layoutReactiveRoom.error = message(ConfigError.REACTIVE_ROOM_REQUIRED)
        val inReactive = errors.none { it in TASK1_ERRORS } && errors.any { it in REACTIVE_ERRORS }
        if (errors.isNotEmpty()) tabsTask.getTabAt(if (inReactive) 1 else 0)?.select()
    }

    private fun messageFor(error: ConfigError): Int = when (error) {
        ConfigError.EMPTY_COMMAND -> R.string.error_empty_command
        ConfigError.COMMAND_TOO_LONG -> R.string.error_command_too_long
        ConfigError.TOO_MANY_COMMANDS -> R.string.error_too_many_commands
        ConfigError.INTERVAL_TOO_SHORT -> R.string.error_interval_too_short
        ConfigError.JITTER_RANGE -> R.string.error_jitter_range
        ConfigError.JITTER_TOO_LARGE -> R.string.error_jitter_too_large
        ConfigError.ROOM_TOO_LONG -> R.string.error_room_too_long
        ConfigError.ROOM_REQUIRED, ConfigError.REACTIVE_ROOM_REQUIRED -> R.string.error_room_required
        ConfigError.SCHEDULE_TIMES -> R.string.error_schedule_times
        ConfigError.REACTIVE_NO_TRIGGER -> R.string.error_no_trigger
        ConfigError.REACTIVE_NO_COMMAND -> R.string.error_no_reaction
    }

    private fun launchOverlay() {
        val config = readConfig()
        val errors = config.validate()
        showErrors(errors)
        if (errors.isNotEmpty()) return
        store.saveConfig(config)
        store.schedulePromptPending = false
        binding.textSchedulePrompt.visibility = View.GONE
        AutoTaskService.instance?.reloadSettings()

        if (!isAccessibilityEnabled() || !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_permissions_needed, Toast.LENGTH_LONG).show()
            return
        }
        // Finishing frees memory and leaves no recents card, as both were behind process kills.
        if (OverlayService.start(this)) finishAndRemoveTask()
    }

    private fun refreshPermissions() {
        showPermission(binding.rowAccessibility, isAccessibilityEnabled())
        showPermission(binding.rowOverlay, Settings.canDrawOverlays(this))
        showPermission(binding.rowNotifications, hasNotificationPermission())
        showPermission(binding.rowBattery, isBatteryExempt())
    }

    private fun showPermission(row: RowPermissionBinding, granted: Boolean) {
        row.status.setText(if (granted) R.string.permission_granted else R.string.permission_missing)
        row.action.isEnabled = !granted
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, AutoTaskService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == expected }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isBatteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** The direct request is the only way to reach this setting for one app in a single step. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private companion object {
        const val STATE_COMMANDS = "commands"
        const val STATE_TAB = "tab"
        const val STATUS_REFRESH_MS = 1_000L
        const val RECOVERY_DELAY_TICKS = 10
        val REACTIVE_ERRORS = setOf(
            ConfigError.REACTIVE_NO_TRIGGER,
            ConfigError.REACTIVE_NO_COMMAND,
            ConfigError.REACTIVE_ROOM_REQUIRED,
        )
        val TASK1_ERRORS = ConfigError.entries.toSet() - REACTIVE_ERRORS
    }
}
