package kr.sehotech.foodcourtcaller.common

import kr.sehotech.foodcourtcaller.terminal.KeyCodes
import org.json.JSONArray
import org.json.JSONObject

/** 방송 장치에서 모든 매장 태블릿에 한꺼번에 내려 보내는 설정 */
data class TerminalConfig(
    val triggerKeys: Set<Int>,
    val cancelTimeoutSec: Int,
    val navAsDigits: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("triggerKeys", JSONArray().apply { triggerKeys.sorted().forEach { put(it) } })
        put("cancelTimeoutSec", cancelTimeoutSec)
        put("navAsDigits", navAsDigits)
    }

    /** 이 태블릿 설정에 적용 */
    fun applyTo(settings: AppSettings) {
        settings.triggerKeys = triggerKeys
        settings.cancelTimeoutSec = cancelTimeoutSec
        settings.navAsDigits = navAsDigits
    }

    companion object {
        private val ALLOWED = KeyCodes.TRIGGER_CHOICES.map { it.first }.toSet()

        /** 잘못된 값이면 null */
        fun fromJson(o: JSONObject?): TerminalConfig? {
            if (o == null) return null
            val arr = o.optJSONArray("triggerKeys") ?: return null
            val keys = (0 until arr.length()).mapNotNull { arr.optInt(it, -1).takeIf { k -> k in ALLOWED } }.toSet()
            if (keys.isEmpty()) return null
            val sec = o.optInt("cancelTimeoutSec", 8)
            if (sec !in 2..60) return null
            return TerminalConfig(keys, sec, o.optBoolean("navAsDigits", false))
        }

        fun fromText(text: String): TerminalConfig? = try {
            if (text.isBlank()) null else fromJson(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }
}
