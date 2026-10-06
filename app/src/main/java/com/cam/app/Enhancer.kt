package com.cam.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

interface Enhancer {
    fun enhance(src: Bitmap): Bitmap
}

/**
 * Natural-looking on-device auto enhance (multi-core, in place on pixel arrays):
 *  - denoise first (so nothing below amplifies grain)
 *  - mild levels + gamma lift that only kicks in for genuinely dark photos
 *  - tone changes are applied to brightness only, so colors keep their hue and saturation
 *  - sharpening with a noise threshold (flat/grainy areas are left alone)
 *  - near-neutral saturation
 */
class AutoEnhancer(
    private val sharpen: Float = 0.35f,
    private val saturation: Float = 1.05f,
    private val core: Float = 5f,
) : Enhancer {

    override fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        process(px, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Enhances [px] (ARGB ints, size w*h) in place. Set [denoise] false if already denoised. */
    fun process(px: IntArray, w: Int, h: Int, denoise: Boolean = true) {
        if (denoise) Denoiser.run(px, w, h)

        // Luma statistics from a sparse sample
        val hist = IntArray(256)
        var sum = 0L
        var count = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val l = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
            hist[l]++
            sum += l
            count++
            i += 7
        }
        count = maxOf(count, 1)
        // Levels stay gentle: black point at most 12, white point at least 235
        val lo = minOf(percentile(hist, count, 0.002), 12)
        val hi = maxOf(percentile(hist, count, 0.998), 235)
        val mean = (sum.toDouble() / count / 255.0).coerceIn(0.02, 0.98)
        // Lift only genuinely dark photos, and only toward a modest target
        val gamma = if (mean < 0.35) (ln(0.42) / ln(mean)).coerceIn(0.7, 1.0) else 1.0

        // Per-luma brightness ratio (applied equally to R, G, B so hue/saturation are preserved)
        val ratioLut = FloatArray(256) { v ->
            val t = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
            val out = t.pow(gamma) * 255.0
            ((out + 1.0) / (v + 1.0)).toFloat()
        }

        // Luma blur reference for sharpening: box-averaged 1/8 copy, sampled bilinearly
        val sw = maxOf(w / 8, 1)
        val sh = maxOf(h / 8, 1)
        val bx = maxOf(w / sw, 1)
        val by = maxOf(h / sh, 1)
        val sl = FloatArray(sw * sh)
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                var acc = 0
                var c = 0
                val y0 = sy * by
                val x0 = sx * bx
                val y1 = minOf(y0 + by, h)
                val x1 = minOf(x0 + bx, w)
                var yy = y0
                while (yy < y1) {
                    var xx = x0
                    while (xx < x1) {
                        val p = px[yy * w + xx]
                        acc += (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                        c++
                        xx += 2
                    }
                    yy += 2
                }
                sl[sy * sw + sx] = acc / maxOf(c, 1).toFloat()
            }
        }

        val cx0 = IntArray(w)
        val cx1 = IntArray(w)
        val ctx = FloatArray(w)
        for (x in 0 until w) {
            val g = ((x + 0.5f) / bx - 0.5f).coerceIn(0f, sw - 1f)
            val a = g.toInt().coerceAtMost(sw - 1)
            cx0[x] = a
            cx1[x] = minOf(a + 1, sw - 1)
            ctx[x] = g - a
        }

        val sharp = sharpen
        val sat = saturation
        val thr = core
        parallelRows(h) { ys, ye ->
            for (y in ys until ye) {
                val gy = ((y + 0.5f) / by - 0.5f).coerceIn(0f, sh - 1f)
                val ya = gy.toInt().coerceAtMost(sh - 1)
                val yb = minOf(ya + 1, sh - 1)
                val ty = gy - ya
                val r0 = ya * sw
                val r1 = yb * sw
                val row = y * w
                for (x in 0 until w) {
                    val tx = ctx[x]
                    val blurY = (sl[r0 + cx0[x]] * (1f - tx) + sl[r0 + cx1[x]] * tx) * (1f - ty) +
                        (sl[r1 + cx0[x]] * (1f - tx) + sl[r1 + cx1[x]] * tx) * ty

                    val p = px[row + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val yl = (77 * r + 150 * g + 29 * b) shr 8

                    // Brightness-only tone change
                    val ratio = ratioLut[yl]
                    var nr = r * ratio
                    var ng = g * ratio
                    var nb = b * ratio
                    val m = maxOf(nr, maxOf(ng, nb))
                    if (m > 255f) {
                        val kf = 255f / m
                        nr *= kf
                        ng *= kf
                        nb *= kf
                    }

                    // Luma sharpening with a noise threshold
                    val dY = yl - blurY
                    val ad = abs(dY)
                    if (ad > thr) {
                        val boost = sharp * (if (dY > 0f) ad - thr else -(ad - thr))
                        nr += boost
                        ng += boost
                        nb += boost
                    }

                    // Near-neutral saturation
                    val l = 0.299f * nr + 0.587f * ng + 0.114f * nb
                    nr = l + (nr - l) * sat
                    ng = l + (ng - l) * sat
                    nb = l + (nb - l) * sat

                    px[row + x] = (0xFF shl 24) or
                        (nr.toInt().coerceIn(0, 255) shl 16) or
                        (ng.toInt().coerceIn(0, 255) shl 8) or
                        nb.toInt().coerceIn(0, 255)
                }
            }
        }
    }

    private fun percentile(hist: IntArray, total: Int, q: Double): Int {
        val target = (total * q).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return 255
    }
}
