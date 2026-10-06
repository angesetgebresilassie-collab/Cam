package com.cam.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully on-device AI tone/low-light enhancement using Zero-DCE (TFLite, ~0.3 MB).
 *
 * The model runs on the GPU (CPU only if the GPU delegate fails) on a 512x512 copy; its
 * output becomes a per-pixel brightness gain map that is upsampled and applied to the
 * full-resolution photo in parallel across all cores. [polish] then runs in place.
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

    private fun run(src: Bitmap): Bitmap {
        val s = SIZE
        val n = s * s

        // 1) Downscale and build the model input
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

        // 3) Gain map (gentler on already bright scenes)
        val strength = if (meanIn > 0.5) 0.4f else 0.9f
        val gain = FloatArray(n)
        for (i in 0 until n) {
            val lo = 0.299f * outArr[i * 3] + 0.587f * outArr[i * 3 + 1] + 0.114f * outArr[i * 3 + 2]
            val ratio = ((lo + 0.01f) / (lumIn[i] + 0.01f)).coerceIn(0.6f, 6f)
            gain[i] = 1f + strength * (ratio - 1f)
        }

        // 4) Apply the upsampled gain map to the full-resolution pixels, all cores
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

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
                    val r = (((p shr 16) and 0xFF) * gn).toInt().coerceAtMost(255)
                    val g = (((p shr 8) and 0xFF) * gn).toInt().coerceAtMost(255)
                    val b = ((p and 0xFF) * gn).toInt().coerceAtMost(255)
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        // 5) Final polish (levels, sharpen, saturation) in place, then build the bitmap
        polish.process(px, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private companion object {
        const val MODEL = "zerodce_512.tflite"
        const val SIZE = 512
    }
}
