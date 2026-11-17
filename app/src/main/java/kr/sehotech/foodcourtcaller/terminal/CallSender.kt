package kr.sehotech.foodcourtcaller.terminal

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.sehotech.foodcourtcaller.common.AppSettings
import kr.sehotech.foodcourtcaller.common.CallRequest
import kr.sehotech.foodcourtcaller.common.CallResponse
import kr.sehotech.foodcourtcaller.common.NetUtil
import kr.sehotech.foodcourtcaller.common.Protocol
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 방송 폰으로 호출을 보낸다. 모든 네트워크 작업은 메인 스레드 밖(전용 스레드)에서 한다.
 * - 연결 제한 3초, 실패하면 1초·2초 뒤 재시도(모두 3번)
 * - 같은 번호를 3초 안에 다시 보내면 한 번만 보낸다
 * - 3번 다 실패하면 NSD로 방송 폰을 다시 찾아 주소를 갱신하고 한 번 더 보낸다
 */
object CallSender {
    private const val TAG = "CallSender"
    private const val TIMEOUT_MS = 3_000
    private const val LOCAL_DEDUP_MS = 3_000L
    private val RETRY_DELAYS_MS = longArrayOf(0, 1_000, 2_000)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "call-sender") }
    private val main = Handler(Looper.getMainLooper())
    private val lastSentAt = HashMap<String, Long>()

    sealed class Result {
        data class Ok(val duplicate: Boolean, val position: Int) : Result()

        /** 서버가 거절(매장 없음, 번호 오류, 토큰 오류) */
        data class Rejected(val error: String) : Result()

        /** 연결 안 됨 */
        object Unreachable : Result()

        /** 3초 안에 같은 번호를 이미 보냄 */
        object Skipped : Result()
    }

    /** 결과는 메인 스레드에서 callback으로 받는다 */
    fun send(
        context: Context,
        number: String?,
        test: Boolean = false,
        source: String = Protocol.SOURCE_KEYPAD,
        callback: (Result) -> Unit,
    ) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        val key = if (test) "test" else number.orEmpty()
        val now = System.currentTimeMillis()
        synchronized(lastSentAt) {
            val last = lastSentAt[key]
            if (!test && last != null && now - last < LOCAL_DEDUP_MS) {
                callback(Result.Skipped)
                return
            }
            lastSentAt[key] = now
        }
        val req = CallRequest(
            store = settings.storeName,
            number = number,
            source = source,
            device = settings.effectiveDeviceName,
            test = test,
        )
        executor.execute {
            val result = sendWithRetry(app, settings, req)
            record(settings, req, result)
            main.post { callback(result) }
        }
    }

    private fun sendWithRetry(context: Context, settings: AppSettings, req: CallRequest): Result {
        val address = settings.serverAddress
        if (address.isNotBlank()) {
            for (delay in RETRY_DELAYS_MS) {
                if (delay > 0) Thread.sleep(delay)
                val r = post(address, req, settings.terminalToken)
                if (r != null) return r
            }
        }
        // 다시 찾기([연결 끊기]를 누른 상태면 찾지 않는다)
        if (!settings.autoConnect) return Result.Unreachable
        val found = discover(context)
        if (found != null) {
            if (found != address) {
                Log.i(TAG, "방송 장치 주소 갱신: $address → $found")
                settings.serverAddress = found
            }
            post(found, req, settings.terminalToken)?.let { return it }
        }
        return Result.Unreachable
    }

    private fun record(settings: AppSettings, req: CallRequest, r: Result) {
        if (r is Result.Skipped) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val what = if (req.test) "테스트" else "${req.number}번"
        val res = when (r) {
            is Result.Ok -> if (r.duplicate) "중복(이미 방송)" else "완료"
            is Result.Rejected -> "거절: ${errorText(r.error)}"
            Result.Unreachable -> "연결 안 됨"
            Result.Skipped -> ""
        }
        settings.addRecentSend("$time  $what → $res")
    }

    fun errorText(code: String): String = when (code) {
        Protocol.ERR_UNKNOWN_STORE -> "방송 장치에 없는 매장"
        Protocol.ERR_INVALID_NUMBER -> "번호 오류"
        Protocol.ERR_BAD_TOKEN -> "토큰 불일치"
        else -> code
    }

    /** 한 번 보내기. 연결 실패·서버 오류면 null(재시도 대상) */
    private fun post(address: String, req: CallRequest, token: String): Result? {
        return try {
            val conn = (URL("http://$address/call").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotBlank()) setRequestProperty(Protocol.HEADER_TOKEN, token)
            }
            try {
                conn.outputStream.use { it.write(req.toJson().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val resp = CallResponse.fromJson(body)
                when {
                    code in 200..299 && resp?.ok == true -> Result.Ok(resp.duplicate, resp.position)
                    code in 400..499 -> Result.Rejected(resp?.error ?: "http_$code")
                    else -> null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "전송 실패 $address: ${e.message}")
            null
        }
    }

    @Volatile
    private var heartbeatFails = 0

    /**
     * 1분마다: 이 태블릿 상태를 방송 장치에 알린다(원격 관리 화면에 표시).
     * 두 번 연속 안 닿으면(방송 장치 주소가 바뀐 경우 등) 스스로 다시 찾아 저장한다.
     * 사용자가 [연결 끊기]를 누른 뒤(주소 비움)에는 다시 찾지 않는다.
     */
    fun heartbeat(context: Context, accessibility: Boolean) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        val address = settings.serverAddress
        if (address.isBlank()) return
        executor.execute {
            val ok = sendHeartbeat(app, settings, address, accessibility)
            if (ok) {
                heartbeatFails = 0
                return@execute
            }
            if (++heartbeatFails < 2 || !settings.autoConnect) return@execute
            val found = discover(app)
            if (found != null && settings.serverAddress.isNotBlank()) {
                if (found != settings.serverAddress) {
                    Log.i(TAG, "방송 장치 주소 자동 갱신: ${settings.serverAddress} → $found")
                    settings.serverAddress = found
                }
                if (sendHeartbeat(app, settings, found, accessibility)) heartbeatFails = 0
            }
        }
    }

    private fun sendHeartbeat(app: Context, settings: AppSettings, address: String, accessibility: Boolean): Boolean {
            try {
                val pm = app.getSystemService(android.os.PowerManager::class.java)
                val body = JSONObject().apply {
                    put("store", settings.storeName)
                    put("device", settings.effectiveDeviceName)
                    put("version", kr.sehotech.foodcourtcaller.BuildConfig.VERSION_NAME)
                    put("accessibility", accessibility)
                    put("battery", pm.isIgnoringBatteryOptimizations(app.packageName))
                    put("lastSend", settings.recentSends.firstOrNull() ?: "")
                }.toString()
                val conn = (URL("http://$address/heartbeat").openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (settings.terminalToken.isNotBlank()) setRequestProperty(Protocol.HEADER_TOKEN, settings.terminalToken)
                }
                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    return conn.responseCode in 200..499
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "상태 신호 실패: ${e.message}")
                return false
            }
    }

    /** GET 요청. 실패하면 null. 메인 스레드에서 부르지 않는다. */
    fun get(address: String, path: String): String? = try {
        val conn = (URL("http://$address$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** 백그라운드에서 작업하고 결과를 메인 스레드로 */
    fun <T> runAsync(work: () -> T, done: (T) -> Unit) {
        executor.execute {
            val r = work()
            main.post { done(r) }
        }
    }

    /** [연결 테스트]: GET /status 결과를 사람이 읽을 문장으로 */
    fun statusText(address: String): String {
        if (address.isBlank()) return "방송 장치 주소가 없습니다."
        val body = get(address, "/status") ?: return "연결 안 됨 ($address)"
        return try {
            val o = JSONObject(body)
            val tts = when (o.optString("tts")) {
                "ready" -> "음성 준비됨"
                "no_korean" -> "한국어 음성 없음!"
                else -> "음성 ${o.optString("tts")}"
            }
            "연결됨 · 버전 ${o.optString("version")} · 대기 ${o.optInt("queue")}건 · $tts"
        } catch (e: Exception) {
            "응답 형식 오류 ($address)"
        }
    }

    /** GET /stores → 사용하는 매장 이름 목록. 실패하면 null */
    fun fetchStores(address: String): List<String>? {
        val body = get(address, "/stores") ?: return null
        return try {
            val a = JSONObject(body).getJSONArray("stores")
            (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optBoolean("enabled", true) }
                .map { it.getString("name") }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * NSD(_fcall._tcp)로 같은 네트워크의 방송 폰을 찾는다. 찾으면 "IP:포트".
     * 블로킹 함수이므로 메인 스레드에서 부르지 않는다.
     */
    @Suppress("DEPRECATION")
    fun discover(context: Context, timeoutMs: Long = 6_000): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val latch = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        var result: String? = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "찾기 시작 실패 $errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_fcall")) return
                if (!resolving.compareAndSet(false, true)) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "주소 확인 실패 $errorCode")
                        resolving.set(false)
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host
                        if (host != null) {
                            result = NetUtil.format(host, si.port)
                            latch.countDown()
                        } else {
                            resolving.set(false)
                        }
                    }
                })
            }
        }
        try {
            nsd.discoverServices(Protocol.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "찾기 오류", e)
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        return result
    }
}
