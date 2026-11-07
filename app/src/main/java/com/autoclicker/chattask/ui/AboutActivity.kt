package com.autoclicker.chattask.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.autoclicker.chattask.R
import com.autoclicker.chattask.databinding.ActivityAboutBinding

/** App facts and a link to the releases page. Nothing is downloaded or installed from here. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.pageBar.root.setTitle(R.string.page_about)
        binding.pageBar.root.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val info = packageManager.getPackageInfo(packageName, 0)
        binding.textVersion.text = getString(
            R.string.about_version,
            info.versionName,
            PackageInfoCompat.getLongVersionCode(info),
        )
        binding.buttonReleases.setOnClickListener { openReleases() }
    }

    private fun openReleases() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
        // Naming the browser keeps this out of any installed app that also claims the link.
        val inBrowser = Intent(link).setPackage(browserPackage())
        runCatching { startActivity(inBrowser) }
            .recoverCatching { startActivity(link) }
            .onFailure { Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_LONG).show() }
    }

    /** The default browser, or else the first one installed. A neutral address finds only browsers. */
    private fun browserPackage(): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_PROBE_URL)).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = packageManager.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }
        val default = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return default?.takeIf { it in browsers } ?: browsers.firstOrNull()
    }

    private companion object {
        const val RELEASES_URL = "https://github.com/yousse97/AutoClickerApp/releases"
        const val BROWSER_PROBE_URL = "https://example.com"
    }
}
