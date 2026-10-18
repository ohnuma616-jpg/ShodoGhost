package dev.shodoghost.protocol.camera

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyStillCaptureTest {
    @Test
    fun prefersTheTwelveMegapixelSizeThenReleases() {
        val camera = FakeStillCamera(
            sizes = listOf(
                PictureSize(640, 480),
                PictureSize(8000, 6000),
                PictureSize(4032, 3024),
            ),
            jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
        )
        val order = camera.events
        val still = LegacyStillCapture(camera) { millis -> order += "settle $millis" }.captureStill(90)

        assertEquals(4032, still.width)
        assertEquals(3024, still.height)
        assertTrue(still.cameraReleased)
        assertEquals(4, still.jpeg.size)
        assertEquals(
            listOf(
                "open 0",
                "sizes",
                "jpeg 4032x3024 q90",
                "preview",
                "settle 700",
                "picture",
                "stop",
                "release",
            ),
            order,
        )
    }

    @Test
    fun fallsBackToTheLargestReportedSize() {
        val size = StillCapture.selectSize(listOf(PictureSize(640, 480), PictureSize(1280, 960), PictureSize(320, 240)))
        assertEquals(PictureSize(1280, 960), size)
    }

    @Test
    fun equalAreaKeepsTheFirstSize() {
        val size = StillCapture.selectSize(listOf(PictureSize(20, 10), PictureSize(10, 20)))
        assertEquals(PictureSize(20, 10), size)
    }

    @Test
    fun releasesWhenThePictureFails() {
        val camera = FakeStillCamera(sizes = listOf(PictureSize(4032, 3024)), failPicture = true)
        assertFailsWith<IllegalStateException> {
            LegacyStillCapture(camera) { }.captureStill(80)
        }
        assertEquals(listOf("open 0", "sizes", "jpeg 4032x3024 q80", "preview", "picture", "stop", "release"), camera.events)
    }

    @Test
    fun releasesWhenTheCameraHasNoSizes() {
        val camera = FakeStillCamera(sizes = emptyList())
        assertFailsWith<IllegalArgumentException> {
            LegacyStillCapture(camera) { }.captureStill(80)
        }
        assertEquals(listOf("open 0", "sizes", "release"), camera.events)
    }

    @Test
    fun doesNotOpenWhenQualityIsOutOfRange() {
        val camera = FakeStillCamera(sizes = listOf(PictureSize(4032, 3024)))
        assertFailsWith<IllegalArgumentException> {
            LegacyStillCapture(camera) { }.captureStill(0)
        }
        assertTrue(camera.events.isEmpty())
    }

    @Test
    fun doesNotReportAStillWhenReleaseFails() {
        val camera = FakeStillCamera(sizes = listOf(PictureSize(4032, 3024)), failRelease = true)
        assertFailsWith<IllegalStateException> {
            LegacyStillCapture(camera) { }.captureStill(90)
        }
        assertTrue(camera.events.contains("release"))
    }

    @Test
    fun rejectsAnEmptyJpegAfterRelease() {
        val camera = FakeStillCamera(sizes = listOf(PictureSize(4032, 3024)), jpeg = byteArrayOf())
        assertFailsWith<IllegalArgumentException> {
            LegacyStillCapture(camera) { }.captureStill(90)
        }
        assertEquals("release", camera.events.last())
    }

    private class FakeStillCamera(
        private val sizes: List<PictureSize>,
        private val jpeg: ByteArray = byteArrayOf(1, 2, 3),
        private val failPicture: Boolean = false,
        private val failRelease: Boolean = false,
    ) : StillCamera {
        val events = mutableListOf<String>()

        override fun open(cameraId: Int) {
            events += "open $cameraId"
        }

        override fun supportedPictureSizes(): List<PictureSize> {
            events += "sizes"
            return sizes
        }

        override fun applyJpeg(width: Int, height: Int, quality: Int) {
            events += "jpeg ${width}x${height} q$quality"
        }

        override fun startPreview() {
            events += "preview"
        }

        override fun takePicture(): ByteArray {
            events += "picture"
            if (failPicture) throw IllegalStateException("picture")
            return jpeg
        }

        override fun stopPreview() {
            events += "stop"
        }

        override fun release() {
            events += "release"
            if (failRelease) throw IllegalStateException("release")
        }
    }
}
