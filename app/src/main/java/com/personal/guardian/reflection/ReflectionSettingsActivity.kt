package com.personal.guardian.reflection

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.personal.guardian.R
import com.personal.guardian.databinding.ActivityReflectionSettingsBinding
import com.personal.guardian.databinding.ItemReflectionContentBinding

/**
 * Stage 6 settings: build the Reflection Mode content library (text typed in, or
 * image/audio/video picked with the system document picker and kept with a persisted
 * read grant) and set the duration (30-second minimum enforced in code).
 */
class ReflectionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReflectionSettingsBinding
    private val adapter = ContentAdapter()

    private fun pickerFor(type: ReflectionType) = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val flags = result.data?.flags ?: 0
        ReflectionSettings.addMedia(this, type, uri, flags, displayName(uri))
        refresh()
    }

    private val pickImage = pickerFor(ReflectionType.IMAGE)
    private val pickAudio = pickerFor(ReflectionType.AUDIO)
    private val pickVideo = pickerFor(ReflectionType.VIDEO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReflectionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.reflection_settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.listContent.adapter = adapter
        binding.listContent.emptyView = binding.txtContentEmpty
        binding.btnAddText.setOnClickListener { addTextDialog() }
        binding.btnAddImage.setOnClickListener { pickImage.launch(openDocument("image/*")) }
        binding.btnAddAudio.setOnClickListener { pickAudio.launch(openDocument("audio/*")) }
        binding.btnAddVideo.setOnClickListener { pickVideo.launch(openDocument("video/*")) }
        binding.btnSaveDuration.setOnClickListener { saveDuration() }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    private fun refresh() {
        adapter.items = ReflectionSettings.library(this).items
        adapter.notifyDataSetChanged()
        binding.editDuration.setText(ReflectionSettings.durationSeconds(this).toString())
        binding.txtDurationHint.text = getString(R.string.reflection_duration_hint, ReflectionDuration.MIN_SECONDS)
    }

    private fun openDocument(mime: String) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        // Ask for a grant we can persist, so the file still opens after a reboot.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    private fun addTextDialog() {
        val input = EditText(this).apply {
            setHint(R.string.reflection_text_hint)
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reflection_add_text)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) Toast.makeText(this, R.string.reflection_text_empty, Toast.LENGTH_SHORT).show()
                else { ReflectionSettings.addText(this, text); refresh() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveDuration() {
        val requested = binding.editDuration.text?.toString()?.trim()?.toLongOrNull()
        if (requested == null) {
            Toast.makeText(this, R.string.reflection_duration_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val clamp = ReflectionSettings.setDurationSeconds(this, requested)
        // The 30-second floor is enforced in code: tell the user when a lower value was raised.
        val msg = if (clamp.adjusted) clamp.reason ?: getString(R.string.reflection_duration_saved, clamp.seconds)
        else getString(R.string.reflection_duration_saved, clamp.seconds)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    private inner class ContentAdapter : BaseAdapter() {
        var items: List<ReflectionItem> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemReflectionContentBinding.bind(it) }
                ?: ItemReflectionContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val item = items[position]
            row.txtType.text = item.type.label.uppercase()
            row.txtLabel.text = item.displayLabel()
            row.btnRemove.contentDescription = getString(R.string.reflection_remove, item.displayLabel())
            row.btnRemove.setOnClickListener {
                ReflectionSettings.remove(this@ReflectionSettingsActivity, item.id)
                refresh()
            }
            return row.root
        }
    }
}
