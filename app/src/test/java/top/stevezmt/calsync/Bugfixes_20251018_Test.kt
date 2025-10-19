package top.stevezmt.calsync

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Regression tests for reported issues on 2025-10-18
 */
class Bugfixes_20251018_Test {

    private val baseCal: Calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2025)
        set(Calendar.MONTH, Calendar.OCTOBER) // 9
        set(Calendar.DAY_OF_MONTH, 18)
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun parseWithBase(ctx: android.content.Context, text: String): DateTimeParser.ParseResult? {
        return DateTimeParser.parseDateTime(ctx, text, baseCal.timeInMillis)
    }

    private fun slots(text: String) = TimeNLPAdapter.parse(text, baseCal.timeInMillis)

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
    fun case1_noon_to_room_should_parse_time_and_location() {
        val text = "测试2421班被抽到了，需要在10月22日中午12:20到21B6教室填写问卷"
        val r = parseWithBase(DummyContext, text)
        assertNotNull(r)
        val rr = r!!
        val s = Calendar.getInstance().apply { timeInMillis = rr.startMillis }
        assertEquals(2025, s.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, s.get(Calendar.MONTH))
        assertEquals(22, s.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, s.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, s.get(Calendar.MINUTE))
        assertEquals("21B6教室", rr.location)
    }

    @Test
    fun case2_room_change_without_time_should_not_create_event() {
        val text = "通知，下午104的课挪至207进行，请留意开关机房"
        val r = parseWithBase(DummyContext, text)
        // 仅地点变更且无时间，不应创建事件
        assertNull(r)
    }

    @Test
    fun case3_deadline_to_day_should_end_2359() {
        val text = "这个月的操行分申请表时间截止到10月27日"
        val r = parseWithBase(DummyContext, text)
        assertNotNull(r)
        val rr = r!!
        val e = Calendar.getInstance().apply { timeInMillis = rr.endMillis!! }
        assertEquals(27, e.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, e.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, e.get(Calendar.MINUTE))
    }
}
