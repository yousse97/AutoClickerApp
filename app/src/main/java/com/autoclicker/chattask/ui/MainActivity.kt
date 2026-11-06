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
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.ActivityMainBinding
import com.autoclicker.chattask.databinding.RowPermissionBinding
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.OverlayService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        if (savedInstanceState == null) showConfig(store.loadConfig())

        binding.rowAccessibility.title.setText(R.string.permission_accessibility)
        binding.rowOverlay.title.setText(R.string.permission_overlay)
        binding.rowNotifications.title.setText(R.string.permission_notifications)
        binding.rowBattery.title.setText(R.string.permission_battery)

        binding.rowAccessibility.action.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
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

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun showConfig(config: TaskConfig) = with(binding) {
        inputCommand.setText(config.command)
        inputIntervalMinutes.setText((config.intervalSec / 60).toString())
        inputIntervalSeconds.setText((config.intervalSec % 60).toString())
        inputDurationHours.setText((config.durationSec / 3600).toString())
        inputDurationMinutes.setText((config.durationSec % 3600 / 60).toString())
        inputJitterMin.setText(config.jitterMinSec.toString())
        inputJitterMax.setText(config.jitterMaxSec.toString())
        inputKeywords.setText(config.popupKeywords.joinToString(", "))
        inputTarget.setText(config.targetPackage)
        switchSkipLists.isChecked = config.skipListItems
        switchKeepScreen.isChecked = config.keepScreenOn
    }

    private fun readConfig() = with(binding) {
        TaskConfig(
            command = inputCommand.text?.toString().orEmpty().trim(),
            intervalSec = seconds(
                inputIntervalMinutes.intValue() * 60L + inputIntervalSeconds.intValue(),
            ),
            durationSec = seconds(
                inputDurationHours.intValue() * 3600L + inputDurationMinutes.intValue() * 60L,
            ),
            jitterMinSec = inputJitterMin.intValue(),
            jitterMaxSec = inputJitterMax.intValue(),
            popupKeywords = TaskConfig.parseKeywords(inputKeywords.text?.toString().orEmpty()),
            targetPackage = inputTarget.text?.toString().orEmpty().trim(),
            skipListItems = switchSkipLists.isChecked,
            keepScreenOn = switchKeepScreen.isChecked,
        )
    }

    private fun android.widget.EditText.intValue(): Int = text?.toString()?.toIntOrNull() ?: 0

    private fun seconds(value: Long): Int = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun showErrors(errors: List<ConfigError>) = with(binding) {
        fun message(vararg matching: ConfigError): String? =
            errors.firstOrNull { it in matching }?.let { getString(messageFor(it)) }

        layoutCommand.error = message(ConfigError.EMPTY_COMMAND, ConfigError.COMMAND_TOO_LONG)
        layoutIntervalMinutes.error = message(ConfigError.INTERVAL_TOO_SHORT)
        layoutDurationHours.error = message(ConfigError.DURATION_TOO_SHORT)
        layoutJitterMin.error = message(ConfigError.JITTER_RANGE, ConfigError.JITTER_TOO_LARGE)
        layoutTarget.error = message(ConfigError.BAD_PACKAGE)
    }

    private fun messageFor(error: ConfigError): Int = when (error) {
        ConfigError.EMPTY_COMMAND -> R.string.error_empty_command
        ConfigError.COMMAND_TOO_LONG -> R.string.error_command_too_long
        ConfigError.INTERVAL_TOO_SHORT -> R.string.error_interval_too_short
        ConfigError.DURATION_TOO_SHORT -> R.string.error_duration_too_short
        ConfigError.JITTER_RANGE -> R.string.error_jitter_range
        ConfigError.JITTER_TOO_LARGE -> R.string.error_jitter_too_large
        ConfigError.BAD_PACKAGE -> R.string.error_bad_package
    }

    private fun launchOverlay() {
        val config = readConfig()
        val errors = config.validate()
        showErrors(errors)
        if (errors.isNotEmpty()) return
        store.saveConfig(config)

        if (!isAccessibilityEnabled() || !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_permissions_needed, Toast.LENGTH_LONG).show()
            return
        }
        if (OverlayService.start(this)) moveTaskToBack(true)
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
}
