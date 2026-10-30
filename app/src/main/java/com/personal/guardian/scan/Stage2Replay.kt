package com.personal.guardian.scan

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.personal.guardian.util.GuardianLog
import java.io.File
import java.util.Locale

/**
 * Developer tool (README "Second stage"): runs the second stage over the review thumbnails
 * saved for past confirmed detections ([DetectionStore], `detection-*.jpg`, at most
 * 256 px) and counts what it would have decided — an estimate, from real past locks, of
 * how many it would keep. Only counts and scores are produced and logged; no image is
 * shown. The thumbnails mix true and false past locks, and they are small, so the number
 * needs reading, not trusting blindly. Runs off the main thread; no network.
 */
object Stage2Replay {

    data class Summary(val images: Int, val confirm: Int, val downgrade: Int, val veto: Int, val failed: Int, val medianMs: Long)

    fun run(context: Context): Summary {
        val ctx = context.applicationContext
        val files = DetectionStore.dir(ctx).listFiles { f: File -> f.isFile && f.name.startsWith("detection-") && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }.orEmpty()
        val counts = IntArray(SecondStage.Decision.entries.size)
        var failed = 0
        val times = ArrayList<Long>()
        BodyPartDetector.create(ctx).use { detector ->
            for (f in files) {
                val bitmap = BitmapFactory.decodeFile(f.path)
                if (bitmap == null) {
                    failed++
                    continue
                }
                try {
                    val t = SystemClock.elapsedRealtime()
                    val surface = SecondStage.Surface("thumbnail", detector.detect(bitmap), bitmap.width, bitmap.height)
                    val result = SecondStage.decide(SecondStage.Trigger.EXPLICIT, listOf(surface))
                    times += SystemClock.elapsedRealtime() - t
                    counts[result.decision.ordinal]++
                    GuardianLog.i(
                        ctx,
                        "STAGE2 replay: ${f.name} would ${result.decision.label} " +
                            "exposed=${SecondStage.hitLabel(result.exposed)} covered=${SecondStage.hitLabel(result.covered)}" +
                            (if (SecondStage.genitalUnderFace(surface) != null) " #face-mic" else "")
                    )
                } catch (t: Throwable) {
                    failed++
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val summary = Summary(
            files.size, counts[SecondStage.Decision.CONFIRM.ordinal], counts[SecondStage.Decision.DOWNGRADE.ordinal],
            counts[SecondStage.Decision.VETO.ordinal], failed, times.sorted().getOrElse(times.size / 2) { 0L }
        )
        GuardianLog.i(ctx, String.format(Locale.US, "STAGE2 replay: %s", summary))
        return summary
    }
}
