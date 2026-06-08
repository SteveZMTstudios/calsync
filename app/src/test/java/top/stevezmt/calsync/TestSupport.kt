package top.stevezmt.calsync

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import org.mockito.kotlin.mock

class InMemorySharedPreferences : SharedPreferences {
    private val mem = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = mem.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = mem[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val value = mem[key] as? Set<String> ?: return defValues
        return value.toMutableSet()
    }

    override fun getInt(key: String?, defValue: Int): Int = (mem[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = (mem[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = (mem[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (mem[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = key != null && mem.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(mem)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class Editor(private val mem: MutableMap<String, Any>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value == null) mem.remove(key) else mem[key] = value
            }
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) {
                if (values == null) mem.remove(key) else mem[key] = values.toSet()
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) mem[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) mem[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) mem[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) mem[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) mem.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            mem.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() {}
    }
}

open class TestContext : ContextWrapper(null) {
    private val prefsByName = mutableMapOf<String, InMemorySharedPreferences>()

    val broadcasts = mutableListOf<Intent>()
    var packageNameValue: String = "top.stevezmt.calsync.test"
    var resolver: ContentResolver = mock()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        return prefsByName.getOrPut(name ?: "") { InMemorySharedPreferences() }
    }

    override fun getContentResolver(): ContentResolver = resolver

    override fun getPackageName(): String = packageNameValue

    override fun sendBroadcast(intent: Intent?) {
        if (intent != null) broadcasts += intent
    }

    fun prefs(name: String): InMemorySharedPreferences = getSharedPreferences(name, Context.MODE_PRIVATE) as InMemorySharedPreferences
}
