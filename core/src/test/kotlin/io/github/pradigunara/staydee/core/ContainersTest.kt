package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContainersTest {
    private fun container(ext: String): Pair<String, List<String>> {
        val warnings = mutableListOf<String>()
        val result = containerFor(ext) { warnings += it }
        return result to warnings
    }

    @Test
    fun h264ContainersKeepExtension() {
        for (ext in listOf("mp4", "mov", "m4v", "mkv", "avi", "ts")) {
            assertEquals(ext to emptyList(), container(ext))
        }
    }

    @Test
    fun noH264ContainersFallBackToMkvWithWarning() {
        for (ext in listOf("webm", "gif", "ogv", "mp3")) {
            val (result, warnings) = container(ext)
            assertEquals("mkv", result)
            assertEquals(
                listOf("container '.$ext' can't hold h264 — writing Matroska instead"),
                warnings,
            )
        }
    }

    @Test
    fun extensionIsLowercased() {
        assertEquals("mp4" to emptyList(), container("MP4"))
    }

    @Test
    fun emptyExtensionDefaultsToMkv() {
        assertEquals("mkv" to emptyList(), container(""))
    }

    @Test
    fun outputPathBesideSourceWithStabSuffix() {
        assertEquals("/a/b/clip-stab.mp4", outputPath("/a/b/clip.mp4", null))
    }

    @Test
    fun outputPathSplitsOnLastDotOnly() {
        assertEquals("/a/b/clip.tar-stab.gz", outputPath("/a/b/clip.tar.gz", null))
    }

    @Test
    fun outputPathIgnoresLeadingDots() {
        assertEquals("/a/b/.bashrc-stab.mkv", outputPath("/a/b/.bashrc", null))
    }

    @Test
    fun outputPathWithoutDirectory() {
        assertEquals("clip-stab.mov", outputPath("clip.MOV", null))
    }

    @Test
    fun explicitOutputWithExtensionIsUsedAsIs() {
        assertEquals("/o/final.mp4", outputPath("/a/b/clip.mov", "/o/final.mp4"))
    }

    @Test
    fun bareOutputNameWithoutExtensionTakesSourceExtension() {
        assertEquals("/o/final.mov", outputPath("/a/b/clip.mov", "/o/final"))
    }

    @Test
    fun explicitOutputWithDotInBasenameIsKeptEvenWithoutExtension() {
        assertEquals("/o/final.v2", outputPath("/a/b/clip.mov", "/o/final.v2"))
    }

    @Test
    fun creationTimeReturnsTimestampedSourceTagVerbatim() {
        assertEquals(
            "2020-01-02T03:04:05.000000Z",
            creationTime("2020-01-02T03:04:05.000000Z", "2026-11-06T02:48:00"),
        )
    }

    @Test
    fun creationTimeFallsBackToMtimeWhenTagNotTimestamp() {
        assertEquals("2026-11-06T02:48:00", creationTime("not a date", "2026-11-06T02:48:00"))
        assertEquals("2026-11-06T02:48:00", creationTime(null, "2026-11-06T02:48:00"))
    }

    @Test
    fun creationTimeNullWhenNothingKnown() {
        assertNull(creationTime(null, null))
    }

    @Test
    fun localTimestampInZone() {
        val jakarta = java.time.ZoneId.of("Asia/Jakarta")
        assertEquals("1970-01-01T07:00:00", localTimestamp(0L, jakarta))
    }
}
