package com.jarron.jch.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

enum class SpeechState {
    IDLE_MONITORING,
    PRE_ROLL,
    SPEAKING,
    POST_ROLL,
    SILENCE_CUT
}

data class AudioMeterData(
    val rawDb: Float = 0f,
    val compressedDb: Float = 0f,
    val roomToneBaselineDb: Float = 28f,
    val thresholdDb: Float = 35f,
    val isSpeechActive: Boolean = false,
    val state: SpeechState = SpeechState.IDLE_MONITORING,
    val isCalibrating: Boolean = false
)

class AudioMonitor(
    private var settings: JumpCutSettings
) {
    companion object {
        private const val TAG = "AudioMonitor"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val _meterData = MutableStateFlow(AudioMeterData())
    val meterData: StateFlow<AudioMeterData> = _meterData.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var monitorJob: Job? = null
    private var isRunning = false

    // Room tone averaging
    private var roomToneBaseline = 28.0f
    private var isCalibrating = false
    private var calibrationStartTime = 0L
    private val calibrationSamples = mutableListOf<Float>()

    // Buffer state management
    private var lastSpeechDetectedTime = 0L
    private var speechStartTime = 0L
    private var currentSpeechState = SpeechState.IDLE_MONITORING

    // Circular pre-roll buffer of raw PCM frames (for audio splicing)
    class PcmChunk(val data: ShortArray, val timestampNs: Long)
    private val preRollQueue = ArrayDeque<PcmChunk>()
    private val preRollLock = Any()

    // Listeners for chunk recorder
    interface AudioCutListener {
        fun onSpeechSegmentStarted(preRollPcm: List<PcmChunk>)
        fun onPcmAudioAvailable(data: ShortArray, timestampNs: Long)
        fun onSpeechSegmentEnded()
    }
    var cutListener: AudioCutListener? = null

    fun updateSettings(newSettings: JumpCutSettings) {
        this.settings = newSettings
    }

    @SuppressLint("MissingPermission")
    fun startMonitoring(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            return
        }

        monitorJob = scope.launch(Dispatchers.Default) {
            val audioBuffer = ShortArray(1024)
            var lastUiUpdate = 0L

            while (isActive && isRunning) {
                val readShorts = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                if (readShorts > 0) {
                    val nowNs = System.nanoTime()
                    val nowMs = System.currentTimeMillis()

                    // Clone data for buffer
                    val chunkData = audioBuffer.copyOf(readShorts)
                    val pcmChunk = PcmChunk(chunkData, nowNs)

                    // Maintain circular pre-roll buffer
                    synchronized(preRollLock) {
                        preRollQueue.addLast(pcmChunk)
                        val maxPreRollDurationNs = settings.preRollMs * 1_000_000L
                        while (preRollQueue.size > 1 && (nowNs - preRollQueue.first.timestampNs) > maxPreRollDurationNs) {
                            preRollQueue.removeFirst()
                        }
                    }

                    // Calculate RMS and Decibels
                    var sumSquares = 0.0
                    for (i in 0 until readShorts) {
                        val s = chunkData[i].toDouble()
                        sumSquares += s * s
                    }
                    val rms = sqrt(sumSquares / readShorts)
                    val rawDb = if (rms > 0) {
                        (20.0 * log10(rms / 32767.0) + 90.0).toFloat().coerceIn(0f, 100f)
                    } else {
                        0f
                    }

                    // Process Room Tone & Calibration
                    if (isCalibrating) {
                        calibrationSamples.add(rawDb)
                        if (nowMs - calibrationStartTime > 2000L) {
                            isCalibrating = false
                            if (calibrationSamples.isNotEmpty()) {
                                roomToneBaseline = calibrationSamples.average().toFloat().coerceIn(15f, 60f)
                            }
                            calibrationSamples.clear()
                        }
                    } else if (settings.autoCalibrateRoomTone) {
                        // Slowly adapt room tone baseline when noise is low (ambient floor)
                        if (rawDb < roomToneBaseline + 4f) {
                            val alpha = 0.02f
                            roomToneBaseline = alpha * rawDb + (1f - alpha) * roomToneBaseline
                        }
                    }

                    // Dynamic Range Compression:
                    // Separates ambient room tone from speech sharply
                    val thresholdDb = (roomToneBaseline + settings.noiseGateOffsetDb).coerceIn(15f, 85f)
                    val compressedDb = applyDynamicCompression(rawDb, thresholdDb, settings.compressorRatio)

                    val isAboveThreshold = compressedDb >= thresholdDb

                    // State machine with Pre-roll and Post-roll
                    var newState = currentSpeechState
                    if (isAboveThreshold) {
                        lastSpeechDetectedTime = nowMs
                        if (currentSpeechState != SpeechState.SPEAKING) {
                            if (currentSpeechState == SpeechState.IDLE_MONITORING || currentSpeechState == SpeechState.SILENCE_CUT) {
                                speechStartTime = nowMs
                                newState = SpeechState.SPEAKING
                                // Trigger cut listener with pre-roll
                                val preRollList: List<PcmChunk>
                                synchronized(preRollLock) {
                                    preRollList = preRollQueue.toList()
                                }
                                cutListener?.onSpeechSegmentStarted(preRollList)
                            } else {
                                newState = SpeechState.SPEAKING
                            }
                        }
                    } else {
                        // Below threshold: Check Post-Roll clamp
                        if (currentSpeechState == SpeechState.SPEAKING || currentSpeechState == SpeechState.POST_ROLL) {
                            val timeSinceSpeech = nowMs - lastSpeechDetectedTime
                            if (timeSinceSpeech < settings.postRollMs) {
                                newState = SpeechState.POST_ROLL
                            } else {
                                newState = SpeechState.SILENCE_CUT
                                cutListener?.onSpeechSegmentEnded()
                            }
                        } else {
                            newState = SpeechState.IDLE_MONITORING
                        }
                    }
                    currentSpeechState = newState

                    // Stream PCM audio to active cut if speaking or in post-roll
                    if (currentSpeechState == SpeechState.SPEAKING || currentSpeechState == SpeechState.POST_ROLL) {
                        cutListener?.onPcmAudioAvailable(chunkData, nowNs)
                    }

                    // Throttle UI updates to ~30 fps
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

    /**
     * Broadcast Dynamic Range Compressor:
     * Applies downward expansion below threshold to push ambient hiss into silence,
     * and compression curve above threshold to bring speech into clear, consistent focus.
     */
    private fun applyDynamicCompression(rawDb: Float, threshold: Float, ratio: Float): Float {
        return if (rawDb < threshold) {
            // Downward gating/expansion: push quiet room tone further down
            val diff = threshold - rawDb
            (threshold - diff * 1.5f).coerceAtLeast(0f)
        } else {
            // Compression above threshold: smooth out peaks while highlighting presence
            val excess = rawDb - threshold
            threshold + (excess / ratio) + (excess * 0.4f)
        }
    }

    fun startCalibration() {
        isCalibrating = true
        calibrationStartTime = System.currentTimeMillis()
        calibrationSamples.clear()
    }

    fun stopMonitoring() {
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {
        }
        audioRecord = null
    }
}
