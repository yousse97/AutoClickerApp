package com.autoclicker.chattask.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autoclicker.chattask.R
import com.autoclicker.chattask.data.LanguageManager
import com.autoclicker.chattask.databinding.ActivityActivationBinding
import com.autoclicker.chattask.security.AppGate
import com.autoclicker.chattask.security.LicenseRole
import com.autoclicker.chattask.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launch screen. Passes straight through to settings once this device is activated. */
class ActivationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityActivationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppGate.isUnlocked(this)) return openSettings()

        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val licenseId = AppGate.licenseId(this)
        binding.textLicenseId.text = licenseId
        binding.buttonCopyLicense.setOnClickListener { copyToClipboard(licenseId) }
        binding.topBar.buttonLanguage.setOnClickListener { LanguageManager.toggle(this) }
        if (AppGate.isGenuine(this)) {
            binding.buttonActivate.setOnClickListener { submit() }
        } else {
            binding.textTampered.visibility = View.VISIBLE
            binding.layoutPasscode.isEnabled = false
            binding.buttonActivate.isEnabled = false
        }
    }

    private fun submit() {
        val entered = binding.inputPasscode.text?.toString().orEmpty()
        if (entered.isBlank()) {
            binding.layoutPasscode.error = getString(R.string.error_passcode_empty)
            return
        }
        binding.buttonActivate.isEnabled = false
        lifecycleScope.launch {
            val role = withContext(Dispatchers.Default) { AppGate.roleFor(this@ActivationActivity, entered) }
            binding.buttonActivate.isEnabled = true
            if (role == LicenseRole.NONE) {
                binding.layoutPasscode.error = getString(R.string.error_passcode_wrong)
            } else {
                withContext(Dispatchers.IO) { SecureStore(this@ActivationActivity).role = role }
                openSettings()
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
