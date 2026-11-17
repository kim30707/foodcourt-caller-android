package kr.sehotech.foodcourtcaller.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kr.sehotech.foodcourtcaller.BuildConfig
import kr.sehotech.foodcourtcaller.MainActivity
import kr.sehotech.foodcourtcaller.R
import kr.sehotech.foodcourtcaller.common.AppSettings
import kr.sehotech.foodcourtcaller.common.CallRequest
import kr.sehotech.foodcourtcaller.common.CallResponse
import kr.sehotech.foodcourtcaller.common.DefaultStores
import kr.sehotech.foodcourtcaller.common.NetUtil
import kr.sehotech.foodcourtcaller.common.NumberRules
import kr.sehotech.foodcourtcaller.common.Protocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * 방송용 포그라운드 서비스.
 * - HTTP 서버(기본 8080)로 호출을 받고, 대기열에 넣어 한 건씩 방송한다.
 * - NSD(_fcall._tcp)로 자신을 알려 매장용 앱이 자동으로 찾게 한다.
 * - 도는 동안 CPU·와이파이 잠금을 잡고, 서버·TTS에 문제가 생기면 스스로 다시 시작한다.
 */
class BroadcastService : Service() {

    companion object {
        private const val TAG = "BroadcastService"
        private const val CHANNEL_ID = "broadcast"
        private const val NOTIF_ID = 1
        private const val WATCHDOG_MS = 30_000L
        const val ACTION_RESTART = "kr.sehotech.foodcourtcaller.RESTART"

        @Volatile
        var instance: BroadcastService? = null
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, BroadcastService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "서비스 시작 실패", e)
            }
        }

        fun restart(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, BroadcastService::class.java).setAction(ACTION_RESTART),
                )
            } catch (e: Exception) {
                Log.e(TAG, "서비스 재시작 실패", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BroadcastService::class.java))
        }
    }

    lateinit var settings: AppSettings
        private set
    lateinit var announcer: Announcer
        private set
    lateinit var callLog: CallLog
        private set
    val queue = CallQueue()

    var startedAt = 0L
        private set

    @Volatile
    var serverRunning = false
        private set

    @Volatile
    var currentIp: String? = null
        private set

    private var server: CallHttpServer? = null
    private var worker: Thread? = null

    @Volatile
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())
    private var ttsFailures = 0
    private var lastPrune = 0L

    lateinit var music: MusicControl
        private set

    /** 음악 예약: 마지막으로 확인한 시각(하루 중 분), -1이면 아직 없음 */
    private var musicPrevMin = -1

    @Volatile
    var musicLastText: String = ""
        private set

    private val musicTicker = object : Runnable {
        override fun run() {
            try {
                checkMusicSchedule()
            } catch (e: Exception) {
                Log.e(TAG, "음악 예약 오류", e)
            }
            main.postDelayed(this, 15_000)
        }
    }

    private fun nowMinute(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    /** 켜기/끄기 시각을 지났으면 음악 앱에 재생·정지 신호를 보낸다 */
    private fun checkMusicSchedule() {
        val now = nowMinute()
        val prev = musicPrevMin
        musicPrevMin = now
        if (!settings.musicAutoEnabled || prev < 0) return
        when (MusicSchedule.due(prev, now, settings.musicOnMin, settings.musicOffMin)) {
            MusicSchedule.Action.PLAY -> musicNow(true, "예약")
            MusicSchedule.Action.PAUSE -> musicNow(false, "예약")
            null -> {}
        }
    }

    /** 음악 켜기/끄기(예약 또는 설정 화면 테스트) */
    fun musicNow(play: Boolean, why: String) {
        val what = if (play) "음악 켜기" else "음악 끄기"
        musicLastText = "$what 신호 보냄($why) · 확인 중…"
        val done: (Boolean) -> Unit = { ok ->
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date())
            musicLastText = "$time $what($why): " + if (ok) "성공" else if (play) "소리가 안 남(음악 앱을 한 번 켜 두세요)" else "아직 소리가 남"
            logEvent(if (play) "music_on" else "music_off", musicLastText)
        }
        if (play) music.play(done) else music.pause(done)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                checkHealth()
            } catch (e: Exception) {
                Log.e(TAG, "점검 오류", e)
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        createChannel()
        startInForeground("방송 장치 시작 중…")
        instance = this
        startedAt = System.currentTimeMillis()
        callLog = CallLog(filesDir)
        callLog.prune()
        lastPrune = startedAt
        acquireLocks()
        announcer = Announcer(this, settings)
        announcer.init()
        startWorker()
        startServer()
        main.postDelayed(watchdog, WATCHDOG_MS)
        music = MusicControl(this)
        main.post(musicTicker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 안드로이드는 startForegroundService 뒤 매번 startForeground를 기대한다.
        updateNotification()
        if (intent?.action == ACTION_RESTART) {
            restartAll("설정 화면에서 다시 시작")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        main.removeCallbacksAndMessages(null)
        stopServer()
        worker?.interrupt()
        announcer.shutdown()
        releaseLocks()
        instance = null
        super.onDestroy()
    }

    // ───────── 알림 ─────────

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "방송 대기", NotificationManager.IMPORTANCE_LOW).apply {
            description = "방송 장치가 켜져 있는 동안 표시됩니다."
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("호출 방송")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    fun addressText(): String = "${currentIp ?: "주소 없음(와이파이 확인)"}:${settings.port}"

    private fun updateNotification() {
        currentIp = NetUtil.localIpv4()
        val text = if (serverRunning) "방송 대기 중 · ${addressText()}" else "서버 꺼짐 · 다시 시작하는 중"
        startInForeground(text)
    }

    // ───────── 잠금 ─────────

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "foodcourtcaller:server").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "foodcourtcaller:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "잠금 실패", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    // ───────── 서버 ─────────

    private fun startServer() {
        stopServer()
        val port = settings.port
        try {
            val s = CallHttpServer(port, this)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = s
            serverRunning = true
            registerNsd(port)
            Log.i(TAG, "서버 시작 포트 $port")
        } catch (e: Exception) {
            serverRunning = false
            Log.e(TAG, "서버 시작 실패(포트 $port)", e)
            logEvent("server_error", e.message ?: "start failed")
        }
        updateNotification()
    }

    private fun stopServer() {
        unregisterNsd()
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        serverRunning = false
    }

    private fun registerNsd(port: Int) {
        val nsd = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = Protocol.NSD_NAME
            serviceType = Protocol.NSD_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Log.i(TAG, "NSD 등록: ${si.serviceName}")
            }

            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD 등록 실패 $errorCode")
            }

            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        } catch (e: Exception) {
            Log.w(TAG, "NSD 등록 오류", e)
        }
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        nsdListener = null
        try {
            getSystemService(NsdManager::class.java)?.unregisterService(l)
        } catch (_: Exception) {
        }
    }

    private fun restartAll(reason: String) {
        Log.i(TAG, "다시 시작: $reason")
        logEvent("restart", reason)
        announcer.init()
        startServer()
    }

    /** 30초마다 점검: 서버가 죽었으면 다시 켜고, 주소가 바뀌었으면 알림·NSD를 갱신한다 */
    private fun checkHealth() {
        if (stopping) return
        val alive = server?.isAlive == true
        if (!alive) {
            logEvent("server_error", "서버 멈춤 → 다시 시작")
            startServer()
        } else {
            val ip = NetUtil.localIpv4()
            if (ip != currentIp) {
                Log.i(TAG, "주소 변경 $currentIp → $ip")
                registerNsdAgain()
                updateNotification()
            }
        }
        if (announcer.state == Announcer.TtsState.ERROR) {
            logEvent("tts_error", "TTS 오류 → 다시 시작")
            announcer.init()
        }
        if (worker?.isAlive != true) startWorker()
        val now = System.currentTimeMillis()
        if (now - lastPrune > 60 * 60 * 1000L) {
            callLog.prune(now)
            lastPrune = now
        }
    }

    private fun registerNsdAgain() {
        unregisterNsd()
        if (serverRunning) registerNsd(settings.port)
    }

    // ───────── 방송 작업자 ─────────

    private fun startWorker() {
        val t = Thread({ workLoop() }, "announce-worker")
        t.isDaemon = true
        worker = t
        t.start()
    }

    private fun workLoop() {
        while (!stopping) {
            try {
                val item = queue.take(1_000) ?: continue
                val store = settings.findStore(item.store)
                if (store == null) {
                    logItem(item, "error", "매장 없음")
                    continue
                }
                val text = if (item.test) DefaultStores.testSentence(store)
                else DefaultStores.sentence(store, item.number ?: "0")
                val ok = announcer.announce(text)
                logItem(item, if (ok) "played" else "error", if (ok) "" else "TTS ${announcer.state.code}")
                if (ok) {
                    ttsFailures = 0
                } else if (++ttsFailures >= 2) {
                    ttsFailures = 0
                    logEvent("tts_error", "방송 연속 실패 → TTS 다시 시작")
                    announcer.init()
                    Thread.sleep(2_000)
                }
            } catch (e: InterruptedException) {
                if (stopping) return
            } catch (e: Throwable) {
                Log.e(TAG, "방송 작업 오류", e)
                logEvent("worker_error", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun logItem(item: CallQueue.Item, result: String, note: String = "") {
        callLog.add(
            CallLog.Entry(
                System.currentTimeMillis(), item.store, item.number, item.source, item.device,
                item.test, result, note,
            ),
        )
    }

    private fun logEvent(result: String, note: String) {
        if (!::callLog.isInitialized) return
        callLog.add(CallLog.Entry(System.currentTimeMillis(), "", null, "system", "", false, result, note))
    }

    // ───────── 요청 처리(통신 규약 4-4) ─────────

    data class HttpResult(val status: Int, val body: CallResponse)

    fun handleCall(body: String, token: String?): HttpResult {
        val need = settings.token
        if (need.isNotBlank() && token != need) {
            return HttpResult(401, CallResponse(ok = false, error = Protocol.ERR_BAD_TOKEN))
        }
        val req = CallRequest.fromJson(body)
            ?: return HttpResult(400, CallResponse(ok = false, error = Protocol.ERR_BAD_REQUEST))
        return enqueue(req)
    }

    fun enqueue(req: CallRequest): HttpResult {
        if (!req.test && !NumberRules.isValid(req.number)) {
            return HttpResult(400, CallResponse(ok = false, error = Protocol.ERR_INVALID_NUMBER))
        }
        val store = settings.findStore(req.store)
        if (store == null || !store.enabled) {
            return HttpResult(404, CallResponse(ok = false, error = Protocol.ERR_UNKNOWN_STORE))
        }
        queue.duplicateWindowMs = settings.duplicateSec * 1000L
        val r = queue.offer(store.name, req.number, req.source, req.device, req.test)
        if (r.duplicate) {
            callLog.add(
                CallLog.Entry(
                    System.currentTimeMillis(), store.name, req.number, req.source, req.device,
                    false, "duplicate",
                ),
            )
            return HttpResult(200, CallResponse(ok = true, queued = false, duplicate = true))
        }
        r.dropped?.let { logItem(it, "dropped", "대기열 30건 초과") }
        return HttpResult(200, CallResponse(ok = true, queued = true, duplicate = false, position = r.position))
    }

    fun statusJson(): String = JSONObject().apply {
        put("ok", true)
        put("version", BuildConfig.VERSION_NAME)
        put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000)
        put("queue", queue.size)
        put("tts", announcer.state.code)
        put("ip", currentIp ?: JSONObject.NULL)
        put("port", settings.port)
    }.toString()

    fun storesJson(): String = JSONObject().apply {
        put("stores", JSONArray().apply {
            settings.stores.forEach { put(JSONObject().put("name", it.name).put("enabled", it.enabled)) }
        })
    }.toString()

    fun logJson(limit: Int, store: String?): String {
        val entries = callLog.recent(limit, store)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson().put("resultText", WebPages.resultText(it.result))) }
        return JSONObject().put("entries", arr).toString()
    }

    fun statusPage(): String = WebPages.statusPage(
        address = addressText(),
        version = BuildConfig.VERSION_NAME,
        queue = queue.size,
        ttsLabel = announcer.state.label,
        ttsOk = announcer.state == Announcer.TtsState.READY,
        stores = settings.stores,
        recent = callLog.recent(20).filter { it.source != "system" },
    )
}

/** NanoHTTPD 기반 내장 HTTP 서버 */
class CallHttpServer(port: Int, private val svc: BroadcastService) : NanoHTTPD(port) {

    private fun json(status: Int, body: String): Response =
        newFixedLengthResponse(statusOf(status), "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun html(status: Int, body: String): Response =
        newFixedLengthResponse(statusOf(status), "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun statusOf(code: Int): Response.IStatus = when (code) {
        200 -> Response.Status.OK
        400 -> Response.Status.BAD_REQUEST
        401 -> Response.Status.UNAUTHORIZED
        404 -> Response.Status.NOT_FOUND
        else -> Response.Status.INTERNAL_ERROR
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        Log.e("CallHttpServer", "요청 처리 오류", e)
        json(500, CallResponse(ok = false, error = Protocol.ERR_SERVER).toJson())
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        return when {
            method == Method.POST && uri == "/call" -> {
                val body = readBody(session)
                    ?: return json(400, CallResponse(ok = false, error = Protocol.ERR_BAD_REQUEST).toJson())
                val token = session.headers[Protocol.HEADER_TOKEN.lowercase()]
                val r = svc.handleCall(body, token)
                json(r.status, r.body.toJson())
            }

            method == Method.GET && uri == "/status" -> json(200, svc.statusJson())
            method == Method.GET && uri == "/stores" -> json(200, svc.storesJson())
            method == Method.GET && uri == "/log" -> {
                val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
                val store = session.parameters["store"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                json(200, svc.logJson(limit, store))
            }

            method == Method.GET && (uri == "/" || uri == "/index.html") -> html(200, svc.statusPage())
            method == Method.GET && uri.startsWith("/s/") -> {
                val name = uri.removePrefix("/s/").trimEnd('/')
                val store = svc.settings.findStore(name)
                if (store == null || !store.enabled) html(404, WebPages.notFoundPage(name))
                else html(200, WebPages.storePage(store, session.parameters["t"]?.firstOrNull().orEmpty()))
            }

            else -> json(404, JSONObject().put("ok", false).put("error", Protocol.ERR_NOT_FOUND).toString())
        }
    }

    /** 본문을 UTF-8로 직접 읽는다(한글 매장 이름이 깨지지 않게) */
    private fun readBody(session: IHTTPSession): String? {
        val len = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (len !in 1..16_384) return null
        val buf = ByteArray(len)
        var read = 0
        val input = session.inputStream
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }
}
