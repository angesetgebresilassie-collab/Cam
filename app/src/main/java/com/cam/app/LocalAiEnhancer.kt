package com.cam.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully on-device AI enhancement using Zero-DCE (TFLite, ~0.3 MB, no internet).
 *
 * The model works on a fixed 512x512 input, so it runs on a downscaled copy and the
 * result is turned into a per-pixel brightness gain map, which is then upsampled and
 * applied to the full-resolution photo. That keeps all the original detail.
 * A final polish pass (levels, sharpen, saturation) runs on top.
 *
 * [lastError] holds the reason for the most recent fallback (null = worked).
 */
class LocalAiEnhancer(
    context: Context,
    private val polish: Enhancer,
) : Enhancer {

    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null

    @Volatile
    var lastError: String? = null
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

    private fun interp(): Interpreter {
        interpreter?.let { return it }
        val bytes = appContext.assets.open(MODEL).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()
        val options = Interpreter.Options().setNumThreads(4)
        return Interpreter(buf, options).also { interpreter = it }
    }

    private fun run(src: Bitmap): Bitmap {
        val s = SIZE
        val n = s * s

        // 1) Downscale and feed the model
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

        val output = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
        interp().run(input, output)
        output.rewind()
        val outArr = FloatArray(n * 3)
        output.asFloatBuffer().get(outArr)

        // 2) Turn model output into a brightness gain map (gentler on already bright scenes)
        val strength = if (meanIn > 0.5) 0.4f else 0.9f
        val gain = FloatArray(n)
        for (i in 0 until n) {
            val lo = 0.299f * outArr[i * 3] + 0.587f * outArr[i * 3 + 1] + 0.114f * outArr[i * 3 + 2]
            val ratio = ((lo + 0.01f) / (lumIn[i] + 0.01f)).coerceIn(0.6f, 6f)
            gain[i] = 1f + strength * (ratio - 1f)
        }

        // 3) Upsample the gain map and apply it to the full-resolution photo
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

        for (y in 0 until h) {
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

        val brightened = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        brightened.setPixels(px, 0, w, 0, 0, w, h)

        // 4) Final polish: levels, sharpen, saturation
        return polish.enhance(brightened)
    }

    private companion object {
        const val MODEL = "zerodce_512.tflite"
        const val SIZE = 512
    }
}
