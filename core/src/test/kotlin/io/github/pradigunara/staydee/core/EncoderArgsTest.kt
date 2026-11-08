package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncoderArgsTest {
    private val s = Settings()
    private val ctime = "2026-11-06T02:48:00"

    private fun joined(args: List<String>) = args.joinToString(" ")

    @Test
    fun mp4GetsBrandAndDate() {
        assertEquals(
            "-c:v libx264 -preset veryfast -crf 22 -map_metadata 0 -movflags +faststart -brand mp42 " +
                "-metadata creation_time=2026-11-06T02:48:00 -c:a copy",
            joined(encodeArgs(s, "mp4", true, ctime)),
        )
    }

    @Test
    fun mkvGetsNeitherBrandNorDate() {
        assertEquals(
            "-c:v libx264 -preset veryfast -crf 22 -map_metadata 0 -c:a copy",
            joined(encodeArgs(s, "mkv", true, ctime)),
        )
    }

    @Test
    fun mp4LikeExtensionIsCaseInsensitive() {
        assertTrue("-movflags +faststart" in joined(encodeArgs(s, "MOV", false, null)))
    }

    @Test
    fun noCreationTimeWhenAbsent() {
        val args = joined(encodeArgs(s, "mp4", false, null))
        assertTrue("-movflags +faststart" in args)
        assertFalse("creation_time" in args)
    }

    @Test
    fun audioTranscodeWhenAudioOff() {
        assertTrue("-c:a aac -b:a 192k" in joined(encodeArgs(s.copy(audio = false), "mp4", true, null)))
    }

    @Test
    fun noAudioCodecWithoutAudioStream() {
        assertFalse("-c:a" in joined(encodeArgs(s, "mp4", false, null)))
    }

    @Test
    fun explicitCrfQuality() {
        assertTrue("-crf 18" in joined(encodeArgs(s, "mkv", false, null, Quality.Crf(18))))
    }

    @Test
    fun detectArgvIsExact() {
        assertEquals(
            listOf(
                "ffmpeg", "-hide_banner", "-nostdin", "-i", "/s/in.mp4", "-map", "0:v:0",
                "-an", "-sn", "-vf",
                "vidstabdetect=result=/t/x.trf:shakiness=5:accuracy=15:stepsize=6:mincontrast=0.25",
                "-f", "null", "-",
            ),
            detectArgs("/s/in.mp4", s, "/t/x.trf", null),
        )
    }

    @Test
    fun transformArgvIsExact() {
        assertEquals(
            listOf(
                "ffmpeg", "-hide_banner", "-nostdin", "-i", "/s/in.mp4", "-map", "0:v:0", "-map", "0:a?",
                "-vf",
                "scale=w='if(gte(iw,ih),min(iw,1920),-2)':h='if(gte(iw,ih),-2,min(ih,1920))'," +
                    "vidstabtransform=input=/t/x.trf:smoothing=15:optzoom=1:optalgo=gauss:zoomspeed=0.25:" +
                    "crop=keep:interpol=bicubic:relative=1:zoom=0:maxshift=-1:maxangle=-1," +
                    "unsharp=5:5:0.8:3:3:0.4",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-map_metadata", "0",
                "-movflags", "+faststart", "-brand", "mp42", "-metadata", "creation_time=2026-11-06T02:48:00",
                "-c:a", "copy", "/o/out.mp4",
            ),
            transformArgs(
                "/s/in.mp4", "/o/out.mp4", s, "/t/x.trf", scaleFilter(1920), ColorTransfer.SDR,
                "mp4", true, ctime,
            ),
        )
    }
}
