package network.midnight.vulkanbench.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.midnight.vulkanbench.core.BackendMode
import network.midnight.vulkanbench.core.BenchPreset
import network.midnight.vulkanbench.core.BenchSpec
import network.midnight.vulkanbench.core.BenchmarkReport
import network.midnight.vulkanbench.core.EntryResult
import network.midnight.vulkanbench.core.SmokeReport
import java.io.File
import java.util.Locale

@Composable
fun BenchApp(
    state: BenchUiState,
    vm: BenchViewModel,
    onShareJson: (File, BenchmarkReport) -> Unit,
    onCopyMarkdown: (BenchmarkReport) -> Unit,
    onRestartApp: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = BenchColors.Bg,
        bottomBar = {
            NavigationBar(containerColor = BenchColors.Surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    label = { Text("Benchmark") },
                    colors = navItemColors(),
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Build, contentDescription = null) },
                    label = { Text("Device") },
                    colors = navItemColors(),
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                    label = { Text("Reports (${state.savedReports.size})") },
                    colors = navItemColors(),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HeaderBar(state)
            if (state.statusBanner != null) {
                Surface(
                    color = BenchColors.SurfaceAlt,
                    border = BorderStroke(1.dp, BenchColors.Primary.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.statusBanner,
                            color = BenchColors.TextPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { vm.dismissBanner() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text("OK", fontSize = 12.sp)
                        }
                    }
                }
            }

            when (tab) {
                0 -> BenchmarkTab(state, vm, onShareJson, onCopyMarkdown)
                1 -> DeviceTab(state, vm, onRestartApp)
                2 -> ReportsTab(state, vm, onShareJson, onCopyMarkdown)
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = BenchColors.Primary,
    selectedTextColor = BenchColors.Primary,
    indicatorColor = BenchColors.Primary.copy(alpha = 0.16f),
    unselectedIconColor = BenchColors.TextMuted,
    unselectedTextColor = BenchColors.TextMuted,
)

@Composable
private fun HeaderBar(state: BenchUiState) {
    val gpu = state.vulkanInfo
    val statusColor = when {
        gpu == null -> BenchColors.TextMuted
        gpu.usable -> BenchColors.Green
        gpu.present -> BenchColors.Amber
        else -> BenchColors.Red
    }
    val statusLabel = when {
        gpu == null -> "Probing GPU…"
        gpu.usable -> "${gpu.name ?: "Vulkan GPU"} · ${gpu.variant ?: "?"}"
        gpu.present -> "${gpu.name ?: "GPU"} (not usable)"
        else -> "No compatible Vulkan GPU"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BenchColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Midnight Vulkan Bench",
                color = BenchColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    color = BenchColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        state.thermal?.let { t ->
            val thermColor = when {
                t.thermalStatusCode >= 3 -> BenchColors.Red
                t.thermalStatusCode >= 2 -> BenchColors.Amber
                else -> BenchColors.Cyan
            }
            Surface(
                color = thermColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, thermColor.copy(alpha = 0.4f)),
            ) {
                Text(
                    text = buildString {
                        t.batteryTempC?.let { append(String.format(Locale.US, "%.1f°C ", it)) }
                        append(t.thermalStatus)
                    },
                    color = thermColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchmarkTab(
    state: BenchUiState,
    vm: BenchViewModel,
    onShareJson: (File, BenchmarkReport) -> Unit,
    onCopyMarkdown: (BenchmarkReport) -> Unit,
) {
    val displayEntries = if (state.isRunning || state.liveEntries.isNotEmpty()) {
        state.liveEntries
    } else {
        state.lastReport?.entries.orEmpty()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Active run banner
        if (state.isRunning) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BenchColors.Surface),
                    border = BorderStroke(1.dp, BenchColors.Primary),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = BenchColors.Cyan,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "${state.currentEntryLabel}  (${state.completedCount}/${state.totalCount})",
                                color = BenchColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatDuration(state.elapsedSec),
                                style = MonoStyle,
                                color = BenchColors.Cyan,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Stage: ${state.currentStage.ifBlank { "working…" }} · Screen kept on",
                            color = BenchColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        val progress = if (state.totalCount > 0) {
                            state.completedCount.toFloat() / state.totalCount.toFloat()
                        } else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = BenchColors.Primary,
                            trackColor = BenchColors.SurfaceAlt,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.cancelBenchmark() },
                            border = BorderStroke(1.dp, BenchColors.Red),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BenchColors.Red),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel After Current Step")
                        }
                    }
                }
            }
        }

        // Configuration card
        item {
            SectionCard(title = "Benchmark Suite") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BenchPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = state.preset == preset,
                            onClick = { if (!state.isRunning) vm.selectPreset(preset) },
                            label = { Text(preset.label, fontSize = 13.sp) },
                            colors = chipColors(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.preset.subtitle,
                    color = BenchColors.TextSecondary,
                    fontSize = 12.sp,
                )

                if (state.preset == BenchPreset.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    CustomMatrixPicker(state, vm)
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = BenchColors.Border)
                Spacer(Modifier.height(12.dp))

                Text("Backend", color = BenchColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.backendMode == mode,
                            onClick = { if (!state.isRunning) vm.selectBackendMode(mode) },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            colors = chipColors(),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vulkan repeats", color = BenchColors.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 2, 3).forEach { r ->
                                FilterChip(
                                    selected = state.repeats == r,
                                    onClick = { if (!state.isRunning) vm.setRepeats(r) },
                                    label = { Text("${r}x", fontSize = 12.sp) },
                                    colors = chipColors(),
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stage cooldown", color = BenchColors.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0, 10, 15, 30).forEach { c ->
                                FilterChip(
                                    selected = state.cooldownSec == c,
                                    onClick = { if (!state.isRunning) vm.setCooldownSec(c) },
                                    label = { Text("${c}s", fontSize = 11.sp) },
                                    colors = chipColors(),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("RAM safety guard", color = BenchColors.TextPrimary, fontSize = 13.sp)
                        Text(
                            "Auto-skip k18–k20 on low-RAM phones to prevent OOM kills",
                            color = BenchColors.TextMuted,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = state.ramGuardEnabled,
                        onCheckedChange = { if (!state.isRunning) vm.setRamGuard(it) },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pre-run GPU smoke check", color = BenchColors.TextPrimary, fontSize = 13.sp)
                        Text(
                            "Verify buffer fill, FFT k15, and MSM k15 against CPU first",
                            color = BenchColors.TextMuted,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = state.runSmokeBefore,
                        onCheckedChange = { if (!state.isRunning) vm.setRunSmokeBefore(it) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                val count = vm.activeSpecs().size
                Button(
                    onClick = { vm.startBenchmark() },
                    enabled = !state.isRunning && !state.isSmoking && count > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BenchColors.Primary,
                        contentColor = BenchColors.Bg,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (state.isRunning) "Benchmark Running…" else "Run $count Circuits",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        // Export action bar if a completed report exists
        val report = state.lastReport
        if (report != null && !state.isRunning) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { onCopyMarkdown(report) },
                        border = BorderStroke(1.dp, BenchColors.Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BenchColors.Cyan),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Markdown", fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            vm.fileFor(report)?.let { onShareJson(it, report) }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BenchColors.Cyan,
                            contentColor = BenchColors.Bg,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share JSON", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Live / latest results
        if (displayEntries.isNotEmpty()) {
            item {
                Text(
                    text = if (state.isRunning) "Live Results" else "Latest Run Results",
                    color = BenchColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(displayEntries, key = { it.entry }) { entry ->
                EntryResultCard(entry)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomMatrixPicker(state: BenchUiState, vm: BenchViewModel) {
    val groups = listOf(
        "Squaring Chain (MSM-heavy)" to ("chain" to listOf(14, 15, 16, 17, 18, 19, 20)),
        "Poseidon Chain (ZkStdLib dense)" to ("poseidon-chain" to listOf(14, 15, 16, 17, 18)),
        "Poseidon Preimage (ZkStdLib sparse)" to ("poseidon-preimage" to listOf(14, 15, 16, 17)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (title, pair) ->
            val (cid, ks) = pair
            Text(title, color = BenchColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ks.forEach { k ->
                    val spec = BenchSpec(cid, k)
                    FilterChip(
                        selected = spec in state.customSpecs,
                        onClick = { if (!state.isRunning) vm.toggleCustomSpec(spec) },
                        label = { Text("k$k", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryResultCard(e: EntryResult) {
    val borderColor = when {
        e.error != null -> BenchColors.Red.copy(alpha = 0.6f)
        e.skippedReason != null -> BenchColors.Border
        !e.verified || e.eraseOk == false -> BenchColors.Red
        else -> BenchColors.Border
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = BenchColors.Surface),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = e.entry,
                    color = BenchColors.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (e.speedup != null) {
                    val spdColor = if (e.speedup!! >= 1.0) BenchColors.Green else BenchColors.Amber
                    Surface(
                        color = spdColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.2fx", e.speedup),
                            color = spdColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (e.skippedReason != null) {
                Spacer(Modifier.height(4.dp))
                Text(e.skippedReason, color = BenchColors.Amber, fontSize = 12.sp)
                return@Column
            }

            if (e.error != null) {
                Spacer(Modifier.height(4.dp))
                Text("Error: ${e.error}", color = BenchColors.Red, fontSize = 12.sp)
                return@Column
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricCell("CPU", e.cpuMs?.let { fmtMs(it) } ?: "—")
                MetricCell("Vulkan #1", e.vulkanColdMs?.let { fmtMs(it) } ?: "—")
                MetricCell("Vulkan warm", e.vulkanWarmMs?.let { fmtMs(it) } ?: "—", BenchColors.Cyan)
                MetricCell("Peak RAM", String.format(Locale.US, "%.0f MB", e.peakMb))
            }

            e.gpu?.let { g ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "GPU %.1f ms (MSM %.1f · Quot %.1f · iFFT %.1f) · Calls %d · Host %.1f ms",
                        g.totalGpuMs,
                        g.msmMs,
                        g.quotientMs,
                        g.ifftMs,
                        g.totalCalls,
                        g.hostRunMs,
                    ),
                    color = BenchColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusTag("Ver", e.verified)
                e.eraseOk?.let { StatusTag("Erase", it) }
                e.thermalAfter?.batteryTempC?.let { temp ->
                    Text(
                        text = String.format(Locale.US, "%.1f°C %s", temp, e.thermalAfter.thermalStatus),
                        color = BenchColors.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, valueColor: Color = BenchColors.TextPrimary) {
    Column {
        Text(label, color = BenchColors.TextMuted, fontSize = 11.sp)
        Text(
            text = value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StatusTag(label: String, ok: Boolean) {
    val c = if (ok) BenchColors.Green else BenchColors.Red
    Text(
        text = "$label: ${if (ok) "OK" else "FAIL"}",
        color = c,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceTab(
    state: BenchUiState,
    vm: BenchViewModel,
    onRestartApp: () -> Unit,
) {
    val a = state.androidInfo
    val v = state.vulkanInfo

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Android Hardware") {
                if (a == null) {
                    Text("Loading…", color = BenchColors.TextMuted)
                } else {
                    val ramGb = a.totalRamBytes / (1024.0 * 1024.0 * 1024.0)
                    val availGb = a.availRamBytes / (1024.0 * 1024.0 * 1024.0)
                    val soc = listOf(a.socManufacturer, a.socModel).filter { it.isNotBlank() }
                        .joinToString(" ").ifBlank { a.hardware }
                    SpecRow("Device", "${a.manufacturer} ${a.model} (${a.device})")
                    SpecRow("SoC / Board", "$soc / ${a.board}")
                    SpecRow("Android", "${a.androidRelease} (API ${a.sdkInt})")
                    SpecRow("CPU Cores", "${v?.cpuCores ?: "?"} (rayon=${v?.cpuThreads ?: "?"})")
                    if (v != null && v.cpuClusterMaxKhz.isNotEmpty()) {
                        val clusters = v.cpuClusterMaxKhz.groupingBy { it }.eachCount()
                            .entries.sortedByDescending { it.key }
                            .joinToString(" + ") { (khz, cnt) ->
                                String.format(Locale.US, "%dx %.2f GHz", cnt, khz / 1_000_000.0)
                            }
                        SpecRow("CPU Max Clocks", clusters)
                    }
                    SpecRow("RAM", String.format(Locale.US, "%.1f GB total · %.1f GB avail", ramGb, availGb))
                    SpecRow("ABIs", a.abis.joinToString(", "))
                }
            }
        }

        item {
            SectionCard(title = "Vulkan GPU Capabilities") {
                if (v == null) {
                    Text("Probing Vulkan…", color = BenchColors.TextMuted)
                } else {
                    SpecRow("Usable", if (v.usable) "YES" else "NO (${v.contextError ?: "unavailable"})")
                    SpecRow("GPU", v.name ?: "None")
                    SpecRow(
                        "Vendor / ID",
                        "${v.vendorName ?: "?"} (${v.vendorId?.let { "0x%04x".format(it) } ?: "-"})",
                    )
                    SpecRow("API Version", v.apiVersion ?: "-")
                    SpecRow("Driver", v.driver?.replace("\n", " / ") ?: "-")
                    SpecRow("Arith Variant", "${v.variant ?: "none"} (override=${v.arithEnv ?: "auto"})")
                    SpecRow("Int64 / Int16", "int64=${v.shaderInt64} · int16=${v.shaderInt16}")
                    SpecRow(
                        "Subgroup",
                        "${v.subgroupSize} (min=${v.minSubgroupSize}, max=${v.maxSubgroupSize}, full=${v.computeFullSubgroups})",
                    )
                    SpecRow(
                        "Shared Memory",
                        "${(v.maxComputeSharedMemoryBytes ?: 0) / 1024} KiB · maxWG=${v.maxComputeWorkGroupInvocations}",
                    )
                    SpecRow(
                        "Memory",
                        "unified=${v.unifiedMemory} · BDA=${v.bufferDeviceAddress}",
                    )
                    SpecRow(
                        "Thresholds",
                        "msm=${v.thresholdMsmTable} fft=${v.thresholdFft} ifft=${v.thresholdIfft} quot=${v.thresholdQuotient}",
                    )
                    SpecRow("Prover Rev", v.proverRev.take(12))
                }
            }
        }

        item {
            SectionCard(title = "GPU Kernel Smoke Test") {
                Text(
                    "Tests Vulkan buffer fill/readback, FFT (k=15), and MSM (k=15) bit-exact against CPU. Useful on untested Mali / Xclipse / PowerVR drivers.",
                    color = BenchColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.runSmokeOnly() },
                    enabled = !state.isRunning && !state.isSmoking && state.vulkanInfo?.usable == true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BenchColors.Cyan,
                        contentColor = BenchColors.Bg,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isSmoking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = BenchColors.Bg,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Running ${state.currentStage}…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Run GPU Smoke Test", fontWeight = FontWeight.Bold)
                    }
                }
                state.smokeReport?.let { smoke ->
                    Spacer(Modifier.height(10.dp))
                    SmokeResultView(smoke)
                }
            }
        }

        item {
            SectionCard(title = "Driver & Arithmetic Overrides") {
                Text(
                    "Force 32-bit shaders if a mobile driver miscompiles 64-bit/16-bit integer shaders. Changing this requires restarting the app.",
                    color = BenchColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "Auto", "32" to "Force 32-bit", "64" to "Force 64-bit").forEach { (k, label) ->
                        FilterChip(
                            selected = state.arithPreference == k,
                            onClick = { vm.setArithPreference(k) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = chipColors(),
                        )
                    }
                }
                val needsRestart = (state.arithPreference.ifBlank { null }) != state.vulkanInfo?.arithEnv
                if (needsRestart) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRestartApp,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BenchColors.Amber,
                            contentColor = BenchColors.Bg,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply & Restart App", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Pipeline cache: %.1f KB",
                            state.pipelineCacheBytes / 1024.0,
                        ),
                        color = BenchColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { vm.clearPipelineCache() }) {
                        Text("Clear Cache", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmokeResultView(smoke: SmokeReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (smoke.ok) "ALL SMOKE CHECKS PASSED" else "SMOKE FAILED: ${smoke.error ?: ""}",
            color = if (smoke.ok) BenchColors.Green else BenchColors.Red,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        smoke.steps.forEach { st ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (st.ok) "PASS" else "FAIL",
                    color = if (st.ok) BenchColors.Green else BenchColors.Red,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.width(42.dp),
                )
                Text(
                    text = st.name,
                    color = BenchColors.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = fmtMs(st.ms),
                    color = BenchColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (!st.error.isNullOrBlank()) {
                Text(st.error, color = BenchColors.Red, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ReportsTab(
    state: BenchUiState,
    vm: BenchViewModel,
    onShareJson: (File, BenchmarkReport) -> Unit,
    onCopyMarkdown: (BenchmarkReport) -> Unit,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }

    if (state.savedReports.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No saved benchmark reports yet", color = BenchColors.TextSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Run a suite on the Benchmark tab to save & export JSON/Markdown reports.",
                    color = BenchColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.savedReports, key = { it.second.id }) { (file, report) ->
            val isExpanded = expandedId == report.id
            Card(
                colors = CardDefaults.cardColors(containerColor = BenchColors.Surface),
                border = BorderStroke(1.dp, BenchColors.Border),
                shape = RoundedCornerShape(12.dp),
                onClick = { expandedId = if (isExpanded) null else report.id },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${report.preset} · ${report.entries.size} circuits",
                                color = BenchColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = "${report.timestampIso} · ${report.vulkan.name ?: "CPU"} (${report.vulkan.variant ?: "-"})",
                                color = BenchColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        IconButton(onClick = { onCopyMarkdown(report) }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Copy Markdown", tint = BenchColors.Cyan)
                        }
                        IconButton(onClick = { onShareJson(file, report) }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share JSON", tint = BenchColors.Primary)
                        }
                        IconButton(onClick = { vm.deleteReport(file) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = BenchColors.TextMuted)
                        }
                    }

                    if (isExpanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = BenchColors.Border)
                        Spacer(Modifier.height(8.dp))
                        report.entries.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = e.entry,
                                    color = BenchColors.TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = when {
                                        e.skippedReason != null -> "skipped"
                                        e.error != null -> "ERROR"
                                        else -> buildString {
                                            e.cpuMs?.let { append("CPU ${fmtMs(it)}  ") }
                                            e.bestVulkanMs?.let { append("VK ${fmtMs(it)}") }
                                            e.speedup?.let { append(String.format(Locale.US, " (%.2fx)", it)) }
                                        }
                                    },
                                    color = if (e.error != null) BenchColors.Red else BenchColors.Cyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BenchColors.Surface),
        border = BorderStroke(1.dp, BenchColors.Border),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                color = BenchColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = BenchColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            color = BenchColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = BenchColors.Primary.copy(alpha = 0.22f),
    selectedLabelColor = BenchColors.Primary,
    containerColor = BenchColors.SurfaceAlt,
    labelColor = BenchColors.TextSecondary,
)

private fun fmtMs(ms: Double): String =
    if (ms >= 1000.0) String.format(Locale.US, "%.2fs", ms / 1000.0)
    else String.format(Locale.US, "%.0fms", ms)

private fun formatDuration(sec: Int): String =
    String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
