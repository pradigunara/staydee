package io.github.pradigunara.staydee.diag

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * M0 repeatability check (plan §3.7, §8): detect once, then run the transform
 * [runs] times from that one fixed trf, without x264, and compare decoded frames.
 *
 * Run 1 is kept as lossless FFV1 so later runs can be PSNR-compared against it;
 * every run also writes a framemd5 so per-frame identity is exact.
 */
class Repeatability(
    private val workDir: File,
    private val log: (String) -> Unit,
) {
    data class Chains(
        /** pass-1 filter chain writing to the given trf path */
        val detect: (trf: String) -> String,
        /** transform-only chain reading the given trf path (no unsharp, no encoder) */
        val transform: (trf: String) -> String,
    )

    data class RunResult(val run: Int, val framesDiffering: Int, val psnrAvg: String)

    data class SizeResult(
        val label: String,
        val frames: Int,
        val distinctHashes: Int,
        val runs: List<RunResult>,
    ) {
        val deterministic get() = distinctHashes == 1
    }

    fun measure(src: String, label: String, chains: Chains, runs: Int = 5, frames: Int = 60): SizeResult {
        val dir = File(workDir, label).apply { deleteRecursively(); mkdirs() }
        val trf = File(dir, "detect.trf").path
        log("[$label] pass 1")
        ffmpeg("-i", src, "-map", "0:v:0", "-an", "-sn", "-frames:v", "$frames",
            "-vf", chains.detect(trf), "-f", "null", "-")

        val reference = File(dir, "run1.mkv")
        val chain = chains.transform(trf)
        val hashes = mutableListOf<List<String>>()
        val results = mutableListOf<RunResult>()
        for (i in 1..runs) {
            val md5 = File(dir, "run$i.framemd5")
            val out = File(dir, "run$i.mkv")
            log("[$label] transform run $i/$runs")
            ffmpeg("-i", src, "-filter_complex", "[0:v:0]$chain,split[f][m]",
                "-map", "[f]", "-frames:v", "$frames", "-c:v", "ffv1", out.path,
                "-map", "[m]", "-frames:v", "$frames", "-f", "framemd5", md5.path)
            val h = frameHashes(md5)
            hashes += h
            if (i == 1) {
                results += RunResult(1, 0, "reference")
            } else {
                val differing = h.indices.count { it >= hashes[0].size || h[it] != hashes[0][it] }
                // file vs file: comparing a live filter output against a decoded file
                // pairs frames by timestamp and misaligns them (28.8 dB on identical frames)
                val logs = ffmpeg("-i", reference.path, "-i", out.path, "-lavfi", "[0:v][1:v]psnr", "-f", "null", "-")
                val psnr = PSNR_AVG.findAll(logs).lastOrNull()?.groupValues?.get(1) ?: "?"
                results += RunResult(i, differing, psnr)
                out.delete()
            }
            log("[$label] run $i: ${results.last().framesDiffering} frames differ, PSNR ${results.last().psnrAvg}")
        }
        reference.delete()
        return SizeResult(label, hashes[0].size, hashes.map { it.joinToString() }.toSet().size, results)
    }

    private fun frameHashes(md5: File): List<String> =
        md5.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringAfterLast(',').trim() }

    private fun ffmpeg(vararg args: String): String {
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-nostdin", "-y", *args))
        val logs = session.allLogsAsString.orEmpty()
        if (ReturnCode.isCancel(session.returnCode)) throw InterruptedException("cancelled")
        check(ReturnCode.isSuccess(session.returnCode)) { "ffmpeg failed (${session.returnCode}):\n${logs.takeLast(2000)}" }
        return logs
    }

    private companion object {
        val PSNR_AVG = Regex("""PSNR .*?average:(\S+)""")
    }
}
