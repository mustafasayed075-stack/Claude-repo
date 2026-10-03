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
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.tabs.TabLayout
import com.personal.guardian.R
import com.personal.guardian.databinding.ActivityReflectionSettingsBinding
import com.personal.guardian.databinding.ItemReflectionContentBinding

/**
 * Stage 6 settings, redesigned (UI only): build the Reflection Mode content library
 * (text typed in, or image/audio/video picked with the system document picker and
 * kept with a persisted read grant) and set the duration (30-second minimum enforced
 * in code). Type tabs filter the list; preview cards show a thumbnail (image) or the
 * first line (text); one add button adds the tab's type.
 */
class ReflectionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReflectionSettingsBinding
    private val adapter = ContentAdapter()

    /** null = the "All" tab; otherwise the type being shown/added. */
    private val tabTypes = listOf<ReflectionType?>(
        null, ReflectionType.TEXT, ReflectionType.IMAGE, ReflectionType.AUDIO, ReflectionType.VIDEO
    )
    private val tabTitles = listOf(
        R.string.reflection_tab_all, R.string.reflection_type_text, R.string.reflection_type_image,
        R.string.reflection_type_audio, R.string.reflection_type_video
    )
    private var selectedType: ReflectionType? = null

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
        applyInsets()

        binding.toolbar.setNavigationOnClickListener { finish() }

        tabTitles.forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedType = tabTypes[tab.position]
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.listContent.adapter = adapter
        binding.listContent.emptyView = binding.txtContentEmpty
        binding.btnAdd.setOnClickListener { onAddClicked() }
        binding.btnSaveDuration.setOnClickListener { saveDuration() }
        refresh()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top)
            binding.btnAdd.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun refresh() {
        val all = ReflectionSettings.library(this).items
        adapter.items = selectedType?.let { t -> all.filter { it.type == t } } ?: all
        adapter.notifyDataSetChanged()
        binding.editDuration.setText(ReflectionSettings.durationSeconds(this).toString())
        binding.txtDurationHint.text = getString(R.string.reflection_duration_hint, ReflectionDuration.MIN_SECONDS)
    }

    /** The add button adds the selected tab's type; on the "All" tab it asks which. */
    private fun onAddClicked() {
        when (selectedType) {
            ReflectionType.TEXT -> addTextDialog()
            ReflectionType.IMAGE -> pickImage.launch(openDocument("image/*"))
            ReflectionType.AUDIO -> pickAudio.launch(openDocument("audio/*"))
            ReflectionType.VIDEO -> pickVideo.launch(openDocument("video/*"))
            null -> chooseTypeDialog()
        }
    }

    private fun chooseTypeDialog() {
        val labels = arrayOf(
            getString(R.string.reflection_type_text), getString(R.string.reflection_type_image),
            getString(R.string.reflection_type_audio), getString(R.string.reflection_type_video)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.reflection_add_choose_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> addTextDialog()
                    1 -> pickImage.launch(openDocument("image/*"))
                    2 -> pickAudio.launch(openDocument("audio/*"))
                    3 -> pickVideo.launch(openDocument("video/*"))
                }
            }
            .show()
    }

    private fun openDocument(mime: String) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
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
        // The 30-second floor is enforced in code (ReflectionSettings); show it in Arabic.
        val msg = if (clamp.adjusted) getString(R.string.reflection_duration_clamped, clamp.seconds)
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
            row.txtType.setText(typeLabel(item.type))
            row.txtLabel.text = item.displayLabel()
            bindPreview(row.imgPreview, item)
            row.btnRemove.contentDescription = getString(R.string.reflection_remove, item.displayLabel())
            row.btnRemove.setOnClickListener {
                ReflectionSettings.remove(this@ReflectionSettingsActivity, item.id)
                refresh()
            }
            return row.root
        }
    }

    /** Image items show a real thumbnail; the others show a type glyph. */
    private fun bindPreview(img: ImageView, item: ReflectionItem) {
        if (item.type == ReflectionType.IMAGE) {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            val ok = runCatching { img.setImageURI(Uri.parse(item.value)); img.drawable != null }.getOrDefault(false)
            if (ok) return
        }
        img.scaleType = ImageView.ScaleType.CENTER_INSIDE
        img.setImageResource(
            when (item.type) {
                ReflectionType.TEXT -> R.drawable.ic_reflection_text
                ReflectionType.IMAGE -> R.drawable.ic_reflection_image
                ReflectionType.AUDIO -> R.drawable.ic_reflection_audio
                ReflectionType.VIDEO -> R.drawable.ic_reflection_video
            }
        )
    }

    private fun typeLabel(type: ReflectionType) = when (type) {
        ReflectionType.TEXT -> R.string.reflection_type_text
        ReflectionType.IMAGE -> R.string.reflection_type_image
        ReflectionType.AUDIO -> R.string.reflection_type_audio
        ReflectionType.VIDEO -> R.string.reflection_type_video
    }
}
