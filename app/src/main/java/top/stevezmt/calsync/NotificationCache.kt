package top.stevezmt.calsync

object NotificationCache {
    private val deque = ArrayDeque<String>()
    private const val LIMIT = 50

    fun add(entry: String) {
        synchronized(deque) {
            deque.addFirst(entry)
            while (deque.size > LIMIT) deque.removeLast()
        }
    }

    fun snapshot(): List<String> {
        synchronized(deque) { return deque.toList() }
    }
}
