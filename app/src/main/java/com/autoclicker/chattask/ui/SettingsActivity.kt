package com.autoclicker.chattask.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.ActivitySettingsBinding
import com.autoclicker.chattask.databinding.ItemCollapsibleBinding
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.TaskBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Shows one section, or all of them as collapsible groups. Fields apply when saved or on leaving
 * the page, never mid-typing, because a saved change reaches a running session at once.
 */
class SettingsActivity : AppCompatActivity() {
    private class Group(val kind: SettingsSectionKind, val section: SettingsSection) {
        var item: ItemCollapsibleBinding? = null
        var expanded = true
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: SettingsStore
    private lateinit var groups: List<Group>
    private lateinit var baseline: TaskConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        val page = SettingsPage.entries.getOrElse(intent.getIntExtra(EXTRA_PAGE, 0)) { SettingsPage.COMMANDS }
        binding.pageBar.root.setTitle(page.title)
        binding.pageBar.root.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        groups = page.sections.map { Group(it, buildSection(it)) }
        baseline = store.loadConfig()
        groups.forEach { it.section.show(baseline) }
        savedInstanceState?.let { state -> groups.forEach { it.section.restoreState(state) } }
        val collapsible = page == SettingsPage.ALL
        groups.forEachIndexed { index, group ->
            if (collapsible) {
                group.expanded = savedInstanceState?.getBoolean(stateKey(group)) ?: (index == 0)
                addCollapsible(group)
            } else {
                binding.pageContent.addView(group.section.view)
            }
        }
        val hasFields = groups.any { it.section.hasFormFields }
        binding.buttonSave.visibility = if (hasFields) View.VISIBLE else View.GONE
        binding.buttonSave.setOnClickListener {
            if (trySave() && isDirty().not()) Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        }
        onBackPressedDispatcher.addCallback(this, leaveCallback)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TaskBus.status.collect { status -> groups.forEach { it.section.onStatus(status) } }
            }
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_ERRORS, false)) showErrors(visibleErrors(form()))
    }

    private val leaveCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                !isDirty() || trySave() -> leave()
                else -> confirmDiscard()
            }
        }
    }

    private fun leave() {
        leaveCallback.isEnabled = false
        finish()
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.button_keep_editing, null)
            .setNegativeButton(R.string.button_discard) { _, _ ->
                groups.forEach { it.section.show(baseline) }
                leave()
            }
            .show()
    }

    private fun buildSection(kind: SettingsSectionKind): SettingsSection {
        val parent = binding.pageContent
        return when (kind) {
            SettingsSectionKind.COMMANDS -> CommandsSection(layoutInflater, parent, store)
            SettingsSectionKind.AUTO_REPLY -> AutoReplySection(layoutInflater, parent, store, ::startReplies)
            SettingsSectionKind.CHANNEL -> ChannelSection(layoutInflater, parent) { trySave() }
            SettingsSectionKind.TIMER -> TimerSection(layoutInflater, parent, store) { trySave() }
            SettingsSectionKind.HELP -> HelpSection(this, layoutInflater, parent)
        }
    }

    /** What is on the page counts, so a change made here does not need Save first. */
    private fun startReplies() {
        if (trySave()) SessionControl.start(this) {}
    }

    private fun addCollapsible(group: Group) {
        val item = ItemCollapsibleBinding.inflate(layoutInflater, binding.pageContent, false)
        group.item = item
        item.title.setText(group.kind.title)
        item.header.contentDescription = getString(group.kind.title)
        item.body.addView(group.section.view)
        item.header.setOnClickListener {
            group.expanded = !group.expanded
            renderExpansion(group)
        }
        renderExpansion(group)
        binding.pageContent.addView(item.root)
    }

    private fun renderExpansion(group: Group) {
        val item = group.item ?: return
        item.body.visibility = if (group.expanded) View.VISIBLE else View.GONE
        item.chevron.rotation = if (group.expanded) 180f else 0f
    }

    /** The mode changes outside these fields, and which fields must be valid depends on it. */
    private fun form(): TaskConfig =
        groups.fold(baseline.copy(mode = store.operatingMode)) { config, group -> group.section.read(config) }

    private fun isDirty() = form() != baseline

    /** Errors outside the sections on screen are the home screen's to report, not this page's. */
    private fun visibleErrors(config: TaskConfig): List<ConfigError> {
        val owned = groups.flatMap { it.kind.errors }
        return config.validate().filter { it in owned }
    }

    private fun showErrors(errors: List<ConfigError>) {
        groups.forEach { group ->
            val own = errors.filter { it in group.kind.errors }
            group.section.showErrors(own) { getString(messageFor(it)) }
            if (own.isNotEmpty() && !group.expanded) {
                group.expanded = true
                renderExpansion(group)
            }
        }
    }

    /** Saves if the fields on screen are valid, and marks them if they are not. */
    private fun trySave(): Boolean {
        val config = form()
        val errors = visibleErrors(config)
        showErrors(errors)
        if (errors.isNotEmpty()) return false
        if (config != baseline) {
            store.saveConfig(config)
            store.legacySchedulePrompt = false
            baseline = config
            AutoTaskService.instance?.reloadSettings()
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        // Anything saved from another view shows here, unless there are unsaved edits to keep.
        val saved = store.loadConfig()
        if (saved != baseline && !isDirty()) {
            baseline = saved
            groups.forEach { it.section.show(saved) }
        }
        groups.forEach { it.section.onResume() }
    }

    override fun onPause() {
        super.onPause()
        if (leaveCallback.isEnabled && isDirty()) trySave()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        groups.forEach {
            it.section.saveState(outState)
            outState.putBoolean(stateKey(it), it.expanded)
        }
    }

    private fun stateKey(group: Group) = "expanded_${group.kind.name}"

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_SHOW_ERRORS = "show_errors"

        fun intent(context: Context, page: SettingsPage, showErrors: Boolean = false): Intent =
            Intent(context, SettingsActivity::class.java)
                .putExtras(bundleOf(EXTRA_PAGE to page.ordinal, EXTRA_SHOW_ERRORS to showErrors))
    }
}
