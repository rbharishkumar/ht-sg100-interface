package com.example.sg100usb.data

data class RecordedPoint(
    val timeMs: Long,
    val rpm: Float,
    val pwmPercent: Float,
    val actuatorCurrentA: Float,
)

data class SpeedRecording(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val durationSec: Long,
    val pointCount: Int,
    val createdAt: Long,
)
