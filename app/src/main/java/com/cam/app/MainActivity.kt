package com.cam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
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
    private lateinit var resultView: ImageView
    private lateinit var status: TextView
    private lateinit var shutter: Button
    private var imageCapture: ImageCapture? = null
    private val enhancer: Enhancer = AutoEnhancer()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Camera permission is needed"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT

        previewView = PreviewView(this)
        resultView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { visibility = View.GONE }
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(32, 48, 32, 24)
        }
        shutter = Button(this).apply {
            text = "Capture"
            setOnClickListener { capture() }
        }

        val root = FrameLayout(this)
        root.addView(previewView, FrameLayout.LayoutParams(match, match))
        root.addView(resultView, FrameLayout.LayoutParams(match, match))
        root.addView(status, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))
        root.addView(
            shutter,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = 96 }
        )
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val cap = imageCapture ?: return
        shutter.isEnabled = false
        status.text = "Capturing..."
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
                    status.text = "Capture failed: ${exception.message}"
                    shutter.isEnabled = true
                }
            }
        )
    }

    private fun enhance(raw: Bitmap, rotation: Int) {
        status.text = "Enhancing..."
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.Default) {
                val upright = if (rotation != 0) {
                    Bitmap.createBitmap(
                        raw, 0, 0, raw.width, raw.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, true
                    )
                } else raw
                enhancer.enhance(upright)
            }
            withContext(Dispatchers.IO) { save(result) }
            val secs = (SystemClock.elapsedRealtime() - start) / 1000.0
            resultView.setImageBitmap(result)
            resultView.visibility = View.VISIBLE
            status.text = "Saved to Pictures/Cam in %.1fs. Tap photo to dismiss.".format(secs)
            shutter.isEnabled = true
        }
    }

    private fun save(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CAM_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return
        contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }
}
