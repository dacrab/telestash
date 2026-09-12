package com.telestash.util

import android.graphics.BitmapFactory
import java.io.File
import java.io.Serializable

/**
 * Perceptual hash (average hash, aHash) for near-duplicate image detection.
 * 64-bit hash over an 8x8 grayscale thumbnail — Hamming distance <= 8 counts
 * as a probable duplicate.
 */
object PerceptualHash : Serializable {

    fun compute(file: File): Long? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val (w, h) = opts.outWidth to opts.outHeight
        if (w <= 0 || h <= 0) return null

        // decode a tiny grayscale 8x8 directly (approximate via sampling)
        val sample = BitmapSample(w, h)
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ALPHA_8
            }
        ) ?: return null

        // scale down to 8x8, grayscale
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 8, 8, true)
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (bmp !== scaled) bmp.recycle()
        scaled.recycle()

        // luminances
        val lums = IntArray(64)
        for (i in pixels.indices) {
            val p = pixels[i]
            lums[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val avg = lums.sum() / 64

        var hash = 0L
        for (i in 0 until 64) {
            hash = hash shl 1
            if (lums[i] > avg) hash = hash or 1L
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun isProbablyDuplicate(a: Long, b: Long): Boolean = distance(a, b) <= 8

    private fun BitmapSample(w: Int, h: Int): Int {
        var shift = 0
        while ((w shr shift) > 16 || (h shr shift) > 16) shift++
        return 1 shl shift
    }
}
