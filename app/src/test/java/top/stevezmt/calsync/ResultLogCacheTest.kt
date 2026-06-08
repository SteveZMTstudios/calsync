package top.stevezmt.calsync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResultLogCacheTest {

    @Before
    fun resetCacheSingleton() {
        val dequeField = ResultLogCache::class.java.getDeclaredField("deque").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val deque = dequeField.get(null) as ArrayDeque<String>
        deque.clear()

        ResultLogCache::class.java.getDeclaredField("loaded").apply {
            isAccessible = true
            setBoolean(null, false)
        }
    }

    @Test
    fun snapshotLoadsOnlyResultLogPrefs() {
        val context = TestContext()
        context.prefs("calsync_log_cache").edit()
            .putString("recent_logs", "[\"[12:00:00] pkg - raw notification\"]")
            .apply()
        context.prefs("calsync_result_log_cache").edit()
            .putString("recent_results", "[\"已保存日历: 开会\"]")
            .apply()

        val snapshot = ResultLogCache.snapshot(context)

        assertEquals(listOf("已保存日历: 开会"), snapshot)
        assertFalse(snapshot.any { it.contains("raw notification") })
    }

    @Test
    fun addPersistsNewestFirstAndKeepsOnlyFifty() {
        val context = TestContext()

        for (i in 1..51) {
            ResultLogCache.add(context, "result-$i")
        }

        val snapshot = ResultLogCache.snapshot(context)
        assertEquals(50, snapshot.size)
        assertEquals("result-51", snapshot.first())
        assertEquals("result-2", snapshot.last())

        val persisted = context
            .getSharedPreferences("calsync_result_log_cache", Context.MODE_PRIVATE)
            .getString("recent_results", null)
        assertTrue(persisted!!.contains("result-51"))
        assertFalse(persisted.contains("\"result-1\""))
    }

    @Test
    fun addTruncatesAndRoundTripsEscapedEntries() {
        val context = TestContext()
        val escaped = "发现可能日程: \"答辩\"\n地点: A\\B\tC"
        val oversized = "x".repeat(1300)

        ResultLogCache.add(context, escaped)
        ResultLogCache.add(context, oversized)

        val snapshot = ResultLogCache.snapshot(context)
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.first().endsWith("..."))
        assertEquals(1203, snapshot.first().length)
        assertEquals(escaped, snapshot.last())

        resetCacheSingleton()

        val reloaded = ResultLogCache.snapshot(context)
        assertEquals(snapshot, reloaded)
    }

    @Test
    fun filtersUnmatchedAndUnprocessedEntriesFromResultLog() {
        val context = TestContext()

        ResultLogCache.add(context, "未保存日程：未匹配关键字")
        ResultLogCache.add(context, "未处理: 未包含时间句子")
        ResultLogCache.add(context, "发现可能日程: 宣讲会")

        assertEquals(listOf("发现可能日程: 宣讲会"), ResultLogCache.snapshot(context))

        resetCacheSingleton()
        context.prefs("calsync_result_log_cache").edit()
            .putString("recent_results", "[\"未保存日程：未匹配关键字\",\"已保存日历: 宣讲会\"]")
            .apply()

        assertEquals(listOf("已保存日历: 宣讲会"), ResultLogCache.snapshot(context))
    }
}
