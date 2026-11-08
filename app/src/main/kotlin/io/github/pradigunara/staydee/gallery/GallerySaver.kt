package io.github.pradigunara.staydee.gallery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Writes finished videos into `Movies/Staydee` through MediaStore. */
object GallerySaver {
    private const val RELATIVE_PATH = "Movies/Staydee"
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Copy [file] into the gallery as [displayName] and return its MediaStore content Uri. The row is
     * inserted pending and only published once every byte is written. On any failure, including
     * cancellation, the pending row is deleted so no stale item is left behind.
     */
    suspend fun save(
        context: Context,
        file: File,
        displayName: String,
        mime: String,
        creationTime: String?,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values)) { "MediaStore refused $displayName" }
        try {
            val out = checkNotNull(resolver.openOutputStream(uri, "w")) { "cannot open $uri for writing" }
            out.use { sink ->
                file.inputStream().use { source ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                    }
                }
            }
            val publish = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                dateTakenMillis(creationTime)?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
            }
            check(resolver.update(uri, publish, null, null) == 1) { "could not publish $uri" }
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    /**
     * Epoch milliseconds for a creation_time string: ISO with a zone offset (`...Z`, `...+07:00`), or a
     * zone-less local `yyyy-MM-ddTHH:mm:ss` read in the system zone. Null when unparseable.
     */
    internal fun dateTakenMillis(creationTime: String?): Long? {
        if (creationTime.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(creationTime).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(creationTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
