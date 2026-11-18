package kr.sehotech.foodcourtcaller.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateWindowTest {
    private fun m(h: Int, min: Int = 0) = h * 60 + min

    @Test
    fun musicAutoUsesClosedHours() {
        // 10:30 켜기, 21:00 끄기 → 21:00~10:29 업데이트 시간
        assertFalse(UpdateWindow.serverAllows(m(12), true, m(10, 30), m(21)))
        assertFalse(UpdateWindow.serverAllows(m(20, 59), true, m(10, 30), m(21)))
        assertTrue(UpdateWindow.serverAllows(m(21), true, m(10, 30), m(21)))
        assertTrue(UpdateWindow.serverAllows(m(3), true, m(10, 30), m(21)))
        assertFalse(UpdateWindow.serverAllows(m(10, 30), true, m(10, 30), m(21)))
    }

    @Test
    fun overnightBusinessHours() {
        // 17:00 켜기, 02:00 끄기 → 02:00~16:59
        assertFalse(UpdateWindow.serverAllows(m(23), true, m(17), m(2)))
        assertFalse(UpdateWindow.serverAllows(m(1, 59), true, m(17), m(2)))
        assertTrue(UpdateWindow.serverAllows(m(2), true, m(17), m(2)))
        assertTrue(UpdateWindow.serverAllows(m(16, 59), true, m(17), m(2)))
    }

    @Test
    fun withoutMusicAutoUsesNight() {
        assertFalse(UpdateWindow.serverAllows(m(1, 59), false, m(10, 30), m(21)))
        assertTrue(UpdateWindow.serverAllows(m(2), false, m(10, 30), m(21)))
        assertTrue(UpdateWindow.serverAllows(m(5, 59), false, m(10, 30), m(21)))
        assertFalse(UpdateWindow.serverAllows(m(6), false, m(10, 30), m(21)))
        // 켜기=끄기(하루 종일 영업)도 새벽 시간
        assertTrue(UpdateWindow.serverAllows(m(3), true, m(9), m(9)))
    }

    @Test
    fun terminalFollowsServerWhileInContact() {
        val now = 1_000_000_000L
        assertTrue(UpdateWindow.terminalAllows(now, m(12), allowUntil = now + 1000, lastContact = now - 1000))
        assertFalse(UpdateWindow.terminalAllows(now, m(3), allowUntil = 0, lastContact = now - 1000))
    }

    @Test
    fun terminalFallsBackToNightWithoutServer() {
        val now = 1_000_000_000L
        val old = now - UpdateWindow.CONTACT_VALID_MS - 1
        assertFalse(UpdateWindow.terminalAllows(now, m(12), allowUntil = now + 1000, lastContact = old))
        assertTrue(UpdateWindow.terminalAllows(now, m(3), allowUntil = 0, lastContact = old))
    }
}
