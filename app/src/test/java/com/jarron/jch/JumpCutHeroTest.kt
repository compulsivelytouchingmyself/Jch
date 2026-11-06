package com.jarron.jch

import com.jarron.jch.audio.AudioCaptureService
import com.jarron.jch.data.JumpCutSettings
import com.jarron.jch.data.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpCutHeroTest {

    @Test
    fun testDefaultSettings() {
        val settings = JumpCutSettings()
        assertEquals(250, settings.preRollMs)
        assertEquals(350, settings.postRollMs)
        assertEquals(128_000, settings.audioBitrate)
        assertEquals("1080p", settings.videoResolution)
        assertTrue(settings.autoCalibrateRoomTone)
    }

    @Test
    fun testTimeSavedCalculations() {
        val video = RecordedVideo(
            id = "test-1",
            title = "Test Cut",
            filePath = "/mock/path/video.mp4",
            timestamp = System.currentTimeMillis(),
            originalDurationMs = 60_000L,
            jumpCutDurationMs = 30_000L,
            cutCount = 8,
            fileSizeBytes = 5_000_000L
        )

        assertEquals(30_000L, video.savedDurationMs)
        assertEquals(50, video.percentSaved)
        assertEquals("0:30.0", video.formatDuration(video.jumpCutDurationMs))
        assertEquals("1:00.0", video.formatDuration(video.originalDurationMs))
    }

    @Test
    fun testPcmBufferCreation() {
        val bytes = ByteArray(2048) { (it % 128).toByte() }
        val buffer = AudioCaptureService.PcmBuffer(bytes, bytes.size, 1_000_000L)
        assertEquals(2048, buffer.length)
        assertEquals(1_000_000L, buffer.timestampNs)
    }

    @Test
    fun testAacCsd0Generation() {
        val csd44k = com.jarron.jch.recorder.ScorchedEarthRemuxer.generateAacCsd0(44100, 1)
        assertEquals(2, csd44k.size)
        assertEquals(0x12.toByte(), csd44k[0])
        assertEquals(0x08.toByte(), csd44k[1])

        val csd48k = com.jarron.jch.recorder.ScorchedEarthRemuxer.generateAacCsd0(48000, 1)
        assertEquals(2, csd48k.size)
        assertEquals(0x11.toByte(), csd48k[0])
        assertEquals(0x88.toByte(), csd48k[1])
    }
}
