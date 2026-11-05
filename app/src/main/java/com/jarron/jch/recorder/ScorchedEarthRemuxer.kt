package com.jarron.jch.recorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

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
    private const val DEFAULT_AUDIO_SAMPLES_PER_FRAME = 1024L
    private const val DEFAULT_SAMPLE_RATE = 44100L

    /**
     * Scorched Earth Remuxer:
     * Takes all isolated video/audio chunks and remuxes them into a single continuous MP4,
     * recalculating monotonic presentation timestamps under constant bit rate audio
     * with properly interleaved video and audio tracks.
     */
    suspend fun remuxChunks(
        chunkFiles: List<File>,
        outputFile: File,
        originalDurationMs: Long
    ): RemuxResult = withContext(Dispatchers.IO) {
        val validChunks = chunkFiles.filter { it.exists() && it.length() > 1024 }
        if (validChunks.isEmpty()) {
            return@withContext RemuxResult(
                success = false,
                outputFile = outputFile,
                originalDurationMs = originalDurationMs,
                finalDurationMs = 0L,
                cutCount = 0,
                errorMessage = "No valid recorded speech chunks to remux."
            )
        }

        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Discover track formats from first chunk
            val firstExtractor = MediaExtractor().apply { setDataSource(validChunks[0].absolutePath) }
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoFormat == null) {
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioFormat == null) {
                    audioFormat = format
                }
            }
            firstExtractor.release()

            if (videoFormat == null && audioFormat == null) {
                return@withContext RemuxResult(
                    success = false,
                    outputFile = outputFile,
                    originalDurationMs = originalDurationMs,
                    finalDurationMs = 0L,
                    cutCount = 0,
                    errorMessage = "Could not find video or audio tracks in recorded chunks."
                )
            }

            var outVideoTrack = -1
            var outAudioTrack = -1

            videoFormat?.let { outVideoTrack = muxer.addTrack(it) }
            audioFormat?.let { outAudioTrack = muxer.addTrack(it) }

            muxer.start()

            // Monotonic scorched-earth presentation timestamp counters
            var nextVideoPtsUs = 0L
            var nextAudioPtsUs = 0L

            val sampleRate = audioFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE)?.toLong() ?: DEFAULT_SAMPLE_RATE
            val audioBufferDurationUs = (DEFAULT_AUDIO_SAMPLES_PER_FRAME * 1_000_000L) / sampleRate

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (chunkFile in validChunks) {
                val extractor = MediaExtractor().apply { setDataSource(chunkFile.absolutePath) }
                var cVideoTrack = -1
                var cAudioTrack = -1

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && cVideoTrack == -1) cVideoTrack = i
                    if (mime.startsWith("audio/") && cAudioTrack == -1) cAudioTrack = i
                }

                // Select both tracks for natural chronological interleaved reading
                if (cVideoTrack != -1) extractor.selectTrack(cVideoTrack)
                if (cAudioTrack != -1) extractor.selectTrack(cAudioTrack)

                while (true) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break // end of current chunk

                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.flags = extractor.sampleFlags

                    if (trackIndex == cVideoTrack && outVideoTrack != -1) {
                        bufferInfo.presentationTimeUs = nextVideoPtsUs
                        muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                        nextVideoPtsUs += DEFAULT_FRAME_DURATION_US
                    } else if (trackIndex == cAudioTrack && outAudioTrack != -1) {
                        bufferInfo.presentationTimeUs = nextAudioPtsUs
                        muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                        nextAudioPtsUs += audioBufferDurationUs
                    }

                    extractor.advance()
                }

                extractor.release()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            // Clean up temporary chunks
            for (chunkFile in validChunks) {
                try { chunkFile.delete() } catch (ignored: Exception) {}
            }

            val finalDurationUs = maxOf(nextVideoPtsUs, nextAudioPtsUs)
            val finalDurationMs = finalDurationUs / 1000L
            val cutCount = (validChunks.size - 1).coerceAtLeast(0)

            RemuxResult(
                success = true,
                outputFile = outputFile,
                originalDurationMs = originalDurationMs,
                finalDurationMs = finalDurationMs,
                cutCount = cutCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scorched earth remux failed", e)
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
}
