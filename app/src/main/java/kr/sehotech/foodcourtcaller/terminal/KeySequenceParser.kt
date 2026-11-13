package kr.sehotech.foodcourtcaller.terminal

/**
 * 안드로이드 KeyEvent 키 코드 값(android.view.KeyEvent와 같은 숫자).
 * 해석 로직을 안드로이드 없이 단위 테스트하려고 따로 둔다.
 */
object KeyCodes {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val KEY_0 = 7
    const val KEY_9 = 16
    const val SHIFT_LEFT = 59
    const val SHIFT_RIGHT = 60
    const val ENTER = 66
    const val DEL = 67
    const val MINUS = 69
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val CTRL_LEFT = 113
    const val CTRL_RIGHT = 114
    const val ALT_LEFT = 57
    const val ALT_RIGHT = 58
    const val META_LEFT = 117
    const val META_RIGHT = 118
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val INSERT = 124
    const val NUM_LOCK = 143
    const val NUMPAD_0 = 144
    const val NUMPAD_9 = 153
    const val NUMPAD_DIVIDE = 154
    const val NUMPAD_MULTIPLY = 155
    const val NUMPAD_SUBTRACT = 156
    const val NUMPAD_ADD = 157
    const val NUMPAD_DOT = 158
    const val NUMPAD_ENTER = 160

    /** 눌러도 방송 입력을 취소하지 않는 보조 키 */
    val MODIFIERS = setOf(
        SHIFT_LEFT, SHIFT_RIGHT, CTRL_LEFT, CTRL_RIGHT, ALT_LEFT, ALT_RIGHT,
        META_LEFT, META_RIGHT, NUM_LOCK,
    )

    /** Num Lock이 꺼진 키패드가 보내는 이동 키 → 숫자 */
    val NAV_AS_DIGIT = mapOf(
        MOVE_END to '1', DPAD_DOWN to '2', PAGE_DOWN to '3',
        DPAD_LEFT to '4', DPAD_RIGHT to '6',
        MOVE_HOME to '7', DPAD_UP to '8', PAGE_UP to '9',
        INSERT to '0',
    )

    /** 설정 화면에서 고를 수 있는 방송 키 */
    val TRIGGER_CHOICES: List<Pair<Int, String>> = listOf(
        NUMPAD_SUBTRACT to "키패드 -",
        MINUS to "일반 -",
        NUMPAD_MULTIPLY to "키패드 *",
        NUMPAD_DIVIDE to "키패드 /",
        NUMPAD_ADD to "키패드 +",
        NUMPAD_DOT to "키패드 .",
    )
}

/**
 * 키패드 입력 해석기(순수 로직).
 *
 * 평소: 모든 키 통과. 방송 키 → "방송 입력 중".
 * 방송 입력 중: 숫자는 번호에 붙이고(최대 5자리), Backspace는 지우고, Enter는 전송(번호 없으면 취소).
 * 방송 키를 다시 누르거나 다른 키를 누르면 취소. 일정 시간 입력이 없으면 자동 취소.
 * 소비한 키는 눌림(DOWN)과 뗌(UP)을 둘 다 소비한다. 길게 눌러 생기는 반복 이벤트는 무시한다.
 */
class KeySequenceParser(private val clock: () -> Long = System::currentTimeMillis) {

    data class Config(
        val triggerKeys: Set<Int> = setOf(KeyCodes.NUMPAD_SUBTRACT, KeyCodes.MINUS),
        val timeoutMs: Long = 8_000,
        val navAsDigits: Boolean = false,
        val maxDigits: Int = 5,
    )

    enum class CancelReason { EMPTY_ENTER, TRIGGER_AGAIN, OTHER_KEY, TIMEOUT }

    sealed class Event {
        /** 방송 입력 시작 */
        object Started : Event()

        /** 번호가 바뀜(화면 배너 갱신) */
        data class Updated(val number: String) : Event()

        /** Enter로 번호 확정 → 전송 */
        data class Submitted(val number: String) : Event()

        data class Cancelled(val reason: CancelReason) : Event()
    }

    /** consume=true면 이 키를 기존 호출 앱에 전달하지 않는다 */
    data class Decision(val consume: Boolean, val events: List<Event> = emptyList())

    var config: Config = Config()

    private var armed = false
    private val buffer = StringBuilder()
    private var lastInputAt = 0L

    /** DOWN을 소비한 키. 짝이 되는 UP과 반복 이벤트도 소비한다. */
    private val consumedDown = mutableSetOf<Int>()

    val isArmed: Boolean get() = armed
    val currentNumber: String get() = buffer.toString()

    /** 자동 취소 확인. 시간이 지났으면 취소 이벤트를 돌려준다. */
    fun checkTimeout(): Event? {
        if (armed && clock() - lastInputAt >= config.timeoutMs) {
            reset()
            return Event.Cancelled(CancelReason.TIMEOUT)
        }
        return null
    }

    fun onKey(keyCode: Int, isDown: Boolean, repeatCount: Int = 0): Decision {
        // 뗌(UP): DOWN을 소비했던 키만 소비한다.
        if (!isDown) {
            return Decision(consumedDown.remove(keyCode))
        }
        // 길게 눌러 생긴 반복: 동작 없음. DOWN을 소비한 키면 계속 소비.
        if (repeatCount > 0) {
            return Decision(consumedDown.contains(keyCode))
        }

        val events = mutableListOf<Event>()
        checkTimeout()?.let { events += it }

        if (!armed) {
            if (keyCode in config.triggerKeys) {
                armed = true
                buffer.clear()
                lastInputAt = clock()
                events += Event.Started
                return consume(keyCode, events)
            }
            return Decision(false, events)
        }

        // 보조 키는 그대로 통과(취소하지 않음)
        if (keyCode in KeyCodes.MODIFIERS) return Decision(false, events)

        val digit = digitOf(keyCode)
        return when {
            digit != null -> {
                if (buffer.length < config.maxDigits) buffer.append(digit)
                lastInputAt = clock()
                events += Event.Updated(buffer.toString())
                consume(keyCode, events)
            }

            keyCode == KeyCodes.DEL -> {
                if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                lastInputAt = clock()
                events += Event.Updated(buffer.toString())
                consume(keyCode, events)
            }

            keyCode == KeyCodes.ENTER || keyCode == KeyCodes.NUMPAD_ENTER -> {
                val number = buffer.toString()
                reset()
                events += if (number.isEmpty()) Event.Cancelled(CancelReason.EMPTY_ENTER)
                else Event.Submitted(number)
                consume(keyCode, events)
            }

            keyCode in config.triggerKeys -> {
                reset()
                events += Event.Cancelled(CancelReason.TRIGGER_AGAIN)
                consume(keyCode, events)
            }

            else -> {
                // 그 밖의 키: 취소하고 키는 원래대로 전달
                reset()
                events += Event.Cancelled(CancelReason.OTHER_KEY)
                Decision(false, events)
            }
        }
    }

    private fun digitOf(keyCode: Int): Char? = when (keyCode) {
        in KeyCodes.KEY_0..KeyCodes.KEY_9 -> '0' + (keyCode - KeyCodes.KEY_0)
        in KeyCodes.NUMPAD_0..KeyCodes.NUMPAD_9 -> '0' + (keyCode - KeyCodes.NUMPAD_0)
        else -> if (config.navAsDigits) KeyCodes.NAV_AS_DIGIT[keyCode] else null
    }

    private fun consume(keyCode: Int, events: List<Event>): Decision {
        consumedDown += keyCode
        return Decision(true, events)
    }

    private fun reset() {
        armed = false
        buffer.clear()
    }
}
