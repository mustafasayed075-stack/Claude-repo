package com.personal.guardian.scan

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Local-only review log for confirmed detections (spec §5): a downscaled JPEG
 * thumbnail per detection plus one JSON line of metadata in `detections.jsonl`.
 * Everything lives in the app's private storage and is never uploaded.
 */
object DetectionStore {

    private const val DIR_NAME = "detections"
    private const val METADATA_FILE = "detections.jsonl"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun metadataFile(context: Context): File = File(dir(context), METADATA_FILE)

    /**
     * Saves a thumbnail of [frame] for a detection at [timestampMs]. Returns the
     * thumbnail's file name, or null if it could not be written.
     */
    fun saveThumbnail(context: Context, frame: Bitmap, timestampMs: Long): String? {
        val name = "detection-$timestampMs.jpg"
        val file = File(dir(context), name)
        val thumb = downscale(frame, ScanConfig.THUMBNAIL_MAX_DIM)
        return try {
            file.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, ScanConfig.THUMBNAIL_JPEG_QUALITY, it) }
            pruneThumbnails(context)
            name
        } catch (t: Throwable) {
            file.delete()
            null
        } finally {
            if (thumb !== frame) thumb.recycle()
        }
    }

    /** Appends [event]'s metadata as one JSON line. */
    fun appendMetadata(context: Context, event: DetectionEvent) {
        metadataFile(context).appendText(event.toJsonLine() + "\n")
    }

    /** Number of thumbnails currently kept on disk. */
    fun thumbnailCount(context: Context): Int = thumbnails(context).size

    private fun thumbnails(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.toList().orEmpty()

    private fun pruneThumbnails(context: Context) {
        val files = thumbnails(context).sortedBy { it.name } // names embed the timestamp
        val excess = files.size - ScanConfig.MAX_SAVED_THUMBNAILS
        if (excess > 0) files.take(excess).forEach { it.delete() }
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
