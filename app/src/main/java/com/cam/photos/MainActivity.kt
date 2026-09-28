package com.cam.photos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.cam.photos.ui.nav.PhotosAppRoot
import com.cam.photos.ui.theme.PhotosAppTheme

class MainActivity : ComponentActivity() {

    private var hasMediaPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Re-check after the dialog closes; flipping this state makes the grid load.
        hasMediaPermission = checkMediaPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasMediaPermission = checkMediaPermission()
        if (!hasMediaPermission) {
            permissionLauncher.launch(requiredPermissions())
        }

        setContent {
            PhotosAppTheme {
                PhotosAppRoot(hasMediaPermission = hasMediaPermission)
            }
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun checkMediaPermission(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
