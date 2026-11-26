package network.midnight.vulkanbench

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.midnight.vulkanbench.core.BenchmarkReport
import network.midnight.vulkanbench.core.ReportFormatter
import network.midnight.vulkanbench.ui.BenchApp
import network.midnight.vulkanbench.ui.BenchViewModel
import network.midnight.vulkanbench.ui.MidnightBenchTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: BenchViewModel by viewModels()

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional notification permission on Android 13+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val uiState by vm.state.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.isRunning, uiState.isSmoking) {
                if (uiState.isRunning || uiState.isSmoking) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MidnightBenchTheme {
                BenchApp(
                    state = uiState,
                    vm = vm,
                    onShareJson = ::shareReportJson,
                    onCopyMarkdown = ::copyMarkdown,
                    onRestartApp = ::restartApp,
                )
            }
        }
    }

    private fun shareReportJson(file: File, report: BenchmarkReport) {
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "Midnight Vulkan Bench — ${report.android.manufacturer} ${report.android.model}",
            )
            putExtra(Intent.EXTRA_TEXT, ReportFormatter.toMarkdown(report))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Benchmark Report"))
    }

    private fun copyMarkdown(report: BenchmarkReport) {
        val md = ReportFormatter.toMarkdown(report)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Midnight Vulkan Benchmark", md))
        Toast.makeText(this, "Copied Markdown report to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent != null) {
            startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
    }
}
