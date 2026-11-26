package network.midnight.vulkanbench

import android.app.Application
import android.content.Context
import android.util.Log
import network.midnight.vulkanbench.core.NativeBridge
import java.io.File

class BenchApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arith = prefs.getString(KEY_ARITH_OVERRIDE, "")?.takeIf { it == "32" || it == "64" }
        val cacheFile = File(cacheDir, PIPELINE_CACHE_FILE)
        val loader = if (BuildConfig.USE_TURNIP) openTurnipLoader() else null
        NativeBridge.init(cacheFile.absolutePath, arith, loader)
    }

    private fun openTurnipLoader(): String? {
        val directory = applicationInfo.nativeLibraryDir + File.separator
        return try {
            System.loadLibrary("midnight_turnip")
            if (TurnipLoader.open(directory, directory)) {
                directory + "libmidnight_turnip.so"
            } else {
                Log.w("MidnightBench", "Could not open the app-local Turnip loader")
                null
            }
        } catch (error: LinkageError) {
            Log.w("MidnightBench", "Could not load the Turnip bridge", error)
            null
        }
    }

    companion object {
        const val PREFS_NAME = "midnight_bench_prefs"
        const val KEY_ARITH_OVERRIDE = "arith_override"
        const val PIPELINE_CACHE_FILE = "vulkan-pipeline-cache.bin"
    }
}

internal object TurnipLoader {
    external fun open(nativeDir: String, driverDir: String): Boolean
}
