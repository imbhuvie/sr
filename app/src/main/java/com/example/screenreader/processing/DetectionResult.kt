package com.example.screenreader.processing

data class DetectionResult(
    val symbol: String?,
    val result: String?,
    val centerX: Int?,
    val centerY: Int?,
    val confidence: Float,
    val status: String
)
