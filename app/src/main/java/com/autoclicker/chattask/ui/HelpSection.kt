package com.autoclicker.chattask.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CheckItem
import com.autoclicker.chattask.core.CheckResult
import com.autoclicker.chattask.core.CheckStatus
import com.autoclicker.chattask.core.ReadOnlyCheck
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.core.WolfScreen
import com.autoclicker.chattask.databinding.RowPermissionBinding
import com.autoclicker.chattask.databinding.SectionHelpBinding
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.ScheduleKeeper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun readPermissions(context: Context) = PermissionState(
    accessibility = isAccessibilityEnabled(context),
    overlay = Settings.canDrawOverlays(context),
    notifications = hasNotificationPermission(context),
    battery = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName),
)

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AutoTaskService::class.java)
    return context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { ComponentName.unflattenFromString(it.id) == expected }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** Permissions, the setup guide and the check that sends nothing. Holds no settings of its own. */
class HelpSection(
    private val activity: AppCompatActivity,
    inflater: LayoutInflater,
    parent: ViewGroup,
) : SettingsSection {
    private val binding = SectionHelpBinding.inflate(inflater, parent, false)
    private val notificationPermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onResume() }

    override val view: View get() = binding.root
    override val hasFormFields: Boolean get() = false

    init {
        binding.rowAccessibility.title.setText(R.string.permission_accessibility)
        binding.rowOverlay.title.setText(R.string.permission_overlay)
        binding.rowNotifications.title.setText(R.string.permission_notifications)
        binding.rowBattery.title.setText(R.string.permission_battery)
        binding.rowExactAlarm.title.setText(R.string.permission_exact_alarm)
        binding.rowAccessibility.action.setOnClickListener { showAccessibilityDisclosure() }
        binding.rowOverlay.action.setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        }
        binding.rowNotifications.action.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.rowBattery.action.setOnClickListener { requestBatteryExemption() }
        binding.rowExactAlarm.action.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
            }
        }
        binding.buttonGuide.setOnClickListener { open(Intent(activity, GuideActivity::class.java)) }
        binding.buttonCheck.setOnClickListener { runCheck(openWolf = false) }
    }

    override fun show(config: TaskConfig) = Unit

    override fun read(base: TaskConfig) = base

    override fun onResume() {
        val state = readPermissions(activity)
        showPermission(binding.rowAccessibility, state.accessibility)
        showPermission(binding.rowOverlay, state.overlay)
        showPermission(binding.rowNotifications, state.notifications)
        showPermission(binding.rowBattery, state.battery)
        val exact = ScheduleKeeper.canUseExactAlarms(activity)
        binding.rowExactAlarm.root.visibility = if (exact) View.GONE else View.VISIBLE
        showPermission(binding.rowExactAlarm, exact)
    }

    private fun packageUri() = Uri.parse("package:${activity.packageName}")

    private fun open(intent: Intent) = activity.startActivity(intent)

    private fun showPermission(row: RowPermissionBinding, granted: Boolean) {
        row.status.setText(if (granted) R.string.permission_granted else R.string.permission_missing)
        row.action.isEnabled = !granted
    }

    /** Always shown first, so nobody turns the service on without knowing what it does. */
    private fun showAccessibilityDisclosure() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setPositiveButton(R.string.disclosure_continue) { _, _ ->
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The direct request is the only way to reach this setting for one app in a single step. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() =
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))

    private fun runCheck(openWolf: Boolean) {
        val service = AutoTaskService.instance ?: return showCheck(
            ReadOnlyCheck.evaluate(false, Settings.canDrawOverlays(activity), false, WolfScreen(), false, "", false),
        )
        service.checkWithoutSending(openWolf) { results ->
            if (openWolf) {
                open(
                    Intent(activity, activity.javaClass).putExtras(activity.intent)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
            showCheck(results)
        }
    }

    private fun showCheck(results: List<CheckResult>) {
        val wolfClosed = results.any { it.item == CheckItem.WOLF_OPEN && it.status == CheckStatus.FAIL }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.button_check)
            .setMessage(results.joinToString("\n\n") { CheckText.lineFor(activity, it) })
            .setPositiveButton(android.R.string.ok, null)
        if (wolfClosed && AutoTaskService.instance != null) {
            dialog.setNeutralButton(R.string.check_open_wolf) { _, _ -> runCheck(openWolf = true) }
        }
        dialog.show()
    }
}
