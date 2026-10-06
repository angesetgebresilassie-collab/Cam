package com.cam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultLayer: FrameLayout
    private lateinit var resultView: ImageView
    private lateinit var status: TextView
    private lateinit var shutter: FrameLayout
    private lateinit var thumb: ImageView
    private lateinit var modeChip: TextView
    private lateinit var flashBtn: TextView
    private lateinit var spinner: ProgressBar

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    // All modes are fully on-device.
    // HD = classic auto-enhance, AI = Zero-DCE tone model, MAX = AI + Real-ESRGAN detail restore
    private var mode = MODE_AI
    private val hdEnhancer: Enhancer = AutoEnhancer()
    private lateinit var aiEnhancer: LocalAiEnhancer
    private lateinit var maxEnhancer: MaxEnhancer

    private var lastOriginal: Bitmap? = null
    private var lastEnhanced: Bitmap? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera() else showStatus("Camera permission is needed")
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        aiEnhancer = LocalAiEnhancer(this, hdEnhancer)
        maxEnhancer = MaxEnhancer(this, aiEnhancer)
        maxEnhancer.onProgress = { pct ->
            runOnUiThread { showStatus("Max quality: restoring detail... $pct%") }
        }

        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Viewfinder (letterboxed so you see the full frame that will be captured)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(match, match))
        setupGestures()

        // Top bar: flash + mode (AI / Max / HD)
        flashBtn = chip("\u26A1 Off").apply { setOnClickListener { cycleFlash() } }
        modeChip = chip("").apply {
            setOnClickListener {
                mode = when (mode) {
                    MODE_AI -> MODE_MAX
                    MODE_MAX -> MODE_HD
                    else -> MODE_AI
                }
                updateModeLabel()
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        top.addView(flashBtn, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(12) })
        top.addView(modeChip)
        root.addView(
            top,
            FrameLayout.LayoutParams(match, wrap, Gravity.TOP).apply { topMargin = dp(40) }
        )
        updateFlashLabel()
        updateModeLabel()

        // Status pill under the top bar
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            visibility = View.GONE
            background = pill(0x99000000.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
            maxLines = 4
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(96); marginStart = dp(16); marginEnd = dp(16) }
        )

        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        // Bottom bar: last photo | shutter | flip
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x33FFFFFF)
            }
            clipToOutline = true
            setOnClickListener {
                if (lastEnhanced != null) showResult()
            }
        }

        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(4), Color.WHITE)
                setColor(Color.TRANSPARENT)
            }
        }
        val inner = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        shutter = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
            addView(inner, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
            setOnClickListener { capture() }
        }

        val flip = TextView(this).apply {
            text = "\u27F2"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x44FFFFFF)
            }
            setOnClickListener { flipCamera() }
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottom.addView(cell(thumb, dp(56)), LinearLayout.LayoutParams(0, dp(96), 1f))
        bottom.addView(cell(shutter, dp(84)), LinearLayout.LayoutParams(0, dp(96), 1f))
        bottom.addView(cell(flip, dp(56)), LinearLayout.LayoutParams(0, dp(96), 1f))
        root.addView(
            bottom,
            FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM).apply { bottomMargin = dp(28) }
        )

        // Result viewer: hold to compare with the original
        resultView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lastOriginal?.let { setImageBitmap(it) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        lastEnhanced?.let { setImageBitmap(it) }
                }
                true
            }
        }
        val close = chip("\u2715").apply { setOnClickListener { resultLayer.visibility = View.GONE } }
        val hint = TextView(this).apply {
            text = "Hold photo to see the original"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
        }
        resultLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(resultView, FrameLayout.LayoutParams(match, match))
            addView(
                close,
                FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END)
                    .apply { topMargin = dp(40); marginEnd = dp(16) }
            )
            addView(
                hint,
                FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    .apply { bottomMargin = dp(40) }
            )
        }
        root.addView(resultLayer, FrameLayout.LayoutParams(match, match))

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---- UI helpers ----

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(color)
    }

    private fun chip(label: String) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        background = pill(0x66000000)
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun cell(v: View, size: Int) = FrameLayout(this).apply {
        addView(v, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
    }

    private fun showStatus(msg: String?) {
        if (msg == null) {
            status.visibility = View.GONE
        } else {
            status.text = msg
            status.visibility = View.VISIBLE
        }
    }

    private fun setBusy(busy: Boolean, msg: String? = null) {
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        shutter.isEnabled = !busy
        shutter.alpha = if (busy) 0.4f else 1f
        showStatus(msg)
    }

    private fun updateModeLabel() {
        modeChip.text = when (mode) {
            MODE_MAX -> "\uD83D\uDC8E Max"
            MODE_AI -> "\u2728 AI"
            else -> "HD"
        }
    }

    private fun updateFlashLabel() {
        flashBtn.text = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> "\u26A1 Off"
            ImageCapture.FLASH_MODE_AUTO -> "\u26A1 Auto"
            else -> "\u26A1 On"
        }
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        updateFlashLabel()
    }

    private fun flipCamera() {
        val previous = lensFacing
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera {
            lensFacing = previous
            bindCamera()
        }
    }

    private fun showResult() {
        resultView.setImageBitmap(lastEnhanced)
        resultLayer.visibility = View.VISIBLE
    }

    // ---- Camera ----

    private fun setupGestures() {
        val scale = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val state = cam.cameraInfo.zoomState.value ?: return true
                    val target = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(target)
                    return true
                }
            }
        )
        val tap = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val cam = camera ?: return true
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    return true
                }
            }
        )
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            tap.onTouchEvent(ev)
            true
        }
    }

    private fun bindCamera(onError: (() -> Unit)? = null) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setFlashMode(flashMode)
                    .build()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(this, "Camera unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
                onError?.invoke()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val cap = imageCapture ?: return
        setBusy(true, "Capturing...")
        cap.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = image.toBitmap()
                    image.close()
                    enhance(bitmap, rotation)
                }

                override fun onError(exception: ImageCaptureException) {
                    setBusy(false, "Capture failed: ${exception.message}")
                }
            }
        )
    }

    private fun enhance(raw: Bitmap, rotation: Int) {
        val m = mode
        setBusy(
            true,
            when (m) {
                MODE_MAX -> "Max quality: enhancing... this takes a while"
                MODE_AI -> "Enhancing with on-device AI..."
                else -> "Enhancing in HD..."
            }
        )
        lifecycleScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                val enhancer: Enhancer = when (m) {
                    MODE_MAX -> maxEnhancer
                    MODE_AI -> aiEnhancer
                    else -> hdEnhancer
                }
                val pair = withContext(Dispatchers.Default) {
                    val upright = if (rotation != 0) {
                        Bitmap.createBitmap(
                            raw, 0, 0, raw.width, raw.height,
                            Matrix().apply { postRotate(rotation.toFloat()) }, true
                        )
                    } else raw
                    Pair(upright, enhancer.enhance(upright))
                }
                val original = pair.first
                val enhanced = pair.second
                val stamp = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    save(original, "CAM_${stamp}_original")
                    save(enhanced, "CAM_$stamp")
                }
                lastOriginal = original
                lastEnhanced = enhanced
                thumb.setImageBitmap(enhanced)
                val secs = (SystemClock.elapsedRealtime() - start) / 1000.0
                val aiError = if (m >= MODE_AI) aiEnhancer.lastError else null
                val maxError = if (m == MODE_MAX) maxEnhancer.lastError else null
                val msg = when {
                    maxError != null -> "Max detail pass failed: $maxError. Kept the AI result (%.1fs)".format(secs)
                    aiError != null -> "On-device AI failed: $aiError. Used HD instead (%.1fs)".format(secs)
                    m == MODE_MAX -> "Max quality done in %.1fs".format(secs)
                    m == MODE_AI -> "Enhanced with on-device AI in %.1fs".format(secs)
                    else -> "Enhanced in HD in %.1fs".format(secs)
                }
                setBusy(false, msg)
                showResult()
            } catch (e: Throwable) {
                setBusy(false, "Enhance failed: ${e.message}")
            }
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return
        contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)
        }
    }

    private companion object {
        const val MODE_HD = 0
        const val MODE_AI = 1
        const val MODE_MAX = 2
    }
}
