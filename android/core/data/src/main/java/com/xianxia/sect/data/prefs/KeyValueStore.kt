@file:Suppress("TooManyFunctions")
package com.xianxia.sect.data.prefs

/**
 * 类型化键值存储端口。
 *
 * 生产实现 [GamePreferences]（MMKV，跨平台，iOS 迁移就绪）；单元测试使用
 * 内存 Fake（MMKV native 库在 Robolectric 沙箱不可用，见 GamePreferences KDoc）。
 */
interface KeyValueStore {
    fun contains(key: String): Boolean
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun getString(key: String, default: String? = null): String?
    fun getInt(key: String, default: Int = 0): Int
    fun getLong(key: String, default: Long = 0L): Long
    fun getFloat(key: String, default: Float = 0f): Float
    fun putBoolean(key: String, value: Boolean)
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putFloat(key: String, value: Float)
    fun remove(key: String)
    fun clearAll()

    /**
     * 一次性迁移旧 SharedPreferences 值（读旧值 → 写本存储 → 清空旧文件，幂等）。
     * Fake 实现可 no-op（无迁移需求）。
     */
    fun migrateFromSharedPreferences(spName: String)
}
