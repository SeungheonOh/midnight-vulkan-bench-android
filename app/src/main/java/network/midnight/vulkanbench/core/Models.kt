package network.midnight.vulkanbench.core

import kotlinx.serialization.Serializable

@Serializable
data class BenchSpec(
    val circuit: String,
    val k: Int,
) {
    val id: String get() = "$circuit-k$k"
}

enum class BenchPreset(val label: String, val subtitle: String, val specs: List<BenchSpec>) {
    QUICK(
        label = "Quick",
        subtitle = "~1–2 min · k15–k17 core comparison",
        specs = listOf(
            BenchSpec("chain", 15),
            BenchSpec("chain", 16),
            BenchSpec("chain", 17),
            BenchSpec("poseidon-chain", 15),
            BenchSpec("poseidon-preimage", 15),
        ),
    ),
    STANDARD(
        label = "Standard",
        subtitle = "~3–5 min · k14–k18 multi-circuit suite",
        specs = listOf(
            BenchSpec("chain", 14),
            BenchSpec("chain", 15),
            BenchSpec("chain", 16),
            BenchSpec("chain", 17),
            BenchSpec("chain", 18),
            BenchSpec("poseidon-chain", 15),
            BenchSpec("poseidon-chain", 16),
            BenchSpec("poseidon-preimage", 15),
            BenchSpec("poseidon-preimage", 16),
        ),
    ),
    HEAVY(
        label = "Heavy",
        subtitle = "~8–15 min · adds k19–k20 & Poseidon k17–k18",
        specs = listOf(
            BenchSpec("chain", 15),
            BenchSpec("chain", 16),
            BenchSpec("chain", 17),
            BenchSpec("chain", 18),
            BenchSpec("chain", 19),
            BenchSpec("chain", 20),
            BenchSpec("poseidon-chain", 15),
            BenchSpec("poseidon-chain", 16),
            BenchSpec("poseidon-chain", 17),
            BenchSpec("poseidon-chain", 18),
            BenchSpec("poseidon-preimage", 15),
            BenchSpec("poseidon-preimage", 16),
            BenchSpec("poseidon-preimage", 17),
        ),
    ),
    CUSTOM(
        label = "Custom",
        subtitle = "Pick specific circuits & k sizes",
        specs = emptyList(),
    ),
}

enum class BackendMode(val label: String) {
    BOTH("CPU vs Vulkan"),
    VULKAN_ONLY("Vulkan Only"),
    CPU_ONLY("CPU Only"),
}

@Serializable
data class AndroidDeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val socManufacturer: String,
    val socModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val appVersion: String,
)

@Serializable
data class ThermalSnapshot(
    val batteryTempC: Float?,
    val thermalStatus: String,
    val thermalStatusCode: Int,
)

@Serializable
data class VulkanDeviceInfo(
    val vulkanBuilt: Boolean = false,
    val present: Boolean = false,
    val usable: Boolean = false,
    val variant: String? = null,
    val arithEnv: String? = null,
    val contextError: String? = null,
    val name: String? = null,
    val vendorId: Int? = null,
    val vendorName: String? = null,
    val deviceId: Int? = null,
    val deviceType: String? = null,
    val apiVersion: String? = null,
    val driver: String? = null,
    val unifiedMemory: Boolean? = null,
    val deviceLocalHeapBytes: Long? = null,
    val maxComputeSharedMemoryBytes: Long? = null,
    val maxComputeWorkGroupInvocations: Long? = null,
    val subgroupSize: Int? = null,
    val minSubgroupSize: Int? = null,
    val maxSubgroupSize: Int? = null,
    val subgroupSizeControl: Boolean? = null,
    val computeFullSubgroups: Boolean? = null,
    val shaderInt64: Boolean? = null,
    val shaderInt16: Boolean? = null,
    val bufferDeviceAddress: Boolean? = null,
    val cpuCores: Int? = null,
    val cpuThreads: Int? = null,
    val cpuClusterMaxKhz: List<Long> = emptyList(),
    val thresholdMsmTable: Int? = null,
    val thresholdFft: Int? = null,
    val thresholdIfft: Int? = null,
    val thresholdQuotient: Int? = null,
    val proverRev: String = "",
)

@Serializable
data class SmokeStepResult(
    val name: String,
    val ok: Boolean,
    val ms: Double,
    val error: String? = null,
)

@Serializable
data class SmokeReport(
    val ok: Boolean,
    val device: String? = null,
    val variant: String? = null,
    val error: String? = null,
    val steps: List<SmokeStepResult> = emptyList(),
)

@Serializable
data class GpuBreakdown(
    val msmCalls: Int = 0,
    val msmMs: Double = 0.0,
    val quotientCalls: Int = 0,
    val quotientMs: Double = 0.0,
    val ifftCalls: Int = 0,
    val ifftMs: Double = 0.0,
    val fftCalls: Int = 0,
    val fftMs: Double = 0.0,
    val totalCalls: Int = 0,
    val totalGpuMs: Double = 0.0,
    val hostCopyMs: Double = 0.0,
    val hostRunMs: Double = 0.0,
    val pipelineCreationMs: Double = 0.0,
)

@Serializable
data class EntryResult(
    val entry: String,
    val circuit: String,
    val k: Int,
    val prepareMs: Double = 0.0,
    val srsReused: Boolean = false,
    val cpuMs: Double? = null,
    val vulkanColdMs: Double? = null,
    val vulkanWarmMs: Double? = null,
    val vulkanAllMs: List<Double> = emptyList(),
    val gpu: GpuBreakdown? = null,
    val verified: Boolean = false,
    val eraseOk: Boolean? = null,
    val peakMb: Double = 0.0,
    val thermalBefore: ThermalSnapshot? = null,
    val thermalAfter: ThermalSnapshot? = null,
    val skippedReason: String? = null,
    val error: String? = null,
) {
    val bestVulkanMs: Double? get() = vulkanWarmMs ?: vulkanColdMs
    val speedup: Double?
        get() {
            val c = cpuMs ?: return null
            val v = bestVulkanMs ?: return null
            return if (v > 0.0) c / v else null
        }
}

@Serializable
data class BenchmarkReport(
    val id: String,
    val timestampIso: String,
    val preset: String,
    val backendMode: String,
    val repeats: Int,
    val cooldownSec: Int,
    val android: AndroidDeviceInfo,
    val vulkan: VulkanDeviceInfo,
    val smoke: SmokeReport? = null,
    val entries: List<EntryResult>,
    val totalElapsedSec: Double,
    val cancelled: Boolean = false,
)

/** Estimated minimum RAM (in GB) required to safely run a circuit of size `k`. */
fun minRequiredRamGb(circuit: String, k: Int): Double = when (circuit) {
    "chain" -> when {
        k >= 20 -> 8.0
        k == 19 -> 5.5
        k == 18 -> 3.5
        else -> 2.0
    }
    else -> when {
        k >= 18 -> 6.0
        k == 17 -> 4.0
        else -> 2.0
    }
}
