package com.cam.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully on-device AI low-light enhancement using Zero-DCE (TFLite, ~0.3 MB).
 *
 * Order matters: the photo is DENOISED first, then Zero-DCE (GPU first, CPU only if the GPU
 * delegate fails) produces a smooth brightness gain map that is applied to the full-resolution
 * pixels. The gain is deliberately gentle: it scales with how dark the scene actually is
 * (bright scenes are left alone), is capped, and fades out in highlights so nothing blows out.
 * [polish] then runs in place (it does not denoise a second time).
 *
 * [lastError] = reason for the most recent fallback (null = worked),
 * [lastBackend] = "GPU" or "CPU" for the model run.
 */
class LocalAiEnhancer(
    context: Context,
    private val polish: AutoEnhancer,
) : Enhancer {

    private val appContext = context.applicationContext

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastBackend: String = "-"
        private set

    @Synchronized
    override fun enhance(src: Bitmap): Bitmap {
        lastError = null
        return try {
            run(src)
        } catch (e: FileNotFoundException) {
            lastError = "model file missing from this build ($MODEL)"
            polish.enhance(src)
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(140)
            polish.enhance(src)
        }
    }

    /** Creates the interpreter, runs once and closes it, all on the calling thread. */
    private fun infer(input: ByteBuffer, n: Int, gpu: Boolean): FloatArray {
        val bytes = appContext.assets.open(MODEL).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()

        var delegate: GpuDelegate? = null
        val interp: Interpreter = try {
            val options = Interpreter.Options()
            if (gpu) {
                delegate = GpuDelegate()
                options.addDelegate(delegate)
            } else {
                options.setNumThreads(4)
            }
            Interpreter(buf, options)
        } catch (t: Throwable) {
            delegate?.close()
            throw t
        }

        try {
            input.rewind()
            val output = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
            interp.run(input, output)
            output.rewind()
            val arr = FloatArray(n * 3)
            output.asFloatBuffer().get(arr)
            lastBackend = if (gpu) "GPU" else "CPU"
            return arr
        } finally {
            interp.close()
            delegate?.close()
        }
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun run(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val s = SIZE
        val n = s * s

        // 1) Build the model input from a downscaled copy (downscaling also averages out noise)
        val small = Bitmap.createScaledBitmap(src, s, s, true)
        val sp = IntArray(n)
        small.getPixels(sp, 0, s, 0, 0, s, s)
        if (small !== src) small.recycle()

        val input = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
        val lumIn = FloatArray(n)
        var meanIn = 0.0
        for (i in 0 until n) {
            val p = sp[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            input.putFloat(r)
            input.putFloat(g)
            input.putFloat(b)
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            lumIn[i] = l
            meanIn += l
        }
        meanIn /= n
        input.rewind()

        // 2) Run the model: GPU first, CPU only if the GPU path fails
        val outArr = try {
            infer(input, n, true)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (t: Throwable) {
            infer(input, n, false)
        }

        // 3) Gentle gain map. Bright scenes get no lift at all; dark ones get up to 0.75 of the
        //    model's suggestion, capped at 3x, and the lift fades out in highlights.
        val darkness = ((0.45 - meanIn) / 0.25).coerceIn(0.0, 1.0).toFloat()
        val strength = 0.75f * darkness
        val gain = FloatArray(n)
        for (i in 0 until n) {
            val lo = 0.299f * outArr[i * 3] + 0.587f * outArr[i * 3 + 1] + 0.114f * outArr[i * 3 + 2]
            val ratio = ((lo + 0.01f) / (lumIn[i] + 0.01f)).coerceIn(1f, 3f)
            val taper = 1f - smoothstep(0.55f, 0.95f, lumIn[i])
            gain[i] = 1f + strength * taper * (ratio - 1f)
        }

        // 4) Denoise the full-resolution photo BEFORE brightening, so noise isn't amplified
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        Denoiser.run(px, w, h)

        // 5) Apply the upsampled gain map, all cores
        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val tx = FloatArray(w)
        for (x in 0 until w) {
            val gx = if (w > 1) x * (s - 1f) / (w - 1f) else 0f
            val a = gx.toInt().coerceAtMost(s - 1)
            x0[x] = a
            x1[x] = minOf(a + 1, s - 1)
            tx[x] = gx - a
        }

        parallelRows(h) { ys, ye ->
            for (y in ys until ye) {
                val gy = if (h > 1) y * (s - 1f) / (h - 1f) else 0f
                val ya = gy.toInt().coerceAtMost(s - 1)
                val yb = minOf(ya + 1, s - 1)
                val ty = gy - ya
                val r0 = ya * s
                val r1 = yb * s
                val rowStart = y * w
                for (x in 0 until w) {
                    val ga = gain[r0 + x0[x]]
                    val gb = gain[r0 + x1[x]]
                    val gc = gain[r1 + x0[x]]
                    val gd = gain[r1 + x1[x]]
                    val top = ga + (gb - ga) * tx[x]
                    val bot = gc + (gd - gc) * tx[x]
                    val gn = top + (bot - top) * ty

                    val i = rowStart + x
                    val p = px[i]
                    var r = ((p shr 16) and 0xFF) * gn
                    var g = ((p shr 8) and 0xFF) * gn
                    var b = (p and 0xFF) * gn
                    // Keep hue when a channel clips instead of letting colors skew
                    val m = maxOf(r, maxOf(g, b))
                    if (m > 255f) {
                        val k = 255f / m
                        r *= k
                        g *= k
                        b *= k
                    }
                    px[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
                }
            }
        }

        // 6) Light polish (no second denoise), then build the bitmap
        polish.process(px, w, h, denoise = false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private companion object {
        const val MODEL = "zerodce_512.tflite"
        const val SIZE = 512
    }
}
