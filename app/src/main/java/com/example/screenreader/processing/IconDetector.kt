package com.example.screenreader.processing

import android.graphics.Bitmap
import android.graphics.Color
import com.example.screenreader.settings.MonitorSettings
import kotlin.math.max

/** Pure image-processing component. It has no dependency on Android services or the database. */
class IconDetector {
    private data class ColumnHit(val x: Int, val blue: Int, val green: Int, val yellow: Int) {
        val total: Int get() = blue + green + yellow
    }
    private data class Candidate(val left: Int, val right: Int)

    fun detect(source: Bitmap, settings: MonitorSettings): DetectionResult {
        if (source.width < 2 || source.height < 2) return failure("NO_IMAGE")
        val left = (settings.roiX * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (settings.roiY * source.height).toInt().coerceIn(0, source.height - 1)
        val width = (settings.roiWidth * source.width).toInt().coerceAtLeast(1).coerceAtMost(source.width - left)
        val height = (settings.roiHeight * source.height).toInt().coerceAtLeast(1).coerceAtMost(source.height - top)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, left, top, width, height)
        val hits = ArrayList<ColumnHit>(width)
        val minimumPixels = max(2, (height * 0.10f).toInt())

        for (x in 0 until width) {
            var blue = 0; var green = 0; var yellow = 0
            for (y in 0 until height) {
                val color = pixels[y * width + x]
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                if (hsv[1] < 0.42f || hsv[2] < 0.30f) continue
                val hue = hsv[0]
                when {
                    hue in 185f..255f -> blue++
                    hue in 85f..165f -> green++
                    hue in 12f..75f -> yellow++
                }
            }
            if (blue + green + yellow >= minimumPixels) hits.add(ColumnHit(x, blue, green, yellow))
        }

        if (hits.isEmpty()) return failure("NO_ICON")
        val groups = ArrayList<Candidate>()
        var groupStart = hits.first().x
        var previous = hits.first().x
        for (index in 1 until hits.size) {
            val current = hits[index].x
            if (current - previous > 4) {
                if (previous - groupStart + 1 >= 8) groups.add(Candidate(groupStart, previous))
                groupStart = current
            }
            previous = current
        }
        if (previous - groupStart + 1 >= 8) groups.add(Candidate(groupStart, previous))
        val candidate = groups.filter { it.right - it.left + 1 in 8..120 }.maxByOrNull { it.right }
            ?: return failure("NO_ICON")

        var blueTotal = 0; var greenTotal = 0; var yellowTotal = 0
        for (hit in hits) {
            if (hit.x in candidate.left..candidate.right) {
                blueTotal += hit.blue; greenTotal += hit.green; yellowTotal += hit.yellow
            }
        }
        val total = blueTotal + greenTotal + yellowTotal
        if (total == 0) return failure("UNKNOWN")
        val symbol: String
        val result: String
        val winning: Int
        when (max(blueTotal, max(greenTotal, yellowTotal))) {
            blueTotal -> { symbol = "D"; result = "Dragon"; winning = blueTotal }
            greenTotal -> { symbol = "Tie"; result = "Tie"; winning = greenTotal }
            else -> { symbol = "T"; result = "Tiger"; winning = yellowTotal }
        }
        val confidence = winning.toFloat() / total.toFloat()
        if (confidence < settings.confidenceThreshold) return DetectionResult(null, null, null, null, confidence, "UNKNOWN")
        return DetectionResult(symbol, result, left + (candidate.left + candidate.right) / 2,
            top + height / 2, confidence, "SUCCESS")
    }

    private fun failure(status: String) = DetectionResult(null, null, null, null, 0f, status)
}
