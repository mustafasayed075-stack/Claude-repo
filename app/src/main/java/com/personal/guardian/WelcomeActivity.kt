package com.personal.guardian

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.personal.guardian.databinding.ActivityWelcomeBinding

/**
 * First-run welcome: explains that everything runs on the device and nothing is sent
 * anywhere, then lets the user start. Shown once — [MainActivity] launches it when
 * [PREF_SEEN] is not set, and the start button sets it.
 *
 * UI only; no detection/lock behaviour here.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.contentWelcome.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.point1.imgPoint.setImageResource(R.drawable.ic_guardian_shield)
        binding.point1.txtPointTitle.setText(R.string.welcome_point1_title)
        binding.point1.txtPointDesc.setText(R.string.welcome_point1_desc)

        binding.point2.imgPoint.setImageResource(R.drawable.ic_cloud_off)
        binding.point2.txtPointTitle.setText(R.string.welcome_point2_title)
        binding.point2.txtPointDesc.setText(R.string.welcome_point2_desc)

        binding.point3.imgPoint.setImageResource(R.drawable.ic_check_circle)
        binding.point3.txtPointTitle.setText(R.string.welcome_point3_title)
        binding.point3.txtPointDesc.setText(R.string.welcome_point3_desc)

        binding.btnWelcomeStart.setOnClickListener {
            markSeen(this)
            finish()
        }
    }

    companion object {
        private const val PREFS = "rafiq_prefs"
        private const val PREF_SEEN = "welcome_seen"

        fun shouldShow(context: Context): Boolean =
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SEEN, false)

        fun markSeen(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SEEN, true).apply()
        }
    }
}
