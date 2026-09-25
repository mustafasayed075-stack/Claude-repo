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
 * On-device NSFW classifier: TensorFlow Lite running the bundled OpenNSFW model
 * ([ScanConfig.MODEL_ASSET]). Inference is entirely local — the interpreter and the
 * model file make no network calls.
 *
 * Not thread-safe; use from the scanner's single worker thread.
 */
class NsfwClassifier private constructor(private val interpreter: Interpreter) : Closeable {

    private val pixels = IntArray(NsfwPreprocessor.RESIZE_DIM * NsfwPreprocessor.RESIZE_DIM)
    private val floats = FloatArray(NsfwPreprocessor.INPUT_FLOATS)
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(NsfwPreprocessor.INPUT_FLOATS * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(2) }

    /** Returns the NSFW probability (0..1) for [bitmap] (any size, software config). */
    fun classify(bitmap: Bitmap): Float {
        val dim = NsfwPreprocessor.RESIZE_DIM
        val scaled = Bitmap.createScaledBitmap(bitmap, dim, dim, /* filter = */ true)
        try {
            scaled.getPixels(pixels, 0, dim, 0, 0, dim, dim)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        NsfwPreprocessor.toInput(pixels, floats)
        input.rewind()
        input.asFloatBuffer().put(floats)
        interpreter.run(input, output)
        return NsfwPreprocessor.scoreFromOutput(output[0])
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
