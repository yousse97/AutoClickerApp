package com.autoclicker.chattask.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.chattask.R
import com.autoclicker.chattask.databinding.ActivityGuideBinding

/** Text-only setup steps. Works offline and opens before any permission is granted. */
class GuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.pageBar.root.setTitle(R.string.guide_title)
        binding.pageBar.root.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val packageUri = Uri.parse("package:$packageName")
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        binding.buttonRestricted.setOnClickListener { open(appInfo) }
        binding.buttonAccessibility.setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.buttonOverlay.setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        }
        binding.buttonBattery.setOnClickListener { openBatterySetting(packageUri) }
        binding.buttonAutostart.setOnClickListener { open(appInfo) }
        binding.buttonNotificationAccess.setOnClickListener { openNotificationAccess(this) }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySetting(packageUri: Uri) {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
    }

    /** Device makers can remove any settings screen, so a missing one must not crash the page. */
    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.guide_screen_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
