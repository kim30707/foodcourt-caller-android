package kr.sehotech.foodcourtcaller.common

import kr.sehotech.foodcourtcaller.server.MusicSchedule
import kr.sehotech.foodcourtcaller.server.MusicSchedule.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressAndMusicTest {
    @Test
    fun addressCleanup() {
        // 현장에서 실제로 생긴 값: 키패드 숫자가 앞에 섞임
        assertEquals("192.168.0.44:8080", AddressRules.normalize("366192.168.0.44:8080"))
        assertEquals("192.168.0.44:8080", AddressRules.normalize("192.168.0.44"))
        assertEquals("192.168.0.44:9000", AddressRules.normalize(" http://192.168.0.44:9000/ "))
        assertEquals("192.168.0.44:8080", AddressRules.normalize("192.168.0.44:99999"))
        assertEquals("", AddressRules.normalize("   "))
        assertEquals("caller.local:8080", AddressRules.normalize("caller.local"))
    }

    @Test
    fun parseTimes() {
        assertEquals(10 * 60 + 30, MusicSchedule.parse("10:30"))
        assertEquals(9 * 60 + 5, MusicSchedule.parse("9:05"))
        assertEquals(21 * 60, MusicSchedule.parse("21"))
        assertEquals(21 * 60, MusicSchedule.parse("2100"))
        assertEquals(21 * 60 + 30, MusicSchedule.parse("21시 30분"))
        assertNull(MusicSchedule.parse("25:00"))
        assertNull(MusicSchedule.parse("abc"))
        assertEquals("09:05", MusicSchedule.format(9 * 60 + 5))
    }

    @Test
    fun dueAtBoundaries() {
        val on = 10 * 60 + 30
        val off = 21 * 60
        assertEquals(Action.PLAY, MusicSchedule.due(on - 1, on, on, off))
        assertEquals(Action.PAUSE, MusicSchedule.due(off - 1, off, on, off))
        assertNull(MusicSchedule.due(on, on, on, off))          // 같은 분에 두 번 안 함
        assertNull(MusicSchedule.due(on, on + 1, on, off))
        // 자정 넘김: 23:59 → 00:01 사이 00:00에 끄기
        assertEquals(Action.PAUSE, MusicSchedule.due(23 * 60 + 59, 1, on, 0))
        // 기기가 잠깐 멈춰 두 시각을 모두 지나면 나중 것
        assertEquals(Action.PAUSE, MusicSchedule.due(on - 1, off + 1, on, off))
    }

    @Test
    fun openHours() {
        assertTrue(MusicSchedule.isOpen(12 * 60, 10 * 60, 21 * 60))
        assertFalse(MusicSchedule.isOpen(22 * 60, 10 * 60, 21 * 60))
        assertTrue(MusicSchedule.isOpen(1 * 60, 18 * 60, 2 * 60)) // 새벽까지 영업
    }
}
