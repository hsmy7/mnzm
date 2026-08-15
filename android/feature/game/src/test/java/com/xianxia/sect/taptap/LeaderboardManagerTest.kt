package com.xianxia.sect.taptap

import android.content.Context
import com.xianxia.sect.data.prefs.KeyValueStore
import com.xianxia.sect.taptap.TapTapLeaderboardApi.LeaderboardApiException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LeaderboardManager 测试：节流上报与榜单拉取编排。
 *
 * Robolectric 提供真实 android.util.Log 实现（普通 JVM 测试中 Log 抛
 * "not mocked"）；存储依赖内存 Fake [KeyValueStore]（D-29 接口抽象，
 * MMKV native 库在 Robolectric 沙箱不可用），SDK 依赖经
 * LeaderboardCloudApi 接口 fake，无需真实 TapTap 环境。
 */
@RunWith(RobolectricTestRunner::class)
class LeaderboardManagerTest {

    /** 内存 Fake——put/get 联动（类型化存取，与 KeyValueStore 契约一致） */
    private class FakeKeyValueStore : KeyValueStore {
        private val values = mutableMapOf<String, Any?>()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getBoolean(key: String, default: Boolean): Boolean =
            values[key] as? Boolean ?: default
        override fun getString(key: String, default: String?): String? =
            values[key] as? String ?: default
        override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long =
            values[key] as? Long ?: default
        override fun getFloat(key: String, default: Float): Float =
            values[key] as? Float ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun putLong(key: String, value: Long) { values[key] = value }
        override fun putFloat(key: String, value: Float) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clearAll() { values.clear() }
        override fun migrateFromSharedPreferences(spName: String) = Unit

        fun readRaw(key: String): Any? = values[key]
        fun writeRaw(key: String, value: Any?) { values[key] = value }
    }

    private lateinit var context: Context
    private lateinit var store: FakeKeyValueStore
    private lateinit var cloudApi: LeaderboardCloudApi
    private lateinit var loginBridge: TapTapLoginBridge
    private lateinit var manager: LeaderboardManager

    @Before
    fun setUp() {
        store = FakeKeyValueStore()
        context = mockk(relaxed = true)
        cloudApi = mockk(relaxed = true)
        loginBridge = mockk(relaxed = true)

        manager = LeaderboardManager(cloudApi, loginBridge, store)
    }

    // ── uploadIfNeeded：登录态 ──

    @Test
    fun `uploadIfNeeded - 未登录静默跳过且不调云端`() = runTest {
        every { loginBridge.isLoggedIn() } returns false
        coEvery { cloudApi.submitStatistic(any()) } returns true

        val result = manager.uploadIfNeeded(100)

        assertFalse(result)
        coVerify(exactly = 0) { cloudApi.submitStatistic(any()) }
    }

    // ── uploadIfNeeded：节流 ──

    @Test
    fun `uploadIfNeeded - 已登录且从未上报时上报并记录`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.submitStatistic(any()) } returns true

        val result = manager.uploadIfNeeded(100)

        assertTrue(result)
        coVerify(exactly = 1) { cloudApi.submitStatistic(100) }
        assertEquals(100L, store.readRaw("last_uploaded_power"))
        assertTrue((store.readRaw("last_upload_date") as? String)?.length == 10)
    }

    @Test
    fun `uploadIfNeeded - 同日同战力节流跳过`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.submitStatistic(any()) } returns true
        assertTrue(manager.uploadIfNeeded(100))

        val result = manager.uploadIfNeeded(100)

        assertFalse(result)
        coVerify(exactly = 1) { cloudApi.submitStatistic(any()) }
    }

    @Test
    fun `uploadIfNeeded - 同日战力变化时上报`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.submitStatistic(any()) } returns true
        assertTrue(manager.uploadIfNeeded(100))

        val result = manager.uploadIfNeeded(200)

        assertTrue(result)
        coVerify(exactly = 2) { cloudApi.submitStatistic(any()) }
        assertEquals(200L, store.readRaw("last_uploaded_power"))
    }

    @Test
    fun `uploadIfNeeded - 跨天上报（每日首次进游戏）`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.submitStatistic(any()) } returns true
        assertTrue(manager.uploadIfNeeded(100))
        // 模拟跨天：把上次记录日期改为昨天
        val yesterday = LeaderboardUploadPolicy.formatDate(
            System.currentTimeMillis() - 24L * 60 * 60 * 1000
        )
        store.writeRaw("last_upload_date", yesterday)

        val result = manager.uploadIfNeeded(100)

        assertTrue(result)
        coVerify(exactly = 2) { cloudApi.submitStatistic(any()) }
    }

    @Test
    fun `uploadIfNeeded - 上报失败仅记日志不更新记录`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.submitStatistic(any()) } returns false

        val result = manager.uploadIfNeeded(100)

        assertFalse(result)
        assertNull(store.readRaw("last_upload_date"))
    }

    // ── fetchLeaderboard：登录态与错误映射 ──

    @Test
    fun `fetchLeaderboard - 未登录返回 NeedLogin`() = runTest {
        every { loginBridge.isLoggedIn() } returns false

        val result = manager.fetchLeaderboard()

        assertEquals(LeaderboardResult.NeedLogin, result)
    }

    @Test
    fun `fetchLeaderboard - 成功时合并榜单与我的排名`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.fetchTop() } returns listOf(
            LeaderboardEntry(1, "张三", power = 5000),
            LeaderboardEntry(2, "李四", power = 3000)
        )
        coEvery { cloudApi.fetchCurrentPlayerScore() } returns LeaderboardEntry(5, "王五", power = 1000, isMe = true)

        val result = manager.fetchLeaderboard() as LeaderboardResult.Success

        assertEquals(2, result.entries.size)
        assertEquals("张三", result.entries.first().name)
        assertEquals(5, result.myRanking?.rank)
        assertTrue(result.myRanking?.isMe == true)
    }

    @Test
    fun `fetchLeaderboard - 双空返回 Empty`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.fetchTop() } returns emptyList()
        coEvery { cloudApi.fetchCurrentPlayerScore() } returns null

        val result = manager.fetchLeaderboard()

        assertEquals(LeaderboardResult.Empty, result)
    }

    @Test
    fun `fetchLeaderboard - 我的排名查询失败不影响榜单展示`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.fetchTop() } returns listOf(LeaderboardEntry(1, "张三", power = 5000))
        coEvery { cloudApi.fetchCurrentPlayerScore() } throws RuntimeException("查询失败")

        val result = manager.fetchLeaderboard() as LeaderboardResult.Success

        assertEquals(1, result.entries.size)
        assertNull(result.myRanking)
    }

    @Test
    fun `fetchLeaderboard - 500102 映射为 NeedLogin`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.fetchTop() } throws LeaderboardApiException(
            LeaderboardApiExceptionCodes.NOT_LOGGED_IN, "用户未登录"
        )

        val result = manager.fetchLeaderboard()

        assertEquals(LeaderboardResult.NeedLogin, result)
    }

    @Test
    fun `fetchLeaderboard - 500001 映射为排行榜不存在错误`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.fetchTop() } throws LeaderboardApiException(
            LeaderboardApiExceptionCodes.ID_NOT_FOUND, "id not found"
        )

        val result = manager.fetchLeaderboard() as LeaderboardResult.Error

        assertTrue(result.message.contains("排行榜不存在"))
    }

    @Test
    fun `fetchLeaderboard - 500000 映射为周期已结束错误`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.fetchTop() } throws LeaderboardApiException(
            LeaderboardApiExceptionCodes.PERIOD_EXPIRED, "expired"
        )

        val result = manager.fetchLeaderboard() as LeaderboardResult.Error

        assertTrue(result.message.contains("周期"))
    }

    @Test
    fun `fetchLeaderboard - 普通异常映射为通用错误`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.fetchTop() } throws RuntimeException("network down")

        val result = manager.fetchLeaderboard() as LeaderboardResult.Error

        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `isCloudAvailable - 反映登录态`() {
        every { loginBridge.isLoggedIn() } returns true
        assertTrue(manager.isCloudAvailable())

        every { loginBridge.isLoggedIn() } returns false
        assertFalse(manager.isCloudAvailable())
    }

    @Test
    fun `uploadIfNeeded - 战力求零不消耗节流判定`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { cloudApi.submitStatistic(any()) } returns true

        assertFalse(manager.uploadIfNeeded(0))
        coVerify(exactly = 0) { cloudApi.submitStatistic(any()) }
    }
}
