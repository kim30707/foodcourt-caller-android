package kr.sehotech.foodcourtcaller.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class CallQueueTest {
    private var now = 5_000_000L
    private fun q(window: Long = 20_000, max: Int = 30) = CallQueue(window, max) { now }

    @Test
    fun `들어온 순서대로 한 건씩 꺼낸다`() {
        val q = q()
        assertEquals(1, q.offer("하즈벤", "24", "keypad", "t", false).position)
        assertEquals(2, q.offer("달순", "7", "keypad", "t", false).position)
        assertEquals(3, q.offer("하즈벤", "25", "web", "p", false).position)
        assertEquals("24", q.poll()?.number)
        assertEquals("7", q.poll()?.number)
        assertEquals("25", q.poll()?.number)
        assertNull(q.poll())
    }

    @Test
    fun `20초 안에 같은 매장·번호는 중복`() {
        val q = q()
        assertTrue(q.offer("하즈벤", "24", "keypad", "t", false).queued)
        now += 19_999
        val r = q.offer("하즈벤", "24", "web", "p", false)
        assertFalse(r.queued)
        assertTrue(r.duplicate)
        assertEquals(1, q.size)
    }

    @Test
    fun `20초가 지나면 다시 방송`() {
        val q = q()
        q.offer("하즈벤", "24", "keypad", "t", false)
        now += 20_000
        assertTrue(q.offer("하즈벤", "24", "keypad", "t", false).queued)
    }

    @Test
    fun `중복 판정은 처음 받은 시각 기준(무시된 요청은 시간을 늘리지 않는다)`() {
        val q = q()
        q.offer("하즈벤", "24", "keypad", "t", false)
        now += 15_000
        assertTrue(q.offer("하즈벤", "24", "keypad", "t", false).duplicate)
        now += 5_000
        assertTrue(q.offer("하즈벤", "24", "keypad", "t", false).queued)
    }

    @Test
    fun `다른 매장의 같은 번호, 같은 매장의 다른 번호는 중복 아님`() {
        val q = q()
        assertTrue(q.offer("하즈벤", "24", "keypad", "t", false).queued)
        assertTrue(q.offer("달순", "24", "keypad", "t", false).queued)
        assertTrue(q.offer("하즈벤", "25", "keypad", "t", false).queued)
    }

    @Test
    fun `매장 이름 앞뒤 공백과 번호 앞의 0은 무시하고 비교`() {
        val q = q()
        q.offer("하즈벤", "24", "keypad", "t", false)
        assertTrue(q.offer("  하즈벤 ", "024", "web", "p", false).duplicate)
    }

    @Test
    fun `테스트 방송은 중복으로 막지 않는다`() {
        val q = q()
        assertTrue(q.offer("하즈벤", null, "app", "t", true).queued)
        assertTrue(q.offer("하즈벤", null, "app", "t", true).queued)
        assertEquals(2, q.size)
    }

    @Test
    fun `중복 무시 시간은 설정 가능`() {
        val q = q(window = 5_000)
        q.offer("하즈벤", "24", "keypad", "t", false)
        now += 5_000
        assertTrue(q.offer("하즈벤", "24", "keypad", "t", false).queued)
        q.duplicateWindowMs = 60_000
        now += 30_000
        assertTrue(q.offer("하즈벤", "24", "keypad", "t", false).duplicate)
    }

    @Test
    fun `30건을 넘으면 가장 오래된 건을 버린다`() {
        val q = q()
        for (i in 1..30) assertNull(q.offer("하즈벤", "$i", "keypad", "t", false).dropped)
        val r = q.offer("하즈벤", "31", "keypad", "t", false)
        assertTrue(r.queued)
        assertEquals("1", r.dropped?.number)
        assertEquals(30, q.size)
        assertEquals("2", q.poll()?.number)
    }

    @Test
    fun `take는 새 요청이 올 때까지 기다린다`() {
        val q = CallQueue(20_000, 30)
        var got: String? = null
        val t = thread { got = q.take(3_000)?.number }
        Thread.sleep(100)
        q.offer("하즈벤", "77", "keypad", "t", false)
        t.join(3_000)
        assertEquals("77", got)
    }

    @Test
    fun `take는 시간이 지나면 null`() {
        val q = CallQueue(20_000, 30)
        assertNull(q.take(50))
    }
}
