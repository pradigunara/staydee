package io.github.pradigunara.staydee.core

/** Video quality for the libx264 encode. */
sealed interface Quality {
    /** libx264 constant rate factor, 0-51. */
    data class Crf(val value: Int) : Quality {
        init {
            require(value in 0..51) { "crf must be 0-51 (got $value)" }
        }
    }
}

/**
 * libx264 and muxer arguments for pass 2 (Python `encode_args`).
 * [creationTime] is written only for MP4-like containers; [hasAudio] says whether
 * the source has an audio stream to encode.
 */
fun encodeArgs(
    s: Settings,
    outExt: String,
    hasAudio: Boolean,
    creationTime: String?,
    quality: Quality = Quality.Crf(s.crf),
): List<String> {
    val args = mutableListOf("-c:v", "libx264", "-preset", s.preset)
    args += when (quality) {
        is Quality.Crf -> listOf("-crf", quality.value.toString())
    }
    args += listOf("-map_metadata", "0")
    if (outExt.lowercase() in MP4_LIKE) {
        args += listOf("-movflags", "+faststart", "-brand", "mp42")
        if (!creationTime.isNullOrEmpty()) args += listOf("-metadata", "creation_time=$creationTime")
    }
    if (hasAudio) {
        args += if (s.audio) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-b:a", "192k")
    }
    return args
}

/** Pass 1 argv: analyse [src] and write the transforms to [trf] (Python `detect_cmd`). */
fun detectArgs(src: String, s: Settings, trf: String, preScale: String?): List<String> =
    listOf(
        "ffmpeg", "-hide_banner", "-nostdin", "-i", src, "-map", "0:v:0",
        "-an", "-sn", "-vf", detectChain(s, trf, preScale), "-f", "null", "-",
    )

/** Pass 2 argv: render the stabilised [src] into [out] (Python `transform_cmd`). */
fun transformArgs(
    src: String,
    out: String,
    s: Settings,
    trf: String,
    renderScale: String?,
    transfer: ColorTransfer,
    outExt: String,
    hasAudio: Boolean,
    creationTime: String?,
): List<String> =
    listOf(
        "ffmpeg", "-hide_banner", "-nostdin", "-i", src, "-map", "0:v:0", "-map", "0:a?",
        "-vf", transformChain(s, trf, renderScale, transfer),
    ) + encodeArgs(s, outExt, hasAudio, creationTime) + out
