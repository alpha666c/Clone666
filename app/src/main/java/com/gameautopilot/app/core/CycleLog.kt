package com.gameautopilot.app.core

import android.content.Context
import com.gameautopilot.app.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Append-only JSONL log of decision cycles, one file per calendar day,
 * rotated automatically. Used for offline debugging / replay.
 * Capped at 7 days of retained logs.
 */
class CycleLog(context: Context) {

    private val dir = File(context.filesDir, "cycles").apply { mkdirs() }
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val enabled = AtomicBoolean(true)

    fun setEnabled(on: Boolean) {
        enabled.set(on)
    }

    fun write(entry: CycleEntry) {
        if (!enabled.get()) return
        val file = File(dir, "${dateFmt.format(Date(entry.timestampMs))}.log")
        try {
            FileWriter(file, true).use { w ->
                w.appendLine(entry.toJson().toString())
            }
            pruneIfNeeded()
        } catch (t: Throwable) {
            Logger.w("CycleLog write failed: ${t.message}")
        }
    }

    private fun pruneIfNeeded() {
        val files = dir.listFiles()?.sortedByDescending { it.name }.orEmpty()
        if (files.size <= MAX_DAYS) return
        files.drop(MAX_DAYS).forEach { runCatching { it.delete() } }
    }

    data class CycleEntry(
        val timestampMs: Long,
        val gamePackage: String,
        val foregroundPackage: String?,
        val hash: Long,
        val deltaSincePrev: Int,
        val markCount: Int,
        val thought: String,
        val actions: List<String>,
        val dispatchOk: List<Boolean>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", timestampMs)
            put("game", gamePackage)
            put("fg", foregroundPackage)
            put("hash", hash)
            put("delta", deltaSincePrev)
            put("marks", markCount)
            put("thought", thought)
            put("actions", JSONArray(actions))
            put("ok", JSONArray(dispatchOk))
        }
    }

    companion object {
        private const val MAX_DAYS = 7
    }
}
