package top.stevezmt.calsync

import com.xkzhangsan.time.nlp.TimeNLPUtil
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ParsingEnginesAndBatterySaverTest {

    private val baseCal: Calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2025)
        set(Calendar.MONTH, Calendar.SEPTEMBER)
        set(Calendar.DAY_OF_MONTH, 16) // Tue
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
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
    fun testEngineCoupling_datetimeAiForcesEventAi() {
        SettingsStore.setParsingEngine(DummyContext, ParseEngine.AI_GGUF)
        assertEquals(ParseEngine.AI_GGUF, SettingsStore.getParsingEngine(DummyContext))
        assertEquals(EventParseEngine.AI_GGUF, SettingsStore.getEventParsingEngine(DummyContext))

        SettingsStore.setParsingEngine(DummyContext, ParseEngine.ML_KIT)
        assertEquals(ParseEngine.ML_KIT, SettingsStore.getParsingEngine(DummyContext))
        assertEquals(EventParseEngine.ML_KIT, SettingsStore.getEventParsingEngine(DummyContext))

        SettingsStore.setParsingEngine(DummyContext, ParseEngine.XK_TIME)
        assertEquals(ParseEngine.XK_TIME, SettingsStore.getParsingEngine(DummyContext))
        assertEquals(EventParseEngine.BUILTIN, SettingsStore.getEventParsingEngine(DummyContext))
    }

    @Test
    fun testEngineCoupling_eventAiForcesDatetimeAi_andTurningOffEventAiTurnsOffDatetimeAi() {
        SettingsStore.setEventParsingEngine(DummyContext, EventParseEngine.AI_GGUF)
        assertEquals(EventParseEngine.AI_GGUF, SettingsStore.getEventParsingEngine(DummyContext))
        assertEquals(ParseEngine.AI_GGUF, SettingsStore.getParsingEngine(DummyContext))

        SettingsStore.setEventParsingEngine(DummyContext, EventParseEngine.ML_KIT)
        assertEquals(EventParseEngine.ML_KIT, SettingsStore.getEventParsingEngine(DummyContext))
        assertEquals(ParseEngine.ML_KIT, SettingsStore.getParsingEngine(DummyContext))

        SettingsStore.setEventParsingEngine(DummyContext, EventParseEngine.BUILTIN)
        assertEquals(EventParseEngine.BUILTIN, SettingsStore.getEventParsingEngine(DummyContext))
        assertEquals(ParseEngine.BUILTIN, SettingsStore.getParsingEngine(DummyContext))
    }

    @Test
    fun testGuessContainsDateTime() {
        SettingsStore.setCustomRules(DummyContext, emptyList())

        val likely = "明天上午9点开会"
        val unlikely = "通知，下午104的课挪至207进行，请留意开关机房"

        assertTrue(DateTimeParser.guessContainsDateTime(DummyContext, likely))
        assertFalse(DateTimeParser.guessContainsDateTime(DummyContext, unlikely))
    }

    @Test
    fun testXkTimeRangePreferEndOverDefaultDuration() {
        // Verify xk-time can produce a range for this pattern in the current dependency version.
        val baseStr = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date(baseCal.timeInMillis))
        val raw = TimeNLPUtil.parse("本周五3点到5点开会", baseStr)
        assertNotNull(raw)
        assertTrue("xk-time should provide at least 2 results for a range", (raw?.size ?: 0) >= 2)

        SettingsStore.setParsingEngine(DummyContext, ParseEngine.XK_TIME)
        val r = DateTimeParser.parseDateTime(DummyContext, "本周五3点到5点开会", baseCal.timeInMillis)
        assertNotNull(r)
        val rr = r!!
        assertNotNull(rr.endMillis)
        val dur = rr.endMillis!! - rr.startMillis
        assertTrue("duration should be >= 2h for '3点到5点', actual=${dur/60000}min", dur >= 2 * 60 * 60 * 1000L)
    }
}
