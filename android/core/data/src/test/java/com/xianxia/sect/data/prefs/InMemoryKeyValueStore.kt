package com.xianxia.sect.data.prefs

/**
 * 测试用内存 [KeyValueStore]（core:data 测试基座）。
 *
 * 生产实现为 MMKV（GamePreferences），native 库在 Robolectric/JVM 沙箱不可用；
 * 本 Fake 语义对齐：put/remove/contains 直写内存表，clearAll 清空，
 * migrateFromSharedPreferences 无迁移需求 no-op（接口 KDoc 明示 Fake 可 no-op）。
 */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, Any>()

    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun getString(key: String, default: String?): String? = map[key] as? String ?: default
    override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
    override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
    override fun getFloat(key: String, default: Float): Float = map[key] as? Float ?: default

    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun putFloat(key: String, value: Float) { map[key] = value }

    override fun remove(key: String) { map.remove(key) }
    override fun clearAll() { map.clear() }

    override fun migrateFromSharedPreferences(spName: String) = Unit
}
