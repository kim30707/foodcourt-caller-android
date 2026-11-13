package kr.sehotech.foodcourtcaller.common

import kr.sehotech.foodcourtcaller.server.AudioProgram
import kr.sehotech.foodcourtcaller.server.WavPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NumberRulesTest {
    @Test
    fun `번호는 숫자 1~5자리`() {
        assertTrue(NumberRules.isValid("0"))
        assertTrue(NumberRules.isValid("24"))
        assertTrue(NumberRules.isValid("12345"))
        assertFalse(NumberRules.isValid(""))
        assertFalse(NumberRules.isValid(null))
        assertFalse(NumberRules.isValid("123456"))
        assertFalse(NumberRules.isValid("2a"))
        assertFalse(NumberRules.isValid("-1"))
    }

    @Test
    fun `앞의 0을 떼고 읽는다`() {
        assertEquals("24", NumberRules.spoken("024"))
        assertEquals("24", NumberRules.spoken("24"))
        assertEquals("0", NumberRules.spoken("000"))
        assertEquals("0", NumberRules.spoken("0"))
        assertEquals("100", NumberRules.spoken("00100"))
    }

    @Test
    fun `방송 문장`() {
        val hz = DefaultStores.list.first { it.name == "하즈벤" }
        assertEquals("하즈벤 24번 고객님, 주문하신 음식이 나왔습니다.", DefaultStores.sentence(hz, "024"))
        val cp = DefaultStores.list.first { it.name == "컴포즈" }
        assertEquals("컴포즈 7번 고객님, 주문하신 음료가 나왔습니다.", DefaultStores.sentence(cp, "7"))
        assertFalse(cp.enabled)
        assertEquals("방송 테스트입니다. 하즈벤", DefaultStores.testSentence(hz))
    }

    @Test
    fun `WAV 읽기와 소리 이어 붙이기`() {
        val sr = 16_000
        val samples = ShortArray(1600) { (it % 100).toShort() }
        val wav = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sr); putInt(sr * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
            samples.forEach { putShort(it) }
        }.array()
        val pcm = WavPcm.parse(wav)
        assertNotNull(pcm)
        assertEquals(sr, pcm!!.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals(1600, pcm.data.size)

        val chime = AudioProgram.chime(sr).size
        val out = AudioProgram.build(pcm, preSilenceMs = 500, chime = true, repeat = 2, repeatGapMs = 800, tailMs = 250)
        // 앞 무음 + 차임 + 문장 + 사이 무음 + 문장 + 꼬리 무음
        assertEquals(8_000 + chime + 1600 + 12_800 + 1600 + 4_000, out.size)
        // 앞 무음은 정말 무음
        assertTrue((0 until 8_000).all { out[it] == 0.toShort() })

        val noChime = AudioProgram.build(pcm, preSilenceMs = 0, chime = false, repeat = 1, repeatGapMs = 800, tailMs = 0)
        assertEquals(1600, noChime.size)
    }
}
