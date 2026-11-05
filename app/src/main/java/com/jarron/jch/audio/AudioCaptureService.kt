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

class AudioCaptureService(
    private var settings: JumpCutSettings
) {
    companion object {
        private const val TAG = "AudioCaptureService"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
    }

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

    // Circular pre-roll memory buffer
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

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope) {
        if (isCapturing) return
        isCapturing = true

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val internalBufferSize = (minBufSize * 4).coerceAtLeast(8192)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                internalBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                isCapturing = false
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "AudioCaptureService started recording at $SAMPLE_RATE Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            isCapturing = false
            return
        }

        captureJob = scope.launch(Dispatchers.Default) {
            // Read 1024 samples = 2048 bytes per read chunk
            val readChunkSize = 2048
            val audioBuffer = ByteArray(readChunkSize)
            var lastUiUpdate = 0L

            while (isActive && isCapturing) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, readChunkSize) ?: -1
                if (bytesRead > 0) {
                    val nowNs = System.nanoTime()
                    val nowMs = System.currentTimeMillis()

                    // Pure raw native byte copy for pre-roll buffer & encoder
                    val chunkBytes = audioBuffer.copyOf(bytesRead)
                    val pcmBuffer = PcmBuffer(chunkBytes, bytesRead, nowNs)

                    // Maintain circular pre-roll buffer in memory
                    synchronized(preRollLock) {
                        preRollQueue.addLast(pcmBuffer)
                        val maxPreRollDurationNs = settings.preRollMs * 1_000_000L
                        while (preRollQueue.size > 1 && (nowNs - preRollQueue.first.timestampNs) > maxPreRollDurationNs) {
                            preRollQueue.removeFirst()
                        }
                    }

                    // Compute RMS amplitude directly from 16-bit Little-Endian bytes
                    // Native PCM 16-bit on Android: byte[0]=low, byte[1]=high
                    var sumSquares = 0.0
                    val sampleCount = bytesRead / BYTES_PER_SAMPLE
                    for (i in 0 until bytesRead - 1 step 2) {
                        val low = chunkBytes[i].toInt() and 0xFF
                        val high = chunkBytes[i + 1].toInt()
                        val sample = (high shl 8) or low
                        sumSquares += sample * sample
                    }

                    val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
                    val rawDb = if (rms > 0) {
                        (20.0 * log10(rms / 32767.0) + 90.0).toFloat().coerceIn(0f, 100f)
                    } else {
                        0f
                    }

                    // Room Tone Calibration & Baseline Tracking
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

                    // Broadcast Dynamic Range Compression:
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
                                // Trigger cut start with pre-roll buffer dump
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

                    // Send live audio bytes to active speech segment
                    if (currentSpeechState == SpeechState.SPEAKING || currentSpeechState == SpeechState.POST_ROLL) {
                        captureListener?.onPcmAudioAvailable(chunkBytes, bytesRead, nowNs)
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
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
    }
}
