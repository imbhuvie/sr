package com.example.screenreader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.screenreader.capture.ScreenCaptureService
import com.example.screenreader.db.AppDatabase
import com.example.screenreader.processing.IconDetector
import com.example.screenreader.settings.MonitorSettings
import com.example.screenreader.ui.CalibrationActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportCsv(uri)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            statusText.text = "Monitoring requested · switch to the target app"
            Toast.makeText(this, "Screen capture started. Switch to the target app.", Toast.LENGTH_LONG).show()
        } else {
            statusText.text = "Screen capture permission was not granted"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildDashboard()
        refreshStats()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun buildDashboard() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 18)
            setBackgroundColor(0xFFF4F6F5.toInt())
        }
        val title = TextView(this).apply { text = "SCREEN READER"; textSize = 25f; setTextColor(0xFF174C3C.toInt()); setPadding(0, 0, 0, 8) }
        root.addView(title)
        root.addView(TextView(this).apply { text = "Rightmost icon monitor · D / T / Tie"; textSize = 14f; setTextColor(0xFF53635D.toInt()) })
        statusText = TextView(this).apply { text = "Not monitoring"; textSize = 17f; setTextColor(0xFF174C3C.toInt()); setPadding(0, 18, 0, 14) }
        root.addView(statusText)
        countText = TextView(this).apply { textSize = 15f; setTextColor(0xFF26332D.toInt()); setPadding(0, 0, 0, 14) }
        root.addView(countText)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        left.addView(actionButton("START MONITORING") { requestCapturePermission() })
        left.addView(actionButton("STOP MONITORING") { stopMonitoring() })
        left.addView(actionButton("CALIBRATE REGION") { startActivity(Intent(this, CalibrationActivity::class.java)) })
        right.addView(actionButton("SETTINGS") { showSettings() })
        right.addView(actionButton("PREVIEW DETECTION") { previewDetection() })
        right.addView(actionButton("VIEW RECORDS") { showRecords() })
        right.addView(actionButton("EXPORT CSV") { createCsv.launch("screen-reader-records.csv") })
        grid.addView(left); grid.addView(right)
        root.addView(grid)
        root.addView(TextView(this).apply {
            text = "Tip: Start monitoring, grant screen-capture permission, then switch to the target application. Keep the target screen visible in landscape."
            textSize = 12f; setTextColor(0xFF65736D.toInt()); setPadding(0, 16, 0, 0)
        })
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 58).apply { setMargins(5, 4, 5, 4) }
    }

    private fun requestCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenPermission.launch(manager.createScreenCaptureIntent())
    }

    private fun stopMonitoring() {
        startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        statusText.text = "Stop requested"
    }

    private fun showSettings() {
        val current = MonitorSettings.load(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 0) }
        val interval = numberField("Capture interval (seconds)", current.intervalSeconds.toString(), layout)
        val retries = numberField("Additional retries (0–5)", current.maxRetries.toString(), layout)
        val delay = numberField("Retry delay (milliseconds)", current.retryDelayMs.toString(), layout)
        val threshold = numberField("Confidence threshold (0.0–1.0)", current.confidenceThreshold.toString(), layout)
        AlertDialog.Builder(this).setTitle("Monitoring settings").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val updated = current.copy(
                    intervalSeconds = interval.text.toString().toIntOrNull()?.coerceIn(5, 300) ?: 10,
                    maxRetries = retries.text.toString().toIntOrNull()?.coerceIn(0, 5) ?: 2,
                    retryDelayMs = delay.text.toString().toLongOrNull()?.coerceIn(250, 10000) ?: 1000,
                    confidenceThreshold = threshold.text.toString().toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 0.55f
                )
                MonitorSettings.save(this, updated)
                Toast.makeText(this, "Settings saved. Restart monitoring to apply capture timing.", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun numberField(label: String, value: String, parent: LinearLayout): android.widget.EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 13f })
        val field = android.widget.EditText(this).apply { setText(value); textSize = 15f; singleLine = true }
        parent.addView(field)
        return field
    }

    private fun previewDetection() {
        val screenshot = java.io.File(filesDir, "latest_screen.png")
        if (!screenshot.exists()) {
            Toast.makeText(this, "No screenshot yet. Start monitoring first.", Toast.LENGTH_LONG).show()
            return
        }
        val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "Could not open the latest screenshot", Toast.LENGTH_LONG).show()
            return
        }
        val detection = IconDetector().detect(bitmap, MonitorSettings.load(this))
        bitmap.recycle()
        val message = "Status: ${detection.status}\nSymbol: ${detection.symbol ?: "—"}\nResult: ${detection.result ?: "—"}\nCenter: ${detection.centerX ?: "—"}, ${detection.centerY ?: "—"}\nConfidence: ${"%.2f".format(detection.confidence)}"
        AlertDialog.Builder(this).setTitle("Detection preview").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun showRecords() {
        lifecycleScope.launch {
            val records = AppDatabase.get(this@MainActivity).captureDao().latest(100)
            val text = if (records.isEmpty()) "No records yet." else records.joinToString("\n\n") { record ->
                val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.capturedAt))
                "#${record.id}  $time\n${record.result ?: record.status} · attempts ${record.attemptCount} · confidence ${"%.2f".format(record.confidence)}"
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Latest records").setMessage(text).setPositiveButton("Close", null).show()
        }
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val count = AppDatabase.get(this@MainActivity).captureDao().count()
            countText.text = "Saved capture cycles: $count"
        }
    }

    private fun exportCsv(uri: Uri) {
        lifecycleScope.launch {
            val records = AppDatabase.get(this@MainActivity).captureDao().latest(10000).reversed()
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.appendLine("id,cycle_id,scheduled_at,captured_at,symbol,result,center_x,center_y,confidence,attempt_count,status,processing_time_ms")
                    records.forEach { r ->
                        writer.appendLine(listOf(r.id, r.cycleId, r.scheduledAt, r.capturedAt, r.symbol ?: "", r.result ?: "", r.centerX ?: "", r.centerY ?: "", r.confidence, r.attemptCount, r.status, r.processingTimeMs).joinToString(","))
                    }
                }
                Toast.makeText(this@MainActivity, "CSV exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onResume() { super.onResume(); refreshStats() }
}
