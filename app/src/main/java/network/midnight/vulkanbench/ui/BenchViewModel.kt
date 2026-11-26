package network.midnight.vulkanbench.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.midnight.vulkanbench.BenchApplication
import network.midnight.vulkanbench.core.AndroidDeviceInfo
import network.midnight.vulkanbench.core.BackendMode
import network.midnight.vulkanbench.core.BenchPreset
import network.midnight.vulkanbench.core.BenchRepository
import network.midnight.vulkanbench.core.BenchService
import network.midnight.vulkanbench.core.BenchSpec
import network.midnight.vulkanbench.core.BenchmarkReport
import network.midnight.vulkanbench.core.DeviceCollector
import network.midnight.vulkanbench.core.EntryResult
import network.midnight.vulkanbench.core.GpuBreakdown
import network.midnight.vulkanbench.core.NativeBridge
import network.midnight.vulkanbench.core.SmokeReport
import network.midnight.vulkanbench.core.ThermalSnapshot
import network.midnight.vulkanbench.core.VulkanDeviceInfo
import network.midnight.vulkanbench.core.minRequiredRamGb
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

data class BenchUiState(
    val androidInfo: AndroidDeviceInfo? = null,
    val vulkanInfo: VulkanDeviceInfo? = null,
    val thermal: ThermalSnapshot? = null,
    val arithPreference: String = "",
    val pipelineCacheBytes: Long = 0L,
    val preset: BenchPreset = BenchPreset.STANDARD,
    val customSpecs: List<BenchSpec> = listOf(
        BenchSpec("chain", 15),
        BenchSpec("chain", 16),
        BenchSpec("chain", 17),
        BenchSpec("chain", 18),
        BenchSpec("poseidon-chain", 15),
        BenchSpec("poseidon-preimage", 15),
    ),
    val backendMode: BackendMode = BackendMode.BOTH,
    val repeats: Int = 2,
    val cooldownSec: Int = 5,
    val ramGuardEnabled: Boolean = true,
    val runSmokeBefore: Boolean = true,
    val isRunning: Boolean = false,
    val isSmoking: Boolean = false,
    val currentEntryLabel: String = "",
    val currentStage: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val elapsedSec: Int = 0,
    val smokeReport: SmokeReport? = null,
    val liveEntries: List<EntryResult> = emptyList(),
    val lastReport: BenchmarkReport? = null,
    val savedReports: List<Pair<File, BenchmarkReport>> = emptyList(),
    val statusBanner: String? = null,
)

class BenchViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Context = application.applicationContext
    private val repo = BenchRepository(ctx)
    private val _state = MutableStateFlow(BenchUiState())
    val state: StateFlow<BenchUiState> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private var runJob: Job? = null
    private var timerJob: Job? = null

    init {
        NativeBridge.setProgressListener { stage ->
            _state.update { it.copy(currentStage = stage) }
            val s = _state.value
            if (s.isRunning && s.currentEntryLabel.isNotBlank()) {
                BenchService.update(
                    ctx,
                    "${s.completedCount}/${s.totalCount} · ${s.currentEntryLabel} ($stage)",
                )
            }
        }
        refreshDeviceAndReports()
    }

    override fun onCleared() {
        NativeBridge.setProgressListener(null)
        super.onCleared()
    }

    fun refreshDeviceAndReports() {
        viewModelScope.launch {
            val prefs = ctx.getSharedPreferences(BenchApplication.PREFS_NAME, Context.MODE_PRIVATE)
            val arithPref = prefs.getString(BenchApplication.KEY_ARITH_OVERRIDE, "").orEmpty()
            val cacheFile = File(ctx.cacheDir, BenchApplication.PIPELINE_CACHE_FILE)
            val androidInfo = DeviceCollector.androidInfo(ctx)
            val thermal = DeviceCollector.thermalSnapshot(ctx)
            val vulkanInfo = withContext(Dispatchers.Default) {
                DeviceCollector.parseVulkanInfo(NativeBridge.call("""{"op":"device_info"}"""))
            }
            val saved = withContext(Dispatchers.IO) { repo.listReports() }
            _state.update {
                it.copy(
                    androidInfo = androidInfo,
                    vulkanInfo = vulkanInfo,
                    thermal = thermal,
                    arithPreference = arithPref,
                    pipelineCacheBytes = if (cacheFile.exists()) cacheFile.length() else 0L,
                    savedReports = saved,
                )
            }
        }
    }

    fun selectPreset(preset: BenchPreset) = _state.update { it.copy(preset = preset) }
    fun selectBackendMode(mode: BackendMode) = _state.update { it.copy(backendMode = mode) }
    fun setRepeats(n: Int) = _state.update { it.copy(repeats = n.coerceIn(1, 5)) }
    fun setCooldownSec(sec: Int) = _state.update { it.copy(cooldownSec = sec) }
    fun setRamGuard(on: Boolean) = _state.update { it.copy(ramGuardEnabled = on) }
    fun setRunSmokeBefore(on: Boolean) = _state.update { it.copy(runSmokeBefore = on) }

    fun toggleCustomSpec(spec: BenchSpec) {
        _state.update { s ->
            val next = if (spec in s.customSpecs) {
                s.customSpecs - spec
            } else {
                (s.customSpecs + spec).sortedWith(compareBy({ it.circuit }, { it.k }))
            }
            s.copy(customSpecs = next)
        }
    }

    fun setArithPreference(value: String) {
        val prefs = ctx.getSharedPreferences(BenchApplication.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(BenchApplication.KEY_ARITH_OVERRIDE, value).commit()
        _state.update { it.copy(arithPreference = value) }
    }

    fun clearPipelineCache() {
        val cacheFile = File(ctx.cacheDir, BenchApplication.PIPELINE_CACHE_FILE)
        cacheFile.delete()
        _state.update {
            it.copy(
                pipelineCacheBytes = 0L,
                statusBanner = "Cleared Vulkan pipeline cache",
            )
        }
    }

    fun dismissBanner() = _state.update { it.copy(statusBanner = null) }

    fun runSmokeOnly() {
        if (_state.value.isRunning || _state.value.isSmoking) return
        viewModelScope.launch {
            _state.update { it.copy(isSmoking = true, currentStage = "smoke-fill", statusBanner = null) }
            val smoke = withContext(Dispatchers.Default) {
                DeviceCollector.parseSmoke(NativeBridge.call("""{"op":"smoke"}"""))
            }
            val cacheFile = File(ctx.cacheDir, BenchApplication.PIPELINE_CACHE_FILE)
            _state.update {
                it.copy(
                    isSmoking = false,
                    currentStage = "",
                    smokeReport = smoke,
                    pipelineCacheBytes = if (cacheFile.exists()) cacheFile.length() else 0L,
                    statusBanner = if (smoke.ok) {
                        "GPU smoke test PASSED (${smoke.steps.size} checks)"
                    } else {
                        "GPU smoke test FAILED: ${smoke.error ?: "check step details"}"
                    },
                )
            }
        }
    }

    fun activeSpecs(): List<BenchSpec> {
        val s = _state.value
        val raw = if (s.preset == BenchPreset.CUSTOM) s.customSpecs else s.preset.specs
        // Sort by k first so same-k circuits reuse the cached SRS!
        return raw.sortedWith(compareBy({ it.k }, { it.circuit }))
    }

    fun startBenchmark() {
        val s = _state.value
        if (s.isRunning || s.isSmoking) return
        val specs = activeSpecs()
        if (specs.isEmpty()) {
            _state.update { it.copy(statusBanner = "Select at least one circuit to benchmark") }
            return
        }

        cancelRequested.set(false)
        val startMs = System.currentTimeMillis()
        BenchService.start(ctx, "Starting ${specs.size} circuit benchmarks…")

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                val therm = DeviceCollector.thermalSnapshot(ctx)
                _state.update { it.copy(elapsedSec = elapsed, thermal = therm) }
                delay(1000L)
            }
        }

        runJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isRunning = true,
                    liveEntries = emptyList(),
                    completedCount = 0,
                    totalCount = specs.size,
                    elapsedSec = 0,
                    currentEntryLabel = "Initializing…",
                    currentStage = "",
                    statusBanner = null,
                )
            }

            val androidInfo = DeviceCollector.androidInfo(ctx)
            val vulkanInfo = withContext(Dispatchers.Default) {
                DeviceCollector.parseVulkanInfo(NativeBridge.call("""{"op":"device_info"}"""))
            }
            val totalRamGb = androidInfo.totalRamBytes / (1024.0 * 1024.0 * 1024.0)

            var smokeReport: SmokeReport? = s.smokeReport
            if (s.runSmokeBefore && s.backendMode != BackendMode.CPU_ONLY && vulkanInfo.usable) {
                _state.update { it.copy(currentEntryLabel = "GPU Smoke Test", currentStage = "smoke-fill") }
                smokeReport = withContext(Dispatchers.Default) {
                    DeviceCollector.parseSmoke(NativeBridge.call("""{"op":"smoke"}"""))
                }
                _state.update { it.copy(smokeReport = smokeReport) }
            }

            val results = mutableListOf<EntryResult>()
            var wasCancelled = false

            for ((idx, spec) in specs.withIndex()) {
                if (cancelRequested.get()) {
                    wasCancelled = true
                    break
                }

                _state.update {
                    it.copy(
                        completedCount = idx,
                        currentEntryLabel = spec.id,
                        currentStage = "check",
                    )
                }
                BenchService.update(ctx, "${idx + 1}/${specs.size} · ${spec.id}")

                // Optional RAM safety guardrail so 4-6 GB phones skip k19/k20 cleanly
                val neededGb = minRequiredRamGb(spec.circuit, spec.k)
                if (s.ramGuardEnabled && totalRamGb < neededGb) {
                    val skipped = EntryResult(
                        entry = spec.id,
                        circuit = spec.circuit,
                        k = spec.k,
                        skippedReason = String.format(
                            Locale.US,
                            "Skipped: needs ~%.1f GB RAM (device has %.1f GB)",
                            neededGb,
                            totalRamGb,
                        ),
                    )
                    results.add(skipped)
                    _state.update { it.copy(liveEntries = results.toList()) }
                    continue
                }

                // Cooldown pause between circuits (except before first)
                if (idx > 0 && s.cooldownSec > 0) {
                    for (rem in s.cooldownSec downTo 1) {
                        if (cancelRequested.get()) {
                            wasCancelled = true
                            break
                        }
                        _state.update { it.copy(currentStage = "cooldown ${rem}s") }
                        delay(1000L)
                    }
                    if (wasCancelled) break
                }

                val entry = withContext(Dispatchers.Default) {
                    runSingleEntry(spec, s.backendMode, s.repeats, vulkanInfo.usable)
                }
                if (entry == null) {
                    wasCancelled = true
                    break
                }
                results.add(entry)
                _state.update {
                    it.copy(
                        liveEntries = results.toList(),
                        completedCount = idx + 1,
                    )
                }
            }

            // Release native memory/pool after the suite completes
            withContext(Dispatchers.Default) {
                NativeBridge.call("""{"op":"release"}""")
            }

            timerJob?.cancel()
            BenchService.stop(ctx)

            val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
            val idFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val now = Date()
            val report = BenchmarkReport(
                id = idFmt.format(now),
                timestampIso = isoFmt.format(now),
                preset = s.preset.label,
                backendMode = s.backendMode.label,
                repeats = s.repeats,
                cooldownSec = s.cooldownSec,
                android = androidInfo,
                vulkan = vulkanInfo,
                smoke = smokeReport,
                entries = results.toList(),
                totalElapsedSec = elapsedSec,
                cancelled = wasCancelled,
            )

            if (results.isNotEmpty()) {
                withContext(Dispatchers.IO) { repo.save(report) }
            }
            val saved = withContext(Dispatchers.IO) { repo.listReports() }
            val cacheFile = File(ctx.cacheDir, BenchApplication.PIPELINE_CACHE_FILE)

            _state.update {
                it.copy(
                    isRunning = false,
                    currentEntryLabel = "",
                    currentStage = "",
                    lastReport = report,
                    savedReports = saved,
                    pipelineCacheBytes = if (cacheFile.exists()) cacheFile.length() else 0L,
                    statusBanner = if (wasCancelled) {
                        "Benchmark cancelled (${results.size} completed, saved)"
                    } else {
                        "Benchmark complete (${results.size} circuits saved)"
                    },
                )
            }
        }
    }

    fun cancelBenchmark() {
        cancelRequested.set(true)
        NativeBridge.cancel()
        _state.update { it.copy(currentStage = "cancelling…") }
    }

    fun deleteReport(file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(file) }
            val saved = withContext(Dispatchers.IO) { repo.listReports() }
            _state.update { it.copy(savedReports = saved) }
        }
    }

    fun fileFor(report: BenchmarkReport): File? = repo.fileFor(report)

    private fun runSingleEntry(
        spec: BenchSpec,
        mode: BackendMode,
        repeats: Int,
        vulkanUsable: Boolean,
    ): EntryResult? {
        val thermBefore = DeviceCollector.thermalSnapshot(ctx)

        // 1. Prepare (SRS + keygen + register)
        val prepRaw = NativeBridge.call(
            JSONObject()
                .put("op", "prepare")
                .put("circuit", spec.circuit)
                .put("k", spec.k)
                .toString()
        )
        val prep = runCatching { JSONObject(prepRaw) }.getOrNull()
        if (prep == null || !prep.optBoolean("ok", false)) {
            if (prep?.optBoolean("cancelled", false) == true || cancelRequested.get()) return null
            return EntryResult(
                entry = spec.id,
                circuit = spec.circuit,
                k = spec.k,
                thermalBefore = thermBefore,
                thermalAfter = DeviceCollector.thermalSnapshot(ctx),
                error = prep?.optString("error") ?: "prepare failed",
            )
        }
        val prepareMs = prep.optDouble("total_prepare_ms", 0.0)
        val srsReused = prep.optBoolean("srs_reused", false)
        var peakBytes = prep.optLong("peak_footprint_bytes", 0L)
        var allVerified = true
        var cpuMs: Double? = null
        var eraseOk: Boolean? = null
        val vkTimes = mutableListOf<Double>()
        var lastGpu: GpuBreakdown? = null

        // 2. CPU run
        if (mode == BackendMode.BOTH || mode == BackendMode.CPU_ONLY) {
            if (cancelRequested.get()) return null
            _state.update { it.copy(currentStage = "cpu-prove") }
            val cpuRaw = NativeBridge.call(
                JSONObject()
                    .put("op", "prove")
                    .put("circuit", spec.circuit)
                    .put("k", spec.k)
                    .put("backend", "cpu")
                    .toString()
            )
            val cpuObj = runCatching { JSONObject(cpuRaw) }.getOrNull()
            if (cpuObj == null || !cpuObj.optBoolean("ok", false)) {
                if (cpuObj?.optBoolean("cancelled", false) == true || cancelRequested.get()) return null
                return EntryResult(
                    entry = spec.id,
                    circuit = spec.circuit,
                    k = spec.k,
                    prepareMs = prepareMs,
                    srsReused = srsReused,
                    thermalBefore = thermBefore,
                    thermalAfter = DeviceCollector.thermalSnapshot(ctx),
                    error = "CPU: ${cpuObj?.optString("error") ?: "prove failed"}",
                )
            }
            cpuMs = cpuObj.optDouble("prove_ms")
            allVerified = allVerified && cpuObj.optBoolean("verified", false)
            peakBytes = maxOf(
                peakBytes,
                cpuObj.optJSONObject("memory")?.optLong("prove_peak_footprint_bytes", 0L) ?: 0L,
            )
        }

        // 3. Vulkan runs (1..repeats)
        if ((mode == BackendMode.BOTH || mode == BackendMode.VULKAN_ONLY) && vulkanUsable) {
            for (iter in 1..repeats) {
                if (cancelRequested.get()) return null
                _state.update { it.copy(currentStage = "vulkan #$iter/$repeats") }
                val vkRaw = NativeBridge.call(
                    JSONObject()
                        .put("op", "prove")
                        .put("circuit", spec.circuit)
                        .put("k", spec.k)
                        .put("backend", "vulkan")
                        .toString()
                )
                val vkObj = runCatching { JSONObject(vkRaw) }.getOrNull()
                if (vkObj == null || !vkObj.optBoolean("ok", false)) {
                    if (vkObj?.optBoolean("cancelled", false) == true || cancelRequested.get()) return null
                    return EntryResult(
                        entry = spec.id,
                        circuit = spec.circuit,
                        k = spec.k,
                        prepareMs = prepareMs,
                        srsReused = srsReused,
                        cpuMs = cpuMs,
                        thermalBefore = thermBefore,
                        thermalAfter = DeviceCollector.thermalSnapshot(ctx),
                        error = "Vulkan #$iter: ${vkObj?.optString("error") ?: "prove failed"}",
                    )
                }
                vkTimes.add(vkObj.optDouble("prove_ms"))
                allVerified = allVerified && vkObj.optBoolean("verified", false)
                peakBytes = maxOf(
                    peakBytes,
                    vkObj.optJSONObject("memory")?.optLong("prove_peak_footprint_bytes", 0L) ?: 0L,
                )
                val vk = vkObj.optJSONObject("vulkan")
                val erase = vk?.optJSONObject("erase")
                if (erase != null) {
                    val ok = erase.optBoolean("ok", false)
                    eraseOk = (eraseOk ?: true) && ok
                }
                if (vk != null) {
                    val calls = vk.optJSONObject("calls")
                    val host = vk.optJSONObject("host_times")
                    lastGpu = GpuBreakdown(
                        msmCalls = calls?.optJSONObject("msm")?.optInt("calls", 0) ?: 0,
                        msmMs = calls?.optJSONObject("msm")?.optDouble("device_ms", 0.0) ?: 0.0,
                        quotientCalls = calls?.optJSONObject("quotient")?.optInt("calls", 0) ?: 0,
                        quotientMs = calls?.optJSONObject("quotient")?.optDouble("device_ms", 0.0) ?: 0.0,
                        ifftCalls = calls?.optJSONObject("ifft")?.optInt("calls", 0) ?: 0,
                        ifftMs = calls?.optJSONObject("ifft")?.optDouble("device_ms", 0.0) ?: 0.0,
                        fftCalls = calls?.optJSONObject("fft")?.optInt("calls", 0) ?: 0,
                        fftMs = calls?.optJSONObject("fft")?.optDouble("device_ms", 0.0) ?: 0.0,
                        totalCalls = vk.optInt("total_calls", 0),
                        totalGpuMs = vk.optDouble("total_gpu_ms", 0.0),
                        hostCopyMs = host?.optDouble("copy_ms", 0.0) ?: 0.0,
                        hostRunMs = host?.optDouble("run_ms", 0.0) ?: 0.0,
                        pipelineCreationMs = vk.optDouble("pipeline_total_creation_ms", 0.0),
                    )
                }
            }
        } else if (mode == BackendMode.VULKAN_ONLY && !vulkanUsable) {
            return EntryResult(
                entry = spec.id,
                circuit = spec.circuit,
                k = spec.k,
                prepareMs = prepareMs,
                srsReused = srsReused,
                thermalBefore = thermBefore,
                thermalAfter = DeviceCollector.thermalSnapshot(ctx),
                error = "Vulkan GPU is not usable on this device",
            )
        }

        val vkCold = vkTimes.firstOrNull()
        val vkWarm = if (vkTimes.size > 1) vkTimes.drop(1).minOrNull() else null

        return EntryResult(
            entry = spec.id,
            circuit = spec.circuit,
            k = spec.k,
            prepareMs = prepareMs,
            srsReused = srsReused,
            cpuMs = cpuMs,
            vulkanColdMs = vkCold,
            vulkanWarmMs = vkWarm,
            vulkanAllMs = vkTimes.toList(),
            gpu = lastGpu,
            verified = allVerified,
            eraseOk = eraseOk,
            peakMb = peakBytes / (1024.0 * 1024.0),
            thermalBefore = thermBefore,
            thermalAfter = DeviceCollector.thermalSnapshot(ctx),
        )
    }
}
