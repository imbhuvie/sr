package com.example.screenreader.settings

import android.content.Context

data class MonitorSettings(
    val intervalSeconds: Int = 10,
    val maxRetries: Int = 2,
    val retryDelayMs: Long = 1000,
    val confidenceThreshold: Float = 0.55f,
    val roiX: Float = 0.25f,
    val roiY: Float = 0.28f,
    val roiWidth: Float = 0.49f,
    val roiHeight: Float = 0.10f
) {
    companion object {
        private const val PREFS = "monitor_settings"
        fun load(context: Context): MonitorSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return MonitorSettings(
                p.getInt("interval", 10), p.getInt("retries", 2), p.getLong("retry_delay", 1000),
                p.getFloat("threshold", 0.55f), p.getFloat("roi_x", 0.25f), p.getFloat("roi_y", 0.28f),
                p.getFloat("roi_w", 0.49f), p.getFloat("roi_h", 0.10f)
            )
        }
        fun save(context: Context, settings: MonitorSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("interval", settings.intervalSeconds).putInt("retries", settings.maxRetries)
                .putLong("retry_delay", settings.retryDelayMs).putFloat("threshold", settings.confidenceThreshold)
                .putFloat("roi_x", settings.roiX).putFloat("roi_y", settings.roiY)
                .putFloat("roi_w", settings.roiWidth).putFloat("roi_h", settings.roiHeight).apply()
        }
    }
}
