package kr.sehotech.foodcourtcaller.common

import org.json.JSONObject

/**
 * 통신 규약(지시서 4-4). 매장용 앱, 직원 휴대폰 페이지, 2단계 PC 서버가 모두 이 형식을 따른다.
 * 문자 인코딩은 UTF-8이다.
 */
object Protocol {
    const val DEFAULT_PORT = 8080

    /** NSD(같은 네트워크 자동 찾기) 서비스 종류 */
    const val NSD_TYPE = "_fcall._tcp"
    const val NSD_NAME = "FoodcourtCaller"

    const val HEADER_TOKEN = "X-Token"

    const val SOURCE_KEYPAD = "keypad" // 매장 태블릿
    const val SOURCE_WEB = "web"       // 직원 휴대폰
    const val SOURCE_APP = "app"       // 설정 화면 테스트

    const val ERR_INVALID_NUMBER = "invalid_number"
    const val ERR_UNKNOWN_STORE = "unknown_store"
    const val ERR_BAD_TOKEN = "bad_token"
    const val ERR_BAD_REQUEST = "bad_request"
    const val ERR_NOT_FOUND = "not_found"
    const val ERR_SERVER = "server_error"
}

/** POST /call 본문 */
data class CallRequest(
    val store: String,
    val number: String?,
    val source: String,
    val device: String,
    val test: Boolean,
) {
    fun toJson(): String = JSONObject().apply {
        put("store", store)
        if (number != null) put("number", number)
        put("source", source)
        put("device", device)
        put("test", test)
    }.toString()

    companion object {
        /** 형식이 틀리면 null */
        fun fromJson(text: String): CallRequest? = try {
            val o = JSONObject(text)
            val number = if (o.has("number") && !o.isNull("number")) o.get("number").toString().trim() else null
            CallRequest(
                store = o.optString("store", ""),
                number = number?.ifEmpty { null },
                source = o.optString("source", "unknown"),
                device = o.optString("device", ""),
                test = o.optBoolean("test", false),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** POST /call 응답 */
data class CallResponse(
    val ok: Boolean,
    val queued: Boolean = false,
    val duplicate: Boolean = false,
    val position: Int = 0,
    val error: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("ok", ok)
        if (ok) {
            put("queued", queued)
            put("duplicate", duplicate)
            if (queued) put("position", position)
        } else {
            put("error", error ?: Protocol.ERR_SERVER)
        }
    }.toString()

    companion object {
        fun fromJson(text: String): CallResponse? = try {
            val o = JSONObject(text)
            CallResponse(
                ok = o.optBoolean("ok", false),
                queued = o.optBoolean("queued", false),
                duplicate = o.optBoolean("duplicate", false),
                position = o.optInt("position", 0),
                error = if (o.has("error")) o.optString("error") else null,
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** 번호·매장 이름 규칙(안드로이드에 의존하지 않는 순수 로직) */
object NumberRules {
    private val VALID = Regex("^[0-9]{1,5}$")

    /** 숫자 1~5자리인지 */
    fun isValid(number: String?): Boolean = number != null && VALID.matches(number)

    /** 읽을 번호: 앞의 0을 뗀다. 모두 0이면 "0" */
    fun spoken(number: String): String {
        val t = number.trim().trimStart('0')
        return t.ifEmpty { "0" }
    }

    /** 매장 이름은 앞뒤 공백을 무시하고 비교한다 */
    fun normalizeStore(name: String): String = name.trim()

    fun sameStore(a: String, b: String): Boolean = normalizeStore(a) == normalizeStore(b)
}
