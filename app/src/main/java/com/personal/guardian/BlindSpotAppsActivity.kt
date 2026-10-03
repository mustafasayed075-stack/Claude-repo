package com.personal.guardian

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.personal.guardian.databinding.ActivityBlindSpotAppsBinding
import com.personal.guardian.databinding.DialogAppPickerBinding
import com.personal.guardian.databinding.ItemAppPickerBinding
import com.personal.guardian.databinding.ItemBlindSpotAppBinding
import com.personal.guardian.scan.BlindSpotApp
import com.personal.guardian.scan.BlindSpotList
import com.personal.guardian.scan.BlindSpotSettings
import com.personal.guardian.scan.InstalledApp
import com.personal.guardian.util.GuardianLog
import java.util.concurrent.Executors

/**
 * "Blind-spot apps" settings screen: the apps where Guardian locks (after the escalating
 * grace) if it stays unable to read the screen (a screenshot-protected window or a blank
 * frame). "+" opens a picker of launchable apps; each row can be removed. Banks, password
 * managers and wallets are not listed by default. Edits are saved at once
 * ([BlindSpotSettings]) and the running scanner reads them live.
 */
class BlindSpotAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlindSpotAppsBinding
    private val adapter = EntriesAdapter()
    private val icons = HashMap<String, Drawable?>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlindSpotAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.listBlindSpotApps.adapter = adapter
        binding.listBlindSpotApps.emptyView = binding.txtBlindSpotEmpty
        binding.btnAddApp.setOnClickListener { showPicker() }
        show(BlindSpotSettings.get(this))
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun show(list: BlindSpotList) {
        adapter.entries = list.entries
        adapter.notifyDataSetChanged()
    }

    private fun edit(description: String, change: (BlindSpotList) -> BlindSpotList) {
        val list = try {
            BlindSpotSettings.update(this, description, change)
        } catch (t: Throwable) {
            GuardianLog.e(this, "Blind-spot apps: could not save the change ($description).", t)
            Toast.makeText(this, R.string.blind_spot_save_failed, Toast.LENGTH_LONG).show()
            BlindSpotSettings.get(this)
        }
        show(list)
    }

    private fun confirmRemove(app: BlindSpotApp) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.blind_spot_remove_title, app.label))
            .setPositiveButton(R.string.blind_spot_remove) { _, _ ->
                edit("removed ${app.label} (${app.packageName})") { it.remove(app.packageName) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- picker ----

    private fun showPicker() {
        val picker = DialogAppPickerBinding.inflate(layoutInflater)
        val pickerAdapter = PickerAdapter()
        picker.listApps.adapter = pickerAdapter
        picker.listApps.emptyView = picker.txtPickerEmpty
        picker.txtPickerEmpty.setText(R.string.fast_scan_picker_loading)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.blind_spot_picker_title)
            .setView(picker.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        var installed: List<InstalledApp> = emptyList()
        fun refilter() {
            pickerAdapter.apps = BlindSpotSettings.get(this)
                .pickerCandidates(installed, packageName, picker.editSearch.text?.toString().orEmpty())
            pickerAdapter.notifyDataSetChanged()
        }
        picker.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refilter()
        })
        picker.listApps.setOnItemClickListener { _, _, position, _ ->
            val app = pickerAdapter.apps[position]
            edit("added ${app.label} (${app.packageName})") {
                it.add(BlindSpotApp(app.packageName, app.label))
            }
            dialog.dismiss()
        }
        dialog.show()

        io.execute {
            val apps = launchableApps()
            main.post {
                if (isDestroyed || !dialog.isShowing) return@post
                installed = apps
                picker.txtPickerEmpty.setText(R.string.fast_scan_picker_empty)
                refilter()
            }
        }
    }

    private fun launchableApps(): List<InstalledApp> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return infos.distinctBy { it.activityInfo.packageName }.map { info ->
            val pkg = info.activityInfo.packageName
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            main.post { icons[pkg] = icon }
            InstalledApp(pkg, info.loadLabel(pm).toString())
        }
    }

    private fun iconFor(pkg: String): Drawable? =
        icons.getOrPut(pkg) {
            try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

    private fun isInstalled(pkg: String): Boolean = iconFor(pkg) != null

    // ---- adapters ----

    private inner class EntriesAdapter : BaseAdapter() {
        var entries: List<BlindSpotApp> = emptyList()

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemBlindSpotAppBinding.bind(it) }
                ?: ItemBlindSpotAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val app = entries[position]
            val icon = iconFor(app.packageName)
            if (icon != null) row.imgIcon.setImageDrawable(icon) else row.imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            row.txtLabel.text = app.label
            row.txtDetail.text = app.packageName +
                if (isInstalled(app.packageName)) "" else getString(R.string.fast_scan_not_installed)
            row.btnRemove.contentDescription = getString(R.string.blind_spot_remove_title, app.label)
            row.btnRemove.setOnClickListener { confirmRemove(app) }
            return row.root
        }
    }

    private inner class PickerAdapter : BaseAdapter() {
        var apps: List<InstalledApp> = emptyList()

        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemAppPickerBinding.bind(it) }
                ?: ItemAppPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val app = apps[position]
            val icon = icons[app.packageName]
            if (icon != null) row.imgIcon.setImageDrawable(icon) else row.imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            row.txtLabel.text = app.label
            row.txtPackage.text = app.packageName
            return row.root
        }
    }
}
