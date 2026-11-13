package kr.sehotech.foodcourtcaller.server

import android.util.Log
import kr.sehotech.foodcourtcaller.common.NumberRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 방송 기록. 앱 내부 파일에 JSON Lines(한 줄에 JSON 하나)로 남기고, 7일이 지난 기록은 지운다.
 */
class CallLog(dir: File) {
    data class Entry(
        val time: Long,
        val store: String,
        val number: String?,
        val source: String,
        val device: String,
        val test: Boolean,
        /** played / duplicate / dropped / error / restart 등 */
        val result: String,
        val note: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("time", time)
            put("store", store)
            if (number != null) put("number", number)
            put("source", source)
            put("device", device)
            put("test", test)
            put("result", result)
            if (note.isNotEmpty()) put("note", note)
        }

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                time = o.optLong("time"),
                store = o.optString("store"),
                number = if (o.has("number")) o.optString("number") else null,
                source = o.optString("source"),
                device = o.optString("device"),
                test = o.optBoolean("test"),
                result = o.optString("result"),
                note = o.optString("note", ""),
            )
        }
    }

    companion object {
        private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        private const val MEMORY_MAX = 200
    }

    private val file = File(dir, "calls.jsonl")
    private val recent = ArrayDeque<Entry>()

    init {
        load()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    recent.addLast(Entry.fromJson(JSONObject(line)))
                    if (recent.size > MEMORY_MAX) recent.removeFirst()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w("CallLog", "기록 읽기 실패", e)
        }
    }

    @Synchronized
    fun add(e: Entry) {
        recent.addLast(e)
        if (recent.size > MEMORY_MAX) recent.removeFirst()
        try {
            file.appendText(e.toJson().toString() + "\n", Charsets.UTF_8)
        } catch (ex: Exception) {
            Log.w("CallLog", "기록 쓰기 실패", ex)
        }
    }

    /** 최근 기록(최신이 앞). store를 주면 그 매장만 */
    @Synchronized
    fun recent(limit: Int, store: String? = null): List<Entry> =
        recent.reversed()
            .filter { store == null || NumberRules.sameStore(it.store, store) }
            .take(limit.coerceIn(1, MEMORY_MAX))

    /** 7일 지난 기록 삭제 */
    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        try {
            if (!file.exists()) return
            val keep = file.readLines(Charsets.UTF_8).filter { line ->
                try {
                    now - JSONObject(line).optLong("time") < KEEP_MS
                } catch (_: Exception) {
                    false
                }
            }
            file.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            recent.removeAll { now - it.time >= KEEP_MS }
        } catch (e: Exception) {
            Log.w("CallLog", "기록 정리 실패", e)
        }
    }

    fun toJsonArray(entries: List<Entry>): JSONArray = JSONArray().apply { entries.forEach { put(it.toJson()) } }
}
