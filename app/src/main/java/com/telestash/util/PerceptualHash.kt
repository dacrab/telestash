package com.telestash.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.Serializable

/**
 * Perceptual hash (average hash, aHash) for near-duplicate image detection.
 * 64-bit hash over an 8x8 grayscale thumbnail; Hamming distance <= 8 counts
 * as a probable duplicate.
 */
object PerceptualHash : Serializable {

    fun compute(file: File): Long? {
        // pass 1: bounds only, to compute sampling
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // pass 2: small RGB_565 decode (ALPHA_8 has no color channels!)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 16 && bounds.outHeight / (sample * 2) >= 16) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val scaled = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        if (scaled !== bmp) bmp.recycle()
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        scaled.recycle()

        val lums = IntArray(64) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val avg = lums.sum() / 64

        return lums.fold(0L) { hash, l -> (hash shl 1) or (if (l > avg) 1L else 0L) }
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun isProbablyDuplicate(a: Long, b: Long): Boolean = distance(a, b) <= 8
}
