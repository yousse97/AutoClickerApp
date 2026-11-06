package com.autoclicker.chattask.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.autoclicker.chattask.R

fun Context.copyToClipboard(text: CharSequence) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
}
