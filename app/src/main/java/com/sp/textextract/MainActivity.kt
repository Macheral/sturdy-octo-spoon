package com.sp.textextract

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.sp.textextract.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var tempPhotoUri: Uri? = null
    private var tempPhotoFile: File? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Coroutine scope tied to the Activity lifecycle
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri  = tempPhotoUri
            val file = tempPhotoFile
            if (success && uri != null && file != null) {
                runPreprocessAndOcr(uri, file)
            } else {
                file?.delete()
                tempPhotoUri  = null
                tempPhotoFile = null
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialise OpenCV (loads the native .so bundled by the AAR)
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV failed to load.", Toast.LENGTH_LONG).show()
        }

        binding.btnTakePhoto.setOnClickListener { checkPermissionAndLaunchCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        activityScope.cancel()
    }

    // ── camera helpers ─────────────────────────────────────────────────────────

    private fun checkPermissionAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) launchCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val file = File.createTempFile("ocr_photo_", ".jpg", cacheDir)
        val uri  = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        tempPhotoFile = file
        tempPhotoUri  = uri
        takePictureLauncher.launch(uri)
    }

    // ── preprocessing + OCR pipeline ──────────────────────────────────────────

    /**
     * Offloads the heavy image-processing work to [Dispatchers.Default] so the
     * UI thread is never blocked, then hands the processed [Bitmap] to ML Kit
     * on the main thread.
     */
    private fun runPreprocessAndOcr(uri: Uri, file: File) {
        binding.progressBar.visibility   = View.VISIBLE
        binding.cardOcrResult.visibility = View.GONE
        binding.tvOcrResult.text         = ""

        activityScope.launch {
            // ── Step A: decode + preprocess on a background thread ────────────
            val processedBitmap: Bitmap? = withContext(Dispatchers.Default) {
                runCatching {
                    // Decode EXIF rotation so we can pass it to the preprocessor
                    val exifDegrees = file.inputStream().use { stream ->
                        ExifInterface(stream).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        ).toExifDegrees()
                    }

                    // Decode the bitmap (inSampleSize=1 → full resolution up to MAX_SIDE)
                    val raw = BitmapFactory.decodeFile(file.absolutePath)
                        ?: error("Failed to decode image")

                    ImagePreprocessor.process(raw, exifDegrees)
                }.getOrElse { e ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Pre-processing error: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    null
                }
            }

            // Clean up the temp file — we no longer need the JPEG on disk
            file.delete()
            tempPhotoUri  = null
            tempPhotoFile = null

            if (processedBitmap == null) {
                binding.progressBar.visibility = View.GONE
                return@launch
            }

            // ── Step B: run ML Kit OCR on the main thread ─────────────────────
            val image = InputImage.fromBitmap(processedBitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    binding.progressBar.visibility = View.GONE
                    val extracted = visionText.text.trim()
                    binding.tvOcrResult.text =
                        if (extracted.isEmpty()) "No text detected." else extracted
                    binding.cardOcrResult.visibility = View.VISIBLE
                }
                .addOnFailureListener { e ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // ── EXIF helpers ───────────────────────────────────────────────────────────

    /** Converts an [ExifInterface] orientation constant to a clockwise-degree value. */
    private fun Int.toExifDegrees(): Int = when (this) {
        ExifInterface.ORIENTATION_ROTATE_90  -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else                                  -> 0
    }
}