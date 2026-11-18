package kr.sehotech.foodcourtcaller.terminal

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kr.sehotech.foodcourtcaller.BuildConfig
import kr.sehotech.foodcourtcaller.common.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 접근성 서비스: 외장 키패드 입력을 기존 호출 앱보다 먼저 받는다.
 * 평소에는 모든 키를 그대로 통과시키고, '-' 번호 Enter만 가로채 방송 폰으로 보낸다.
 * 화면 내용은 읽지 않는다(canRetrieveWindowContent=false).
 */
class KeypadInterceptService : AccessibilityService() {

    companion object {
        private const val TAG = "KeypadIntercept"

        /** 서비스가 실제로 연결되어 동작 중인지 */
        @Volatile
        var connected = false
            private set

        @Volatile
        private var instance: KeypadInterceptService? = null

        private val recentKeys = ArrayDeque<String>()

        /** [키 입력 확인]용: 마지막으로 누른 키 10개(최근 것이 위) */
        fun recentKeys(): List<String> = synchronized(recentKeys) { recentKeys.toList() }

        /** 설정 화면에서 값을 바꾼 뒤 부른다 */
        fun reloadConfig() {
            instance?.let { s -> s.handler.post { s.loadConfig() } }
        }

        /** 시스템 접근성 설정에서 이 서비스가 켜져 있는지 */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val cn = ComponentName(context, KeypadInterceptService::class.java)
            return enabled.split(':').any {
                it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val parser = KeySequenceParser()
    private lateinit var settings: AppSettings
    private lateinit var banner: OverlayBanner
    private val timeoutRunnable = Runnable { onTimeoutCheck() }

    /** 영업이 끝난 뒤(방송 장치가 알려 준 시간)에만 스스로 업데이트. 입력·방송 중에는 하지 않는다 */
    private fun autoUpdateTick() {
        val now = System.currentTimeMillis()
        val c = java.util.Calendar.getInstance()
        val nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val allowed = kr.sehotech.foodcourtcaller.common.UpdateWindow.terminalAllows(
            now, nowMin, settings.serverAllowUntil, settings.lastServerContact,
        )
        val force = CallSender.takeForceUpdate()
        val idle = !parser.isArmed && now - CallSender.lastSendAt > 5 * 60 * 1000L
        kr.sehotech.foodcourtcaller.common.UpdateChecker.autoTick(this, allowed, force = force, idle = idle || force)
    }

    /** 1분마다 방송 장치에 상태 신호, 업데이트 시간이면 업데이트 확인 */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                CallSender.heartbeat(this@KeypadInterceptService, accessibility = true)
                autoUpdateTick()
            } catch (e: Exception) {
                Log.w(TAG, "상태 신호 오류", e)
            }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings(this)
        banner = OverlayBanner(this)
        loadConfig()
        instance = this
        connected = true
        handler.postDelayed(heartbeatRunnable, 5_000)
        Log.i(TAG, "키 가로채기 시작")
    }

    override fun onDestroy() {
        connected = false
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::banner.isInitialized) banner.hide()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    private fun loadConfig() {
        if (!::settings.isInitialized) return
        parser.config = KeySequenceParser.Config(
            triggerKeys = settings.triggerKeys,
            timeoutMs = settings.cancelTimeoutSec * 1000L,
            navAsDigits = settings.navAsDigits,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 화면 이벤트는 쓰지 않는다.
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "키 ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount} role=${if (::settings.isInitialized) settings.role else "?"}")
        // 매장용으로 설정된 기기에서만 동작한다.
        if (!::settings.isInitialized || settings.role != AppSettings.ROLE_TERMINAL) return false

        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (isDown && event.repeatCount == 0) {
            rememberKey(event)
            if (!parser.isArmed) loadConfig() // 설정 변경 반영
        }

        val decision = try {
            parser.onKey(event.keyCode, isDown, event.repeatCount)
        } catch (e: Exception) {
            Log.e(TAG, "키 해석 오류", e)
            return false
        }
        decision.events.forEach { handleEvent(it) }

        handler.removeCallbacks(timeoutRunnable)
        if (parser.isArmed) handler.postDelayed(timeoutRunnable, parser.config.timeoutMs + 50)
        return decision.consume
    }

    private fun onTimeoutCheck() {
        parser.checkTimeout()?.let { handleEvent(it) }
        if (parser.isArmed) handler.postDelayed(timeoutRunnable, 500)
    }

    private fun handleEvent(e: KeySequenceParser.Event) {
        when (e) {
            KeySequenceParser.Event.Started ->
                banner.show("방송 번호: _", OverlayBanner.COLOR_INFO, null)

            is KeySequenceParser.Event.Updated ->
                banner.show("방송 번호: ${e.number}_", OverlayBanner.COLOR_INFO, null)

            is KeySequenceParser.Event.Cancelled ->
                banner.show("방송 취소", OverlayBanner.COLOR_MUTED, 1_000)

            is KeySequenceParser.Event.Submitted -> submit(e.number)
        }
    }

    private fun submit(number: String) {
        banner.show("${number}번 전송 중…", OverlayBanner.COLOR_INFO, null)
        CallSender.send(this, number) { r ->
            when (r) {
                is CallSender.Result.Ok ->
                    banner.show(
                        if (r.duplicate) "${number}번 이미 방송됨" else "${number}번 방송 요청 완료",
                        OverlayBanner.COLOR_OK, 2_000,
                    )

                is CallSender.Result.Rejected ->
                    banner.show("방송 거절: ${CallSender.errorText(r.error)}", OverlayBanner.COLOR_ERROR, 5_000)

                CallSender.Result.Unreachable ->
                    banner.show("방송 장치에 연결 안 됨", OverlayBanner.COLOR_ERROR, 5_000)

                CallSender.Result.Skipped ->
                    banner.show("${number}번 방송 요청 완료", OverlayBanner.COLOR_OK, 2_000)
            }
        }
    }

    private fun rememberKey(event: KeyEvent) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val dev = event.device?.name ?: "알 수 없는 장치"
        synchronized(recentKeys) {
            recentKeys.addFirst("$time  $name (${event.keyCode}) · $dev")
            while (recentKeys.size > 10) recentKeys.removeLast()
        }
    }
}
