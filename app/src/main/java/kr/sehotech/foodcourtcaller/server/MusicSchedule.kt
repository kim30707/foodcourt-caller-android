package kr.sehotech.foodcourtcaller.server

/** 영업시간 음악 자동 켜기/끄기 시각 계산(순수 로직). 시각은 하루 중 분(0~1439). */
object MusicSchedule {
    enum class Action { PLAY, PAUSE }

    /** "9:30", "09:30", "0930", "21" → 분. 잘못된 값이면 null */
    fun parse(text: String): Int? {
        val t = text.trim().replace("시", ":").replace("분", "").replace(" ", "")
        val (h, m) = when {
            t.contains(':') -> t.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.ifEmpty { "0" }?.toIntOrNull() ?: return null) }
            t.length in 3..4 && t.all { it.isDigit() } -> t.dropLast(2).toInt() to t.takeLast(2).toInt()
            t.length in 1..2 && t.all { it.isDigit() } -> t.toInt() to 0
            else -> return null
        }
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun format(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    /** (prev, now] 구간에 시각 target이 들어 있는지. 자정을 넘는 경우도 처리 */
    private fun crossed(prev: Int, now: Int, target: Int): Boolean = when {
        prev == now -> false
        prev < now -> target in (prev + 1)..now
        else -> target > prev || target <= now
    }

    /**
     * 지난번 확인 시각(prev)과 지금(now) 사이에 켜기/끄기 시각을 지났으면 할 일을 돌려준다.
     * 둘 다 지났으면 나중 것(지금 상태에 맞는 것)을 고른다.
     */
    fun due(prev: Int, now: Int, onMin: Int, offMin: Int): Action? {
        val on = crossed(prev, now, onMin)
        val off = crossed(prev, now, offMin)
        if (on && off) {
            fun dist(t: Int) = (now - t + 1440) % 1440
            return if (dist(onMin) < dist(offMin)) Action.PLAY else Action.PAUSE
        }
        return when {
            on -> Action.PLAY
            off -> Action.PAUSE
            else -> null
        }
    }

    /** 지금이 영업시간(켜져 있어야 할 시간)인지 */
    fun isOpen(now: Int, onMin: Int, offMin: Int): Boolean = when {
        onMin == offMin -> true
        onMin < offMin -> now in onMin until offMin
        else -> now >= onMin || now < offMin
    }
}
