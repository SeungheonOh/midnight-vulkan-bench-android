package network.midnight.vulkanbench.core

import java.util.Locale

object ReportFormatter {
    fun toMarkdown(report: BenchmarkReport): String = buildString {
        val a = report.android
        val v = report.vulkan
        val ramGb = a.totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val soc = listOf(a.socManufacturer, a.socModel).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { a.hardware }

        appendLine("## Midnight Vulkan Prover Benchmark")
        appendLine()
        appendLine("- **Device**: ${a.manufacturer} ${a.model} (`${a.device}`, Android ${a.androidRelease} / API ${a.sdkInt})")
        appendLine("- **SoC / CPU**: $soc (${v.cpuCores ?: "?"} cores, ${fmt(ramGb, 1)} GB RAM)")
        appendLine(
            "- **GPU**: ${v.name ?: "Unavailable"} (${v.vendorName ?: "Unknown"} " +
                "${v.vendorId?.let { "0x%04x".format(it) } ?: ""})"
        )
        appendLine(
            "- **Vulkan**: ${v.apiVersion ?: "-"} · variant=`${v.variant ?: "none"}` · " +
                "int64=`${v.shaderInt64}` · int16=`${v.shaderInt16}` · " +
                "subgroup=`${v.subgroupSize}` · sharedMem=`${(v.maxComputeSharedMemoryBytes ?: 0) / 1024} KiB` · " +
                "unified=`${v.unifiedMemory}`"
        )
        if (!v.driver.isNullOrBlank()) {
            appendLine("- **Driver**: `${v.driver.replace("\n", " / ")}`")
        }
        appendLine(
            "- **Run**: preset=`${report.preset}`, mode=`${report.backendMode}`, " +
                "repeats=`${report.repeats}`, prover=`${v.proverRev.take(8)}`, app=`v${a.appVersion}`"
        )
        if (report.smoke != null) {
            val status = if (report.smoke.ok) "PASS" else "FAIL (${report.smoke.error ?: "see steps"})"
            appendLine("- **GPU Smoke**: $status")
        }
        appendLine()
        appendLine("| Circuit | CPU ms | Vulkan #1 | Vulkan warm | Speedup | GPU ms (msm/quot/ifft) | Ver | Erase | Peak MB | Thermal |")
        appendLine("|---|---:|---:|---:|---:|---:|:---:|:---:|---:|:---:|")
        for (e in report.entries) {
            if (e.skippedReason != null) {
                appendLine("| `${e.entry}` | - | - | - | skipped | ${e.skippedReason} | - | - | - | - |")
                continue
            }
            if (e.error != null) {
                appendLine("| `${e.entry}` | ${fmtOpt(e.cpuMs)} | - | - | ERROR | ${e.error} | - | - | ${fmt(e.peakMb, 0)} | - |")
                continue
            }
            val gpuText = e.gpu?.let {
                "${fmt(it.totalGpuMs, 1)} (${fmt(it.msmMs, 0)}/${fmt(it.quotientMs, 0)}/${fmt(it.ifftMs, 0)})"
            } ?: "-"
            val spd = e.speedup?.let { "**${fmt(it, 2)}x**" } ?: "-"
            val ver = if (e.verified) "ok" else "FAIL"
            val erase = when (e.eraseOk) {
                true -> "ok"
                false -> "FAIL"
                null -> "-"
            }
            val therm = e.thermalAfter?.let { t ->
                val temp = t.batteryTempC?.let { "${fmt(it.toDouble(), 1)}°C " } ?: ""
                "$temp${t.thermalStatus}"
            } ?: "-"
            appendLine(
                "| `${e.entry}` | ${fmtOpt(e.cpuMs)} | ${fmtOpt(e.vulkanColdMs)} | " +
                    "${fmtOpt(e.vulkanWarmMs)} | $spd | $gpuText | $ver | $erase | ${fmt(e.peakMb, 0)} | $therm |"
            )
        }
    }

    private fun fmt(v: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", v)

    private fun fmtOpt(v: Double?): String = v?.let { fmt(it, 1) } ?: "-"
}
