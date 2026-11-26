package network.midnight.vulkanbench.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import network.midnight.vulkanbench.BuildConfig
import org.json.JSONObject

object DeviceCollector {

    fun androidInfo(context: Context): AndroidDeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val socMfg = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        return AndroidDeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            board = Build.BOARD.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            socManufacturer = socMfg,
            socModel = socModel,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    fun thermalSnapshot(context: Context): ThermalSnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (rawTemp != null && rawTemp != Int.MIN_VALUE) rawTemp / 10f else null
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val code = pm.currentThermalStatus
        val label = when (code) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($code)"
        }
        return ThermalSnapshot(
            batteryTempC = tempC,
            thermalStatus = label,
            thermalStatusCode = code,
            cpuCapPct = readCpuCapPct(),
        )
    }

    private fun readCpuCapPct(): Int? {
        var curSum = 0L
        var maxSum = 0L
        for (i in 0 until 64) {
            val maxF = runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull() ?: continue
            if (maxF <= 0L) continue
            val curF = runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq").readText().trim().toLong()
            }.getOrNull() ?: continue
            maxSum += maxF
            curSum += minOf(curF, maxF)
        }
        if (maxSum <= 0L) return null
        return ((curSum * 100L) / maxSum).toInt().coerceIn(1, 100)
    }

    fun parseVulkanInfo(jsonStr: String): VulkanDeviceInfo {
        val o = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return VulkanDeviceInfo(
            contextError = "Failed to parse device_info JSON"
        )
        val t = o.optJSONObject("thresholds")
        val freqArr = o.optJSONArray("cpu_cluster_max_khz")
        val freqs = buildList {
            if (freqArr != null) {
                for (i in 0 until freqArr.length()) {
                    add(freqArr.optLong(i))
                }
            }
        }
        return VulkanDeviceInfo(
            vulkanBuilt = o.optBoolean("vulkan_built", false),
            present = o.optBoolean("present", false),
            usable = o.optBoolean("usable", false),
            variant = o.optStringOrNull("variant"),
            arithEnv = o.optStringOrNull("arith_env"),
            contextError = o.optStringOrNull("context_error"),
            name = o.optStringOrNull("name"),
            vendorId = if (o.isNull("vendor_id")) null else o.optInt("vendor_id"),
            vendorName = o.optStringOrNull("vendor_name"),
            deviceId = if (o.isNull("device_id")) null else o.optInt("device_id"),
            deviceType = o.optStringOrNull("device_type"),
            apiVersion = o.optStringOrNull("api_version"),
            driver = o.optStringOrNull("driver"),
            unifiedMemory = if (o.isNull("unified_memory")) null else o.optBoolean("unified_memory"),
            deviceLocalHeapBytes = if (o.isNull("device_local_heap_bytes")) null else o.optLong("device_local_heap_bytes"),
            maxComputeSharedMemoryBytes = if (o.isNull("max_compute_shared_memory_bytes")) null else o.optLong("max_compute_shared_memory_bytes"),
            maxComputeWorkGroupInvocations = if (o.isNull("max_compute_work_group_invocations")) null else o.optLong("max_compute_work_group_invocations"),
            subgroupSize = if (o.isNull("subgroup_size")) null else o.optInt("subgroup_size"),
            minSubgroupSize = if (o.isNull("min_subgroup_size")) null else o.optInt("min_subgroup_size"),
            maxSubgroupSize = if (o.isNull("max_subgroup_size")) null else o.optInt("max_subgroup_size"),
            subgroupSizeControl = if (o.isNull("subgroup_size_control")) null else o.optBoolean("subgroup_size_control"),
            computeFullSubgroups = if (o.isNull("compute_full_subgroups")) null else o.optBoolean("compute_full_subgroups"),
            shaderInt64 = if (o.isNull("shader_int64")) null else o.optBoolean("shader_int64"),
            shaderInt16 = if (o.isNull("shader_int16")) null else o.optBoolean("shader_int16"),
            bufferDeviceAddress = if (o.isNull("buffer_device_address")) null else o.optBoolean("buffer_device_address"),
            cpuCores = if (o.isNull("cpu_cores")) null else o.optInt("cpu_cores"),
            cpuThreads = if (o.isNull("cpu_threads")) null else o.optInt("cpu_threads"),
            cpuClusterMaxKhz = freqs,
            thresholdMsmTable = t?.optInt("msm_table"),
            thresholdFft = t?.optInt("fft"),
            thresholdIfft = t?.optInt("ifft"),
            thresholdQuotient = t?.optInt("quotient_ext"),
            proverRev = o.optString("prover_rev", ""),
        )
    }

    fun parseSmoke(jsonStr: String): SmokeReport {
        val o = runCatching { JSONObject(jsonStr) }.getOrNull()
            ?: return SmokeReport(ok = false, error = "Invalid smoke JSON")
        val arr = o.optJSONArray("steps")
        val steps = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    add(
                        SmokeStepResult(
                            name = s.optString("name", "step"),
                            ok = s.optBoolean("ok", false),
                            ms = s.optDouble("ms", 0.0),
                            error = s.optStringOrNull("error"),
                        )
                    )
                }
            }
        }
        return SmokeReport(
            ok = o.optBoolean("ok", false),
            device = o.optStringOrNull("device"),
            variant = o.optStringOrNull("variant"),
            error = o.optStringOrNull("error"),
            steps = steps,
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
