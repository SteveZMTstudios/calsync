package top.stevezmt.calsync

import org.junit.Assert.*
import org.junit.Test
import java.util.*

/**
 * Tests for user-reported messages to prevent regressions.
 * Base time frozen to 2025-10-18 10:00 for determinism.
 */
class UserReportedScenariosTest {

    private val baseCal: Calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2025)
        set(Calendar.MONTH, Calendar.OCTOBER)
        set(Calendar.DAY_OF_MONTH, 18)
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun parse(text: String): DateTimeParser.ParseResult? {
        return DateTimeParser.parseDateTime(UserReportedScenariosTest.DummyContext, text, baseCal.timeInMillis)
    }

    object DummyContext: android.content.ContextWrapper(null) {
        private val mem = mutableMapOf<String, Any>()
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
            return object: android.content.SharedPreferences {
                override fun getAll(): MutableMap<String, *> = mem
                override fun getString(key: String?, defValue: String?): String? = mem[key] as? String ?: defValue
                override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = @Suppress("UNCHECKED_CAST") (mem[key] as? MutableSet<String>) ?: defValues
                override fun getInt(key: String?, defValue: Int): Int = (mem[key] as? Int) ?: defValue
                override fun getLong(key: String?, defValue: Long): Long = (mem[key] as? Long) ?: defValue
                override fun getFloat(key: String?, defValue: Float): Float = (mem[key] as? Float) ?: defValue
                override fun getBoolean(key: String?, defValue: Boolean): Boolean = (mem[key] as? Boolean) ?: defValue
                override fun contains(key: String?) = mem.containsKey(key)
                override fun edit(): android.content.SharedPreferences.Editor = object: android.content.SharedPreferences.Editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key!=null) { if (value==null) mem.remove(key) else mem[key]=value }; return this }
                    override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { if (key!=null) { if (values==null) mem.remove(key) else mem[key]=values }; return this }
                    override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key!=null) mem[key]=value; return this }
                    override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key!=null) mem[key]=value; return this }
                    override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key!=null) mem[key]=value; return this }
                    override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key!=null) mem[key]=value; return this }
                    override fun remove(key: String?): android.content.SharedPreferences.Editor { if (key!=null) mem.remove(key); return this }
                    override fun clear(): android.content.SharedPreferences.Editor { mem.clear(); return this }
                    override fun commit(): Boolean = true
                    override fun apply() {}
                }
                override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
                override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            }
        }
    }

    @Test
    fun testCase1_noRangeLocationAfterDao() {
        val text = "测试2421班被抽到了，需要在10月22日中午12:20到21B6教室填写问卷"
        val r = parse(text)
        assertNotNull(r)
        val rr = r!!
        val c = Calendar.getInstance().apply { timeInMillis = rr.startMillis }
        assertEquals(2025, c.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, c.get(Calendar.MONTH))
        assertEquals(22, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, c.get(Calendar.MINUTE))
        assertEquals("21B6教室", rr.location)
        // expect default 1-hour duration
        assertNotNull(rr.endMillis)
        val e = Calendar.getInstance().apply { timeInMillis = rr.endMillis!! }
        assertEquals(13, e.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, e.get(Calendar.MINUTE))
    }

    @Test
    fun testCase2_noEventFromRoomNumbersWithoutTime() {
        val text = "通知，下午104的课挪至207进行，请留意开关机房"
        // contains no valid time expression; parser should return null
        val r = parse(text)
        assertNull(r)
    }

    @Test
    fun testCase3_deadlineToEndOfDay() {
        val text = "这个月的操行分申请表时间截止到10月27日"
        val r = parse(text)
        assertNotNull(r)
        val rr = r!!
        val e = Calendar.getInstance().apply { timeInMillis = rr.endMillis!! }
        assertEquals(2025, e.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, e.get(Calendar.MONTH))
        assertEquals(27, e.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, e.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, e.get(Calendar.MINUTE))
    }
}
