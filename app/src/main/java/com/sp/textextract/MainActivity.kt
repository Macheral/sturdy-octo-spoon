package com.sp.textextract

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sp.textextract.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Temporary URI for the photo — discarded after capture
    private var tempPhotoUri: Uri? = null

    // Launcher: opens camera, returns true if photo was taken
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            // Capture into a local val first — fixes "smart cast impossible" on a mutable var
            val uri = tempPhotoUri
            tempPhotoUri = null
            uri?.path?.let { path -> File(path).delete() }

            if (success) {
                Toast.makeText(this, "Photo taken — returning to home.", Toast.LENGTH_SHORT).show()
            }
        }

    // Launcher: requests camera permission
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required to take photos.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTakePhoto.setOnClickListener {
            checkPermissionAndLaunchCamera()
        }
    }

    private fun checkPermissionAndLaunchCamera() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                launchCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun launchCamera() {
        val tempFile = File.createTempFile("temp_photo_", ".jpg", cacheDir)
        // Store in a local val first, then assign — launch receives a guaranteed non-null Uri
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            tempFile
        )
        tempPhotoUri = uri
        takePictureLauncher.launch(uri)
    }
}