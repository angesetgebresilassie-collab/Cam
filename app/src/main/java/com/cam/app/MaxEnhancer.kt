package com.cam.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

/**
 * Max-quality, fully on-device pipeline:
 *  1) [base] (Zero-DCE tone/low-light + polish) on the full-resolution photo
 *  2) Real-ESRGAN General x4v3 run tile by tile on a working copy to restore detail
 *     and remove noise, mapped back to the original resolution and blended with step 1
 *
 * Slower than the other modes (seconds to tens of seconds). Uses the GPU when possible.
 * If the restore step fails, the result of step 1 is returned and [lastError] says why.
 */
class MaxEnhancer(
    context: Context,
    private val base: Enhancer,
    private val blend: Float = 0.65f,
) : Enhancer {

    private val appContext = context.applicationContext

    @Volatile
    var lastError: String? = null
        private set

    /** Called with 0..100 from a background thread while tiles are processed. */
    @Volatile
    var onProgress: ((Int) -> Unit)? = null

    override fun enhance(src: Bitmap): Bitmap {
        lastError = null
        val toned = base.enhance(src)
        return try {
            restore(toned)
        } catch (e: FileNotFoundException) {
            lastError = "detail model missing from this build ($MODEL)"
            toned
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(140)
            toned
        }
    }

    private fun restore(toned: Bitmap): Bitmap {
        val modelBytes = appContext.assets.open(MODEL).use { it.readBytes() }
        val modelBuf = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuf.put(modelBytes)
        modelBuf.rewind()

        // The GPU delegate must be created and used on the same thread, so everything
        // happens inside this one call.
        var delegate: GpuDelegate? = null
        val interp: Interpreter = try {
            delegate = GpuDelegate()
            Interpreter(modelBuf, Interpreter.Options().addDelegate(delegate))
        } catch (t: Throwable) {
            delegate?.close()
            delegate = null
            modelBuf.rewind()
            Interpreter(modelBuf, Interpreter.Options().setNumThreads(4))
        }
        val usingGpu = delegate != null

        try {
            val inTensor = interp.getInputTensor(0)
            val inShape = inTensor.shape()
            val outShape = interp.getOutputTensor(0).shape()
            if (inShape.size != 4 || inShape[3] != 3 || outShape.size != 4 || outShape[3] != 3) {
                error("unsupported model layout ${inShape.toList()} -> ${outShape.toList()}")
            }
            if (inTensor.dataType() != DataType.FLOAT32) error("model input is not float32")
            if (inShape[1] != inShape[2]) error("non-square model input")

            val t = inShape[1]
            val sc = outShape[1] / t
            if (sc < 1) error("bad model scale")
            val outT = t * sc

            val w0 = toned.width
            val h0 = toned.height

            // Working copy: bigger on GPU, smaller on CPU to keep the time reasonable
            val side = if (usingGpu) 2048 else 1280
            val k = min(1f, side.toFloat() / maxOf(w0, h0))
            val ww = (w0 * k).toInt().coerceAtLeast(t)
            val wh = (h0 * k).toInt().coerceAtLeast(t)
            val work = Bitmap.createScaledBitmap(toned, ww, wh, true)
            val wpx = IntArray(ww * wh)
            work.getPixels(wpx, 0, ww, 0, 0, ww, wh)
            if (work !== toned) work.recycle()

            val px = IntArray(w0 * h0)
            toned.getPixels(px, 0, w0, 0, 0, w0, h0)

            val pad = 8
            val stride = t - 2 * pad
            val fx = w0.toFloat() / (ww * sc)
            val fy = h0.toFloat() / (wh * sc)
            val tilesX = (ww + stride - 1) / stride
            val tilesY = (wh + stride - 1) / stride
            val total = tilesX * tilesY

            val inBuf = ByteBuffer.allocateDirect(t * t * 3 * 4).order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(outT * outT * 3 * 4).order(ByteOrder.nativeOrder())
            val outArr = FloatArray(outT * outT * 3)
            val keep = 1f - blend
            var done = 0

            for (tyi in 0 until tilesY) {
                for (txi in 0 until tilesX) {
                    val tx = txi * stride
                    val ty = tyi * stride
                    val cx1 = min(tx + stride, ww)
                    val cy1 = min(ty + stride, wh)
                    val ox = tx - pad
                    val oy = ty - pad

                    // Fill the model input window (edges are clamped)
                    inBuf.clear()
                    for (yy in 0 until t) {
                        val row = (oy + yy).coerceIn(0, wh - 1) * ww
                        for (xx in 0 until t) {
                            val p = wpx[row + (ox + xx).coerceIn(0, ww - 1)]
                            inBuf.putFloat(((p shr 16) and 0xFF) / 255f)
                            inBuf.putFloat(((p shr 8) and 0xFF) / 255f)
                            inBuf.putFloat((p and 0xFF) / 255f)
                        }
                    }
                    inBuf.rewind()
                    outBuf.clear()
                    interp.run(inBuf, outBuf)
                    outBuf.rewind()
                    outBuf.asFloatBuffer().get(outArr)

                    // Target region in the original-resolution image covered by this tile's core
                    val xs = ceil(tx * sc * fx).toInt().coerceIn(0, w0)
                    val xe = if (cx1 >= ww) w0 else ceil(cx1 * sc * fx).toInt().coerceIn(0, w0)
                    val ys = ceil(ty * sc * fy).toInt().coerceIn(0, h0)
                    val ye = if (cy1 >= wh) h0 else ceil(cy1 * sc * fy).toInt().coerceIn(0, h0)
                    if (xe > xs && ye > ys) {
                        val nx = xe - xs
                        val ny = ye - ys

                        // Two sample positions per axis (2x2 supersampling) for the downscale
                        val cu0 = IntArray(nx * 2)
                        val cu1 = IntArray(nx * 2)
                        val cwu = FloatArray(nx * 2)
                        for (i in 0 until nx) {
                            val g = (xs + i + 0.5f) / fx - 0.5f
                            for (s in 0..1) {
                                val pos = g + (if (s == 0) -0.25f else 0.25f) / fx - ox * sc
                                val c = pos.coerceIn(0f, (outT - 1).toFloat())
                                val a = c.toInt().coerceAtMost(outT - 1)
                                cu0[i * 2 + s] = a
                                cu1[i * 2 + s] = min(a + 1, outT - 1)
                                cwu[i * 2 + s] = c - a
                            }
                        }
                        val cv0 = IntArray(ny * 2)
                        val cv1 = IntArray(ny * 2)
                        val cwv = FloatArray(ny * 2)
                        for (j in 0 until ny) {
                            val g = (ys + j + 0.5f) / fy - 0.5f
                            for (s in 0..1) {
                                val pos = g + (if (s == 0) -0.25f else 0.25f) / fy - oy * sc
                                val c = pos.coerceIn(0f, (outT - 1).toFloat())
                                val a = c.toInt().coerceAtMost(outT - 1)
                                cv0[j * 2 + s] = a
                                cv1[j * 2 + s] = min(a + 1, outT - 1)
                                cwv[j * 2 + s] = c - a
                            }
                        }

                        for (j in 0 until ny) {
                            val rowBase = (ys + j) * w0
                            for (i in 0 until nx) {
                                var r = 0f
                                var g = 0f
                                var b = 0f
                                for (sy in 0..1) {
                                    val v0 = cv0[j * 2 + sy] * outT
                                    val v1 = cv1[j * 2 + sy] * outT
                                    val wv = cwv[j * 2 + sy]
                                    for (sx in 0..1) {
                                        val u0 = cu0[i * 2 + sx]
                                        val u1 = cu1[i * 2 + sx]
                                        val wu = cwu[i * 2 + sx]
                                        val i00 = (v0 + u0) * 3
                                        val i01 = (v0 + u1) * 3
                                        val i10 = (v1 + u0) * 3
                                        val i11 = (v1 + u1) * 3
                                        val w00 = (1f - wu) * (1f - wv)
                                        val w01 = wu * (1f - wv)
                                        val w10 = (1f - wu) * wv
                                        val w11 = wu * wv
                                        r += outArr[i00] * w00 + outArr[i01] * w01 +
                                            outArr[i10] * w10 + outArr[i11] * w11
                                        g += outArr[i00 + 1] * w00 + outArr[i01 + 1] * w01 +
                                            outArr[i10 + 1] * w10 + outArr[i11 + 1] * w11
                                        b += outArr[i00 + 2] * w00 + outArr[i01 + 2] * w01 +
                                            outArr[i10 + 2] * w10 + outArr[i11 + 2] * w11
                                    }
                                }
                                val rr = (r * 0.25f).coerceIn(0f, 1f) * 255f
                                val gg = (g * 0.25f).coerceIn(0f, 1f) * 255f
                                val bb = (b * 0.25f).coerceIn(0f, 1f) * 255f

                                val idx = rowBase + xs + i
                                val p = px[idx]
                                val nr = (((p shr 16) and 0xFF) * keep + rr * blend).toInt().coerceIn(0, 255)
                                val ng = (((p shr 8) and 0xFF) * keep + gg * blend).toInt().coerceIn(0, 255)
                                val nb = ((p and 0xFF) * keep + bb * blend).toInt().coerceIn(0, 255)
                                px[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                            }
                        }
                    }

                    done++
                    onProgress?.invoke(done * 100 / total)
                }
            }

            val out = Bitmap.createBitmap(w0, h0, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w0, 0, 0, w0, h0)
            return out
        } finally {
            interp.close()
            delegate?.close()
        }
    }

    private companion object {
        const val MODEL = "realesrgan_x4v3.tflite"
    }
}
