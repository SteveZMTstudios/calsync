package top.stevezmt.calsync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationCacheTest {

    @Before
    fun resetCacheSingleton() {
        val dequeField = NotificationCache::class.java.getDeclaredField("deque").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val deque = dequeField.get(null) as ArrayDeque<String>
        deque.clear()

        NotificationCache::class.java.getDeclaredField("loaded").apply {
            isAccessible = true
            setBoolean(null, false)
        }
    }

    @Test
    fun snapshotLoadsPersistedLogsInStoredOrder() {
        val context = TestContext()
        context.prefs("calsync_log_cache").edit()
            .putString("recent_logs", "[\"first\",\"second\"]")
            .apply()

        assertEquals(listOf("first", "second"), NotificationCache.snapshot(context))
    }

    @Test
    fun snapshotIgnoresBrokenPersistedJson() {
        val context = TestContext()
        context.prefs("calsync_log_cache").edit()
            .putString("recent_logs", "{broken")
            .apply()

        assertTrue(NotificationCache.snapshot(context).isEmpty())
    }

    @Test
    fun addTruncatesLongEntriesPersistsNewestFirstAndKeepsOnlyTwenty() {
        val context = TestContext()
        val oversized = "x".repeat(1300)

        NotificationCache.add(context, oversized)
        for (i in 1..19) {
            NotificationCache.add(context, "entry-$i")
        }

        val snapshot = NotificationCache.snapshot(context)
        assertEquals(20, snapshot.size)
        assertEquals("entry-19", snapshot.first())
        assertTrue(snapshot.last().endsWith("..."))
        assertEquals(1203, snapshot.last().length)

        val persisted = context
            .getSharedPreferences("calsync_log_cache", Context.MODE_PRIVATE)
            .getString("recent_logs", null)
        assertTrue(persisted!!.contains("entry-19"))
        assertTrue(persisted.contains("..."))
    }

    @Test
    fun addDropsOldestEntriesAfterLimitIsExceeded() {
        val context = TestContext()

        for (i in 1..21) {
            NotificationCache.add(context, "entry-$i")
        }

        val snapshot = NotificationCache.snapshot(context)
        assertEquals(20, snapshot.size)
        assertEquals("entry-21", snapshot.first())
        assertEquals("entry-2", snapshot.last())
        assertFalse(snapshot.any { it == "entry-1" })
    }
}
