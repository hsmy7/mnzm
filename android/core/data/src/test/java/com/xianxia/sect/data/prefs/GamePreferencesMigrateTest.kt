package com.xianxia.sect.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [GamePreferences.migrateInto] 迁移核心纯函数测试。
 *
 * 守卫契约：旧 SharedPreferences 全量键值迁移到目标存储后清空旧文件；
 * 支持类型 Boolean/String/Int/Long/Float；Set 等不支持类型跳过不迁移；
 * 空文件零操作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GamePreferencesMigrateTest {

    /** 内存 Fake 目标存储 */
    private class FakeTarget : KeyValueStore {
        val booleans = mutableMapOf<String, Boolean>()
        val strings = mutableMapOf<String, String>()
        val ints = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val floats = mutableMapOf<String, Float>()
        override fun contains(key: String): Boolean =
            booleans.containsKey(key) || strings.containsKey(key) || ints.containsKey(key) ||
                longs.containsKey(key) || floats.containsKey(key)
        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun getLong(key: String, default: Long): Long = longs[key] ?: default
        override fun getFloat(key: String, default: Float): Float = floats[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
        override fun putFloat(key: String, value: Float) { floats[key] = value }
        override fun remove(key: String) = Unit
        override fun clearAll() = Unit
        override fun migrateFromSharedPreferences(spName: String) = Unit
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migrateInto - 五种支持类型全部迁移并清空旧文件`() {
        val spName = "migrate_test_1"
        val sp = context.getSharedPreferences(spName, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        sp.edit()
            .putBoolean("bool_key", true)
            .putString("str_key", "value")
            .putInt("int_key", 42)
            .putLong("long_key", 99L)
            .putFloat("float_key", 1.5f)
            .commit()
        val target = FakeTarget()

        val migrated = GamePreferences.migrateInto(sp, target)

        assertEquals(5, migrated)
        assertEquals(true, target.booleans["bool_key"])
        assertEquals("value", target.strings["str_key"])
        assertEquals(42, target.ints["int_key"])
        assertEquals(99L, target.longs["long_key"])
        assertEquals(1.5f, target.floats["float_key"])
        assertEquals("迁移后旧文件应清空", 0, sp.all.size)
    }

    @Test
    fun `migrateInto - 不支持类型跳过不迁移`() {
        val spName = "migrate_test_2"
        val sp = context.getSharedPreferences(spName, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        sp.edit()
            .putBoolean("bool_key", true)
            .putStringSet("set_key", setOf("a", "b"))
            .commit()
        val target = FakeTarget()

        val migrated = GamePreferences.migrateInto(sp, target)

        assertEquals("仅布尔键可迁移，Set 跳过", 1, migrated)
        assertEquals(true, target.booleans["bool_key"])
        assertFalse(target.contains("set_key"))
        assertEquals("旧文件清空（Set 键随 clear 清除）", 0, sp.all.size)
    }

    @Test
    fun `migrateInto - 空文件零迁移`() {
        val spName = "migrate_test_3"
        val sp = context.getSharedPreferences(spName, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val target = FakeTarget()

        val migrated = GamePreferences.migrateInto(sp, target)

        assertEquals(0, migrated)
        assertNull(target.strings["any"])
    }

    @Test
    fun `migrateInto - 幂等可重复调用（第二次空文件零迁移）`() {
        val spName = "migrate_test_4"
        val sp = context.getSharedPreferences(spName, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        sp.edit().putInt("int_key", 7).commit()
        val target = FakeTarget()

        GamePreferences.migrateInto(sp, target)
        val second = GamePreferences.migrateInto(sp, target)

        assertEquals("第二次调用旧文件已空，零迁移", 0, second)
        assertEquals(7, target.ints["int_key"])
    }
}
