package kr.sehotech.foodcourtcaller.common

import kr.sehotech.foodcourtcaller.server.MusicSchedule

/** 자동 업데이트를 해도 되는 시간(영업이 끝난 뒤)인지 계산한다(순수 로직) */
object UpdateWindow {
    /** 음악 자동 켜기/끄기를 안 쓸 때 쓰는 기본 시간: 새벽 2:00~5:59 */
    const val NIGHT_START = 2 * 60
    const val NIGHT_END = 6 * 60

    /** 방송 장치에서 서버와 한 번이라도 연락이 되면 이 시간 동안 그 말을 믿는다 */
    const val CONTACT_VALID_MS = 10L * 60 * 1000

    fun night(nowMin: Int): Boolean = nowMin in NIGHT_START until NIGHT_END

    /** 방송 장치 기준: 음악 끄는 시각 뒤~켜는 시각 전이면 업데이트 시간 */
    fun serverAllows(nowMin: Int, musicAuto: Boolean, onMin: Int, offMin: Int): Boolean =
        if (musicAuto && onMin != offMin) !MusicSchedule.isOpen(nowMin, onMin, offMin) else night(nowMin)

    /**
     * 매장 태블릿 기준: 방송 장치와 최근 연락이 됐으면 방송 장치가 정한 대로,
     * 오래 연락이 안 됐으면 새벽 2~6시에만.
     */
    fun terminalAllows(now: Long, nowMin: Int, allowUntil: Long, lastContact: Long): Boolean =
        if (now - lastContact < CONTACT_VALID_MS) now < allowUntil else night(nowMin)
}
