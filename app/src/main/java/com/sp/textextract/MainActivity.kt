package com.sp.textextract

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sp.textextract.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Holds the URI of the temp photo file while the camera is open
    private var tempPhotoUri: Uri? = null
    private var tempPhotoFile: File? = null

    // ML Kit recognizer — reused across captures
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Launcher: opens camera, returns true if photo was taken
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri  = tempPhotoUri
            val file = tempPhotoFile

            if (success && uri != null && file != null) {
                runOcr(uri, file)
            } else {
                // Camera cancelled — clean up
                file?.delete()
                tempPhotoUri  = null
                tempPhotoFile = null
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

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }

    // -------------------------------------------------------------------------
    // Camera helpers
    // -------------------------------------------------------------------------

    private fun checkPermissionAndLaunchCamera() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> launchCamera()

            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val file = File.createTempFile("ocr_photo_", ".jpg", cacheDir)
        val uri  = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        tempPhotoFile = file
        tempPhotoUri  = uri

        takePictureLauncher.launch(uri)
    }

    // -------------------------------------------------------------------------
    // OCR
    // -------------------------------------------------------------------------

    private fun runOcr(uri: Uri, file: File) {
        // Show spinner, clear old result
        binding.progressBar.visibility  = View.VISIBLE
        binding.cardOcrResult.visibility = View.GONE
        binding.tvOcrResult.text         = ""

        val image = InputImage.fromFilePath(this, uri)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                file.delete()
                tempPhotoUri  = null
                tempPhotoFile = null

                binding.progressBar.visibility = View.GONE

                val extracted = visionText.text.trim()
                if (extracted.isEmpty()) {
                    binding.tvOcrResult.text = "No text detected."
                } else {
                    binding.tvOcrResult.text = extracted
                }
                binding.cardOcrResult.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                file.delete()
                tempPhotoUri  = null
                tempPhotoFile = null

                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}