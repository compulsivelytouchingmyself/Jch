package com.jarron.jch.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import com.jarron.jch.audio.AudioCaptureService
import com.jarron.jch.audio.SpeechState
import com.jarron.jch.camera.Camera2Helper
import com.jarron.jch.data.JumpCutSettings
import com.jarron.jch.data.RecordedVideo
import com.jarron.jch.data.VideoStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.UUID

data class RecordingStats(
    val isRecording: Boolean = false,
    val isRemuxing: Boolean = false,
    val elapsedRealtimeMs: Long = 0L,
    val estimatedSavedMs: Long = 0L,
    val chunkCount: Int = 0,
    val currentChunkDurationMs: Long = 0L,
    val remuxProgress: Float = 0f,
    val statusText: String = "Ready"
)

class JumpCutRecorder(
    private val context: Context,
    private val cameraHelper: Camera2Helper,
    private val audioCaptureService: AudioCaptureService,
    private val storageManager: VideoStorageManager,
    private var settings: JumpCutSettings
) : AudioCaptureService.AudioCaptureListener {

    companion object {
        private const val TAG = "JumpCutRecorder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TARGET_FPS = 30
        private const val I_FRAME_INTERVAL = 1
    }

    private val _recordingStats = MutableStateFlow(RecordingStats())
    val recordingStats: StateFlow<RecordingStats> = _recordingStats.asStateFlow()

    private var recordingScope: CoroutineScope? = null
    private var statsJob: Job? = null
    private var recordingStartTime = 0L
    private var sessionId = ""
    private var chunkDir: File? = null
    private val recordedChunkFiles = mutableListOf<File>()

    // Current chunk muxer state
    private var currentChunkMuxer: MediaMuxer? = null
    private var currentChunkFile: File? = null
    private var currentChunkVideoTrack = -1
    private var currentChunkAudioTrack = -1
    private var isCurrentChunkStarted = false
    private var chunkIndex = 0
    private var chunkStartTime = 0L
    private val muxerLock = Any()

    // Video encoder
    private var videoEncoder: MediaCodec? = null
    private var videoEncoderSurface: Surface? = null
    private var videoOutputFormat: MediaFormat? = null
    private var videoDrainJob: Job? = null

    // Audio encoder (CBR AAC)
    private var audioEncoder: MediaCodec? = null
    private var audioOutputFormat: MediaFormat? = null
    private var audioDrainJob: Job? = null
    private var audioSamplesRecorded = 0L

    // Pre-roll video circular buffer (keyframes + recent frames)
    private class EncodedPacket(val buffer: ByteArray, val info: MediaCodec.BufferInfo, val isKeyFrame: Boolean)
    private val preRollVideoPackets = ArrayDeque<EncodedPacket>()
    private val packetBufferLock = Any()

    // Callbacks
    var onVideoCompleted: ((RecordedVideo) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        audioCaptureService.captureListener = this
    }

    fun updateSettings(newSettings: JumpCutSettings) {
        this.settings = newSettings
        audioCaptureService.updateSettings(newSettings)
    }

    fun prepareRecording(width: Int = 1280, height: Int = 720) {
        try {
            // Configure Video MediaCodec with Camera2 surface
            val vFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
                configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                videoEncoderSurface = createInputSurface()
                start()
            }

            cameraHelper.setRecordingSurface(videoEncoderSurface)

            // Configure Constant Bit Rate (CBR) Audio MediaCodec
            val aFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AudioCaptureService.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, settings.audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            Log.d(TAG, "Recording encoders prepared with CBR audio bitrate: ${settings.audioBitrate}")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing hardware encoders", e)
        }
    }

    fun startRecording(scope: CoroutineScope) {
        if (_recordingStats.value.isRecording) return
        recordingScope = scope
        sessionId = UUID.randomUUID().toString().take(8)
        chunkDir = storageManager.createChunkDirectory(sessionId)
        recordedChunkFiles.clear()
        chunkIndex = 0
        audioSamplesRecorded = 0L
        recordingStartTime = System.currentTimeMillis()

        if (videoEncoder == null || audioEncoder == null) {
            prepareRecording()
        }

        _recordingStats.value = RecordingStats(
            isRecording = true,
            statusText = "Waiting for speech..."
        )

        startEncoderDraining(scope)

        // Live stats tracking
        statsJob = scope.launch(Dispatchers.Main) {
            while (isActive && _recordingStats.value.isRecording) {
                val now = System.currentTimeMillis()
                val elapsed = now - recordingStartTime
                val currentChunkDur = if (isCurrentChunkStarted) now - chunkStartTime else 0L

                val totalCutDuration = recordedChunkFiles.sumOf { getChunkDurationEstimate(it) } + currentChunkDur
                val estimatedSaved = (elapsed - totalCutDuration).coerceAtLeast(0L)

                _recordingStats.value = _recordingStats.value.copy(
                    elapsedRealtimeMs = elapsed,
                    estimatedSavedMs = estimatedSaved,
                    chunkCount = recordedChunkFiles.size + (if (isCurrentChunkStarted) 1 else 0),
                    currentChunkDurationMs = currentChunkDur,
                    statusText = when (audioCaptureService.meterData.value.state) {
                        SpeechState.SPEAKING -> "RECORDING SPEECH"
                        SpeechState.POST_ROLL -> "HOLDING POST-ROLL"
                        SpeechState.PRE_ROLL -> "PRE-ROLL BUFFER"
                        SpeechState.SILENCE_CUT -> "CUTTING SILENCE"
                        SpeechState.IDLE_MONITORING -> "MONITORING ROOM TONE"
                    }
                )
                delay(80)
            }
        }
    }

    private fun startEncoderDraining(scope: CoroutineScope) {
        // Video Drainer
        videoDrainJob = scope.launch(Dispatchers.IO) {
            val vEncoder = videoEncoder ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && _recordingStats.value.isRecording) {
                val outIndex = try {
                    vEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: Exception) { -1 }

                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoOutputFormat = vEncoder.outputFormat
                    Log.d(TAG, "Video encoder output format ready")
                } else if (outIndex >= 0) {
                    val encodedBuffer = vEncoder.getOutputBuffer(outIndex)
                    if (encodedBuffer != null && bufferInfo.size > 0) {
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val bytes = ByteArray(bufferInfo.size)
                        encodedBuffer.get(bytes)

                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val packet = EncodedPacket(bytes, bufferInfo, isKey)
                        handleVideoPacket(packet)
                    }
                    vEncoder.releaseOutputBuffer(outIndex, false)
                }
            }
        }

        // Audio Drainer (CBR AAC)
        audioDrainJob = scope.launch(Dispatchers.IO) {
            val aEncoder = audioEncoder ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && _recordingStats.value.isRecording) {
                val outIndex = try {
                    aEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: Exception) { -1 }

                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioOutputFormat = aEncoder.outputFormat
                    Log.d(TAG, "Audio encoder output format ready")
                } else if (outIndex >= 0) {
                    val encodedBuffer = aEncoder.getOutputBuffer(outIndex)
                    if (encodedBuffer != null && bufferInfo.size > 0) {
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val bytes = ByteArray(bufferInfo.size)
                        encodedBuffer.get(bytes)

                        handleAudioPacket(bytes, bufferInfo)
                    }
                    aEncoder.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun handleVideoPacket(packet: EncodedPacket) {
        synchronized(muxerLock) {
            if (isCurrentChunkStarted && currentChunkMuxer != null && currentChunkVideoTrack != -1) {
                try {
                    val byteBuffer = ByteBuffer.wrap(packet.buffer)
                    currentChunkMuxer?.writeSampleData(currentChunkVideoTrack, byteBuffer, packet.info)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing video sample", e)
                }
            } else {
                synchronized(packetBufferLock) {
                    preRollVideoPackets.addLast(packet)
                    val maxPreRollUs = settings.preRollMs * 1000L
                    while (preRollVideoPackets.size > 1 &&
                        (packet.info.presentationTimeUs - preRollVideoPackets.first.info.presentationTimeUs) > maxPreRollUs) {
                        preRollVideoPackets.removeFirst()
                    }
                }
            }
        }
    }

    private fun handleAudioPacket(bytes: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (isCurrentChunkStarted && currentChunkMuxer != null && currentChunkAudioTrack != -1) {
                try {
                    val byteBuffer = ByteBuffer.wrap(bytes)
                    currentChunkMuxer?.writeSampleData(currentChunkAudioTrack, byteBuffer, bufferInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio sample", e)
                }
            }
        }
    }

    /**
     * Feed raw PCM bytes into audio encoder.
     * Native byte-order is preserved directly via inputBuffer.put(bytes),
     * completely eliminating digital static / screeching!
     */
    override fun onPcmAudioAvailable(bytes: ByteArray, length: Int, timestampNs: Long) {
        if (!_recordingStats.value.isRecording) return
        val aEncoder = audioEncoder ?: return

        try {
            val inIndex = aEncoder.dequeueInputBuffer(5_000)
            if (inIndex >= 0) {
                val inputBuffer = aEncoder.getInputBuffer(inIndex)
                inputBuffer?.clear()
                // Direct native byte copy - zero byte swapping errors!
                inputBuffer?.put(bytes, 0, length)

                // Monotonic CBR presentation timestamp
                val ptsUs = (audioSamplesRecorded * 1_000_000L) / AudioCaptureService.SAMPLE_RATE
                val samplesCount = length / AudioCaptureService.BYTES_PER_SAMPLE
                audioSamplesRecorded += samplesCount

                aEncoder.queueInputBuffer(inIndex, 0, length, ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding PCM into audio encoder", e)
        }
    }

    override fun onSpeechSegmentStarted(preRollBuffers: List<AudioCaptureService.PcmBuffer>) {
        if (!_recordingStats.value.isRecording) return

        synchronized(muxerLock) {
            if (isCurrentChunkStarted) return

            try {
                val chunkFile = File(chunkDir, "chunk_${String.format("%04d", chunkIndex)}.mp4")
                currentChunkFile = chunkFile
                chunkStartTime = System.currentTimeMillis()

                val vFormat = videoOutputFormat ?: MediaFormat.createVideoFormat(VIDEO_MIME, 1280, 720).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                }
                val aFormat = audioOutputFormat ?: MediaFormat.createAudioFormat(AUDIO_MIME, AudioCaptureService.SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, settings.audioBitrate)
                }

                val muxer = MediaMuxer(chunkFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                currentChunkVideoTrack = muxer.addTrack(vFormat)
                currentChunkAudioTrack = muxer.addTrack(aFormat)
                muxer.start()

                currentChunkMuxer = muxer
                isCurrentChunkStarted = true
                Log.d(TAG, "Started speech chunk ${chunkFile.name}")

                // 1. Feed pre-roll PCM buffers into audio encoder seamlessly
                for (p in preRollBuffers) {
                    onPcmAudioAvailable(p.bytes, p.length, p.timestampNs)
                }

                // 2. Flush pre-roll video packets into chunk muxer
                synchronized(packetBufferLock) {
                    while (preRollVideoPackets.isNotEmpty()) {
                        val p = preRollVideoPackets.removeFirst()
                        try {
                            val bb = ByteBuffer.wrap(p.buffer)
                            muxer.writeSampleData(currentChunkVideoTrack, bb, p.info)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing pre-roll video", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start chunk muxer", e)
            }
        }
    }

    override fun onSpeechSegmentEnded() {
        if (!_recordingStats.value.isRecording) return
        closeCurrentChunk()
    }

    private fun closeCurrentChunk() {
        synchronized(muxerLock) {
            if (!isCurrentChunkStarted) return
            try {
                currentChunkMuxer?.stop()
                currentChunkMuxer?.release()
                currentChunkFile?.let { file ->
                    if (file.exists() && file.length() > 512) {
                        recordedChunkFiles.add(file)
                        Log.d(TAG, "Saved chunk ${file.name} (${file.length()} bytes)")
                    }
                }
                chunkIndex++
            } catch (e: Exception) {
                Log.e(TAG, "Error closing chunk muxer", e)
            } finally {
                currentChunkMuxer = null
                currentChunkFile = null
                currentChunkVideoTrack = -1
                currentChunkAudioTrack = -1
                isCurrentChunkStarted = false
            }
        }
    }

    fun stopRecording() {
        if (!_recordingStats.value.isRecording) return
        val totalElapsed = System.currentTimeMillis() - recordingStartTime

        closeCurrentChunk()
        statsJob?.cancel()
        videoDrainJob?.cancel()
        audioDrainJob?.cancel()

        _recordingStats.value = _recordingStats.value.copy(
            isRecording = false,
            isRemuxing = true,
            statusText = "Remuxing jump cut video fresh..."
        )

        recordingScope?.launch(Dispatchers.IO) {
            val finalOutputFile = storageManager.createNewVideoOutputFile()
            val remuxResult = ScorchedEarthRemuxer.remuxChunks(
                chunkFiles = recordedChunkFiles.toList(),
                outputFile = finalOutputFile,
                originalDurationMs = totalElapsed
            )

            withContext(Dispatchers.Main) {
                _recordingStats.value = RecordingStats(
                    isRecording = false,
                    isRemuxing = false,
                    statusText = if (remuxResult.success) "Jump cut video ready!" else "Remux failed"
                )

                if (remuxResult.success) {
                    val recordedVideo = RecordedVideo(
                        id = UUID.randomUUID().toString(),
                        title = "Hero Cut #${storageManager.videos.value.size + 1}",
                        filePath = finalOutputFile.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        originalDurationMs = remuxResult.originalDurationMs,
                        jumpCutDurationMs = remuxResult.finalDurationMs,
                        cutCount = remuxResult.cutCount,
                        fileSizeBytes = finalOutputFile.length()
                    )
                    storageManager.saveVideo(recordedVideo)
                    onVideoCompleted?.invoke(recordedVideo)
                } else {
                    onError?.invoke(remuxResult.errorMessage ?: "Failed to remux jump cuts.")
                }
            }
        }
    }

    private fun getChunkDurationEstimate(file: File): Long {
        return if (file.exists()) (file.length() / 500L).coerceAtLeast(200L) else 0L
    }

    fun release() {
        closeCurrentChunk()
        statsJob?.cancel()
        videoDrainJob?.cancel()
        audioDrainJob?.cancel()
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (ignored: Exception) {}
        videoEncoder = null
        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (ignored: Exception) {}
        audioEncoder = null
        videoEncoderSurface?.release()
        videoEncoderSurface = null
    }
}
