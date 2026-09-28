package com.personal.guardian.scan

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device NSFW classifier: TensorFlow Lite running the bundled
 * viddexa/nsfw-detection-2-nano model ([ScanConfig.MODEL_ASSET]; EfficientNet-B0,
 * 5 classes: safe, hentai, porn, sexy, drawing). Inference is entirely local — the
 * interpreter and the model file make no network calls.
 *
 * Not thread-safe; use from the scanner's single worker thread.
 */
class NsfwClassifier private constructor(private val interpreter: Interpreter) : Closeable {

    private val pixels = IntArray(NsfwPreprocessor.INPUT_DIM * NsfwPreprocessor.INPUT_DIM)
    private val floats = FloatArray(NsfwPreprocessor.INPUT_FLOATS)
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(NsfwPreprocessor.INPUT_FLOATS * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(NsfwPreprocessor.CLASS_LABELS.size) }

    /** Returns the per-class probabilities for [bitmap] (any size, software config). */
    fun classify(bitmap: Bitmap): NsfwScores {
        val dim = NsfwPreprocessor.INPUT_DIM
        val scaled = resize(bitmap)
        try {
            scaled.getPixels(pixels, 0, dim, 0, 0, dim, dim)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        NsfwPreprocessor.toInput(pixels, floats)
        input.rewind()
        input.asFloatBuffer().put(floats)
        interpreter.run(input, output)
        return NsfwPreprocessor.scoresFromOutput(output[0])
    }

    /** One nearest-neighbour resize to the model input, as the model was trained (see [NsfwPreprocessor.RESIZE_FILTER]). */
    private fun resize(src: Bitmap): Bitmap {
        val dim = NsfwPreprocessor.INPUT_DIM
        if (src.width == dim && src.height == dim) return src
        return Bitmap.createScaledBitmap(src, dim, dim, NsfwPreprocessor.RESIZE_FILTER)
    }

    override fun close() = interpreter.close()

    companion object {
        private const val NUM_THREADS = 2

        /** Loads the bundled model. Throws if the asset is missing or invalid. */
        fun create(context: Context): NsfwClassifier {
            val options = Interpreter.Options().setNumThreads(NUM_THREADS)
            return NsfwClassifier(Interpreter(loadModel(context), options))
        }

        private fun loadModel(context: Context): MappedByteBuffer =
            context.assets.openFd(ScanConfig.MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}
