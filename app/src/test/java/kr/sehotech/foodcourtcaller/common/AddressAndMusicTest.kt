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

class RemoteAdminTest {
    @Test
    fun versions() {
        assertTrue(Versions.isNewer("v0.3.0", "0.2.1"))
        assertTrue(Versions.isNewer("0.10.0", "0.9.9"))
        assertFalse(Versions.isNewer("0.2.1", "0.2.1"))
        assertFalse(Versions.isNewer("0.2.0", "0.2.1"))
        assertTrue(Versions.isNewer("1.0", "0.0.42"))
    }

    @Test
    fun pinGuardLocksAfterFiveFails() {
        val g = kr.sehotech.foodcourtcaller.server.PinGuard()
        assertEquals(kr.sehotech.foodcourtcaller.server.PinGuard.Check.NOT_SET, g.check("", "1"))
        assertEquals(kr.sehotech.foodcourtcaller.server.PinGuard.Check.OK, g.check("4827", "4827", 0))
        repeat(4) { assertEquals(kr.sehotech.foodcourtcaller.server.PinGuard.Check.WRONG, g.check("4827", "0000", 0)) }
        assertEquals(kr.sehotech.foodcourtcaller.server.PinGuard.Check.LOCKED, g.check("4827", "0000", 0))
        // 잠긴 동안은 맞는 PIN도 거절
        assertEquals(kr.sehotech.foodcourtcaller.server.PinGuard.Check.LOCKED, g.check("4827", "4827", 1000))
        assertEquals(kr.sehotech.foodcourtcaller.server.PinGuard.Check.OK, g.check("4827", "4827", 6 * 60 * 1000))
    }

    @Test
    fun registryMarksOutdatedAndOffline() {
        val r = kr.sehotech.foodcourtcaller.server.DeviceRegistry()
        assertTrue(r.update("""{"store":"하즈벤","device":"하즈벤 태블릿","version":"0.2.1","accessibility":true,"battery":true}""", "192.168.0.38", 0))
        assertFalse(r.update("not json", "x", 0))
        val a = r.toJson("0.3.0", now = 4 * 60 * 1000)
        assertEquals(1, a.length())
        assertTrue(a.getJSONObject(0).getBoolean("outdated"))
        assertFalse(a.getJSONObject(0).getBoolean("online"))
    }
}
