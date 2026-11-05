package com.jarron.jch.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class JumpCutSettings(
    val preRollMs: Int = 250,        // Pre-roll buffer clamp (50..800 ms)
    val postRollMs: Int = 350,       // Post-roll buffer clamp (50..1200 ms)
    val noiseGateOffsetDb: Float = 7.0f, // Threshold offset above ambient room tone (-10..25 dB)
    val compressorRatio: Float = 4.0f,   // Compression ratio for speech vs silence detection (1.5..10.0)
    val audioBitrate: Int = 128_000,     // CBR audio bitrate (bps): 96k, 128k, 192k, 256k
    val videoResolution: String = "1080p", // "1080p" or "720p"
    val autoCalibrateRoomTone: Boolean = true
)

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jump_cut_hero_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<JumpCutSettings> = _settings.asStateFlow()

    private fun loadSettings(): JumpCutSettings {
        return JumpCutSettings(
            preRollMs = prefs.getInt("pre_roll_ms", 250).coerceIn(50, 800),
            postRollMs = prefs.getInt("post_roll_ms", 350).coerceIn(50, 1200),
            noiseGateOffsetDb = prefs.getFloat("noise_gate_offset_db", 7.0f).coerceIn(-10f, 25f),
            compressorRatio = prefs.getFloat("compressor_ratio", 4.0f).coerceIn(1.5f, 10f),
            audioBitrate = prefs.getInt("audio_bitrate", 128_000),
            videoResolution = prefs.getString("video_resolution", "1080p") ?: "1080p",
            autoCalibrateRoomTone = prefs.getBoolean("auto_calibrate_room_tone", true)
        )
    }

    fun updateSettings(newSettings: JumpCutSettings) {
        prefs.edit()
            .putInt("pre_roll_ms", newSettings.preRollMs)
            .putInt("post_roll_ms", newSettings.postRollMs)
            .putFloat("noise_gate_offset_db", newSettings.noiseGateOffsetDb)
            .putFloat("compressor_ratio", newSettings.compressorRatio)
            .putInt("audio_bitrate", newSettings.audioBitrate)
            .putString("video_resolution", newSettings.videoResolution)
            .putBoolean("auto_calibrate_room_tone", newSettings.autoCalibrateRoomTone)
            .apply()
        _settings.value = newSettings
    }

    fun resetToDefaults() {
        val defaultSettings = JumpCutSettings()
        updateSettings(defaultSettings)
    }
}
