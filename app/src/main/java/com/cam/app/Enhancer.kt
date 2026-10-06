package com.cam.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ln
import kotlin.math.pow

interface Enhancer {
    fun enhance(src: Bitmap): Bitmap
}

/**
 * Fast on-device auto enhance: auto levels, low-light gamma lift,
 * saturation boost and a light unsharp mask. Multi-core, works in place on pixel arrays
 * so the AI modes can reuse it as their final polish step.
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
        process(px, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Enhances [px] (ARGB ints, size w*h) in place. */
    fun process(px: IntArray, w: Int, h: Int) {
        // Luma histogram from a sparse sample (plenty for levels and brightness)
        val hist = IntArray(256)
        var sum = 0L
        var count = 0
        var i = 0
        while (i < px.size) {
            val l = luma(px[i])
            hist[l]++
            sum += l
            count++
            i += 7
        }
        count = maxOf(count, 1)
        val lo = percentile(hist, count, 0.005)
        val hi = maxOf(percentile(hist, count, 0.995), lo + 1)
        val mean = (sum.toDouble() / count / 255.0).coerceIn(0.02, 0.98)

        // Brighten dark photos toward a mean of 0.45, never darken
        val gamma = (ln(0.45) / ln(mean)).coerceIn(0.5, 1.0)
        val lut = IntArray(256) { v ->
            val t = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
            (t.pow(gamma) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }

        // Blur reference for the unsharp mask: box-averaged 1/8 copy, sampled bilinearly
        val sw = maxOf(w / 8, 1)
        val sh = maxOf(h / 8, 1)
        val bx = maxOf(w / sw, 1)
        val by = maxOf(h / sh, 1)
        val sr = FloatArray(sw * sh)
        val sg = FloatArray(sw * sh)
        val sb = FloatArray(sw * sh)
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                var r = 0
                var g = 0
                var b = 0
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
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        c++
                        xx += 2
                    }
                    yy += 2
                }
                val d = maxOf(c, 1).toFloat()
                val idx = sy * sw + sx
                sr[idx] = r / d
                sg[idx] = g / d
                sb[idx] = b / d
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
                    val a = r0 + cx0[x]
                    val b = r0 + cx1[x]
                    val c = r1 + cx0[x]
                    val d = r1 + cx1[x]
                    val wa = (1f - tx) * (1f - ty)
                    val wb = tx * (1f - ty)
                    val wc = (1f - tx) * ty
                    val wd = tx * ty
                    val blurR = sr[a] * wa + sr[b] * wb + sr[c] * wc + sr[d] * wd
                    val blurG = sg[a] * wa + sg[b] * wb + sg[c] * wc + sg[d] * wd
                    val blurB = sb[a] * wa + sb[b] * wb + sb[c] * wc + sb[d] * wd

                    val p = px[row + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val bl = p and 0xFF

                    var nr = lut[r] + sharp * (r - blurR)
                    var ng = lut[g] + sharp * (g - blurG)
                    var nb = lut[bl] + sharp * (bl - blurB)

                    val l = 0.299f * nr + 0.587f * ng + 0.114f * nb
                    nr = l + (nr - l) * sat
                    ng = l + (ng - l) * sat
                    nb = l + (nb - l) * sat

                    px[row + x] = Color.rgb(clamp(nr), clamp(ng), clamp(nb))
                }
            }
        }
    }

    private fun luma(p: Int): Int =
        (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8

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
