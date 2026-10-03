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
import com.personal.guardian.databinding.ActivityFastScanAppsBinding
import com.personal.guardian.databinding.DialogAppPickerBinding
import com.personal.guardian.databinding.ItemAppPickerBinding
import com.personal.guardian.databinding.ItemFastScanAppBinding
import com.personal.guardian.scan.FastScanApp
import com.personal.guardian.scan.FastScanList
import com.personal.guardian.scan.FastScanMode
import com.personal.guardian.scan.FastScanSettings
import com.personal.guardian.scan.InstalledApp
import com.personal.guardian.scan.ScanConfig
import com.personal.guardian.util.GuardianLog
import java.util.concurrent.Executors

/**
 * "Fast Scan Apps" settings screen: the apps that get fast scanning while in the
 * foreground, each with its own Text and Image toggle. "+" opens a picker of
 * launchable installed apps; each row can be removed. Edits are saved at once
 * ([FastScanSettings]) and the running scanner follows them live.
 */
class FastScanAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFastScanAppsBinding
    private val adapter = EntriesAdapter()
    private val icons = HashMap<String, Drawable?>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFastScanAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.txtFastScanIntro.text = getString(
            R.string.fast_scan_intro,
            ScanConfig.FAST_INTERVAL_MS / 1000.0,
            ScanConfig.BASELINE_INTERVAL_MS / 1000
        )
        binding.listFastScanApps.adapter = adapter
        binding.listFastScanApps.emptyView = binding.txtFastScanEmpty
        binding.btnAddApp.setOnClickListener { showPicker() }
        show(FastScanSettings.get(this))
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

    private fun show(list: FastScanList) {
        adapter.entries = list.entries
        adapter.notifyDataSetChanged()
    }

    /** Saves an edit; a failed write leaves the screen showing what is really stored. */
    private fun edit(description: String, change: (FastScanList) -> FastScanList) {
        val list = try {
            FastScanSettings.update(this, description, change)
        } catch (t: Throwable) {
            GuardianLog.e(this, "Fast Scan Apps: could not save the change ($description).", t)
            Toast.makeText(this, R.string.fast_scan_save_failed, Toast.LENGTH_LONG).show()
            FastScanSettings.get(this)
        }
        show(list)
    }

    private fun confirmRemove(app: FastScanApp) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fast_scan_remove_title, app.label))
            .setMessage(getString(R.string.fast_scan_remove_message, ScanConfig.BASELINE_INTERVAL_MS / 1000))
            .setPositiveButton(R.string.fast_scan_remove) { _, _ ->
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
            .setTitle(R.string.fast_scan_picker_title)
            .setView(picker.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        var installed: List<InstalledApp> = emptyList()
        fun refilter() {
            pickerAdapter.apps = FastScanSettings.get(this)
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
            edit("added ${app.label} (${app.packageName}), text and image on") {
                it.add(FastScanApp(app.packageName, app.label, text = true, image = true))
            }
            dialog.dismiss()
        }
        dialog.show()

        // PackageManager is only walked here, when the picker opens.
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

    /** Apps with a launcher entry, with their label; icons are cached for the list. */
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

    /** The app's icon, or null if it isn't installed (a default entry, for example). */
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
        var entries: List<FastScanApp> = emptyList()

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemFastScanAppBinding.bind(it) }
                ?: ItemFastScanAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val app = entries[position]
            val icon = iconFor(app.packageName)
            if (icon != null) row.imgIcon.setImageDrawable(icon) else row.imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            row.txtLabel.text = app.label
            row.txtDetail.text = getString(
                R.string.fast_scan_row_detail,
                app.packageName,
                getString(modeText(app.mode)) +
                    if (isInstalled(app.packageName)) "" else getString(R.string.fast_scan_not_installed)
            )

            // Clear listeners before setting state: rows are recycled.
            row.chkText.setOnCheckedChangeListener(null)
            row.chkImage.setOnCheckedChangeListener(null)
            row.chkText.isChecked = app.text
            row.chkImage.isChecked = app.image
            row.chkText.setOnCheckedChangeListener { _, on ->
                edit("text scanning ${onOff(on)} for ${app.label} (${app.packageName})") { it.setText(app.packageName, on) }
            }
            row.chkImage.setOnCheckedChangeListener { _, on ->
                edit("image fast capture ${onOff(on)} for ${app.label} (${app.packageName})") { it.setImage(app.packageName, on) }
            }
            row.btnRemove.contentDescription = getString(R.string.fast_scan_remove_title, app.label)
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

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun modeText(mode: FastScanMode) = when (mode) {
        FastScanMode.BOTH -> R.string.fast_scan_mode_both
        FastScanMode.TEXT_ONLY -> R.string.fast_scan_mode_text
        FastScanMode.IMAGE_ONLY -> R.string.fast_scan_mode_image
        FastScanMode.OFF -> R.string.fast_scan_mode_off
    }
}
