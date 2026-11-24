package com.whispercppdemo.data

import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Entry(val time: LocalDateTime, val text: String)

/**
 * Transcriptions as one JSON file each — the same schema the desktop voice2clip writes
 * (timestamp, transcription, duration_seconds, ...), so the folders can be synced/merged.
 */
class HistoryStore(private val dir: File) {
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun load(): List<Entry> {
        dir.mkdirs()
        return (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    Entry(LocalDateTime.parse(o.getString("timestamp").take(26)), o.getString("transcription"))
                }.getOrNull()
            }
            .sortedByDescending { it.time }
    }

    fun save(text: String, seconds: Float, model: String): Entry {
        dir.mkdirs()
        val now = LocalDateTime.now()
        val o = JSONObject()
            .put("timestamp", now.toString())
            .put("transcription", text)
            .put("duration_seconds", seconds.toDouble())
            .put("audio_device", JSONObject.NULL)
            .put("model_path", model)
            .put("source", "android")
        File(dir, "transcription_${now.format(fileStamp)}.json").writeText(o.toString(2))
        return Entry(now, text)
    }
}
