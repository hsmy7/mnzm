package com.xianxia.sect.analytics

import com.xianxia.sect.data.prefs.KeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FirstEventTracker] 首次事件去重测试。
 *
 * 存储依赖内存 Fake [KeyValueStore]（D-29：MMKV native 库在 Robolectric 沙箱不可用，
 * 接口抽象后以 Fake 测试；真实 TapDBManager 调用被 SDK Throwable 兜底吞掉，
 * 不影响去重语义验证——标记在 SDK 调用前置位）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstEventTrackerTest {

    /** 内存 Fake——跨实例共享（模拟同一进程同一存储） */
    private class FakeKeyValueStore : KeyValueStore {
        val booleans = mutableMapOf<String, Boolean>()
        override fun contains(key: String): Boolean = booleans.containsKey(key)
        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
        override fun getString(key: String, default: String?): String? = default
        override fun getInt(key: String, default: Int): Int = default
        override fun getLong(key: String, default: Long): Long = default
        override fun getFloat(key: String, default: Float): Float = default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun putString(key: String, value: String) = Unit
        override fun putInt(key: String, value: Int) = Unit
        override fun putLong(key: String, value: Long) = Unit
        override fun putFloat(key: String, value: Float) = Unit
        override fun remove(key: String) { booleans.remove(key) }
        override fun clearAll() { booleans.clear() }
        override fun migrateFromSharedPreferences(spName: String) = Unit
    }

    private lateinit var store: FakeKeyValueStore
    private lateinit var tracker: FirstEventTracker

    @Before
    fun setUp() {
        store = FakeKeyValueStore()
        tracker = FirstEventTracker(store)
    }

    @Test
    fun `trackFirst - 首次上报返回 true 且重复调用跳过`() {
        val first = tracker.trackFirst("user-1", "#battle_first_win", mapOf("outcome" to "win"))
        assertTrue("首次应上报并返回 true", first)
        val second = tracker.trackFirst("user-1", "#battle_first_win")
        assertFalse("重复调用应跳过并返回 false", second)
    }

    @Test
    fun `trackFirst - 不同用户互不影响`() {
        tracker.trackFirst("user-1", "#breakthrough_first")
        val otherFirst = tracker.trackFirst("user-2", "#breakthrough_first")
        assertTrue("换账号应视为首次", otherFirst)
    }

    @Test
    fun `trackFirst - 未登录按设备维度去重`() {
        tracker.trackFirst(null, "#battle_first_win")
        assertFalse("同设备重复调用应跳过", tracker.trackFirst(null, "#battle_first_win"))
    }

    @Test
    fun `trackFirst - 首次标记跨实例持久化（登出重登不重报）`() {
        tracker.trackFirst("user-1", "#battle_first_win")
        val newTracker = FirstEventTracker(store)
        assertFalse("新实例应读取到已置位标记", newTracker.trackFirst("user-1", "#battle_first_win"))
    }
}
