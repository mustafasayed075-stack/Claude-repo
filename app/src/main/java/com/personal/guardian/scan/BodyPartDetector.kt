package com.personal.guardian.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * The second-stage reviewer's model: NudeNet v3 `320n` body-part detector as TFLite
 * ([ScanConfig.STAGE2_MODEL_ASSET], third_party/nudenet/README.md). Inference is entirely
 * local — no network calls. Pre-processing follows NudeNet: the image is padded to a
 * square with black on the right/bottom and scaled to 320 (bilinear); here the image is
 * scaled first and drawn at the top-left of a black 320×320 canvas, which is the same
 * thing without allocating a full-size square.
 *
 * Not thread-safe; use one instance per thread.
 */
class BodyPartDetector private constructor(
    private val interpreter: Interpreter,
    /** A GPU delegate in use (closed with the detector), or null on the CPU. */
    private val gpu: Closeable? = null,
    /** "cpu2", "cpu4", "gpu" … for the log. */
    val config: String
) : Closeable {

    /** Detections (boxes in the checked image's pixels) and where the time went. */
    class Detected(val detections: List<BodyPartDetection>, val prepMs: Long, val inferMs: Long)


    private val dim = BodyPartDecoder.INPUT_DIM
    private val anchors = BodyPartDecoder.ANCHORS
    private val canvasBitmap = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)
    private val pixels = IntArray(dim * dim)
    private val input: ByteBuffer = ByteBuffer.allocateDirect(dim * dim * 3 * 4).order(ByteOrder.nativeOrder())
    private val distOut = Array(1) { Array(4) { FloatArray(anchors) } }
    private val clsOut = Array(1) { Array(BodyPartDecoder.CLASSES) { FloatArray(anchors) } }
    private val dist = FloatArray(4 * anchors)
    private val cls = FloatArray(BodyPartDecoder.CLASSES * anchors)

    // The converted graph has two outputs, [1,4,2100] and [1,18,2100]; find which is which.
    private val distIndex: Int
    private val clsIndex: Int

    init {
        val shapes = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).shape().toList() }
        distIndex = shapes.indexOf(listOf(1, 4, anchors))
        clsIndex = shapes.indexOf(listOf(1, BodyPartDecoder.CLASSES, anchors))
        require(distIndex >= 0 && clsIndex >= 0) { "unexpected second-stage model outputs: $shapes" }
    }

    /** Body parts found in [bitmap] (any size, software config), highest score first; boxes in its pixels. */
    fun detect(bitmap: Bitmap): List<BodyPartDetection> = detectTimed(bitmap).detections

    /** [detect], with the pre-processing and inference times. */
    fun detectTimed(bitmap: Bitmap): Detected {
        val t0 = SystemClock.elapsedRealtime()
        val k = dim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * k).roundToInt().coerceIn(1, dim)
        val h = (bitmap.height * k).roundToInt().coerceIn(1, dim)
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(scaled, 0f, 0f, null)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        canvasBitmap.getPixels(pixels, 0, dim, 0, 0, dim, dim)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        val t1 = SystemClock.elapsedRealtime()
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), mapOf<Int, Any>(distIndex to distOut, clsIndex to clsOut))
        val t2 = SystemClock.elapsedRealtime()
        for (r in 0 until 4) System.arraycopy(distOut[0][r], 0, dist, r * anchors, anchors)
        for (c in 0 until BodyPartDecoder.CLASSES) System.arraycopy(clsOut[0][c], 0, cls, c * anchors, anchors)
        val found = BodyPartDecoder.decode(dist, cls).map { BodyPartDecoder.toImage(it, bitmap.width, bitmap.height) }
        return Detected(found, t1 - t0, t2 - t1)
    }

    override fun close() {
        interpreter.close()
        runCatching { gpu?.close() }
        canvasBitmap.recycle()
    }

    companion object {
        /** Threads of the detector the scanner uses (the benchmark tries others). */
        const val NUM_THREADS = 2

        /** Loads the bundled model on the CPU (XNNPACK). Throws if the asset is missing or invalid. */
        fun create(context: Context, threads: Int = NUM_THREADS): BodyPartDetector = BodyPartDetector(
            Interpreter(loadModel(context), Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)),
            config = "cpu$threads"
        )

        /**
         * Loads the bundled model on the GPU delegate, or returns null with the reason when
         * this device doesn't support it (benchmark only — Build 50 doesn't use the GPU to decide).
         */
        fun createGpu(context: Context): Pair<BodyPartDetector?, String> {
            val compat = CompatibilityList()
            return try {
                if (!compat.isDelegateSupportedOnThisDevice) return null to "GPU delegate not supported on this device"
                val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                try {
                    BodyPartDetector(Interpreter(loadModel(context), Interpreter.Options().addDelegate(delegate)), delegate, "gpu") to "ok"
                } catch (t: Throwable) {
                    delegate.close()
                    null to "GPU delegate failed: ${t.javaClass.simpleName}"
                }
            } catch (t: Throwable) {
                null to "GPU delegate unavailable: ${t.javaClass.simpleName}"
            } finally {
                compat.close()
            }
        }

        private fun loadModel(context: Context): MappedByteBuffer =
            context.assets.openFd(ScanConfig.STAGE2_MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}
