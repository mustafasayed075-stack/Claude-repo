package com.personal.guardian

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
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
import com.personal.guardian.databinding.ActivityBlockedSitesBinding
import com.personal.guardian.databinding.ItemBlockedSiteBinding
import com.personal.guardian.util.GuardianLog
import com.personal.guardian.vpn.BlockedDomainLog
import com.personal.guardian.vpn.BlockedDomainLogSettings
import java.util.Date

/**
 * "المواقع المحظورة مؤخرًا": the domains the DNS filter blocked in the last hour
 * ([BlockedDomainLogSettings]), newest first — the list domain, the app that was in front,
 * the last time and how many times. Read-only apart from "مسح السجل"; no network.
 */
class BlockedSitesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedSitesBinding
    private val adapter = EntriesAdapter()
    private val main = Handler(Looper.getMainLooper())

    // While the screen is open, new blocks appear and hour-old ones drop off.
    private val refresher = object : Runnable {
        override fun run() {
            show()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedSitesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.listBlockedSites.adapter = adapter
        binding.listBlockedSites.emptyView = binding.txtBlockedSitesEmpty
        binding.btnClearLog.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(refresher)
        refresher.run()
    }

    override fun onPause() {
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun show() {
        adapter.entries = BlockedDomainLogSettings.recent(this)
        adapter.notifyDataSetChanged()
        binding.btnClearLog.isEnabled = adapter.entries.isNotEmpty()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.blocked_sites_clear_confirm)
            .setPositiveButton(R.string.blocked_sites_clear) { _, _ ->
                try {
                    BlockedDomainLogSettings.clear(this)
                    Toast.makeText(this, R.string.blocked_sites_cleared, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    GuardianLog.e(this, "Blocked-sites log: could not clear.", t)
                    Toast.makeText(this, R.string.blocked_sites_clear_failed, Toast.LENGTH_LONG).show()
                }
                show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class EntriesAdapter : BaseAdapter() {
        var entries: List<BlockedDomainLog.Entry> = emptyList()
        private val timeFormat = DateFormat.getTimeFormat(this@BlockedSitesActivity)

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemBlockedSiteBinding.bind(it) }
                ?: ItemBlockedSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val e = entries[position]
            row.txtDomain.text = e.domain
            row.txtApp.text = e.appLabel ?: e.appPackage ?: getString(R.string.blocked_sites_unknown_app)
            row.txtTime.text = timeFormat.format(Date(e.lastBlockedAtMs))
            row.txtCount.text = getString(R.string.blocked_sites_count, e.count)
            return row.root
        }
    }

    private companion object {
        const val REFRESH_MS = 15_000L
    }
}
