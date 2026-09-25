package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the OpenNSFW input conversion: centre crop, BGR order and VGG
 * mean subtraction, plus reading the score from the model output.
 */
class NsfwPreprocessorTest {

    private val dim = NsfwPreprocessor.RESIZE_DIM
    private val inDim = NsfwPreprocessor.INPUT_DIM

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun convertsToBgrMinusVggMean() {
        val pixels = IntArray(dim * dim) { argb(200, 150, 100) }
        val out = NsfwPreprocessor.toInput(pixels)
        assertEquals(NsfwPreprocessor.INPUT_FLOATS, out.size)
        assertEquals(100f - 104f, out[0], 0f) // B
        assertEquals(150f - 117f, out[1], 0f) // G
        assertEquals(200f - 123f, out[2], 0f) // R
    }

    @Test
    fun takesTheCentre224Crop() {
        // Encode each pixel's coordinates in its colour so the crop is observable.
        val pixels = IntArray(dim * dim) { i -> argb(0, i / dim, i % dim) } // G = y, B = x
        val out = NsfwPreprocessor.toInput(pixels)
        val offset = (dim - inDim) / 2 // 16
        fun bAt(x: Int, y: Int) = out[(y * inDim + x) * 3] + 104f
        fun gAt(x: Int, y: Int) = out[(y * inDim + x) * 3 + 1] + 117f
        assertEquals(offset.toFloat(), bAt(0, 0))
        assertEquals(offset.toFloat(), gAt(0, 0))
        assertEquals((offset + inDim - 1).toFloat(), bAt(inDim - 1, inDim - 1))
        assertEquals((offset + inDim - 1).toFloat(), gAt(inDim - 1, inDim - 1))
        assertEquals((offset + 5).toFloat(), bAt(5, 9))
        assertEquals((offset + 9).toFloat(), gAt(5, 9))
    }

    @Test
    fun ignoresAlpha() {
        val opaque = NsfwPreprocessor.toInput(IntArray(dim * dim) { argb(10, 20, 30) })
        val translucent = NsfwPreprocessor.toInput(IntArray(dim * dim) { argb(10, 20, 30) and 0x00FFFFFF })
        assertEquals(opaque.toList(), translucent.toList())
    }

    @Test
    fun scoreIsTheNsfwProbability() {
        assertEquals(0.93f, NsfwPreprocessor.scoreFromOutput(floatArrayOf(0.07f, 0.93f)), 0f)
        assertEquals(1f, NsfwPreprocessor.scoreFromOutput(floatArrayOf(0f, 1.0000001f)), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongInputSize() {
        NsfwPreprocessor.toInput(IntArray(224 * 224))
    }
}
