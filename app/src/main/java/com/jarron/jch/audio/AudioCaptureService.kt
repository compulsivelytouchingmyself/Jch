package com.jarron.jch.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.jarron.jch.data.JumpCutSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.log10
import kotlin.math.sqrt

class AudioCaptureService(
    private val context: Context? = null,
    private var settings: JumpCutSettings
) {
    companion object {
        private const val TAG = "AudioCaptureService"
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val SAMPLES_PER_FRAME = 1024
        const val BYTES_PER_SAMPLE = 2 // 16-bit PCM = 2 bytes per sample
    }

    // Determine native sample rate directly from the hardware to avoid quantization and resampling errors
    val sampleRate: Int = determineNativeSampleRate(context)

    class PcmBuffer(
        val bytes: ByteArray,
        val length: Int,
        val timestampNs: Long
    )

    interface AudioCaptureListener {
        fun onSpeechSegmentStarted(preRollBuffers: List<PcmBuffer>)
        fun onPcmAudioAvailable(bytes: ByteArray, length: Int, timestampNs: Long)
        fun onSpeechSegmentEnded()
    }

    var captureListener: AudioCaptureListener? = null

    private val _meterData = MutableStateFlow(AudioMeterData())
    val meterData: StateFlow<AudioMeterData> = _meterData.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var isCapturing = false

    // Active audio effect handles to ensure they stay explicitly disabled throughout capture
    private val activeEffects = mutableListOf<AudioEffect>()

    // Circular pre-roll buffer of raw, untouched PCM bytes
    private val preRollQueue = ArrayDeque<PcmBuffer>()
    private val preRollLock = Any()

    // Room tone calibration & ambient floor averaging
    private var roomToneBaseline = 28.0f
    private var isCalibrating = false
    private var calibrationStartTime = 0L
    private val calibrationSamples = mutableListOf<Float>()

    // State machine tracking speech vs silence
    private var lastSpeechDetectedTime = 0L
    private var speechStartTime = 0L
    private var currentSpeechState = SpeechState.IDLE_MONITORING

    fun updateSettings(newSettings: JumpCutSettings) {
        this.settings = newSettings
    }

    private fun determineNativeSampleRate(context: Context?): Int {
        if (context != null) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val propRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (propRate != null && (propRate == 48000 || propRate == 44100)) {
                val minBuf = AudioRecord.getMinBufferSize(propRate, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (minBuf > 0) {
                    Log.d(TAG, "Device native sample rate detected: $propRate Hz")
                    return propRate
                }
            }
        }
        // Check 48000 (standard for modern Android HAL), then 44100
        return if (AudioRecord.getMinBufferSize(48000, CHANNEL_CONFIG, AUDIO_FORMAT) > 0) {
            48000
        } else {
            44100
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope) {
        if (isCapturing) return
        isCapturing = true

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            Log.e(TAG, "AudioRecord minBufferSize error: $minBufSize for sampleRate=$sampleRate")
            isCapturing = false
            return
        }

        // Validate and configure buffer size:
        // Must be an integer multiple of SAMPLES_PER_FRAME * BYTES_PER_SAMPLE (1024 * 2 = 2048 bytes)
        // and comfortably exceed minBufSize (at least 4x) to avoid underrun / buffer overflow glitches
        val frameBytes = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE // 2048 bytes
        var internalBufferSize = maxOf(minBufSize * 4, 16384)
        if (internalBufferSize % frameBytes != 0) {
            internalBufferSize = ((internalBufferSize / frameBytes) + 1) * frameBytes
        }

        Log.d(TAG, "Configured AudioRecord buffer size: $internalBufferSize bytes for native sampleRate=$sampleRate Hz (minBufSize=$minBufSize)")

        audioRecord = createAudioRecord(internalBufferSize)
        if (audioRecord == null) {
            Log.e(TAG, "AudioRecord failed to initialize with all candidate audio sources")
            isCapturing = false
            return
        }

        try {
            audioRecord?.startRecording()
            Log.d(TAG, "AudioCaptureService recording started at $sampleRate Hz (source=${audioRecord?.audioSource}, session=${audioRecord?.audioSessionId})")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            isCapturing = false
            return
        }

        captureJob = scope.launch(Dispatchers.Default) {
            val shortBuffer = ShortArray(SAMPLES_PER_FRAME)
            var lastUiUpdate = 0L

            while (isActive && isCapturing) {
                // Use READ_BLOCKING to guarantee exact 1024-sample frame reads without partial-read truncation
                val shortsRead = audioRecord?.read(shortBuffer, 0, SAMPLES_PER_FRAME, AudioRecord.READ_BLOCKING) ?: -1
                if (shortsRead > 0) {
                    val nowNs = System.nanoTime()
                    val nowMs = System.currentTimeMillis()

                    // Convert raw 16-bit shorts directly to pure Little-Endian PCM bytes
                    // Never modified, amplified, or distorted: 100% raw untouched microphone audio!
                    val pcmBytes = ByteArray(shortsRead * 2)
                    for (i in 0 until shortsRead) {
                        val s = shortBuffer[i].toInt()
                        pcmBytes[i * 2] = (s and 0xFF).toByte()         // Little-endian low byte
                        pcmBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte() // Little-endian high byte
                    }

                    val pcmBuffer = PcmBuffer(pcmBytes, pcmBytes.size, nowNs)

                    // Maintain circular pre-roll buffer in memory
                    synchronized(preRollLock) {
                        preRollQueue.addLast(pcmBuffer)
                        val maxPreRollDurationNs = settings.preRollMs * 1_000_000L
                        while (preRollQueue.size > 1 && (nowNs - preRollQueue.first.timestampNs) > maxPreRollDurationNs) {
                            preRollQueue.removeFirst()
                        }
                    }

                    // Compute RMS amplitude for silence/speech detection ONLY
                    var sumSquares = 0.0
                    for (i in 0 until shortsRead) {
                        val s = shortBuffer[i].toDouble()
                        sumSquares += s * s
                    }
                    val rms = sqrt(sumSquares / shortsRead)
                    val rawDb = if (rms > 0.0) {
                        (20.0 * log10(rms / 32767.0) + 90.0).toFloat().coerceIn(0f, 100f)
                    } else {
                        0f
                    }

                    // Ambient Room Tone Calibration & Baseline Tracking
                    if (isCalibrating) {
                        calibrationSamples.add(rawDb)
                        if (nowMs - calibrationStartTime > 2000L) {
                            isCalibrating = false
                            if (calibrationSamples.isNotEmpty()) {
                                roomToneBaseline = calibrationSamples.average().toFloat().coerceIn(15f, 55f)
                            }
                            calibrationSamples.clear()
                            Log.d(TAG, "Room tone calibrated: $roomToneBaseline dB")
                        }
                    } else if (settings.autoCalibrateRoomTone) {
                        if (rawDb < roomToneBaseline + 3f) {
                            val alpha = 0.02f
                            roomToneBaseline = alpha * rawDb + (1f - alpha) * roomToneBaseline
                        }
                    }

                    // Broadcast Dynamic Range Compression (used ONLY as detection metric):
                    // Separates ambient noise floor from spoken words distinctly
                    val thresholdDb = (roomToneBaseline + settings.noiseGateOffsetDb).coerceIn(15f, 85f)
                    val compressedDb = applyDynamicCompression(rawDb, thresholdDb, settings.compressorRatio)
                    val isAboveThreshold = compressedDb >= thresholdDb

                    // Pre-roll / Post-roll state machine
                    var newState = currentSpeechState
                    if (isAboveThreshold) {
                        lastSpeechDetectedTime = nowMs
                        if (currentSpeechState != SpeechState.SPEAKING) {
                            if (currentSpeechState == SpeechState.IDLE_MONITORING || currentSpeechState == SpeechState.SILENCE_CUT) {
                                speechStartTime = nowMs
                                newState = SpeechState.SPEAKING
                                // Trigger cut start with raw pre-roll buffer dump
                                val preRollList: List<PcmBuffer>
                                synchronized(preRollLock) {
                                    preRollList = preRollQueue.toList()
                                }
                                captureListener?.onSpeechSegmentStarted(preRollList)
                            } else {
                                newState = SpeechState.SPEAKING
                            }
                        }
                    } else {
                        // Sound dropped below threshold: Check Post-Roll clamp
                        if (currentSpeechState == SpeechState.SPEAKING || currentSpeechState == SpeechState.POST_ROLL) {
                            val timeSinceSpeech = nowMs - lastSpeechDetectedTime
                            if (timeSinceSpeech < settings.postRollMs) {
                                newState = SpeechState.POST_ROLL
                            } else {
                                newState = SpeechState.SILENCE_CUT
                                captureListener?.onSpeechSegmentEnded()
                            }
                        } else {
                            newState = SpeechState.IDLE_MONITORING
                        }
                    }
                    currentSpeechState = newState

                    // Stream raw, untouched PCM bytes to active speech segment
                    if (currentSpeechState == SpeechState.SPEAKING || currentSpeechState == SpeechState.POST_ROLL) {
                        captureListener?.onPcmAudioAvailable(pcmBytes, pcmBytes.size, nowNs)
                    }

                    // Update UI meter at ~30 FPS
                    if (nowMs - lastUiUpdate > 33L) {
                        lastUiUpdate = nowMs
                        _meterData.value = AudioMeterData(
                            rawDb = rawDb,
                            compressedDb = compressedDb,
                            roomToneBaselineDb = roomToneBaseline,
                            thresholdDb = thresholdDb,
                            isSpeechActive = isAboveThreshold || currentSpeechState == SpeechState.POST_ROLL,
                            state = currentSpeechState,
                            isCalibrating = isCalibrating
                        )
                    }
                }
            }
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        // Priority 1: MediaRecorder.AudioSource.UNPROCESSED
        // Designed specifically in Android 7.0+ (API 24+) to bypass all hardware AGC, noise suppression, and speech notch filters.
        val sources = mutableListOf<Int>()
        sources.add(MediaRecorder.AudioSource.UNPROCESSED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sources.add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
        }
        sources.add(MediaRecorder.AudioSource.CAMCORDER)
        sources.add(MediaRecorder.AudioSource.MIC)

        for (src in sources) {
            try {
                val record = AudioRecord(src, sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "AudioRecord initialized with source=$src at $sampleRate Hz")
                    // Explicitly disable any OS or DSP filters on this audio session
                    disableAllAudioProcessing(record.audioSessionId)
                    return record
                }
                record.release()
            } catch (ignored: Exception) {}
        }
        return null
    }

    /**
     * Explicitly disables Automatic Gain Control (AGC), Noise Suppressor (NS),
     * and Acoustic Echo Canceler (AEC) on the AudioRecord session to ensure 100% raw PCM capture.
     */
    private fun disableAllAudioProcessing(audioSessionId: Int) {
        // 1. Disable Automatic Gain Control (AGC)
        try {
            if (AutomaticGainControl.isAvailable()) {
                val agc = AutomaticGainControl.create(audioSessionId)
                if (agc != null) {
                    agc.enabled = false
                    activeEffects.add(agc)
                    Log.d(TAG, "Explicitly disabled AutomaticGainControl on session $audioSessionId")
                }
            } else {
                Log.d(TAG, "AutomaticGainControl is not active/available on this device")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not disable AutomaticGainControl", t)
        }

        // 2. Disable Noise Suppressor (NS)
        try {
            if (NoiseSuppressor.isAvailable()) {
                val ns = NoiseSuppressor.create(audioSessionId)
                if (ns != null) {
                    ns.enabled = false
                    activeEffects.add(ns)
                    Log.d(TAG, "Explicitly disabled NoiseSuppressor on session $audioSessionId")
                }
            } else {
                Log.d(TAG, "NoiseSuppressor is not active/available on this device")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not disable NoiseSuppressor", t)
        }

        // 3. Disable Acoustic Echo Canceler (AEC)
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                val aec = AcousticEchoCanceler.create(audioSessionId)
                if (aec != null) {
                    aec.enabled = false
                    activeEffects.add(aec)
                    Log.d(TAG, "Explicitly disabled AcousticEchoCanceler on session $audioSessionId")
                }
            } else {
                Log.d(TAG, "AcousticEchoCanceler is not active/available on this device")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not disable AcousticEchoCanceler", t)
        }
    }

    private fun applyDynamicCompression(rawDb: Float, threshold: Float, ratio: Float): Float {
        return if (rawDb < threshold) {
            val diff = threshold - rawDb
            (threshold - diff * 1.5f).coerceAtLeast(0f)
        } else {
            val excess = rawDb - threshold
            threshold + (excess / ratio) + (excess * 0.4f)
        }
    }

    fun startCalibration() {
        isCalibrating = true
        calibrationStartTime = System.currentTimeMillis()
        calibrationSamples.clear()
    }

    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        // Release all audio effect handles
        for (effect in activeEffects) {
            try {
                effect.enabled = false
                effect.release()
            } catch (ignored: Exception) {}
        }
        activeEffects.clear()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
    }
}
