package network.midnight.vulkanbench

import android.app.Application
import android.content.Context
import network.midnight.vulkanbench.core.NativeBridge
import java.io.File

class BenchApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arith = prefs.getString(KEY_ARITH_OVERRIDE, "")?.takeIf { it == "32" || it == "64" }
        val cacheFile = File(cacheDir, PIPELINE_CACHE_FILE)
        NativeBridge.init(cacheFile.absolutePath, arith)
    }

    companion object {
        const val PREFS_NAME = "midnight_bench_prefs"
        const val KEY_ARITH_OVERRIDE = "arith_override"
        const val PIPELINE_CACHE_FILE = "vulkan-pipeline-cache.bin"
    }
}
