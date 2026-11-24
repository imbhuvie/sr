package com.example.screenreader.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.screenreader.db.AppDatabase
import com.example.screenreader.db.CaptureRecord
import com.example.screenreader.processing.IconDetector
import com.example.screenreader.settings.MonitorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.example.screenreader.START"
        const val ACTION_STOP = "com.example.screenreader.STOP"
        const val ACTION_PAUSE = "com.example.screenreader.PAUSE"
        const val ACTION_RESUME = "com.example.screenreader.RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_reader_monitor"
        private const val NOTIFICATION_ID = 4102
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private val waitingForFrame = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detector = IconDetector()
    private var settings = MonitorSettings()
    private var cycleId = 0L
    private var cycleScheduledAt = 0L
    private var cycleStartedElapsed = 0L
    private var attemptCount = 0
    private var timeoutRunnable: Runnable? = null
    private var nextCycleRunnable: Runnable? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 0
    @Volatile private var monitoringPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            monitoringPaused = true
            waitingForFrame.set(false)
            nextCycleRunnable?.let { worker?.removeCallbacks(it) }
            timeoutRunnable?.let { worker?.removeCallbacks(it) }
            updateNotification("Paused · latest screenshot frozen")
            return START_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            monitoringPaused = false
            updateNotification("Resumed · monitoring")
            scheduleCycle(100)
            return START_STICKY
        }
        if (projection == null) {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification("Starting screen monitor"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Starting screen monitor"))
            }
            settings = MonitorSettings.load(this)
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.densityDpi
        workerThread = HandlerThread("screen-reader-capture").also { it.start() }
        worker = Handler(workerThread!!.looper)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader -> handleAvailableImage(reader) }, worker)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                stopMonitoring()
                stopSelf()
            }
        }, worker)
        virtualDisplay = projection!!.createVirtualDisplay(
            "ScreenReaderDisplay", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, worker
        )
        updateNotification("Monitoring every ${settings.intervalSeconds} seconds")
        scheduleCycle(500)
    }

    private fun scheduleCycle(delayMs: Long) {
        if (monitoringPaused) return
        val handler = worker ?: return
        nextCycleRunnable?.let { handler.removeCallbacks(it) }
        nextCycleRunnable = Runnable { startCycle() }
        handler.postDelayed(nextCycleRunnable!!, delayMs)
    }

    private fun startCycle() {
        if (monitoringPaused) return
        settings = MonitorSettings.load(this)
        cycleId++
        cycleScheduledAt = System.currentTimeMillis()
        cycleStartedElapsed = SystemClock.elapsedRealtime()
        attemptCount = 0
        updateNotification("Capture cycle $cycleId")
        attemptCapture()
    }

    private fun attemptCapture() {
        if (monitoringPaused) return
        attemptCount++
        waitingForFrame.set(true)
        val timeout = Runnable {
            if (waitingForFrame.compareAndSet(true, false)) handleAttemptFailure("CAPTURE_TIMEOUT")
        }
        timeoutRunnable?.let { worker?.removeCallbacks(it) }
        timeoutRunnable = timeout
        worker?.postDelayed(timeout, 1500)
    }

    private fun handleAvailableImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        if (!waitingForFrame.compareAndSet(true, false)) {
            image.close()
            return
        }
        timeoutRunnable?.let { worker?.removeCallbacks(it) }
        val bitmap = try { imageToBitmap(image) } catch (_: Exception) { null } finally { image.close() }
        if (bitmap == null) {
            handleAttemptFailure("IMAGE_CONVERSION_ERROR")
            return
        }
        saveLatestFrame(bitmap)
        val started = SystemClock.elapsedRealtime()
        val result = try { detector.detect(bitmap, settings) } catch (_: Exception) { null }
        val processingTime = SystemClock.elapsedRealtime() - started
        bitmap.recycle()
        if (result == null || result.status != "SUCCESS") {
            handleAttemptFailure(result?.status ?: "PROCESSING_ERROR", result?.confidence ?: 0f, processingTime)
        } else {
            saveCycleRecord(result.symbol, result.result, result.centerX, result.centerY,
                result.confidence, "SUCCESS", processingTime)
        }
    }

    private fun handleAttemptFailure(status: String, confidence: Float = 0f, processingTime: Long = 0) {
        if (attemptCount <= settings.maxRetries) {
            worker?.postDelayed({ attemptCapture() }, settings.retryDelayMs)
        } else {
            saveCycleRecord(null, null, null, null, confidence, status, processingTime)
        }
    }

    private fun saveCycleRecord(symbol: String?, result: String?, x: Int?, y: Int?, confidence: Float, status: String, processingTime: Long) {
        val now = System.currentTimeMillis()
        val savedCycleId = cycleId
        val savedScheduledAt = cycleScheduledAt
        val savedAttemptCount = attemptCount
        scope.launch {
            AppDatabase.get(this@ScreenCaptureService).captureDao().insert(
                CaptureRecord(
                    cycleId = savedCycleId, scheduledAt = savedScheduledAt, capturedAt = now,
                    symbol = symbol, result = result, centerX = x, centerY = y,
                    confidence = confidence, attemptCount = savedAttemptCount, status = status,
                    processingTimeMs = processingTime
                )
            )
        }
        updateNotification(if (status == "SUCCESS") "Saved $symbol · cycle $cycleId" else "Cycle $cycleId · $status")
        val elapsed = SystemClock.elapsedRealtime() - cycleStartedElapsed
        val intervalMs = settings.intervalSeconds * 1000L
        scheduleCycle((intervalMs - elapsed).coerceAtLeast(0))
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun saveLatestFrame(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "latest_screen.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (_: Exception) { }
    }

    private fun stopMonitoring() {
        waitingForFrame.set(false)
        timeoutRunnable?.let { worker?.removeCallbacks(it) }
        nextCycleRunnable?.let { worker?.removeCallbacks(it) }
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        workerThread?.quitSafely(); workerThread = null; worker = null
    }

    override fun onDestroy() {
        stopMonitoring()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen monitoring", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val pauseIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_PAUSE)
        val resumeIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_RESUME)
        val pausePending = PendingIntent.getService(this, 4103, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val resumePending = PendingIntent.getService(this, 4104, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Reader is running").setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
            .addAction(0, "Freeze frame", pausePending)
            .addAction(0, "Resume", resumePending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }
}
