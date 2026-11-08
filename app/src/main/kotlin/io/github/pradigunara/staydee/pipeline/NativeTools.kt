package io.github.pradigunara.staydee.pipeline

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/**
 * The bundled ffmpeg 9.0.2 + vid.stab 1.1.2 executables (vendor/ffmpeg, plan §15.6). They ship as
 * `lib*.so` so the installer extracts them to [android.content.pm.ApplicationInfo.nativeLibraryDir],
 * the only app location Android allows to exec from (app data is noexec for targetSdk >= 29).
 */
object NativeTools {
    fun ffmpeg(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    fun ffprobe(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libffprobe.so")
}

/** Exit status plus what the tool printed; [stderrTail] is the last few KB of its log. */
class ToolResult(val exitCode: Int, val stdout: String, val stderrTail: String)

private const val STDERR_TAIL_CHARS = 4000

/**
 * Run [binary] with [args]. With [stdin] the source is fed as standard input, which the tool reads
 * as `/proc/self/fd/0`. [onStdoutLine] sees every stdout line (`-progress pipe:1` key=value lines); stdout is
 * also returned when [collectStdout]. Cancelling the coroutine kills the process.
 */
suspend fun runTool(
    binary: File,
    args: List<String>,
    stdin: InputSource? = null,
    collectStdout: Boolean = false,
    onStdoutLine: (String) -> Unit = {},
): ToolResult = withContext(Dispatchers.IO) {
    val builder = ProcessBuilder(listOf(binary.path) + args)
    when (stdin) {
        // Reopening our own descriptor through /proc gives the child a fresh, seekable handle
        // on the same file; the parent opens it, so no cross-process /proc access is needed.
        is InputSource.Descriptor -> builder.redirectInput(File("/proc/self/fd/${stdin.pfd.fd}"))
        is InputSource.Copy, null -> builder.redirectInput(File("/dev/null"))
    }
    val process = builder.start()
    val job = currentCoroutineContext()[Job]
    val killer = job?.invokeOnCompletion { cause -> if (cause != null) process.destroy() }
    try {
        val out = StringBuilder()
        val tail = StringBuilder()
        val stdoutReader = thread(name = "tool-stdout") {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (collectStdout) out.append(line).append('\n')
                onStdoutLine(line)
            }
        }
        val stderrReader = thread(name = "tool-stderr") {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(tail) {
                    tail.append(line).append('\n')
                    if (tail.length > 2 * STDERR_TAIL_CHARS) tail.delete(0, tail.length - STDERR_TAIL_CHARS)
                }
            }
        }
        val code = runInterruptible { process.waitFor() }
        stdoutReader.join()
        stderrReader.join()
        ToolResult(code, out.toString(), synchronized(tail) { tail.takeLast(STDERR_TAIL_CHARS).toString() })
    } finally {
        killer?.dispose()
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Throw with the tool's own error lines when it did not exit 0. */
fun ToolResult.requireSuccess(what: String): ToolResult {
    check(exitCode == 0) { "$what failed (exit $exitCode):\n$stderrTail" }
    return this
}

/**
 * How a tool reads the user's video. Picker and share Uris usually open as a seekable file, which
 * is handed to the tool as its stdin without copying. A provider that only hands out a pipe (not
 * seekable; MP4 needs seeking) is copied into the job directory first.
 */
sealed interface InputSource : AutoCloseable {
    /** The `-i` argument for ffmpeg/ffprobe. */
    val arg: String

    class Descriptor(val pfd: ParcelFileDescriptor) : InputSource {
        // The child's own stdin, opened as a regular, seekable file. ffmpeg's fd: protocol would
        // need a separate -fd option in front of -i, which the :core argv doesn't have.
        override val arg = "/proc/self/fd/0"
        override fun close() = pfd.close()
    }

    class Copy(val file: File) : InputSource {
        override val arg: String = file.path
        override fun close() {}
    }

    companion object {
        /** Open [uri] for one tool run; [copyDir] receives the copy when the provider can't seek. */
        fun open(context: Context, uri: Uri, copyDir: File): InputSource {
            val pfd = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "cannot open $uri" }
            if (seekable(pfd)) return Descriptor(pfd)
            val copy = File(copyDir, "input")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> copy.outputStream().use { input.copyTo(it) } }
            return Copy(copy)
        }

        private fun seekable(pfd: ParcelFileDescriptor): Boolean = try {
            Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
            true
        } catch (_: ErrnoException) {
            false
        }
    }
}
