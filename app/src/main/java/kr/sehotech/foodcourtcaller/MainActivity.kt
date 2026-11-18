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
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    /** 업데이트 알림을 누르고 들어온 경우: 바로 받아서 설치 화면을 띄운다 */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action != kr.sehotech.foodcourtcaller.common.UpdateChecker.ACTION_UPDATE) return
        intent.action = null
        startUpdate { toast(it) }
    }

    private fun startUpdate(progress: (String) -> Unit) {
        progress("새 버전 확인 중…")
        kr.sehotech.foodcourtcaller.common.UpdateChecker.check { latest, text ->
            if (latest == null) progress(text)
            else kr.sehotech.foodcourtcaller.common.UpdateChecker.downloadAndInstall(this, latest, progress)
        }
    }

    private fun LinearLayout.updateSection() {
        val ui = kr.sehotech.foodcourtcaller.common.UpdateInstaller
        val c = section("앱 업데이트")
        val modeLine = c.label("", 16f, bold = true)
        val when_ = c.label("", 13f, color = Color.GRAY)
        val lastLine = c.label("", 13f, color = Color.GRAY)
        val setupBtn = c.button("자동 업데이트 켜기") {
            ui.setupSelf(this@MainActivity) { toast(it) }
        }
        val releaseBtn = c.button("전용 기기 모드 해제") {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("전용 기기 모드를 해제할까요?")
                .setMessage("해제하면 자동 업데이트 때 [설치]를 눌러야 할 수 있습니다. 다시 켜려면 태블릿 초기화 후 컴퓨터로 설정해야 합니다.")
                .setPositiveButton("해제") { _, _ ->
                    try {
                        getSystemService(android.app.admin.DevicePolicyManager::class.java).clearDeviceOwnerApp(packageName)
                        toast("해제했습니다")
                    } catch (e: Exception) {
                        toast("해제 실패: ${e.message}")
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
        c.label("지금 버전 ${BuildConfig.VERSION_NAME}", 13f, color = Color.GRAY)
        val st = c.label("", 15f, bold = true)
        c.button("지금 업데이트 확인·설치") { startUpdate { st.text = it } }
        refreshers += {
            val m = ui.mode(this@MainActivity)
            val ok = m == kr.sehotech.foodcourtcaller.common.UpdateInstaller.Mode.SILENT ||
                m == kr.sehotech.foodcourtcaller.common.UpdateInstaller.Mode.DEVICE_OWNER
            modeLine.text = (if (ok) "✔ " else "") + m.label
            modeLine.setTextColor(Color.parseColor(if (ok) "#2E7D32" else if (m == kr.sehotech.foodcourtcaller.common.UpdateInstaller.Mode.NEEDS_SETUP) "#C62828" else "#8D6E00"))
            setupBtn.visibility = if (m == kr.sehotech.foodcourtcaller.common.UpdateInstaller.Mode.NEEDS_SETUP) View.VISIBLE else View.GONE
            releaseBtn.visibility = if (m == kr.sehotech.foodcourtcaller.common.UpdateInstaller.Mode.DEVICE_OWNER) View.VISIBLE else View.GONE
            when_.text = if (ok) {
                val w = BroadcastService.instance?.updateWindowText()
                "새 버전은 영업이 끝난 뒤 저절로 설치됩니다" + if (w != null) ": $w" else "(방송 장치의 음악 끄는 시각 이후)"
            } else if (m == kr.sehotech.foodcourtcaller.common.UpdateInstaller.Mode.NEEDS_SETUP) {
                "설치할 때 한 번만: 아래 버튼을 누르고 설치 화면에서 [업데이트]를 누르면 다음부터 저절로 업데이트됩니다. ('이 출처 허용'이 먼저 뜨면 켜고 돌아오세요)"
            } else {
                "새 버전이 있으면 알림이 뜹니다. 알림을 누르고 [업데이트]를 누르세요."
            }
            lastLine.text = settings.autoResultText.ifBlank { "" }
            lastLine.visibility = if (settings.autoResultText.isBlank()) View.GONE else View.VISIBLE
        }
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
        val s = page.section("방송용 (앰프 옆 기기)")
        s.label("매장 태블릿과 직원 휴대폰에서 온 호출을 음성으로 방송합니다. 방송 기기에는 '호출 방송(방송용)' 앱을 따로 설치하는 것을 권합니다.")
        s.button("방송용으로 쓰기") {
            settings.role = AppSettings.ROLE_SERVER
            render()
        }
    }

    private fun LinearLayout.roleChangeSection() {
        val c = section("기타")
        c.label("앱 버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.SPEAKER_ONLY) " · 방송 전용" else "", color = Color.GRAY)
        if (BuildConfig.SPEAKER_ONLY) return
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
        val page = newPage("호출 방송 · 매장 태블릿 설정")

        // 화면이 열려 있는 동안만 기억하는 확인 결과
        var connectOk: Boolean? = null
        var testOk = false

        // ── 진행 요약 ──
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        page.addView(head)
        val headTitle = head.label("", 22f, bold = true)
        val headSub = head.label("", 14f)

        /** 단계 카드: 번호·제목·상태 줄. body에 버튼 등을 넣는다 */
        class Step(val title: TextView, val status: TextView, val body: LinearLayout)

        fun step(no: Int, name: String, help: String): Step {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            page.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            val t = card.label("$no. $name", 19f, bold = true)
            card.label(help, 13f, color = Color.GRAY)
            val st = card.label("", 16f, bold = true)
            return Step(t, st, card)
        }

        fun setStatus(s: Step, ok: Boolean?, text: String) {
            s.status.text = (when (ok) { true -> "✔ "; false -> "✖ "; null -> "… " }) + text
            s.status.setTextColor(Color.parseColor(when (ok) { true -> "#2E7D32"; false -> "#C62828"; null -> "#1F4E79" }))
            s.title.setTextColor(Color.parseColor(if (ok == true) "#2E7D32" else "#1D1D1F"))
        }

        fun bigButton(parent: LinearLayout, t: String, onClick: () -> Unit): Button =
            parent.button(t, onClick).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                minHeight = dp(56)
            }

        // ── 1. 매장 ──
        val s1 = step(1, "이 태블릿의 매장 고르기", "방송에서 읽을 매장 이름입니다.")
        val storeSpinner = Spinner(this)
        s1.body.addView(storeSpinner)
        var spinnerReady = false
        fun fillStores(names: List<String>) {
            val list = names.toMutableList()
            if (settings.storeName !in list) list.add(0, settings.storeName)
            spinnerReady = false
            storeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
            storeSpinner.setSelection(list.indexOf(settings.storeName))
            storeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (!spinnerReady) { spinnerReady = true; return }
                    settings.storeName = list[pos]
                    settings.storeConfirmed = true
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        fillStores(DefaultStores.list.filter { it.enabled }.map { it.name })
        bigButton(s1.body, "이 매장이 맞아요") {
            settings.storeConfirmed = true
            toast("${settings.storeName}(으)로 정했습니다")
        }

        // ── 2. 방송 장치 연결 ──
        val s2 = step(2, "방송 장치와 연결", "앰프 옆 방송 장치와 같은 와이파이여야 합니다. 버튼 하나로 찾고 확인합니다.")
        val addrLine = s2.body.label("", 14f, color = Color.parseColor("#555555"))
        fun checkConnection(discoverFirst: Boolean) {
            connectOk = null
            CallSender.runAsync({
                var addr = settings.serverAddress
                var found = false
                if (discoverFirst || addr.isBlank() || CallSender.get(addr, "/status") == null) {
                    CallSender.discover(this)?.let { addr = it; found = true }
                }
                Triple(addr, found, if (addr.isBlank()) "방송 장치를 찾지 못했습니다." else CallSender.statusText(addr))
            }) { (addr, found, text) ->
                if (found) settings.serverAddress = addr
                val ok = text.startsWith("연결됨")
                connectOk = ok
                if (ok) settings.autoConnect = true
                s2.status.tag = if (ok) "연결됨 · $addr (저장됨 — 다음부터 자동 연결)" else
                    "연결 안 됨 — 방송 장치가 켜져 있는지, 같은 와이파이인지 확인하세요"
                toast(if (ok) "방송 장치와 연결됨" else "연결 안 됨")
            }
        }
        bigButton(s2.body, "방송 장치 찾기·연결 확인") {
            settings.autoConnect = true
            s2.status.tag = "찾는 중…(최대 10초)"
            connectOk = null
            checkConnection(discoverFirst = false)
        }
        s2.body.label("한 번 연결되면 저장됩니다. 태블릿을 껐다 켜거나 방송 장치 주소가 바뀌어도 스스로 다시 연결합니다.", 13f, color = Color.GRAY)
        s2.body.button("연결 끊기") {
            AlertDialog.Builder(this)
                .setTitle("연결을 끊을까요?")
                .setMessage("이 태블릿은 방송을 보내지 않고, 자동으로 다시 연결하지도 않습니다. 다시 쓰려면 [방송 장치 찾기·연결 확인]을 누르세요.")
                .setPositiveButton("끊기") { _, _ ->
                    settings.autoConnect = false
                    settings.serverAddress = ""
                    connectOk = false
                    s2.status.tag = "연결 끊음 — 다시 쓰려면 [방송 장치 찾기·연결 확인]"
                    toast("연결을 끊었습니다")
                }
                .setNegativeButton("취소", null)
                .show()
        }
        // 직접 입력(자동 찾기가 안 될 때)
        val manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        s2.body.button("주소 직접 넣기") { manual.visibility = if (manual.visibility == View.GONE) View.VISIBLE else View.GONE }
        s2.body.addView(manual)
        manual.label("방송 장치 화면 맨 위의 주소(예: 192.168.0.44:8080)", 13f, color = Color.GRAY)
        val addrEdit = manual.edit("예: 192.168.0.44:8080", settings.serverAddress, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        manual.button("이 주소로 저장·확인") {
            settings.autoConnect = true
            settings.serverAddress = addrEdit.text.toString()
            addrEdit.setText(settings.serverAddress)
            s2.status.tag = "확인 중…"
            connectOk = null
            checkConnection(discoverFirst = false)
        }

        // ── 3. 키패드 기능(접근성) ──
        val s3 = step(
            3, "키패드 기능 켜기",
            "[설정 열기] → '설치된 앱'(또는 '다운로드한 앱') → '호출 방송' → 켜기 → 허용. 끝나면 뒤로 가기로 돌아오세요.",
        )
        bigButton(s3.body, "설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        // ── 4. 배터리 ──
        val s4 = step(4, "꺼지지 않게 하기", "배터리 절약 때문에 앱이 멈추지 않게 합니다. 뜨는 창에서 '허용'을 누르세요.")
        bigButton(s4.body, "배터리 제한 없음으로") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // ── 5. 테스트 ──
        val s5 = step(5, "테스트 방송", "스피커에서 테스트 방송이 나오면 끝입니다. 이후엔 키패드에서 - 번호 Enter.")
        bigButton(s5.body, "테스트 방송 보내기") {
            s5.status.tag = "보내는 중…"
            CallSender.send(this, null, test = true, source = Protocol.SOURCE_APP) { r ->
                testOk = r is CallSender.Result.Ok
                s5.status.tag = when (r) {
                    is CallSender.Result.Ok -> "보냈습니다. 스피커에서 소리가 났나요?"
                    is CallSender.Result.Rejected -> "거절: ${CallSender.errorText(r.error)}"
                    CallSender.Result.Unreachable -> "방송 장치에 연결 안 됨 — 2번을 다시 하세요"
                    CallSender.Result.Skipped -> ""
                }
            }
        }

        // ── 키패드 확인 ──
        val keyCheck = page.section("키패드 눌러 보기")
        keyCheck.label("키패드 키를 누르면 아래에 키 이름이 나옵니다. 아무것도 안 나오면 3번이 꺼져 있거나 키패드 연결을 확인하세요.", 13f, color = Color.GRAY)
        val keyList = keyCheck.label("", 14f)

        page.updateSection()

        // ── 고급 설정(접힘) ──
        val advWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        page.button("고급 설정 보기/숨기기") { advWrap.visibility = if (advWrap.visibility == View.GONE) View.VISIBLE else View.GONE }
        page.addView(advWrap)

        val adv = advWrap.section("방송 장치 · 기록")
        adv.button("방송 장치에서 매장 목록 받기") {
            val addr = settings.serverAddress
            CallSender.runAsync({ CallSender.fetchStores(addr) }) { names ->
                if (names == null) toast("방송 장치에 연결 안 됨") else {
                    fillStores(names); toast("매장 ${names.size}곳을 받았습니다")
                }
            }
        }
        adv.label("토큰(방송 장치에 설정한 경우만)", 13f, color = Color.GRAY)
        val tokenEdit = adv.edit("비워 두면 사용 안 함", settings.terminalToken)
        adv.label("이 기기 이름(기록용, 비우면 \"매장 이름 태블릿\")", 13f, color = Color.GRAY)
        val devEdit = adv.edit("예: 하즈벤 태블릿", settings.deviceName)
        adv.button("저장") {
            settings.terminalToken = tokenEdit.text.toString()
            settings.deviceName = devEdit.text.toString()
            toast("저장했습니다")
        }
        adv.label("최근 전송 5건", 13f, color = Color.GRAY)
        val recent = adv.label("")

        val keys = advWrap.section("방송 키")
        keys.label("이 키를 누르고 번호 + Enter를 치면 방송합니다. 원격 관리에서 '매장 태블릿 공통 설정'을 저장하면 그 값으로 바뀝니다.", 13f, color = Color.GRAY)
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
        advWrap.roleChangeSection()

        // ── 1초마다 상태 갱신 ──
        refreshers += {
            val st1 = settings.storeConfirmed
            setStatus(s1, if (st1) true else null, if (st1) "매장: ${settings.storeName}" else "매장을 고른 뒤 [이 매장이 맞아요]를 누르세요")

            addrLine.text = "주소: ${settings.serverAddress.ifBlank { "아직 없음" }}"
            val t2 = s2.status.tag as? String
            setStatus(s2, connectOk, t2 ?: if (settings.serverAddress.isBlank()) "아직 연결 안 함" else "확인 중…")

            val on = KeypadInterceptService.isEnabledInSettings(this)
            val run = KeypadInterceptService.connected
            setStatus(
                s3, on && run,
                when {
                    on && run -> "켜짐"
                    on -> "켜졌지만 아직 연결 안 됨 — 잠시 기다리거나 껐다 다시 켜세요"
                    else -> "꺼짐 — 키패드 방송이 안 됩니다"
                },
            )

            val bat = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            setStatus(s4, bat, if (bat) "제한 없음" else "아직 — 앱이 멈출 수 있습니다")

            val t5 = s5.status.tag as? String
            setStatus(s5, if (t5 == null) null else if (t5.startsWith("보내는")) null else testOk, t5 ?: "아직 안 함")

            val done = listOf(st1, connectOk == true, on && run, bat, testOk).count { it }
            headTitle.text = if (done == 5) "✔ 설정 끝! 키패드로 방송할 수 있습니다" else "설정 $done / 5 완료"
            headSub.text = if (done == 5) "매장: ${settings.storeName} · 방송 장치: ${settings.serverAddress}" else "위에서부터 차례로 초록색 ✔가 되게 하세요."
            head.setBackgroundColor(Color.parseColor(if (done == 5) "#E8F5E9" else "#FFF8E1"))
            headTitle.setTextColor(Color.parseColor(if (done == 5) "#1B5E20" else "#8D6E00"))

            keyList.text = KeypadInterceptService.recentKeys().take(5).joinToString("\n").ifEmpty { "아직 누른 키가 없습니다." }
            recent.text = settings.recentSends.joinToString("\n").ifEmpty { "없음" }
        }

        // 화면을 열면 바로 연결을 확인한다(주소가 없으면 자동으로 찾는다)
        if (settings.serverAddress.isBlank() && !settings.autoConnect) {
            connectOk = false
            s2.status.tag = "연결 끊음 — 다시 쓰려면 [방송 장치 찾기·연결 확인]"
        } else {
            s2.status.tag = if (settings.serverAddress.isBlank()) "방송 장치를 찾는 중…" else "연결 확인 중…"
            checkConnection(discoverFirst = false)
        }
        CallSender.heartbeat(this, KeypadInterceptService.connected)
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
        sound.slider("목소리 키우기 (0=원래 크기)", 0, 100, settings.voiceBoost, "") { settings.voiceBoost = it }
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

        // 음악 자동 켜기/끄기
        val mu = page.section("음악 자동 켜기/끄기")
        mu.label("영업 시작 시각에 음악 앱(유튜브 뮤직 등)을 재생하고, 끝나는 시각에 멈춥니다. 이 기기에서 음악을 트는 경우에만 동작합니다.", 13f, color = Color.GRAY)
        mu.label("유튜브 뮤직은 프리미엄이어야 화면이 꺼져도 재생됩니다. 영업 시작 전에 음악 앱에서 재생목록을 한 번 틀었다가 멈춰 두면 그 목록이 이어서 나옵니다.", 13f, color = Color.GRAY)
        val muSwitch = mu.toggle("자동 켜기/끄기 사용", settings.musicAutoEnabled) {}
        mu.label("켜는 시각 (예: 10:30)", 13f, color = Color.GRAY)
        val onEdit = mu.edit("10:30", kr.sehotech.foodcourtcaller.server.MusicSchedule.format(settings.musicOnMin))
        mu.label("끄는 시각 (예: 21:00)", 13f, color = Color.GRAY)
        val offEdit = mu.edit("21:00", kr.sehotech.foodcourtcaller.server.MusicSchedule.format(settings.musicOffMin))
        mu.button("음악 시간 저장") {
            val on = kr.sehotech.foodcourtcaller.server.MusicSchedule.parse(onEdit.text.toString())
            val off = kr.sehotech.foodcourtcaller.server.MusicSchedule.parse(offEdit.text.toString())
            if (on == null || off == null) {
                toast("시각을 10:30 처럼 넣어 주세요"); return@button
            }
            if (on == off) {
                toast("켜는 시각과 끄는 시각이 같습니다"); return@button
            }
            settings.musicOnMin = on
            settings.musicOffMin = off
            settings.musicAutoEnabled = muSwitch.isChecked
            onEdit.setText(kr.sehotech.foodcourtcaller.server.MusicSchedule.format(on))
            offEdit.setText(kr.sehotech.foodcourtcaller.server.MusicSchedule.format(off))
            toast(if (muSwitch.isChecked) "저장했습니다. 매일 ${onEdit.text}에 켜고 ${offEdit.text}에 끕니다" else "저장했습니다(자동 사용 꺼짐)")
        }
        mu.row(
            smallButton("지금 켜기 테스트") {
                BroadcastService.instance?.musicNow(true, "테스트") ?: toast("서버가 아직 시작되지 않았습니다")
            },
            smallButton("지금 끄기 테스트") {
                BroadcastService.instance?.musicNow(false, "테스트") ?: toast("서버가 아직 시작되지 않았습니다")
            },
        )
        val muState = mu.label("")
        refreshers += {
            val last = BroadcastService.instance?.musicLastText.orEmpty()
            val plan = if (settings.musicAutoEnabled) {
                "자동 사용 중 · 매일 ${kr.sehotech.foodcourtcaller.server.MusicSchedule.format(settings.musicOnMin)} 켜기 / ${kr.sehotech.foodcourtcaller.server.MusicSchedule.format(settings.musicOffMin)} 끄기"
            } else "자동 사용 안 함"
            muState.text = if (last.isEmpty()) plan else "$plan\n최근: $last"
        }

        // 원격 관리
        val ra = page.section("원격 관리")
        ra.label("같은 와이파이(또는 Tailscale로 연결된 휴대폰)의 브라우저에서 아래 주소로 들어가 상태 확인·설정 변경·테스트 방송을 할 수 있습니다. PIN을 정해야 켜집니다.", 13f, color = Color.GRAY)
        val raAddr = ra.label("", 17f, bold = true)
        refreshers += {
            val a = BroadcastService.instance?.addressText()
            raAddr.text = if (settings.adminPin.isBlank()) "꺼짐 — PIN을 정하세요" else "http://${a ?: "…"}/admin"
            raAddr.setTextColor(Color.parseColor(if (settings.adminPin.isBlank()) "#C62828" else "#1F4E79"))
        }
        ra.label("관리 PIN(숫자 4~8자리 권장)", 13f, color = Color.GRAY)
        val pinEdit = ra.edit("예: 4827", settings.adminPin, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        ra.button("PIN 저장") {
            val pin = pinEdit.text.toString().trim()
            if (pin.isNotEmpty() && pin.length < 4) {
                toast("4자리 이상으로 정하세요"); return@button
            }
            settings.adminPin = pin
            toast(if (pin.isEmpty()) "원격 관리를 껐습니다" else "저장했습니다. 주소로 들어가 PIN을 넣으세요")
        }

        page.updateSection()

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
