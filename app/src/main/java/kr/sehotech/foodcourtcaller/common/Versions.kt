package kr.sehotech.foodcourtcaller.common

/** 버전 비교(순수 로직). "v0.2.1", "0.2.1" 모두 받는다. */
object Versions {
    fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.', '-', '+')
            .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** a가 b보다 새 버전이면 양수 */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0
}
