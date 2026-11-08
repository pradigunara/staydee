package io.github.pradigunara.staydee

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Installs [CrashLog] before any activity or service of the process runs. */
class StaydeeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Sideloaded builds have no Play Console: show the last crash on the next launch so a user can
 * send it without adb. Java crashes come from an uncaught-exception handler; native crashes
 * (FFmpeg runs in-process) from the system's exit records, API 30+.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val PREFS = "crashlog"
    private const val SEEN = "seen_exit_timestamp"
    private const val MAX_TRACE_LINES = 300

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(header(context) + "thread: ${thread.name}\n" + stackTrace(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Show the newest unseen crash once, with Share. */
    fun offer(context: Context) {
        val text = javaCrash(context) ?: nativeCrash(context) ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.crash_title)
            .setMessage(text.lineSequence().take(14).joinToString("\n"))
            .setPositiveButton(R.string.share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, context.getString(R.string.crash_title)))
            }
            .setNegativeButton(R.string.dismiss, null)
            .show()
    }

    private fun javaCrash(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = file.readText()
        file.delete()
        // the same crash is also in the exit records; don't offer it twice
        exitRecords(context).maxOfOrNull { it.timestamp }?.let { markSeen(context, it) }
        return text
    }

    private fun nativeCrash(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SEEN, 0)
        val exit = exitRecords(context)
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > seen }
            ?: return null
        markSeen(context, exit.timestamp)
        // the trace is a protobuf tombstone; its printable strings carry the signal, abort
        // message and symbolized frames, which is what a report needs
        val trace = exit.traceInputStream?.use { printableStrings(it.readBytes()) }.orEmpty()
        return header(context) + "native crash: ${exit.description}\n" + trace
    }

    private fun exitRecords(context: Context): List<ApplicationExitInfo> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            emptyList()
        } else {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 5)
        }

    private fun markSeen(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(SEEN, timestamp).apply()
    }

    private fun printableStrings(bytes: ByteArray): String {
        val lines = mutableListOf<String>()
        val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) {
                run.append(c.toChar())
            } else {
                if (run.length >= 4) lines += run.toString()
                run.setLength(0)
                if (lines.size >= MAX_TRACE_LINES) break
            }
        }
        if (run.length >= 4 && lines.size < MAX_TRACE_LINES) lines += run.toString()
        return lines.joinToString("\n")
    }

    private fun header(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "Staydee $version (${if (BuildConfig.DEBUG) "debug" else "release"})\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"
    }

    private fun stackTrace(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}
