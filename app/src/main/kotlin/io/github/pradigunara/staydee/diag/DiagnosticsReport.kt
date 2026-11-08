package io.github.pradigunara.staydee.diag

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.github.pradigunara.staydee.pipeline.NativeTools
import io.github.pradigunara.staydee.pipeline.runTool
import java.io.File
import java.io.IOException

/** Static checks: can this device run the bundled ffmpeg, and does it have what the chains use. */
object DiagnosticsReport {
    /** Every filter the pass-1/pass-2 chains can emit (core FilterChain.kt). */
    private val requiredFilters =
        listOf("vidstabdetect", "vidstabtransform", "scale", "format", "unsharp", "zscale", "tonemap")

    suspend fun build(context: Context): String = buildString {
        appendLine("device      ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appendLine("soc         ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("abis        ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("page size   ${Os.sysconf(OsConstants._SC_PAGESIZE)} B")

        val ffmpeg = NativeTools.ffmpeg(context)
        val ffprobe = NativeTools.ffprobe(context)
        for (bin in listOf(ffmpeg, ffprobe)) {
            appendLine("${bin.name.padEnd(12)}${bin.path} (${if (bin.exists()) "${bin.length()} B" else "MISSING"})")
        }

        // The exec itself is the check: a non-PIE static binary, SELinux or a packaging mistake
        // all fail here, with the reason in the exception.
        val version = try {
            tool(ffmpeg, "-hide_banner", "-version")
        } catch (e: IOException) {
            appendLine()
            appendLine("EXEC FAILED  ${e.message}")
            return@buildString
        }
        appendLine("ffmpeg      ${version.lineSequence().firstOrNull().orEmpty()}")
        appendLine("ffprobe     ${tool(ffprobe, "-hide_banner", "-version").lineSequence().firstOrNull().orEmpty()}")

        val names = tool(ffmpeg, "-hide_banner", "-filters").lineSequence()
            .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }.toSet()
        appendLine()
        appendLine("filters")
        for (f in requiredFilters) appendLine("  ${if (f in names) "ok     " else "MISSING"} $f")

        val encoders = tool(ffmpeg, "-hide_banner", "-encoders")
        appendLine("  ${if (Regex("\\blibx264\\b").containsMatchIn(encoders)) "ok     " else "MISSING"} libx264 encoder")

        val vidstabOpts = tool(ffmpeg, "-hide_banner", "-h", "filter=vidstabdetect")
        appendLine("  ${if ("fileformat" in vidstabOpts) "ok     " else "MISSING"} binary TRF1 (vidstabdetect fileformat, vid.stab >= 1.1.1)")

        appendLine()
        appendLine("buildconf")
        tool(ffmpeg, "-hide_banner", "-buildconf").lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("--") }
            .forEach { appendLine("  $it") }
    }

    private suspend fun tool(bin: File, vararg args: String): String =
        runTool(bin, args.toList(), collectStdout = true).let { it.stdout + it.stderrTail }
}
