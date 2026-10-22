package com.logistics.vehiclereport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reads a vehicle's full number from its number plate with the camera, for a vehicle posted with only its last 4
 * digits. The text is read on the phone (ML Kit); no picture is saved or sent anywhere.
 * In: [EXTRA_CODE] the 4 digits posted, [EXTRA_KNOWN] list vehicles ending with them, [EXTRA_ROW] the Today row.
 * Out: [EXTRA_PLATE] the full number (plus the same code and row).
 */
class PlateScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_KNOWN = "known"
        const val EXTRA_ROW = "row"
        const val EXTRA_PLATE = "plate"
    }

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var plateText: TextView
    private lateinit var matchText: TextView
    private lateinit var editLayout: TextInputLayout
    private lateinit var edit: TextInputEditText
    private lateinit var resultRow: View
    private lateinit var light: MaterialButton

    private var camera: Camera? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var code: String? = null
    private var known: List<String> = emptyList()
    private var found: String? = null
    private val seen = HashMap<String, Int>()
    @Volatile private var paused = false
    private var torch = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            status.setText(R.string.scan_no_camera)
            showEdit("")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plate_scan)
        val top = findViewById<View>(R.id.scanTop)
        val card = findViewById<View>(R.id.scanCard)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scanRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            top.setPadding(top.paddingLeft, bars.top + dp(12), top.paddingRight, top.paddingBottom)
            (card.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = bars.bottom + dp(14)
            card.requestLayout()
            insets
        }
        preview = findViewById(R.id.scanPreview)
        status = findViewById(R.id.scanStatus)
        plateText = findViewById(R.id.scanPlate)
        matchText = findViewById(R.id.scanMatch)
        editLayout = findViewById(R.id.scanEditLayout)
        edit = findViewById(R.id.scanEdit)
        resultRow = findViewById(R.id.scanResult)
        light = findViewById(R.id.scanLight)

        code = intent.getStringExtra(EXTRA_CODE)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }
        known = intent.getStringArrayListExtra(EXTRA_KNOWN).orEmpty()
        findViewById<TextView>(R.id.scanHint).text =
            code?.let { getString(R.string.scan_hint_code, it) } ?: getString(R.string.scan_hint_any)

        findViewById<View>(R.id.scanClose).setOnClickListener { finish() }
        light.setOnClickListener {
            torch = !torch
            camera?.cameraControl?.enableTorch(torch)
            light.backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.color(this, if (torch) R.color.brand_light else R.color.surface))
        }
        findViewById<View>(R.id.scanType).setOnClickListener { showEdit(found ?: "") }
        findViewById<View>(R.id.scanAgain).setOnClickListener { resume() }
        findViewById<View>(R.id.scanUse).setOnClickListener { use() }
        plateText.setOnClickListener { showEdit(found ?: "") }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val pv = Preview.Builder().build()
                pv.setSurfaceProvider(preview.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(worker) { analyze(it) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
            } catch (e: Exception) {
                status.setText(R.string.scan_no_camera)
                showEdit("")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (paused || media == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        // No Google Play services (or no reader yet): say so and let the number be typed; never crash the camera.
        val task = try { recognizer.process(image) } catch (e: Exception) {
            proxy.close()
            runOnUiThread { if (!paused) status.setText(R.string.scan_status_preparing) }
            return
        }
        task
            .addOnSuccessListener { onText(it.text) }
            .addOnFailureListener { e ->
                // First use: Play services is still downloading the reader (needs internet once).
                if (!paused && e is com.google.mlkit.common.MlKitException && e.errorCode == com.google.mlkit.common.MlKitException.UNAVAILABLE)
                    status.setText(R.string.scan_status_preparing)
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** A plate counts once it is read the same way in 2 frames (a list vehicle) or 3 frames (any other plate). */
    private fun onText(text: String) {
        if (paused) return
        val plate = PlateReader.read(text, code, known)
        if (plate == null) {
            if (text.isNotBlank()) status.setText(R.string.scan_status_reading)
            return
        }
        val n = (seen[plate] ?: 0) + 1
        seen[plate] = n
        status.setText(R.string.scan_status_reading)
        if (n >= if (plate in known) 2 else 3) showResult(plate)
    }

    private fun showResult(plate: String) {
        paused = true
        found = plate
        status.text = ""
        status.visibility = View.GONE
        plateText.text = PlateReader.pretty(plate)
        plateText.visibility = View.VISIBLE
        val c = code
        val (label, color) = when {
            plate in known -> getString(R.string.scan_in_list) to R.color.ok
            c != null && VehicleParser.last4(plate) != c -> getString(R.string.scan_wrong_digits, c) to R.color.no
            else -> getString(R.string.scan_not_in_list) to R.color.chk
        }
        matchText.text = label
        matchText.setTextColor(Ui.color(this, color))
        matchText.visibility = View.VISIBLE
        editLayout.visibility = View.GONE
        resultRow.visibility = View.VISIBLE
    }

    private fun showEdit(prefill: String) {
        paused = true
        status.visibility = View.GONE
        plateText.visibility = View.GONE
        matchText.visibility = View.GONE
        editLayout.visibility = View.VISIBLE
        editLayout.error = null
        edit.setText(prefill)
        edit.setSelection(edit.text?.length ?: 0)
        edit.requestFocus()
        resultRow.visibility = View.VISIBLE
    }

    private fun resume() {
        seen.clear()
        found = null
        status.visibility = View.VISIBLE
        status.setText(R.string.scan_status_looking)
        plateText.visibility = View.GONE
        matchText.visibility = View.GONE
        editLayout.visibility = View.GONE
        resultRow.visibility = View.GONE
        paused = false
    }

    private fun use() {
        val plate = if (editLayout.visibility == View.VISIBLE) {
            val typed = edit.text?.toString().orEmpty()
            PlateReader.findKnown(typed, known) ?: PlateReader.plates(typed).firstOrNull()
        } else found
        val c = code
        when {
            plate == null -> { showEdit(edit.text?.toString().orEmpty()); editLayout.error = getString(R.string.scan_bad_number) }
            c != null && VehicleParser.last4(plate) != c -> {
                showEdit(plate); editLayout.error = getString(R.string.scan_wrong_digits, c)
            }
            else -> {
                setResult(RESULT_OK, Intent()
                    .putExtra(EXTRA_PLATE, plate)
                    .putExtra(EXTRA_CODE, c ?: VehicleParser.last4(plate))
                    .putExtra(EXTRA_ROW, intent.getStringExtra(EXTRA_ROW)))
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        try { recognizer.close() } catch (e: Exception) { }
    }
}
