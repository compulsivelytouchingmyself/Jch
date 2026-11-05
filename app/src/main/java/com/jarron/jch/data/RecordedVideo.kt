package com.jarron.jch.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedVideo(
    val id: String,
    val title: String,
    val filePath: String,
    val timestamp: Long,
    val originalDurationMs: Long,
    val jumpCutDurationMs: Long,
    val cutCount: Int,
    val fileSizeBytes: Long
) {
    val savedDurationMs: Long
        get() = (originalDurationMs - jumpCutDurationMs).coerceAtLeast(0L)

    val percentSaved: Int
        get() = if (originalDurationMs > 0) {
            ((savedDurationMs.toDouble() / originalDurationMs) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

    val formattedDate: String
        get() = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(timestamp))

    fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val tenths = (durationMs % 1000) / 100
        return if (min > 0) {
            String.format(Locale.getDefault(), "%d:%02d.%d", min, sec, tenths)
        } else {
            String.format(Locale.getDefault(), "0:%02d.%d", sec, tenths)
        }
    }
}
