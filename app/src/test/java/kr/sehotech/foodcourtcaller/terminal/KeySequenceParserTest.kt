package kr.sehotech.foodcourtcaller.terminal

import kr.sehotech.foodcourtcaller.terminal.KeySequenceParser.CancelReason
import kr.sehotech.foodcourtcaller.terminal.KeySequenceParser.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeySequenceParserTest {
    private var now = 1_000_000L
    private lateinit var p: KeySequenceParser
    private val events = mutableListOf<Event>()

    @Before
    fun setUp() {
        p = KeySequenceParser { now }
        events.clear()
    }

    /** 키 한 번 누르고 떼기. DOWN/UP 소비 여부를 돌려준다. */
    private fun tap(code: Int): Pair<Boolean, Boolean> {
        val d = p.onKey(code, true, 0)
        events += d.events
        val u = p.onKey(code, false, 0)
        events += u.events
        return d.consume to u.consume
    }

    private fun numpad(d: Int) = KeyCodes.NUMPAD_0 + d
    private fun submitted() = events.filterIsInstance<Event.Submitted>().map { it.number }
    private fun cancelled() = events.filterIsInstance<Event.Cancelled>().map { it.reason }

    @Test
    fun `평소 숫자와 Enter는 그대로 통과`() {
        assertEquals(false to false, tap(numpad(2)))
        assertEquals(false to false, tap(numpad(4)))
        assertEquals(false to false, tap(KeyCodes.ENTER))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `- 2 4 Enter는 24번 전송, 모든 키 소비`() {
        assertEquals(true to true, tap(KeyCodes.NUMPAD_SUBTRACT))
        assertTrue(p.isArmed)
        assertEquals(true to true, tap(numpad(2)))
        assertEquals(true to true, tap(numpad(4)))
        assertEquals("24", p.currentNumber)
        assertEquals(true to true, tap(KeyCodes.NUMPAD_ENTER))
        assertEquals(listOf("24"), submitted())
        assertFalse(p.isArmed)
        // 전송 뒤에는 다시 평소 상태
        assertEquals(false to false, tap(numpad(1)))
    }

    @Test
    fun `일반 MINUS 키와 일반 숫자·Enter도 동작`() {
        tap(KeyCodes.MINUS)
        tap(KeyCodes.KEY_0 + 7)
        tap(KeyCodes.ENTER)
        assertEquals(listOf("7"), submitted())
    }

    @Test
    fun `Backspace는 마지막 숫자를 지운다`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(2))
        assertEquals(true to true, tap(KeyCodes.DEL))
        tap(numpad(3))
        tap(KeyCodes.ENTER)
        assertEquals(listOf("3"), submitted())

        events.clear()
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(2))
        tap(numpad(4))
        tap(KeyCodes.DEL)
        tap(numpad(3))
        tap(KeyCodes.ENTER)
        assertEquals(listOf("23"), submitted())
    }

    @Test
    fun `- Enter는 취소`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        assertEquals(true to true, tap(KeyCodes.ENTER))
        assertEquals(listOf(CancelReason.EMPTY_ENTER), cancelled())
        assertTrue(submitted().isEmpty())
        assertFalse(p.isArmed)
    }

    @Test
    fun `방송 키를 다시 누르면 취소하고 그 키도 소비`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(5))
        assertEquals(true to true, tap(KeyCodes.NUMPAD_SUBTRACT))
        assertEquals(listOf(CancelReason.TRIGGER_AGAIN), cancelled())
        assertFalse(p.isArmed)
    }

    @Test
    fun `그 밖의 키는 취소하고 그대로 전달`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(5))
        assertEquals(false to false, tap(KeyCodes.NUMPAD_ADD))
        assertEquals(listOf(CancelReason.OTHER_KEY), cancelled())
        assertFalse(p.isArmed)
    }

    @Test
    fun `보조 키(Num Lock, Shift)는 취소하지 않는다`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        assertEquals(false to false, tap(KeyCodes.NUM_LOCK))
        assertTrue(p.isArmed)
        tap(numpad(9))
        tap(KeyCodes.ENTER)
        assertEquals(listOf("9"), submitted())
    }

    @Test
    fun `8초 입력 없으면 자동 취소`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        now += 7_999
        assertEquals(null, p.checkTimeout())
        assertTrue(p.isArmed)
        now += 1
        assertEquals(Event.Cancelled(CancelReason.TIMEOUT), p.checkTimeout())
        assertFalse(p.isArmed)
    }

    @Test
    fun `시간이 지난 뒤 숫자는 평소처럼 통과`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(1))
        now += 9_000
        assertEquals(false to false, tap(numpad(2)))
        assertEquals(listOf(CancelReason.TIMEOUT), cancelled())
        assertEquals(false to false, tap(KeyCodes.ENTER))
        assertTrue(submitted().isEmpty())
    }

    @Test
    fun `입력할 때마다 자동 취소 시간이 다시 시작`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        now += 6_000
        tap(numpad(1))
        now += 6_000
        assertEquals(null, p.checkTimeout())
        tap(KeyCodes.ENTER)
        assertEquals(listOf("1"), submitted())
    }

    @Test
    fun `자동 취소 시간은 설정 가능`() {
        p.config = p.config.copy(timeoutMs = 3_000)
        tap(KeyCodes.NUMPAD_SUBTRACT)
        now += 3_000
        assertEquals(Event.Cancelled(CancelReason.TIMEOUT), p.checkTimeout())
    }

    @Test
    fun `최대 5자리`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        listOf(1, 2, 3, 4, 5, 6, 7).forEach { assertEquals(true to true, tap(numpad(it))) }
        tap(KeyCodes.ENTER)
        assertEquals(listOf("12345"), submitted())
    }

    @Test
    fun `앞의 0도 그대로 보낸다(읽을 때 뗀다)`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(0)); tap(numpad(2)); tap(numpad(4))
        tap(KeyCodes.ENTER)
        assertEquals(listOf("024"), submitted())
    }

    @Test
    fun `길게 누른 반복 이벤트는 무시하고, 소비한 키의 반복은 계속 소비`() {
        assertTrue(p.onKey(KeyCodes.NUMPAD_SUBTRACT, true, 0).consume)
        assertTrue(p.onKey(KeyCodes.NUMPAD_SUBTRACT, true, 1).consume)
        assertTrue(p.onKey(KeyCodes.NUMPAD_SUBTRACT, true, 2).consume)
        assertTrue(p.onKey(KeyCodes.NUMPAD_SUBTRACT, false, 0).consume)
        assertTrue(p.isArmed) // 반복으로 취소되지 않음

        assertTrue(p.onKey(numpad(3), true, 0).consume)
        assertTrue(p.onKey(numpad(3), true, 1).consume)
        assertTrue(p.onKey(numpad(3), false, 0).consume)
        assertEquals("3", p.currentNumber) // 반복으로 숫자가 늘지 않음
    }

    @Test
    fun `평소 길게 누른 키의 반복은 통과`() {
        assertFalse(p.onKey(numpad(3), true, 0).consume)
        assertFalse(p.onKey(numpad(3), true, 1).consume)
        assertFalse(p.onKey(numpad(3), false, 0).consume)
    }

    @Test
    fun `Enter DOWN으로 전송한 뒤 UP도 소비`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(8))
        assertTrue(p.onKey(KeyCodes.ENTER, true, 0).consume)
        assertFalse(p.isArmed)
        assertTrue(p.onKey(KeyCodes.ENTER, false, 0).consume)
        // 다음 Enter는 통과
        assertFalse(p.onKey(KeyCodes.ENTER, true, 0).consume)
        assertFalse(p.onKey(KeyCodes.ENTER, false, 0).consume)
    }

    @Test
    fun `이동 키는 기본으로 숫자가 아니다`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        assertEquals(false to false, tap(KeyCodes.MOVE_END))
        assertEquals(listOf(CancelReason.OTHER_KEY), cancelled())
    }

    @Test
    fun `이동 키를 숫자로 해석 켜면 방송 입력 중에만 숫자`() {
        p.config = p.config.copy(navAsDigits = true)
        // 평소에는 그대로 통과
        assertEquals(false to false, tap(KeyCodes.DPAD_DOWN))
        tap(KeyCodes.NUMPAD_SUBTRACT)
        val seq = listOf(
            KeyCodes.MOVE_END, KeyCodes.DPAD_DOWN, KeyCodes.PAGE_DOWN, KeyCodes.DPAD_LEFT,
            KeyCodes.DPAD_RIGHT,
        )
        seq.forEach { assertEquals(true to true, tap(it)) }
        tap(KeyCodes.ENTER)
        assertEquals(listOf("12346"), submitted())

        events.clear()
        tap(KeyCodes.NUMPAD_SUBTRACT)
        listOf(KeyCodes.MOVE_HOME, KeyCodes.DPAD_UP, KeyCodes.PAGE_UP, KeyCodes.INSERT).forEach { tap(it) }
        tap(KeyCodes.ENTER)
        assertEquals(listOf("7890"), submitted())
    }

    @Test
    fun `방송 키를 바꿀 수 있다`() {
        p.config = p.config.copy(triggerKeys = setOf(KeyCodes.NUMPAD_MULTIPLY))
        assertEquals(false to false, tap(KeyCodes.NUMPAD_SUBTRACT))
        assertEquals(true to true, tap(KeyCodes.NUMPAD_MULTIPLY))
        tap(numpad(1))
        tap(KeyCodes.ENTER)
        assertEquals(listOf("1"), submitted())
    }

    @Test
    fun `번호가 바뀔 때마다 Updated 이벤트`() {
        tap(KeyCodes.NUMPAD_SUBTRACT)
        tap(numpad(2))
        tap(numpad(4))
        val updates = events.filterIsInstance<Event.Updated>().map { it.number }
        assertEquals(listOf("2", "24"), updates)
        assertEquals(Event.Started, events.first())
    }
}
