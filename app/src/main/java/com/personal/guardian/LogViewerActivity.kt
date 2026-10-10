package com.personal.guardian

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.personal.guardian.databinding.ActivityLogViewerBinding
import com.personal.guardian.util.GuardianLog
import com.personal.guardian.util.LogExport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the local event log (tail) and copies it to the clipboard so it can be pasted
 * for diagnosis — reachable from a visible settings row, not hidden behind the developer
 * section. Read-only; nothing leaves the device. The event log never contains the
 * detected content text itself (only matched terms), so copying it is safe.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnShareLog.setOnClickListener { copyLog() }
    }

    override fun onResume() {
        super.onResume()
        showLog()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    /** A header line (version + date/time) so a pasted copy is self-identifying. */
    private fun header(): String {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "# رفيق — إصدار $version — $now"
    }

    private fun showLog() {
        val text = try {
            GuardianLog.readAll(this)
        } catch (t: Throwable) {
            GuardianLog.e(this, "Log viewer: could not read the log.", t)
            ""
        }
        binding.txtLog.text = when {
            text.isBlank() -> getString(R.string.share_log_empty)
            text.length > MAX_SHOWN -> getString(R.string.log_viewer_truncated) + "\n\n" + text.takeLast(MAX_SHOWN)
            else -> text
        }
    }

    /** Copies the last [COPY_LINES] log lines (plus a header) to the clipboard. */
    private fun copyLog() {
        val text = try {
            GuardianLog.readAll(this)
        } catch (t: Throwable) {
            GuardianLog.e(this, "Log viewer: could not read the log to copy.", t)
            ""
        }
        if (text.isBlank()) {
            Toast.makeText(this, R.string.share_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val payload = header() + "\n" + LogExport.tail(text, COPY_LINES)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, R.string.share_log_failed, Toast.LENGTH_LONG).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Rafiq log", payload))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_SHOWN = 200_000
        const val COPY_LINES = 300
    }
}
