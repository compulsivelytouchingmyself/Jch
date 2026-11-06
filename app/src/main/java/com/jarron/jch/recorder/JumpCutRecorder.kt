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
import java.io.FileOutputStream
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
    private val recordedSegments = mutableListOf<ChunkSegment>()

    // Current chunk state
    private var currentChunkMuxer: MediaMuxer? = null
    private var currentVideoFile: File? = null
    private var currentPcmFile: File? = null
    private var currentPcmOut: FileOutputStream? = null
    private var currentChunkVideoTrack = -1
    private var isCurrentChunkStarted = false
    private var chunkIndex = 0
    private var chunkStartTime = 0L
    private val chunkLock = Any()

    // Video encoder
    private var videoEncoder: MediaCodec? = null
    private var videoEncoderSurface: Surface? = null
    private var videoOutputFormat: MediaFormat? = null
    private var videoDrainJob: Job? = null

    // Pre-roll video circular buffer
    private class EncodedVideoPacket(val buffer: ByteArray, val info: MediaCodec.BufferInfo)
    private val preRollVideoPackets = ArrayDeque<EncodedVideoPacket>()
    private val videoBufferLock = Any()

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
            Log.d(TAG, "Video encoder prepared with input surface")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing video encoder", e)
        }
    }

    fun startRecording(scope: CoroutineScope) {
        if (_recordingStats.value.isRecording) return
        recordingScope = scope
        sessionId = UUID.randomUUID().toString().take(8)
        chunkDir = storageManager.createChunkDirectory(sessionId)
        recordedSegments.clear()
        chunkIndex = 0
        recordingStartTime = System.currentTimeMillis()

        if (videoEncoder == null) {
            prepareRecording()
        }

        _recordingStats.value = RecordingStats(
            isRecording = true,
            statusText = "Waiting for speech..."
        )

        startVideoDraining(scope)

        // Live stats tracking loop
        statsJob = scope.launch(Dispatchers.Main) {
            while (isActive && _recordingStats.value.isRecording) {
                val now = System.currentTimeMillis()
                val elapsed = now - recordingStartTime
                val currentChunkDur = if (isCurrentChunkStarted) now - chunkStartTime else 0L

                val totalCutDuration = recordedSegments.sumOf { getSegmentDurationEstimate(it) } + currentChunkDur
                val estimatedSaved = (elapsed - totalCutDuration).coerceAtLeast(0L)

                _recordingStats.value = _recordingStats.value.copy(
                    elapsedRealtimeMs = elapsed,
                    estimatedSavedMs = estimatedSaved,
                    chunkCount = recordedSegments.size + (if (isCurrentChunkStarted) 1 else 0),
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

    private fun startVideoDraining(scope: CoroutineScope) {
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

                        val packet = EncodedVideoPacket(bytes, bufferInfo)
                        handleVideoPacket(packet)
                    }
                    vEncoder.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun handleVideoPacket(packet: EncodedVideoPacket) {
        synchronized(chunkLock) {
            if (isCurrentChunkStarted && currentChunkMuxer != null && currentChunkVideoTrack != -1) {
                try {
                    val byteBuffer = ByteBuffer.wrap(packet.buffer)
                    currentChunkMuxer?.writeSampleData(currentChunkVideoTrack, byteBuffer, packet.info)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing video sample to chunk", e)
                }
            } else {
                synchronized(videoBufferLock) {
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

    /**
     * Live raw PCM audio streaming from microphone directly to file.
     * 100% untouched, unamplified, and unfiltered.
     */
    override fun onPcmAudioAvailable(bytes: ByteArray, length: Int, timestampNs: Long) {
        if (!_recordingStats.value.isRecording) return
        synchronized(chunkLock) {
            if (isCurrentChunkStarted) {
                try {
                    currentPcmOut?.write(bytes, 0, length)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing raw PCM audio", e)
                }
            }
        }
    }

    override fun onSpeechSegmentStarted(preRollBuffers: List<AudioCaptureService.PcmBuffer>) {
        if (!_recordingStats.value.isRecording) return

        synchronized(chunkLock) {
            if (isCurrentChunkStarted) return

            try {
                val prefix = String.format("chunk_%04d", chunkIndex)
                val videoFile = File(chunkDir, "$prefix.mp4")
                val pcmFile = File(chunkDir, "$prefix.pcm")
                currentVideoFile = videoFile
                currentPcmFile = pcmFile
                chunkStartTime = System.currentTimeMillis()

                // 1. Open raw PCM stream and immediately dump pre-roll microphone buffers
                val pcmOut = FileOutputStream(pcmFile)
                currentPcmOut = pcmOut
                for (p in preRollBuffers) {
                    pcmOut.write(p.bytes, 0, p.length)
                }

                // 2. Open video chunk muxer
                val vFormat = videoOutputFormat ?: MediaFormat.createVideoFormat(VIDEO_MIME, 1280, 720).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                }

                val muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                currentChunkVideoTrack = muxer.addTrack(vFormat)
                muxer.start()
                currentChunkMuxer = muxer
                isCurrentChunkStarted = true

                // 3. Flush pre-roll video packets into chunk muxer
                synchronized(videoBufferLock) {
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
                Log.d(TAG, "Started speech segment $prefix")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech segment", e)
            }
        }
    }

    override fun onSpeechSegmentEnded() {
        if (!_recordingStats.value.isRecording) return
        closeCurrentChunk()
    }

    private fun closeCurrentChunk() {
        synchronized(chunkLock) {
            if (!isCurrentChunkStarted) return
            try {
                // Close raw PCM stream
                currentPcmOut?.flush()
                currentPcmOut?.close()
                currentPcmOut = null

                // Close video chunk muxer
                currentChunkMuxer?.stop()
                currentChunkMuxer?.release()
                currentChunkMuxer = null

                val vFile = currentVideoFile
                val pFile = currentPcmFile
                if (vFile != null && vFile.exists() && vFile.length() > 512 &&
                    pFile != null && pFile.exists() && pFile.length() > 0) {
                    recordedSegments.add(ChunkSegment(vFile, pFile))
                    Log.d(TAG, "Saved segment: ${vFile.name} (${vFile.length()}b), ${pFile.name} (${pFile.length()}b)")
                }
                chunkIndex++
            } catch (e: Exception) {
                Log.e(TAG, "Error closing speech segment", e)
            } finally {
                currentChunkMuxer = null
                currentVideoFile = null
                currentPcmFile = null
                currentPcmOut = null
                currentChunkVideoTrack = -1
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

        _recordingStats.value = _recordingStats.value.copy(
            isRecording = false,
            isRemuxing = true,
            statusText = "Remuxing clean jump cut video..."
        )

        recordingScope?.launch(Dispatchers.IO) {
            val finalOutputFile = storageManager.createNewVideoOutputFile()
            val remuxResult = ScorchedEarthRemuxer.remux(
                segments = recordedSegments.toList(),
                outputFile = finalOutputFile,
                originalDurationMs = totalElapsed,
                sampleRate = audioCaptureService.sampleRate
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

    private fun getSegmentDurationEstimate(segment: ChunkSegment): Long {
        // Raw PCM at 44100Hz mono 16-bit is 88,200 bytes per second
        return if (segment.pcmFile.exists()) (segment.pcmFile.length() / 88L).coerceAtLeast(100L) else 0L
    }

    fun release() {
        closeCurrentChunk()
        statsJob?.cancel()
        videoDrainJob?.cancel()
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (ignored: Exception) {}
        videoEncoder = null
        videoEncoderSurface?.release()
        videoEncoderSurface = null
    }
}
