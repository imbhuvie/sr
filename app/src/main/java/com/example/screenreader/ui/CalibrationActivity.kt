package com.example.screenreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.screenreader.settings.MonitorSettings
import java.io.File
import kotlin.math.min

class CalibrationActivity : AppCompatActivity() {
    private lateinit var selectionView: RoiSelectionView
    private lateinit var info: TextView
    private var currentSettings = MonitorSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSettings = MonitorSettings.load(this)
        val imageFile = File(filesDir, "latest_screen.png")
        val bitmap = if (imageFile.exists()) BitmapFactory.decodeFile(imageFile.absolutePath) else null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 8, 14, 8) }
        root.addView(TextView(this).apply {
            text = "Detection region calibration · Drag across the history row to select its rectangle"
            textSize = 15f; setPadding(4, 4, 4, 8)
        })
        info = TextView(this).apply { textSize = 12f; setPadding(4, 4, 4, 8) }
        root.addView(info)
        if (bitmap == null) {
            root.addView(TextView(this).apply { text = "No captured screenshot found. Start monitoring first, switch to the target screen, then open calibration again."; textSize = 16f })
        } else {
            selectionView = RoiSelectionView(this, bitmap, currentSettings) { x, y, w, h ->
                info.text = "ROI normalized: x=${"%.3f".format(x)}, y=${"%.3f".format(y)}, width=${"%.3f".format(w)}, height=${"%.3f".format(h)}\nImage: ${bitmap.width} × ${bitmap.height}"
            }
            root.addView(selectionView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this).apply { text = "Reset"; setOnClickListener { selectionView.reset() } }, LinearLayout.LayoutParams(0, 54, 1f))
            buttons.addView(Button(this).apply { text = "Save region"; setOnClickListener {
                val roi = selectionView.getNormalizedRoi()
                MonitorSettings.save(this@CalibrationActivity, currentSettings.copy(roiX = roi[0], roiY = roi[1], roiWidth = roi[2], roiHeight = roi[3]))
                Toast.makeText(this@CalibrationActivity, "Detection region saved", Toast.LENGTH_SHORT).show()
                finish()
            } }, LinearLayout.LayoutParams(0, 54, 1f))
            root.addView(buttons)
        }
        setContentView(root)
    }
}

private class RoiSelectionView(
    context: android.content.Context,
    private val bitmap: Bitmap,
    settings: MonitorSettings,
    private val onChanged: (Float, Float, Float, Float) -> Unit
) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC928.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFC928; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 26f; setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt()) }
    private var imageRect = RectF()
    private var roi = RectF(settings.roiX, settings.roiY, settings.roiX + settings.roiWidth, settings.roiY + settings.roiHeight)
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        imageRect = RectF(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint)
        val screenRect = normalizedToView(roi)
        canvas.drawRect(screenRect, fillPaint)
        canvas.drawRect(screenRect, borderPaint)
        canvas.drawText("SELECTED ROI", screenRect.left + 5f, (screenRect.top - 8f).coerceAtLeast(24f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x.coerceIn(imageRect.left, imageRect.right)
                startY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                roi = RectF(viewToNormalizedX(startX), viewToNormalizedY(startY), viewToNormalizedX(startX), viewToNormalizedY(startY))
                dragging = true; invalidate(); return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (dragging) {
                val x = event.x.coerceIn(imageRect.left, imageRect.right)
                val y = event.y.coerceIn(imageRect.top, imageRect.bottom)
                roi = RectF(viewToNormalizedX(min(startX, x)), viewToNormalizedY(min(startY, y)),
                    viewToNormalizedX(kotlin.math.max(startX, x)), viewToNormalizedY(kotlin.math.max(startY, y)))
                invalidate()
                val values = getNormalizedRoi()
                onChanged(values[0], values[1], values[2], values[3])
                if (event.action == MotionEvent.ACTION_UP) dragging = false
                return true
            }
        }
        return true
    }

    fun reset() {
        roi = RectF(0.25f, 0.28f, 0.74f, 0.38f)
        invalidate()
        val values = getNormalizedRoi(); onChanged(values[0], values[1], values[2], values[3])
    }

    fun getNormalizedRoi(): FloatArray = floatArrayOf(
        roi.left.coerceIn(0f, 1f), roi.top.coerceIn(0f, 1f),
        (roi.right - roi.left).coerceIn(0.005f, 1f), (roi.bottom - roi.top).coerceIn(0.005f, 1f)
    )

    private fun normalizedToView(r: RectF) = RectF(
        imageRect.left + r.left * imageRect.width(), imageRect.top + r.top * imageRect.height(),
        imageRect.left + r.right * imageRect.width(), imageRect.top + r.bottom * imageRect.height()
    )
    private fun viewToNormalizedX(x: Float) = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
    private fun viewToNormalizedY(y: Float) = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
}
