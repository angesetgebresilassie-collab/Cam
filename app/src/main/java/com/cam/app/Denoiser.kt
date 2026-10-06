package com.cam.app

import kotlin.math.abs
import kotlin.math.exp

private fun ub(a: ByteArray, i: Int): Int = a[i].toInt() and 0xFF

/**
 * Noise reduction that runs BEFORE any brightening, so noise doesn't get amplified:
 *  - luma: edge-preserving bilateral filter (5x5) whose strength adapts to the estimated noise
 *  - chroma: smoothed on a 1/4-resolution copy (removes colored speckle), but only blended in
 *    away from edges so colors don't bleed
 * Works in place on ARGB pixel arrays, multi-core.
 */
object Denoiser {
    private const val F = 4

    fun run(px: IntArray, w: Int, h: Int, lumaMix: Float = 0.85f) {
        if (w < 32 || h < 32) return
        val n = w * h

        // 1) Luma plane
        val yp = ByteArray(n)
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val p = px[row + x]
                    val l = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                    yp[row + x] = l.toByte()
                }
            }
        }

        // 2) Noise level estimate (Immerkaer) from a sparse sample
        var acc = 0.0
        var cnt = 0
        var sy = 1
        while (sy < h - 1) {
            var sx = 1
            while (sx < w - 1) {
                val i = sy * w + sx
                val conv = ub(yp, i - w - 1) - 2 * ub(yp, i - w) + ub(yp, i - w + 1) -
                    2 * ub(yp, i - 1) + 4 * ub(yp, i) - 2 * ub(yp, i + 1) +
                    ub(yp, i + w - 1) - 2 * ub(yp, i + w) + ub(yp, i + w + 1)
                acc += abs(conv)
                cnt++
                sx += 5
            }
            sy += 5
        }
        val sigmaN = (0.2089 * acc / maxOf(cnt, 1)).toFloat()
        val sr = (sigmaN * 2.2f).coerceIn(5f, 22f)

        // 3) Bilateral filter on luma (interior pixels; a 2px border is left untouched)
        val rangeW = FloatArray(256) { d -> exp(-(d * d) / (2f * sr * sr)) }
        val offs = IntArray(25)
        val spat = FloatArray(25)
        var k = 0
        for (dy in -2..2) {
            for (dx in -2..2) {
                offs[k] = dy * w + dx
                spat[k] = exp(-(dx * dx + dy * dy) / (2f * 1.5f * 1.5f))
                k++
            }
        }
        val yd = yp.copyOf()
        parallelRows(h) { y0, y1 ->
            for (y in maxOf(y0, 2) until minOf(y1, h - 2)) {
                val row = y * w
                for (x in 2 until w - 2) {
                    val i = row + x
                    val c = ub(yp, i)
                    var sum = 0f
                    var ws = 0f
                    for (t in 0 until 25) {
                        val v = ub(yp, i + offs[t])
                        val wgt = spat[t] * rangeW[abs(v - c)]
                        sum += wgt * v
                        ws += wgt
                    }
                    yd[i] = (sum / ws + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }

        // 4) Low-resolution chroma + luma guide
        val cw = maxOf(w / F, 1)
        val ch = maxOf(h / F, 1)
        val cb = FloatArray(cw * ch)
        val cr = FloatArray(cw * ch)
        val yl = FloatArray(cw * ch)
        parallelRows(ch) { b0, b1 ->
            val sCb = FloatArray(cw)
            val sCr = FloatArray(cw)
            val sY = FloatArray(cw)
            val cn = IntArray(cw)
            for (by in b0 until b1) {
                java.util.Arrays.fill(sCb, 0f)
                java.util.Arrays.fill(sCr, 0f)
                java.util.Arrays.fill(sY, 0f)
                java.util.Arrays.fill(cn, 0)
                val ys0 = by * F
                val ys1 = if (by == ch - 1) h else ys0 + F
                for (yy in ys0 until ys1) {
                    val row = yy * w
                    for (x in 0 until w) {
                        val bx = minOf(x / F, cw - 1)
                        val p = px[row + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        sCb[bx] += -0.168736f * r - 0.331264f * g + 0.5f * b
                        sCr[bx] += 0.5f * r - 0.418688f * g - 0.081312f * b
                        sY[bx] += ub(yp, row + x)
                        cn[bx]++
                    }
                }
                for (bx in 0 until cw) {
                    val d = maxOf(cn[bx], 1).toFloat()
                    val idx = by * cw + bx
                    cb[idx] = sCb[bx] / d
                    cr[idx] = sCr[bx] / d
                    yl[idx] = sY[bx] / d
                }
            }
        }
        val cbS = blur3(cb, cw, ch)
        val crS = blur3(cr, cw, ch)

        // 5) Recombine: denoised luma + edge-aware smoothed chroma
        val edgeAlpha = FloatArray(256) { d -> exp(-(d * d) / (2f * 12f * 12f)) }
        val cx0 = IntArray(w)
        val cx1 = IntArray(w)
        val ctx = FloatArray(w)
        for (x in 0 until w) {
            val g = ((x + 0.5f) / F - 0.5f).coerceIn(0f, cw - 1f)
            val a = g.toInt().coerceAtMost(cw - 1)
            cx0[x] = a
            cx1[x] = minOf(a + 1, cw - 1)
            ctx[x] = g - a
        }
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val gy = ((y + 0.5f) / F - 0.5f).coerceIn(0f, ch - 1f)
                val ya = gy.toInt().coerceAtMost(ch - 1)
                val yb = minOf(ya + 1, ch - 1)
                val ty = gy - ya
                val r0 = ya * cw
                val r1 = yb * cw
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
                    val cbSm = cbS[a] * wa + cbS[b] * wb + cbS[c] * wc + cbS[d] * wd
                    val crSm = crS[a] * wa + crS[b] * wb + crS[c] * wc + crS[d] * wd
                    val ylSm = yl[a] * wa + yl[b] * wb + yl[c] * wc + yl[d] * wd

                    val i = row + x
                    val p = px[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val bl = p and 0xFF
                    val cbO = -0.168736f * r - 0.331264f * g + 0.5f * bl
                    val crO = 0.5f * r - 0.418688f * g - 0.081312f * bl

                    val yOrigApprox = ub(yp, i)
                    val yDen = ub(yd, i)
                    val yExact = 0.299f * r + 0.587f * g + 0.114f * bl
                    val yFinal = yExact + lumaMix * (yDen - yOrigApprox)

                    val alpha = edgeAlpha[minOf(abs(yDen - ylSm).toInt(), 255)]
                    val cbF = cbO + alpha * (cbSm - cbO)
                    val crF = crO + alpha * (crSm - crO)

                    val nr = (yFinal + 1.402f * crF).toInt().coerceIn(0, 255)
                    val ng = (yFinal - 0.344136f * cbF - 0.714136f * crF).toInt().coerceIn(0, 255)
                    val nb = (yFinal + 1.772f * cbF).toInt().coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
    }

    private fun blur3(src: FloatArray, cw: Int, ch: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                var s = 0f
                var c = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= ch) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= cw) continue
                        s += src[yy * cw + xx]
                        c++
                    }
                }
                out[y * cw + x] = s / c
            }
        }
        return out
    }
}
