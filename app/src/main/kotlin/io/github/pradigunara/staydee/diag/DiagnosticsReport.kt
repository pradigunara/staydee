package io.github.pradigunara.staydee.diag

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Packages

/** M0 static checks: what the bundled FFmpeg can do on this device. */
object DiagnosticsReport {
    private val requiredFilters = listOf("vidstabdetect", "vidstabtransform", "scale", "unsharp", "zscale", "tonemap")

    fun build(): String = buildString {
        appendLine("device      ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appendLine("soc         ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("abis        ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("page size   ${Os.sysconf(OsConstants._SC_PAGESIZE)} B")
        appendLine("ffmpegkit   ${FFmpegKitConfig.getVersion()} (${Packages.getPackageName()}, LTS=${FFmpegKitConfig.isLTSBuild()}, built ${FFmpegKitConfig.getBuildDate()})")
        appendLine("ffmpeg      ${FFmpegKitConfig.getFFmpegVersion()}")
        appendLine("libraries   ${Packages.getExternalLibraries().joinToString()}")

        val filters = ffmpeg("-hide_banner", "-filters")
        val names = filters.lineSequence().mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }.toSet()
        appendLine()
        appendLine("filters")
        for (f in requiredFilters) appendLine("  ${if (f in names) "ok     " else "MISSING"} $f")

        val encoders = ffmpeg("-hide_banner", "-encoders")
        appendLine("  ${if (Regex("\\blibx264\\b").containsMatchIn(encoders)) "ok     " else "MISSING"} libx264 encoder")

        val vidstabOpts = ffmpeg("-hide_banner", "-h", "filter=vidstabdetect")
        appendLine("  ${if ("fileformat" in vidstabOpts) "ok     " else "MISSING"} binary TRF1 (vidstabdetect fileformat, vid.stab >= 1.1.1)")

        appendLine()
        appendLine("buildconf")
        ffmpeg("-hide_banner", "-buildconf").lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("--") }
            .forEach { appendLine("  $it") }
    }

    private fun ffmpeg(vararg args: String): String =
        FFmpegKit.executeWithArguments(arrayOf(*args)).allLogsAsString.orEmpty()
}
