package com.autoclicker.chattask.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.LanguageChoice
import com.autoclicker.chattask.data.LanguageManager
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.ActivityMainBinding
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.TaskBus
import com.autoclicker.chattask.service.TaskPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Home: status, what needs attention, and Start or Stop. Settings live behind the menu. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore
    private lateinit var statusCard: StatusCardView
    private var config = TaskConfig()
    private var quick: SettingsSection? = null
    private var quickForm: QuickForm? = null
    private var warnings: List<HomeWarning> = emptyList()
    private var detachedTicks = 0

    private val closeDrawer = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = binding.drawer.closeDrawer(GravityCompat.START)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        config = store.loadConfig()
        statusCard = StatusCardView(this, binding)
        showQuick(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val status = TaskBus.status.value
                    statusCard.render(status, config)
                    binding.textQuickState.setText(if (quickDirty()) R.string.quick_unsaved else R.string.quick_saved)
                    val running = status.phase != TaskPhase.IDLE
                    binding.buttonStartStop.setText(if (running) R.string.button_stop else R.string.button_start)
                    showRecovery()
                    delay(STATUS_REFRESH_MS)
                }
            }
        }

        binding.buttonStartStop.setOnClickListener { startOrStop() }
        binding.buttonQuickSave.setOnClickListener { saveQuick() }
        binding.buttonFix.setOnClickListener {
            warnings.firstOrNull()?.let { openPage(it.page, showErrors = true) }
        }
        binding.buttonRecover.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.topBar.buttonLanguage.setOnClickListener(::showLanguages)
        binding.topBar.buttonMenu.setOnClickListener { binding.drawer.openDrawer(GravityCompat.START) }
        binding.navigation.setNavigationItemSelectedListener { item ->
            binding.drawer.closeDrawer(GravityCompat.START)
            modeOfMenuItem(item.itemId)?.let { mode ->
                if (ModeChoice.select(this, store, mode)) refresh()
                return@setNavigationItemSelectedListener true
            }
            when (val destination = destinationOf(item.itemId)) {
                is Destination.Page -> openPage(destination.page)
                Destination.Log -> startActivity(Intent(this, LogActivity::class.java))
                Destination.About -> startActivity(Intent(this, AboutActivity::class.java))
                null -> Unit
            }
            false
        }
        binding.drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                closeDrawer.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                closeDrawer.isEnabled = false
            }
        })
        closeDrawer.isEnabled = binding.drawer.isDrawerOpen(GravityCompat.START)
        onBackPressedDispatcher.addCallback(this, closeDrawer)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        config = store.loadConfig()
        binding.textMode.setText(config.mode.title)
        binding.navigation.setCheckedItem(menuItemOf(config.mode))
        showQuick(null)
        warnings = homeWarnings(config, readPermissions(this))
        binding.cardWarning.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        binding.textWarning.text = warnings.joinToString("\n") { getString(it.text) }
    }

    /** Fields being edited stay, and anything else follows what is saved. */
    private fun showQuick(state: Bundle?) {
        val form = QuickForm.of(store.operatingMode)
        val keep = quickForm == form && quickDirty()
        if (quickForm != form) {
            binding.quickContent.removeAllViews()
            quick = quickSection(form, layoutInflater, binding.quickContent).also { binding.quickContent.addView(it.view) }
            quickForm = form
        }
        if (!keep) quick?.show(config)
        state?.let { quick?.restoreState(it) }
    }

    private fun quickDirty(): Boolean = quick?.let { it.read(config) != config } == true

    /** Saves and nothing more. A session in progress follows it, but Save never starts or sends. */
    private fun saveQuick() {
        val section = quick ?: return
        val saved = store.loadConfig()
        val form = section.read(saved)
        val errors = form.validate()
        section.showErrors(errors) { getString(messageFor(it)) }
        if (errors.isNotEmpty()) return
        store.saveConfig(form)
        AutoTaskService.instance?.reloadSettings()
        refresh()
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        quick?.saveState(outState)
    }

    private fun openPage(page: SettingsPage, showErrors: Boolean = false) =
        startActivity(SettingsActivity.intent(this, page, showErrors))

    /** Saved and applied at once. The app recreates itself in the chosen language and direction. */
    private fun showLanguages(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.inflate(R.menu.language)
        val current = LanguageChoice.fromLanguage(resources.configuration.locales[0].language)
        menu.menu.findItem(current.itemId).isChecked = true
        menu.setOnMenuItemClickListener { item ->
            val choice = LanguageChoice.fromItem(item.itemId)
            if (choice != null && choice != current) LanguageManager.select(this, choice)
            true
        }
        menu.show()
    }

    private fun startOrStop() {
        if (TaskBus.status.value.phase != TaskPhase.IDLE) {
            AutoTaskService.instance?.stopSession()
            return
        }
        if (quickDirty()) {
            Toast.makeText(this, R.string.quick_save_first, Toast.LENGTH_LONG).show()
            return
        }
        SessionControl.start(this) { openPage(it.page, showErrors = true) }
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

    private companion object {
        const val STATUS_REFRESH_MS = 1_000L
        const val RECOVERY_DELAY_TICKS = 10
    }
}
