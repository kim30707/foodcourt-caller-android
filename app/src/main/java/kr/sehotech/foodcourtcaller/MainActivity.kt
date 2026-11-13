package kr.sehotech.foodcourtcaller

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kr.sehotech.foodcourtcaller.common.AppSettings
import kr.sehotech.foodcourtcaller.common.CallRequest
import kr.sehotech.foodcourtcaller.common.DefaultStores
import kr.sehotech.foodcourtcaller.common.Protocol
import kr.sehotech.foodcourtcaller.common.StoreConfig
import kr.sehotech.foodcourtcaller.server.Announcer
import kr.sehotech.foodcourtcaller.server.BroadcastService
import kr.sehotech.foodcourtcaller.server.WebPages
import kr.sehotech.foodcourtcaller.terminal.CallSender
import kr.sehotech.foodcourtcaller.terminal.KeyCodes
import kr.sehotech.foodcourtcaller.terminal.KeypadInterceptService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 처음 실행하면 역할(매장용/방송용)을 고르고, 이후에는 역할별 설정 화면을 보여 준다.
 * 화면은 코드로 만든다(레이아웃 파일 없음).
 */
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private lateinit var settings: AppSettings
    private val handler = Handler(Looper.getMainLooper())

    /** 1초마다 화면의 상태 글자를 갱신하는 작업 */
    private var refreshers = mutableListOf<() -> Unit>()
    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshers.forEach {
                try {
                    it()
                } catch (_: Exception) {
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    private fun render() {
        refreshers.clear()
        when (settings.role) {
            AppSettings.ROLE_TERMINAL -> showTerminal()
            AppSettings.ROLE_SERVER -> {
                BroadcastService.start(this)
                requestNotificationPermission()
                showServer()
            }

            else -> showRoleChooser()
        }
    }

    // ───────── 화면 도우미 ─────────

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun newPage(titleText: String): LinearLayout {
        title = titleText
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F5F7"))
            addView(col)
        })
        return col
    }

    private fun LinearLayout.section(name: String): LinearLayout {
        addView(TextView(context).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F4E79"))
            setPadding(0, dp(18), 0, dp(6))
        })
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        addView(card)
        return card
    }

    private fun LinearLayout.label(t: String, sizeSp: Float = 15f, bold: Boolean = false, color: Int = Color.parseColor("#1D1D1F")): TextView {
        val tv = TextView(context).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        addView(tv)
        return tv
    }

    private fun LinearLayout.button(t: String, onClick: () -> Unit): Button {
        val b = Button(context).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }

    private fun LinearLayout.row(vararg views: View): LinearLayout {
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { r.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        addView(r)
        return r
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.edit(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(context).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine(true)
        }
        addView(e)
        return e
    }

    private fun LinearLayout.toggle(t: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val s = Switch(context).apply {
            text = t
            isChecked = checked
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        addView(s)
        return s
    }

    private fun LinearLayout.slider(name: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
        val lbl = label("$name: $value$unit")
        val sb = SeekBar(context).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    lbl.text = "$name: ${p + min}$unit"
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    onChange((s?.progress ?: 0) + min)
                }
            })
        }
        addView(sb)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())

    // ───────── 역할 선택 ─────────

    private fun showRoleChooser() {
        val page = newPage("호출 방송 · 역할 선택")
        page.label("이 기기를 어디에 쓰나요?", 22f, bold = true)
        page.label("한 번 고르면 다음부터 바로 그 화면이 열립니다. 나중에 맨 아래 [역할 바꾸기]로 바꿀 수 있습니다.")
        val t = page.section("매장용 (매장 태블릿)")
        t.label("숫자 키패드로 '-' 번호 Enter를 치면 방송 장치로 보냅니다. 기존 호출 앱은 그대로 씁니다.")
        t.button("매장용으로 쓰기") {
            settings.role = AppSettings.ROLE_TERMINAL
            render()
        }
        val s = page.section("방송용 (앰프 옆 폰)")
        s.label("매장 태블릿과 직원 휴대폰에서 온 호출을 음성으로 방송합니다. 앰프에 블루투스나 선으로 연결합니다.")
        s.button("방송용으로 쓰기") {
            settings.role = AppSettings.ROLE_SERVER
            render()
        }
    }

    private fun LinearLayout.roleChangeSection() {
        val c = section("기타")
        c.label("앱 버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color.GRAY)
        c.button("역할 바꾸기") {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("역할 바꾸기")
                .setMessage("역할 선택 화면으로 돌아갑니다. 지금 설정값은 남아 있습니다.")
                .setPositiveButton("바꾸기") { _, _ ->
                    if (settings.role == AppSettings.ROLE_SERVER) BroadcastService.stop(this@MainActivity)
                    settings.role = null
                    render()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun LinearLayout.batterySection() {
        val c = section("배터리")
        val st = c.label("")
        refreshers += {
            val pm = getSystemService(PowerManager::class.java)
            val ok = pm.isIgnoringBatteryOptimizations(packageName)
            st.text = if (ok) "배터리 제한 없음 ✔" else "배터리 최적화 켜짐 — 앱이 멈출 수 있습니다"
            st.setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
        c.button("배터리 제한 없음으로") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    // ───────── 매장용 설정 ─────────

    private fun showTerminal() {
        val page = newPage("호출 방송 · 매장용")

        // 매장
        val storeCard = page.section("매장")
        val storeSpinner = Spinner(this)
        storeCard.addView(storeSpinner)
        fun fillStores(names: List<String>) {
            val list = names.toMutableList()
            if (settings.storeName !in list) list.add(0, settings.storeName)
            storeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
            storeSpinner.setSelection(list.indexOf(settings.storeName))
            storeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    settings.storeName = list[pos]
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        fillStores(DefaultStores.list.filter { it.enabled }.map { it.name })
        storeCard.button("방송 장치에서 매장 목록 받기") {
            val addr = settings.serverAddress
            CallSender.runAsync({ CallSender.fetchStores(addr) }) { names ->
                if (names == null) toast("방송 장치에 연결 안 됨") else {
                    fillStores(names); toast("매장 ${names.size}곳을 받았습니다")
                }
            }
        }
        storeCard.label("이 기기 이름(기록용, 비우면 \"매장 이름 태블릿\")", 13f, color = Color.GRAY)
        val devEdit = storeCard.edit("예: 하즈벤 태블릿", settings.deviceName)

        // 방송 장치
        val net = page.section("방송 장치")
        net.label("주소(IP:포트)", 13f, color = Color.GRAY)
        val addrEdit = net.edit("예: 192.168.0.23:8080", settings.serverAddress, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        net.label("토큰(방송 장치에 설정한 경우만)", 13f, color = Color.GRAY)
        val tokenEdit = net.edit("비워 두면 사용 안 함", settings.terminalToken)
        val netResult = TextView(this)
        fun saveNet() {
            settings.serverAddress = addrEdit.text.toString()
            settings.terminalToken = tokenEdit.text.toString()
            settings.deviceName = devEdit.text.toString()
            addrEdit.setText(settings.serverAddress)
        }
        net.row(
            smallButton("저장") { saveNet(); toast("저장했습니다") },
            smallButton("자동 찾기") {
                netResult.text = "같은 와이파이에서 방송 장치를 찾는 중…"
                CallSender.runAsync({ CallSender.discover(this) }) { found ->
                    if (found == null) {
                        netResult.text = "찾지 못했습니다. 같은 와이파이인지 확인하거나 주소를 직접 넣으세요."
                    } else {
                        settings.serverAddress = found
                        addrEdit.setText(found)
                        netResult.text = "찾았습니다: $found"
                    }
                }
            },
            smallButton("연결 테스트") {
                saveNet()
                netResult.text = "확인 중…"
                val addr = settings.serverAddress
                CallSender.runAsync({ CallSender.statusText(addr) }) { netResult.text = it }
            },
        )
        net.addView(netResult)

        // 접근성
        val acc = page.section("접근성 서비스 (키 가로채기)")
        val accState = acc.label("")
        refreshers += {
            val on = KeypadInterceptService.isEnabledInSettings(this)
            val run = KeypadInterceptService.connected
            accState.text = when {
                on && run -> "켜짐 · 동작 중 ✔"
                on -> "켜짐 · 아직 연결 안 됨(잠시 기다리거나 다시 켜 주세요)"
                else -> "꺼짐 — 키패드 방송이 동작하지 않습니다"
            }
            accState.setTextColor(if (on && run) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
        acc.button("접근성 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        page.batterySection()

        // 키 설정
        val keys = page.section("방송 키")
        keys.label("이 키를 누르고 번호 + Enter를 치면 방송합니다.", 13f, color = Color.GRAY)
        val checks = KeyCodes.TRIGGER_CHOICES.map { (code, name) ->
            CheckBox(this).apply {
                text = name
                isChecked = code in settings.triggerKeys
                keys.addView(this)
            } to code
        }
        keys.label("자동 취소 시간(초)", 13f, color = Color.GRAY)
        val timeoutEdit = keys.edit("8", settings.cancelTimeoutSec.toString(), InputType.TYPE_CLASS_NUMBER)
        val navSwitch = keys.toggle("이동 키를 숫자로 해석 (Num Lock이 꺼진 키패드용)", settings.navAsDigits) {}
        keys.button("방송 키 설정 저장") {
            val chosen = checks.filter { it.first.isChecked }.map { it.second }.toSet()
            if (chosen.isEmpty()) {
                toast("방송 키를 하나 이상 고르세요"); return@button
            }
            settings.triggerKeys = chosen
            settings.cancelTimeoutSec = timeoutEdit.text.toString().toIntOrNull() ?: 8
            timeoutEdit.setText(settings.cancelTimeoutSec.toString())
            settings.navAsDigits = navSwitch.isChecked
            KeypadInterceptService.reloadConfig()
            toast("저장했습니다")
        }

        // 키 입력 확인
        val keyCheck = page.section("키 입력 확인")
        keyCheck.label("키패드를 눌러 보면 마지막 10개 키 이름이 아래에 나옵니다(접근성 서비스가 켜져 있어야 함).", 13f, color = Color.GRAY)
        val keyList = keyCheck.label("")
        var showKeys = false
        keyCheck.button("키 입력 확인 켜기/끄기") { showKeys = !showKeys }
        refreshers += {
            keyList.text = if (!showKeys) "(꺼짐)" else KeypadInterceptService.recentKeys().joinToString("\n").ifEmpty { "아직 누른 키가 없습니다." }
        }

        // 테스트
        val test = page.section("테스트")
        val testResult = test.label("")
        test.button("테스트 방송") {
            saveNet()
            testResult.text = "보내는 중…"
            CallSender.send(this, null, test = true, source = Protocol.SOURCE_APP) { r ->
                testResult.text = when (r) {
                    is CallSender.Result.Ok -> "테스트 방송 요청 완료"
                    is CallSender.Result.Rejected -> "거절: ${CallSender.errorText(r.error)}"
                    CallSender.Result.Unreachable -> "방송 장치에 연결 안 됨"
                    CallSender.Result.Skipped -> ""
                }
            }
        }
        test.label("최근 전송 5건", 13f, color = Color.GRAY)
        val recent = test.label("")
        refreshers += { recent.text = settings.recentSends.joinToString("\n").ifEmpty { "없음" } }

        page.roleChangeSection()
    }

    // ───────── 방송용 설정 ─────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun showServer() {
        val page = newPage("호출 방송 · 방송용")

        // 주소·서버 상태
        val top = page.section("이 폰 주소")
        val addr = top.label("", 34f, bold = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val srv = top.label("")
        top.button("서버 다시 시작") {
            BroadcastService.restart(this); toast("다시 시작합니다")
        }
        refreshers += {
            val s = BroadcastService.instance
            addr.text = s?.addressText() ?: "시작 중…"
            val up = s?.serverRunning == true
            srv.text = if (up) "서버 켜짐 · 대기열 ${s?.queue?.size ?: 0}건" else "서버 꺼짐"
            srv.setTextColor(if (up) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }

        // TTS
        val tts = page.section("음성(TTS)")
        val ttsState = tts.label("")
        val ttsWarn = tts.label(
            "이 폰에 한국어 음성이 없습니다. [TTS 설정 열기]에서 삼성 또는 구글 TTS의 한국어 음성을 받으세요.",
            color = Color.parseColor("#C62828"),
        )
        tts.button("TTS 설정 열기") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        refreshers += {
            val st = BroadcastService.instance?.announcer?.state
            ttsState.text = "상태: ${st?.label ?: "준비 중"}"
            ttsWarn.visibility = if (st == Announcer.TtsState.NO_KOREAN) View.VISIBLE else View.GONE
        }

        // 테스트 방송
        val testCard = page.section("테스트 방송")
        val stores = settings.stores
        val testSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, stores.map { it.name })
        }
        testCard.addView(testSpinner)
        testCard.button("테스트 방송") {
            val svc = BroadcastService.instance
            if (svc == null) {
                toast("서버가 아직 시작되지 않았습니다"); return@button
            }
            val name = stores.getOrNull(testSpinner.selectedItemPosition)?.name ?: return@button
            val r = svc.enqueue(CallRequest(name, null, Protocol.SOURCE_APP, "방송 폰 설정 화면", true))
            toast(if (r.body.ok) "테스트 방송을 대기열에 넣었습니다" else "실패: ${r.body.error} (사용 안 하는 매장인지 확인)")
        }

        // 소리 설정
        val sound = page.section("소리")
        sound.slider("방송 음량", 0, 100, settings.volume, "") { settings.volume = it }
        sound.slider("말하기 속도", 50, 200, settings.speechRatePercent, "%") { settings.speechRatePercent = it }
        sound.slider("반복 횟수", 1, 3, settings.repeatCount, "회") { settings.repeatCount = it }
        sound.toggle("차임(딩동) 켜기", settings.chimeEnabled) { settings.chimeEnabled = it }
        sound.label("앞 무음(ms) — 블루투스에서 앞부분이 잘리면 늘리세요", 13f, color = Color.GRAY)
        val preEdit = sound.edit("500", settings.preSilenceMs.toString(), InputType.TYPE_CLASS_NUMBER)
        sound.label("중복 무시 시간(초)", 13f, color = Color.GRAY)
        val dupEdit = sound.edit("20", settings.duplicateSec.toString(), InputType.TYPE_CLASS_NUMBER)
        sound.button("저장") {
            settings.preSilenceMs = preEdit.text.toString().toIntOrNull() ?: 500
            settings.duplicateSec = dupEdit.text.toString().toIntOrNull() ?: 20
            preEdit.setText(settings.preSilenceMs.toString())
            dupEdit.setText(settings.duplicateSec.toString())
            toast("저장했습니다")
        }

        // 서버 설정
        val net = page.section("서버")
        net.label("포트", 13f, color = Color.GRAY)
        val portEdit = net.edit("8080", settings.port.toString(), InputType.TYPE_CLASS_NUMBER)
        net.label("토큰(비우면 확인 안 함). 넣으면 매장용 앱에도 같은 값을 넣어야 합니다.", 13f, color = Color.GRAY)
        val tokenEdit = net.edit("비워 두면 사용 안 함", settings.token)
        net.button("저장 후 서버 다시 시작") {
            settings.port = portEdit.text.toString().toIntOrNull() ?: Protocol.DEFAULT_PORT
            settings.token = tokenEdit.text.toString()
            portEdit.setText(settings.port.toString())
            BroadcastService.restart(this)
            toast("저장하고 다시 시작합니다")
        }

        // 매장 목록
        val storeCard = page.section("매장 목록")
        storeCard.label("문장의 {매장}은 읽는 이름, {번호}는 번호로 바뀝니다. 문장을 비우면 기본 문장을 씁니다.", 13f, color = Color.GRAY)
        storeCard.label("기본: ${DefaultStores.DEFAULT_TEMPLATE}", 13f, color = Color.GRAY)
        data class Row(val name: String, val spoken: EditText, val template: EditText, val enabled: Switch)
        val rows = settings.stores.map { st ->
            storeCard.label(st.name, 16f, bold = true)
            val spoken = storeCard.edit("읽는 이름", st.spokenName)
            val tmpl = storeCard.edit("문장(비우면 기본)", st.template)
            val en = storeCard.toggle("사용", st.enabled) {}
            Row(st.name, spoken, tmpl, en)
        }
        storeCard.button("매장 목록 저장") {
            settings.stores = rows.map {
                StoreConfig(it.name, it.spoken.text.toString().ifBlank { it.name }, it.template.text.toString(), it.enabled.isChecked)
            }
            toast("저장했습니다")
        }

        // 최근 방송
        val logCard = page.section("최근 방송 20건")
        val logText = logCard.label("")
        refreshers += {
            val entries = BroadcastService.instance?.callLog?.recent(20)?.filter { it.source != "system" }.orEmpty()
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
            logText.text = entries.joinToString("\n") {
                "${fmt.format(Date(it.time))}  ${it.store} ${if (it.test) "테스트" else "${it.number}번"} · ${it.source} · ${WebPages.resultText(it.result)}"
            }.ifEmpty { "아직 기록이 없습니다." }
        }

        page.batterySection()
        page.roleChangeSection()
    }
}
