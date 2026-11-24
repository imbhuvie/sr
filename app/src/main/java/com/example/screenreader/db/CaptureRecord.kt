package com.example.screenreader.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "capture_records")
data class CaptureRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cycleId: Long,
    val scheduledAt: Long,
    val capturedAt: Long,
    val symbol: String?,
    val result: String?,
    val centerX: Int?,
    val centerY: Int?,
    val confidence: Float,
    val attemptCount: Int,
    val status: String,
    val processingTimeMs: Long
)
