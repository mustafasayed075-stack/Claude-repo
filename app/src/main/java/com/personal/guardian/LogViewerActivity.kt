package com.personal.guardian

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.personal.guardian.databinding.ActivityLogViewerBinding
import com.personal.guardian.util.GuardianLog

/**
 * Shows the local event log (tail) and shares it as a file — reachable from a visible
 * settings row, not hidden behind the developer section. Read-only; nothing leaves the
 * device except through the explicit share sheet.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnShareLog.setOnClickListener { shareLog() }
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

    private fun showLog() {
        val text = try {
            GuardianLog.readAll(this)
        } catch (t: Throwable) {
            GuardianLog.e(this, "Log viewer: could not read the log.", t)
            ""
        }
        binding.txtLog.text = when {
            text.isBlank() -> getString(R.string.share_log_empty)
            // Keep the view light: show the most recent slice of a long log.
            text.length > MAX_SHOWN -> getString(R.string.log_viewer_truncated) + "\n\n" + text.takeLast(MAX_SHOWN)
            else -> text
        }
    }

    private fun shareLog() {
        val log = GuardianLog.logFile(this)
        if (!log.exists() || log.length() == 0L) {
            Toast.makeText(this, R.string.share_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", log)
        } catch (t: Throwable) {
            GuardianLog.e(this, "Share log: could not expose the log file.", t)
            Toast.makeText(this, R.string.share_log_failed, Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_log_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.btn_share_log)))
    }

    private companion object {
        const val MAX_SHOWN = 200_000
    }
}
