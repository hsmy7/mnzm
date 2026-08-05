package com.xianxia.sect.taptap

import android.content.Context
import android.content.SharedPreferences
import com.xianxia.sect.taptap.TapTapLeaderboardApi.LeaderboardApiException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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
 * "not mocked"）；存储依赖 mockk 的 SharedPreferences（内存行为由 stub 模拟），
 * SDK 依赖经 LeaderboardCloudApi 接口 fake，无需真实 TapTap 环境。
 */
@RunWith(RobolectricTestRunner::class)
class LeaderboardManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var cloudApi: LeaderboardCloudApi
    private lateinit var loginBridge: TapTapLoginBridge
    private lateinit var manager: LeaderboardManager

    /** 内存 prefs 存储（mockk relaxed 无法实现 put/get 联动，手写 HashMap 后备） */
    private val store = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        store.clear()
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        cloudApi = mockk(relaxed = true)
        loginBridge = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.contains(any()) } answers { store.containsKey(args[0]) }
        every { prefs.getString(any(), any()) } answers {
            val key = args[0] as String
            store[key] as? String ?: args[1] as? String
        }
        every { prefs.getLong(any(), any()) } answers {
            val key = args[0] as String
            (store[key] as? Long) ?: args[1] as Long
        }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            store[args[0] as String] = args[1] as String
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            store[args[0] as String] = args[1] as Long
            editor
        }
        every { editor.apply() } just runs
        every { editor.commit() } returns true

        manager = LeaderboardManager(context, cloudApi, loginBridge)
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
        assertEquals(100L, store["last_uploaded_power"])
        assertTrue((store["last_upload_date"] as? String)?.length == 10)
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
        assertEquals(200L, store["last_uploaded_power"])
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
        store["last_upload_date"] = yesterday

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
        assertNull(store["last_upload_date"])
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
