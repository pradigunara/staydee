package io.github.pradigunara.staydee.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Containers that hold H.264 as-is (Python `H264_CONTAINERS`). */
internal val H264_CONTAINERS: Set<String> =
    setOf("mp4", "mov", "m4v", "mkv", "avi", "ts", "m2ts", "flv", "nut", "3gp")

/** Containers that take `-movflags +faststart -brand mp42` and a creation_time (Python `MP4_LIKE`). */
internal val MP4_LIKE: Set<String> = setOf("mp4", "mov", "m4v")

/** Containers that refuse H.264; output is Matroska instead (Python `NO_H264`). */
internal val NO_H264: Set<String> = setOf("webm", "ogv", "ogg", "gif", "mp3", "m4a", "wav")

/**
 * Output container for a requested extension (Python `container_for`).
 * Containers that cannot hold H.264 fall back to `mkv`, reported through [warn].
 */
fun containerFor(ext: String, warn: (String) -> Unit): String {
    val lower = ext.lowercase()
    return when {
        lower in H264_CONTAINERS -> lower
        lower in NO_H264 -> {
            warn("container '.$lower' can't hold h264 — writing Matroska instead")
            "mkv"
        }
        else -> lower.ifEmpty { "mkv" }
    }
}

/**
 * Output path for [src] (Python `output_path`). Without [out] the file is
 * `<stem>-stab.<ext>` beside the source. A bare [out] takes the source's extension.
 */
fun outputPath(src: String, out: String?): String {
    val base = posixBasename(src)
    var (stem, ext) = posixSplitext(base)
    if (stem.isEmpty()) {
        stem = base
        ext = ".mkv"
    }
    val outExt = ext.trimStart('.').lowercase().ifEmpty { "mkv" }
    if (out.isNullOrEmpty()) return posixJoin(posixDirname(src), "$stem-stab.$outExt")
    if ('.' !in posixBasename(out)) return "$out.$outExt"
    return out
}

private val TIMESTAMP_PREFIX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")

/**
 * creation_time for the output (Python `src_creation_time`): the source's own
 * tag when it starts with a `YYYY-MM-DDTHH:MM:SS` timestamp, else the file's
 * local mtime formatted by [localTimestamp], which the caller supplies.
 */
fun creationTime(sourceTag: String?, fileMtimeLocal: String?): String? =
    sourceTag?.takeIf { TIMESTAMP_PREFIX.containsMatchIn(it) } ?: fileMtimeLocal

private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")

/** `%Y-%m-%dT%H:%M:%S` of [epochSeconds] in [zone]. */
fun localTimestamp(epochSeconds: Long, zone: ZoneId): String =
    LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zone).format(TIMESTAMP_FORMAT)

// posixpath equivalents for '/'-separated paths.

private fun posixBasename(path: String): String = path.substringAfterLast('/')

private fun posixDirname(path: String): String {
    val head = path.substring(0, path.lastIndexOf('/') + 1)
    return if (head.isNotEmpty() && head.trimEnd('/').isNotEmpty()) head.trimEnd('/') else head
}

private fun posixJoin(dir: String, name: String): String =
    if (dir.isEmpty() || dir.endsWith('/')) dir + name else "$dir/$name"

/** Split a basename into stem and extension. A leading run of dots is not an extension. */
private fun posixSplitext(base: String): Pair<String, String> {
    val dot = base.lastIndexOf('.')
    if (dot < 0 || base.substring(0, dot).all { it == '.' }) return base to ""
    return base.substring(0, dot) to base.substring(dot)
}
