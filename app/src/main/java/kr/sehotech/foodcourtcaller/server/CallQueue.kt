package kr.sehotech.foodcourtcaller.server

import kr.sehotech.foodcourtcaller.common.NumberRules

/**
 * 방송 대기열(순수 로직). 요청을 한 줄로 세워 한 건씩 꺼낸다.
 * - 같은 매장·같은 번호가 중복 무시 시간 안에 다시 오면 넣지 않는다(duplicate).
 * - 대기열이 최대 개수를 넘으면 가장 오래된 건을 버린다.
 * 여러 스레드(HTTP 서버, 방송 작업자)에서 함께 쓰므로 잠금으로 보호한다.
 */
class CallQueue(
    @Volatile var duplicateWindowMs: Long = 20_000,
    val maxSize: Int = 30,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Item(
        val store: String,
        val number: String?,
        val source: String,
        val device: String,
        val test: Boolean,
        val receivedAt: Long,
    )

    data class OfferResult(
        val queued: Boolean,
        val duplicate: Boolean,
        /** 대기열에서의 순서(1부터) */
        val position: Int,
        /** 넘쳐서 버린 항목 */
        val dropped: Item? = null,
    )

    private val lock = Object()
    private val queue = ArrayDeque<Item>()
    private val lastAccepted = HashMap<String, Long>()

    val size: Int get() = synchronized(lock) { queue.size }

    fun offer(store: String, number: String?, source: String, device: String, test: Boolean): OfferResult =
        synchronized(lock) {
            val now = clock()
            val s = NumberRules.normalizeStore(store)
            if (!test && number != null) {
                // "024"와 "24"는 똑같이 읽으므로 같은 번호로 본다.
                val key = s + "|" + NumberRules.spoken(number)
                val last = lastAccepted[key]
                if (last != null && now - last < duplicateWindowMs) {
                    return OfferResult(queued = false, duplicate = true, position = 0)
                }
                lastAccepted[key] = now
                // 오래된 중복 기록 정리
                if (lastAccepted.size > 500) {
                    lastAccepted.entries.removeAll { now - it.value >= duplicateWindowMs }
                }
            }
            queue.addLast(Item(s, number, source, device, test, now))
            val dropped = if (queue.size > maxSize) queue.removeFirst() else null
            lock.notifyAll()
            OfferResult(queued = true, duplicate = false, position = queue.size, dropped = dropped)
        }

    /** 바로 꺼내기. 비어 있으면 null */
    fun poll(): Item? = synchronized(lock) { queue.removeFirstOrNull() }

    /** 최대 timeoutMs까지 기다렸다 꺼내기 */
    fun take(timeoutMs: Long): Item? = synchronized(lock) {
        if (queue.isEmpty()) lock.wait(timeoutMs)
        queue.removeFirstOrNull()
    }

    fun clear() = synchronized(lock) { queue.clear() }
}
