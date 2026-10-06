package com.cam.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ln
import kotlin.math.pow

/** Swap this out for a TFLite model (e.g. Zero-DCE) later. */
interface Enhancer {
    fun enhance(src: Bitmap): Bitmap
}

/**
 * Fast on-device auto enhance: auto levels, low-light gamma lift,
 * saturation boost and light unsharp mask. Runs in about 1-3s on a 12MP photo.
 */
class AutoEnhancer(
    private val sharpen: Float = 0.5f,
    private val saturation: Float = 1.12f,
) : Enhancer {

    override fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Luma histogram
        val hist = IntArray(256)
        var sum = 0L
        for (p in px) {
            val l = luma(p)
            hist[l]++
            sum += l
        }
        val lo = percentile(hist, px.size, 0.005)
        val hi = maxOf(percentile(hist, px.size, 0.995), lo + 1)
        val mean = (sum.toDouble() / px.size / 255.0).coerceIn(0.02, 0.98)

        // Brighten dark photos toward a mean of 0.45, never darken
        val gamma = (ln(0.45) / ln(mean)).coerceIn(0.5, 1.0)

        val lut = IntArray(256) { i ->
            val v = ((i - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
            (v.pow(gamma) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }

        // Blurred copy for unsharp mask
        val small = Bitmap.createScaledBitmap(src, maxOf(w / 8, 1), maxOf(h / 8, 1), true)
        val blur = Bitmap.createScaledBitmap(small, w, h, true)
        val bp = IntArray(w * h)
        blur.getPixels(bp, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        blur.recycle()

        for (i in px.indices) {
            val p = px[i]
            val b = bp[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val bl = Color.blue(p)

            var nr = lut[r] + sharpen * (r - Color.red(b))
            var ng = lut[g] + sharpen * (g - Color.green(b))
            var nb = lut[bl] + sharpen * (bl - Color.blue(b))

            val l = 0.299f * nr + 0.587f * ng + 0.114f * nb
            nr = l + (nr - l) * saturation
            ng = l + (ng - l) * saturation
            nb = l + (nb - l) * saturation

            px[i] = Color.rgb(clamp(nr), clamp(ng), clamp(nb))
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun luma(p: Int): Int =
        (77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) shr 8

    private fun percentile(hist: IntArray, total: Int, q: Double): Int {
        val target = (total * q).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return 255
    }

    private fun clamp(v: Float): Int = v.toInt().coerceIn(0, 255)
}
