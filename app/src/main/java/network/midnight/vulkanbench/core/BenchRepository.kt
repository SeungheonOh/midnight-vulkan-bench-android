package network.midnight.vulkanbench.core

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class BenchRepository(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val reportsDir: File
        get() = File(context.filesDir, "reports").apply { mkdirs() }

    fun save(report: BenchmarkReport): File {
        val safeModel = report.android.model
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "android" }
        val file = File(reportsDir, "midnight-bench-${safeModel}-${report.id}.json")
        file.writeText(json.encodeToString(report))
        return file
    }

    fun listReports(): List<Pair<File, BenchmarkReport>> {
        val files = reportsDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { f to json.decodeFromString<BenchmarkReport>(f.readText()) }.getOrNull()
        }
    }

    fun delete(file: File) {
        file.delete()
    }

    fun fileFor(report: BenchmarkReport): File? =
        listReports().firstOrNull { it.second.id == report.id }?.first
}
