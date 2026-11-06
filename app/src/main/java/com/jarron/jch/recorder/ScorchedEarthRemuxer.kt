package com.jarron.jch.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.jarron.jch.audio.AudioCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

data class ChunkSegment(
    val videoFile: File,
    val pcmFile: File
)

data class RemuxResult(
    val success: Boolean,
    val outputFile: File,
    val originalDurationMs: Long,
    val finalDurationMs: Long,
    val cutCount: Int,
    val errorMessage: String? = null
)

object ScorchedEarthRemuxer {
    private const val TAG = "ScorchedEarthRemuxer"
    private const val DEFAULT_FRAME_DURATION_US = 33_333L // ~30 fps
    private const val SAMPLES_PER_AAC_FRAME = 1024L
    private const val AUDIO_SAMPLE_RATE = 44100
    private const val AUDIO_BITRATE = 128_000 // Constant Bit Rate 128 kbps

    /**
     * Scorched Earth Remuxer:
     * Takes raw PCM audio of all kept speech segments and video chunks,
     * encodes the continuous raw audio directly to CBR AAC with valid CSD-0 header,
     * recalculates monotonic timestamps, and writes an interleaved pristine MP4 file.
     */
    suspend fun remux(
        segments: List<ChunkSegment>,
        outputFile: File,
        originalDurationMs: Long,
        sampleRate: Int = 44100
    ): RemuxResult = withContext(Dispatchers.IO) {
        val validSegments = segments.filter {
            it.videoFile.exists() && it.videoFile.length() > 1024 &&
            it.pcmFile.exists() && it.pcmFile.length() > 0
        }

        if (validSegments.isEmpty()) {
            return@withContext RemuxResult(
                success = false,
                outputFile = outputFile,
                originalDurationMs = originalDurationMs,
                finalDurationMs = 0L,
                cutCount = 0,
                errorMessage = "No valid speech segments detected to remux."
            )
        }

        var muxer: MediaMuxer? = null
        try {
            // 1. Discover Video Format from first video chunk
            val firstExtractor = MediaExtractor().apply { setDataSource(validSegments[0].videoFile.absolutePath) }
            var videoFormat: MediaFormat? = null
            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoFormat = format
                    break
                }
            }
            firstExtractor.release()

            if (videoFormat == null) {
                return@withContext RemuxResult(
                    success = false,
                    outputFile = outputFile,
                    originalDurationMs = originalDurationMs,
                    finalDurationMs = 0L,
                    cutCount = 0,
                    errorMessage = "Could not find video track in chunks."
                )
            }

            // 2. Prepare AAC Encoder with dynamic Audio Specific Config (CSD-0) matching native hardware sample rate
            val csd0 = generateAacCsd0(sampleRate, 1)
            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }

            val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // 3. Concatenate all raw PCM audio into a single temporary stream
            val combinedPcmFile = File(outputFile.parentFile, "combined_${System.currentTimeMillis()}.pcm")
            combinedPcmFile.outputStream().use { out ->
                for (seg in validSegments) {
                    seg.pcmFile.inputStream().use { it.copyTo(out) }
                }
            }

            // 4. Encode the raw, untouched PCM stream into CBR AAC packets
            class AacPacket(val data: ByteArray, val ptsUs: Long, val isConfig: Boolean)
            val aacPackets = mutableListOf<AacPacket>()
            var actualAudioFormat: MediaFormat = aFormat

            FileInputStream(combinedPcmFile).use { pcmInput ->
                val bufferInfo = MediaCodec.BufferInfo()
                val pcmChunk = ByteArray(2048) // 1024 samples = 1 AAC frame
                var totalSamplesFed = 0L
                var isEos = false

                while (!isEos) {
                    val bytesRead = pcmInput.read(pcmChunk)
                    val inputIndex = audioEncoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = audioEncoder.getInputBuffer(inputIndex)
                        inputBuffer?.clear()

                        if (bytesRead > 0) {
                            inputBuffer?.put(pcmChunk, 0, bytesRead)
                            val ptsUs = (totalSamplesFed * 1_000_000L) / sampleRate
                            val samples = bytesRead / 2
                            totalSamplesFed += samples
                            audioEncoder.queueInputBuffer(inputIndex, 0, bytesRead, ptsUs, 0)
                        } else {
                            val ptsUs = (totalSamplesFed * 1_000_000L) / sampleRate
                            audioEncoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        }
                    }

                    // Drain output buffers
                    var outIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 5_000)
                    while (outIndex >= 0) {
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            actualAudioFormat = audioEncoder.outputFormat
                        } else {
                            val outBuffer = audioEncoder.getOutputBuffer(outIndex)
                            if (outBuffer != null && bufferInfo.size > 0) {
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                outBuffer.get(bytes)

                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                aacPackets.add(AacPacket(bytes, bufferInfo.presentationTimeUs, isConfig))
                            }
                            audioEncoder.releaseOutputBuffer(outIndex, false)
                        }
                        outIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }

                // Final drain to EOS
                var outIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER && outIndex >= 0) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        actualAudioFormat = audioEncoder.outputFormat
                    } else {
                        val outBuffer = audioEncoder.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outBuffer.get(bytes)
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            aacPackets.add(AacPacket(bytes, bufferInfo.presentationTimeUs, isConfig))
                        }
                        audioEncoder.releaseOutputBuffer(outIndex, false)
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    outIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                }
            }

            audioEncoder.stop()
            audioEncoder.release()
            combinedPcmFile.delete()

            // 5. Build Final MP4 with Interleaved Video and Audio Tracks
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(actualAudioFormat)
            muxer.start()

            // Extract all video samples from chunks with monotonic presentation timestamps starting at 0
            class VideoPacket(val data: ByteArray, val ptsUs: Long, val flags: Int)
            val videoPackets = mutableListOf<VideoPacket>()
            var nextVideoPtsUs = 0L

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            for (seg in validSegments) {
                val extractor = MediaExtractor().apply { setDataSource(seg.videoFile.absolutePath) }
                var vTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                        vTrack = i
                        break
                    }
                }

                if (vTrack != -1) {
                    extractor.selectTrack(vTrack)
                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val bytes = ByteArray(sampleSize)
                        buffer.get(bytes)
                        videoPackets.add(VideoPacket(bytes, nextVideoPtsUs, extractor.sampleFlags))
                        nextVideoPtsUs += DEFAULT_FRAME_DURATION_US
                        extractor.advance()
                    }
                    extractor.unselectTrack(vTrack)
                }
                extractor.release()
            }

            // 6. Chronological Interleaving: write audio and video packets in timestamp order
            var vIdx = 0
            var aIdx = 0
            val bInfo = MediaCodec.BufferInfo()

            // Filter out codec config packet from sample writing (MediaMuxer handles it via addTrack)
            val mediaAacPackets = aacPackets.filter { !it.isConfig }

            while (vIdx < videoPackets.size || aIdx < mediaAacPackets.size) {
                val hasVideo = vIdx < videoPackets.size
                val hasAudio = aIdx < mediaAacPackets.size

                val writeVideo = when {
                    hasVideo && !hasAudio -> true
                    !hasVideo && hasAudio -> false
                    else -> videoPackets[vIdx].ptsUs <= mediaAacPackets[aIdx].ptsUs
                }

                if (writeVideo) {
                    val vp = videoPackets[vIdx]
                    bInfo.offset = 0
                    bInfo.size = vp.data.size
                    bInfo.presentationTimeUs = vp.ptsUs
                    bInfo.flags = vp.flags
                    muxer.writeSampleData(outVideoTrack, ByteBuffer.wrap(vp.data), bInfo)
                    vIdx++
                } else {
                    val ap = mediaAacPackets[aIdx]
                    bInfo.offset = 0
                    bInfo.size = ap.data.size
                    bInfo.presentationTimeUs = ap.ptsUs
                    bInfo.flags = 0
                    muxer.writeSampleData(outAudioTrack, ByteBuffer.wrap(ap.data), bInfo)
                    aIdx++
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            // Clean up temporary chunk files
            for (seg in validSegments) {
                try { seg.videoFile.delete() } catch (ignored: Exception) {}
                try { seg.pcmFile.delete() } catch (ignored: Exception) {}
            }

            val finalDurationUs = maxOf(nextVideoPtsUs, mediaAacPackets.lastOrNull()?.ptsUs ?: 0L)
            val finalDurationMs = finalDurationUs / 1000L
            val cutCount = (validSegments.size - 1).coerceAtLeast(0)

            Log.d(TAG, "Remux complete! Duration: ${finalDurationMs}ms, Cuts: $cutCount")

            RemuxResult(
                success = true,
                outputFile = outputFile,
                originalDurationMs = originalDurationMs,
                finalDurationMs = finalDurationMs,
                cutCount = cutCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Remux failed", e)
            try { muxer?.release() } catch (ignored: Exception) {}
            RemuxResult(
                success = false,
                outputFile = outputFile,
                originalDurationMs = originalDurationMs,
                finalDurationMs = 0L,
                cutCount = 0,
                errorMessage = e.localizedMessage ?: "Failed during remuxing"
            )
        }
    }

    /**
     * Generates standard ISO/IEC 14496-3 AAC Audio Specific Config (CSD-0)
     * for AAC-LC audio at any sample rate and channel count.
     */
    fun generateAacCsd0(sampleRate: Int, channelCount: Int = 1): ByteArray {
        val sampleRateIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 4
        }
        val profile = 2 // AAC-LC
        val byte1 = ((profile shl 3) or (sampleRateIndex shr 1)).toByte()
        val byte2 = (((sampleRateIndex and 0x01) shl 7) or (channelCount shl 3)).toByte()
        return byteArrayOf(byte1, byte2)
    }
}
