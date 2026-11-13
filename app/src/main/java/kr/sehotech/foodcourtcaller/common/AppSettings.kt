package kr.sehotech.foodcourtcaller.common

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kr.sehotech.foodcourtcaller.terminal.KeyCodes

/**
 * 설정 저장·읽기(SharedPreferences). 비밀 정보(와이파이 비밀번호 등)는 저장하지 않는다.
 * 앱을 덮어 설치해도 값이 남는다.
 */
class AppSettings(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        const val ROLE_TERMINAL = "terminal" // 매장용(태블릿)
        const val ROLE_SERVER = "server"     // 방송용(앰프 옆 폰)

        val DEFAULT_TRIGGER_KEYS = setOf(KeyCodes.NUMPAD_SUBTRACT, KeyCodes.MINUS)
    }

    private fun str(key: String, def: String): String = p.getString(key, def) ?: def
    private fun putStr(key: String, v: String) = p.edit().putString(key, v).apply()
    private fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()
    private fun putBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    // ───────── 공통 ─────────

    var role: String?
        get() = p.getString("role", null)
        set(v) = p.edit().putString("role", v).apply()

    // ───────── 매장용 ─────────

    var storeName: String
        get() = str("storeName", "하즈벤")
        set(v) = putStr("storeName", v.trim())

    /** 이 기기 이름(기록용). 비어 있으면 "{매장} 태블릿" */
    var deviceName: String
        get() = str("deviceName", "")
        set(v) = putStr("deviceName", v.trim())

    val effectiveDeviceName: String get() = deviceName.ifBlank { "$storeName 태블릿" }

    /** 방송 장치 주소 "IP:포트" */
    var serverAddress: String
        get() = str("serverAddress", "")
        set(v) = putStr("serverAddress", normalizeAddress(v))

    /** 방송 장치에 토큰이 설정되어 있을 때만 필요 */
    var terminalToken: String
        get() = str("terminalToken", "")
        set(v) = putStr("terminalToken", v.trim())

    var triggerKeys: Set<Int>
        get() = p.getStringSet("triggerKeys", null)
            ?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_TRIGGER_KEYS
        set(v) = p.edit().putStringSet("triggerKeys", v.map { it.toString() }.toSet()).apply()

    /** 방송 입력 중 자동 취소 시간(초) */
    var cancelTimeoutSec: Int
        get() = p.getInt("cancelTimeoutSec", 8).coerceIn(2, 60)
        set(v) = putInt("cancelTimeoutSec", v.coerceIn(2, 60))

    /** Num Lock이 꺼진 키패드의 이동 키를 숫자로 해석 */
    var navAsDigits: Boolean
        get() = p.getBoolean("navAsDigits", false)
        set(v) = putBool("navAsDigits", v)

    /** 최근 전송 5건(화면 표시용 문장) */
    val recentSends: List<String>
        get() = try {
            val a = JSONArray(str("recentSends", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

    fun addRecentSend(line: String) {
        val list = (listOf(line) + recentSends).take(5)
        putStr("recentSends", JSONArray(list).toString())
    }

    // ───────── 방송용 ─────────

    var port: Int
        get() = p.getInt("port", Protocol.DEFAULT_PORT).coerceIn(1024, 65535)
        set(v) = putInt("port", v.coerceIn(1024, 65535))

    /** 비어 있으면 토큰 확인을 하지 않는다 */
    var token: String
        get() = str("token", "")
        set(v) = putStr("token", v.trim())

    /** 방송 음량 0~100 (시스템 음량은 바꾸지 않는다) */
    var volume: Int
        get() = p.getInt("volume", 100).coerceIn(0, 100)
        set(v) = putInt("volume", v.coerceIn(0, 100))

    /** 말하기 속도(%) 50~200, 100이 보통 */
    var speechRatePercent: Int
        get() = p.getInt("speechRate", 100).coerceIn(50, 200)
        set(v) = putInt("speechRate", v.coerceIn(50, 200))

    var repeatCount: Int
        get() = p.getInt("repeat", 1).coerceIn(1, 3)
        set(v) = putInt("repeat", v.coerceIn(1, 3))

    var chimeEnabled: Boolean
        get() = p.getBoolean("chime", true)
        set(v) = putBool("chime", v)

    /** 앞 무음(ms). 블루투스에서 앞부분 잘림 방지 */
    var preSilenceMs: Int
        get() = p.getInt("preSilenceMs", 500).coerceIn(0, 3000)
        set(v) = putInt("preSilenceMs", v.coerceIn(0, 3000))

    /** 같은 매장·번호 중복 무시 시간(초) */
    var duplicateSec: Int
        get() = p.getInt("duplicateSec", 20).coerceIn(0, 600)
        set(v) = putInt("duplicateSec", v.coerceIn(0, 600))

    var stores: List<StoreConfig>
        get() = p.getString("stores", null)?.let { parseStores(it) } ?: DefaultStores.list
        set(v) = putStr("stores", storesToJson(v))

    fun findStore(name: String): StoreConfig? =
        stores.firstOrNull { NumberRules.sameStore(it.name, name) }

    private fun parseStores(text: String): List<StoreConfig>? = try {
        val a = JSONArray(text)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            StoreConfig(
                name = o.getString("name"),
                spokenName = o.optString("spokenName", o.getString("name")),
                template = o.optString("template", ""),
                enabled = o.optBoolean("enabled", true),
            )
        }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun storesToJson(list: List<StoreConfig>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().apply {
                put("name", it.name.trim())
                put("spokenName", it.spokenName.trim())
                put("template", it.template.trim())
                put("enabled", it.enabled)
            })
        }
    }.toString()

    /** "192.168.0.23" → "192.168.0.23:8080", "http://…/" 같은 앞뒤 문자 제거 */
    fun normalizeAddress(raw: String): String {
        var s = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (s.isEmpty()) return ""
        val hasPort = if (s.startsWith("[")) s.contains("]:") else s.count { it == ':' } == 1
        if (!hasPort) s = "$s:${Protocol.DEFAULT_PORT}"
        return s
    }
}
