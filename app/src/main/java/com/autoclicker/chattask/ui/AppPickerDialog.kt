package com.autoclicker.chattask.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autoclicker.chattask.R
import com.autoclicker.chattask.databinding.RowAppBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists launchable apps so a package can be chosen with one tap. */
object AppPickerDialog {
    private class App(val label: String, val packageName: String, val icon: Drawable)

    fun show(activity: AppCompatActivity, onPicked: (String) -> Unit) {
        activity.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps(activity) }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.picker_title)
                .setAdapter(Adapter(activity, apps)) { _, index -> onPicked(apps[index].packageName) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun loadApps(context: Context): List<App> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { App(it.loadLabel(manager).toString(), it.activityInfo.packageName, it.loadIcon(manager)) }
            .sortedBy { it.label.lowercase() }
    }

    private class Adapter(context: Context, apps: List<App>) : ArrayAdapter<App>(context, 0, apps) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let(RowAppBinding::bind)
                ?: RowAppBinding.inflate(LayoutInflater.from(context), parent, false)
            val app = getItem(position) ?: return row.root
            row.icon.setImageDrawable(app.icon)
            row.label.text = app.label
            row.packageName.text = app.packageName
            return row.root
        }
    }
}
