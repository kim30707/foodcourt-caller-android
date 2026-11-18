package kr.sehotech.foodcourtcaller.server

import org.json.JSONArray
import org.json.JSONObject

/** 매장 태블릿이 1분마다 보내는 상태 신호를 모아 둔다(메모리) */
class DeviceRegistry {
    data class Info(
        val store: String,
        val device: String,
        val version: String,
        val accessibility: Boolean,
        val battery: Boolean,
        val lastSend: String,
        val ip: String,
        val lastSeen: Long,
        /** 자동 업데이트 상태: device_owner / ready / setup / notify / "" */
        val autoUpdate: String = "",
        val android: Int = 0,
        val configVersion: Long = 0,
    )

    private val map = LinkedHashMap<String, Info>()

    @Synchronized
    fun update(json: String, ip: String, now: Long = System.currentTimeMillis()): Boolean = try {
        val o = JSONObject(json)
        val device = o.optString("device").ifBlank { o.optString("store") + " 태블릿" }
        map[device] = Info(
            store = o.optString("store"),
            device = device,
            version = o.optString("version"),
            accessibility = o.optBoolean("accessibility", false),
            battery = o.optBoolean("battery", false),
            lastSend = o.optString("lastSend"),
            ip = ip,
            lastSeen = now,
            autoUpdate = o.optString("autoUpdate"),
            android = o.optInt("android", 0),
            configVersion = o.optLong("configVersion", 0),
        )
        true
    } catch (e: Exception) {
        false
    }

    @Synchronized
    fun all(): List<Info> = map.values.sortedBy { it.store }

    fun toJson(latest: String?, configVersion: Long = 0, now: Long = System.currentTimeMillis()): JSONArray = JSONArray().apply {
        all().forEach {
            put(JSONObject().apply {
                put("store", it.store)
                put("device", it.device)
                put("version", it.version)
                put("outdated", latest != null && kr.sehotech.foodcourtcaller.common.Versions.isNewer(latest, it.version))
                put("accessibility", it.accessibility)
                put("battery", it.battery)
                put("lastSend", it.lastSend)
                put("ip", it.ip)
                put("agoSec", (now - it.lastSeen) / 1000)
                put("online", now - it.lastSeen < 3 * 60 * 1000)
                put("autoUpdate", it.autoUpdate)
                put("android", it.android)
                put("configApplied", configVersion == 0L || it.configVersion >= configVersion)
            })
        }
    }
}

/** 관리 PIN 확인. 5번 틀리면 5분 잠금 */
class PinGuard {
    private var fails = 0
    private var lockedUntil = 0L

    enum class Check { OK, NOT_SET, WRONG, LOCKED }

    @Synchronized
    fun check(expected: String, given: String?, now: Long = System.currentTimeMillis()): Check {
        if (expected.isBlank()) return Check.NOT_SET
        if (now < lockedUntil) return Check.LOCKED
        if (given != null && given == expected) {
            fails = 0
            return Check.OK
        }
        if (++fails >= 5) {
            fails = 0
            lockedUntil = now + 5 * 60 * 1000
            return Check.LOCKED
        }
        return Check.WRONG
    }
}
