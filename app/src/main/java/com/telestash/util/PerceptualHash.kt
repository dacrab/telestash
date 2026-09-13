package com.telestash.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Perceptual hash (average hash, aHash) for near-duplicate image detection.
 * A 64-bit hash over a small grayscale thumbnail; Hamming distance <= threshold
 * counts as a probable duplicate.
 */
object PerceptualHash {
    private const val GRID = 8
    private const val GRID_CELLS = GRID * GRID
    private const val MIN_DECODE_SIZE = 16
    private const val DUPLICATE_DISTANCE = 8

    // ITU-R BT.601 luma weights, scaled by 1000.
    private const val LUMA_WEIGHT_R = 299
    private const val LUMA_WEIGHT_G = 587
    private const val LUMA_WEIGHT_B = 114
    private const val LUMA_SCALE = 1000
    private const val CHANNEL_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    fun compute(file: File): Long? {
        // pass 1: bounds only, to compute sampling
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // pass 2: small RGB_565 decode (ALPHA_8 has no color channels!)
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= MIN_DECODE_SIZE &&
            bounds.outHeight / (sample * 2) >= MIN_DECODE_SIZE
        ) {
            sample *= 2
        }
        val opts =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val scaled = Bitmap.createScaledBitmap(bmp, GRID, GRID, true)
        if (scaled !== bmp) bmp.recycle()
        val pixels = IntArray(GRID_CELLS)
        scaled.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
        scaled.recycle()

        val lums =
            IntArray(GRID_CELLS) { i ->
                val p = pixels[i]
                val r = (p shr RED_SHIFT) and CHANNEL_MASK
                val g = (p shr GREEN_SHIFT) and CHANNEL_MASK
                val b = p and CHANNEL_MASK
                (r * LUMA_WEIGHT_R + g * LUMA_WEIGHT_G + b * LUMA_WEIGHT_B) / LUMA_SCALE
            }
        val avg = lums.sum() / GRID_CELLS

        return lums.fold(0L) { hash, l -> (hash shl 1) or (if (l > avg) 1L else 0L) }
    }

    fun distance(
        a: Long,
        b: Long,
    ): Int = java.lang.Long.bitCount(a xor b)

    fun isProbablyDuplicate(
        a: Long,
        b: Long,
    ): Boolean = distance(a, b) <= DUPLICATE_DISTANCE
}
