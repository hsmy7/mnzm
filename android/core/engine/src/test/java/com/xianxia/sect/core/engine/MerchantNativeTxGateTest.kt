package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.service.MerchantAndRecruitService
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * MerchantNativeTxGateTest — 行商刷新族 native 臂门控降级守卫（W4-B/B3，w3-05）。
 *
 * 守护契约（双实现并行契约 + handover findings 13）：
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（JVM 无生产 .so；或镜像服务缺失）下
 *   手动刷新 / 旅行商人刷新 / 收购池刷新 / 年度凭据发放均回退 Kotlin 原实现，
 *   两侧**终态逐位一致**
 * - **镜像缺失不 NPE**：`Provider<GameEngineCore>` 惰性边在 `stateSyncServiceRef`
 *   未 stub（null）时可空判空降级（findings 13 在新注入面上的回归网）
 * - **凭据类语义**：无凭据时手动刷新两臂均如实返回 false、零扣减零覆写
 *
 * C++ 侧判定序/零写入语义由 GTest `merchant_tx_test.cpp`（13 用例）逐位守护。
 */
class MerchantNativeTxGateTest {

    /** 双臂构造：flag OFF 臂无 Provider；AUTHORITATIVE 臂 Provider 指向 sync 未 stub 的 mockCore。 */
    private fun makeService(store: FakeAtomicStateStore, seed: Long, withProvider: Boolean): MerchantAndRecruitService {
        val rngManager = mock<GameRngManager>()
        whenever(rngManager.getRng(RngPartition.SYSTEM))
            .thenReturn(DeterministicRng.fromSeed(seed))
        val provider = if (withProvider) {
            val mockCore = mock<GameEngineCore>()  // stateSyncServiceRef 未 stub → null
            Provider { mockCore }
        } else {
            null
        }
        return MerchantAndRecruitService(store, rngManager, gameEngineCoreProvider = provider)
    }

    private fun seed(store: FakeAtomicStateStore, chances: Int, count: Int, grantYear: Int, gameYear: Int = 12) {
        store.update {
            gameData = gameData.copy(
                gameYear = gameYear,
                gameMonth = 3,
                merchantRefreshChances = chances,
                merchantRefreshCount = count,
                merchantLastRefreshChanceGrantYear = grantYear
            )
        }
    }

    private fun snapshot(store: FakeAtomicStateStore): GameData = store.gameDataSnapshot

    @Test
    fun `manual refresh degrades identically and consumes one chance`() {
        val offStore = FakeAtomicStateStore()
        val authStore = FakeAtomicStateStore()
        seed(offStore, chances = 1, count = 5, grantYear = 0)
        seed(authStore, chances = 1, count = 5, grantYear = 0)

        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            makeService(offStore, seed = 100L, withProvider = false).refreshTravelingMerchantManual()
        }
        val authResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            makeService(authStore, seed = 100L, withProvider = true).refreshTravelingMerchantManual()
        }

        assertTrue("凭据充足时两臂均应成功", offResult && authResult)
        val off = snapshot(offStore)
        val auth = snapshot(authStore)
        assertEquals("扣凭据两臂一致", off.merchantRefreshChances, auth.merchantRefreshChances)
        assertEquals(0, auth.merchantRefreshChances)
        assertEquals("刷新计数两臂一致", off.merchantRefreshCount, auth.merchantRefreshCount)
        assertEquals(6, auth.merchantRefreshCount)
        assertEquals("刷新年份两臂一致", off.merchantLastRefreshYear, auth.merchantLastRefreshYear)
        assertEquals("池非空且规模两臂一致", off.travelingMerchantItems.size, auth.travelingMerchantItems.size)
        assertTrue(auth.travelingMerchantItems.isNotEmpty())
    }

    @Test
    fun `manual refresh without chances returns false identically with zero writes`() {
        val offStore = FakeAtomicStateStore()
        val authStore = FakeAtomicStateStore()
        seed(offStore, chances = 0, count = 5, grantYear = 0)
        seed(authStore, chances = 0, count = 5, grantYear = 0)

        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            makeService(offStore, seed = 100L, withProvider = false).refreshTravelingMerchantManual()
        }
        val authResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            makeService(authStore, seed = 100L, withProvider = true).refreshTravelingMerchantManual()
        }

        assertEquals(false, offResult)
        assertEquals("无凭据两臂均如实失败", offResult, authResult)
        assertEquals("零覆写：池保持空", 0, snapshot(authStore).travelingMerchantItems.size)
        assertEquals(5, snapshot(authStore).merchantRefreshCount)
    }

    @Test
    fun `chance grant degrades identically for grant and interval arms`() {
        // 发放臂：lastGrant=10，year=40（40-10=30 ≥ 30）→ 发放
        val offA = FakeAtomicStateStore(); val authA = FakeAtomicStateStore()
        seed(offA, chances = 2, count = 0, grantYear = 10)
        seed(authA, chances = 2, count = 0, grantYear = 10)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            makeService(offA, seed = 1L, withProvider = false).giveMerchantRefreshChanceIfDue(40)
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            makeService(authA, seed = 1L, withProvider = true).giveMerchantRefreshChanceIfDue(40)
        }
        assertEquals(3, snapshot(authA).merchantRefreshChances)
        assertEquals(40, snapshot(authA).merchantLastRefreshChanceGrantYear)
        assertEquals(snapshot(offA).merchantRefreshChances, snapshot(authA).merchantRefreshChances)
        assertEquals(
            snapshot(offA).merchantLastRefreshChanceGrantYear,
            snapshot(authA).merchantLastRefreshChanceGrantYear
        )

        // 间隔臂：lastGrant=30，year=55（55-30=25 < 30）→ 零写入
        val offB = FakeAtomicStateStore(); val authB = FakeAtomicStateStore()
        seed(offB, chances = 2, count = 0, grantYear = 30)
        seed(authB, chances = 2, count = 0, grantYear = 30)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            makeService(offB, seed = 1L, withProvider = false).giveMerchantRefreshChanceIfDue(55)
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            makeService(authB, seed = 1L, withProvider = true).giveMerchantRefreshChanceIfDue(55)
        }
        assertEquals("未到间隔两臂均零写入", 2, snapshot(authB).merchantRefreshChances)
        assertEquals(30, snapshot(authB).merchantLastRefreshChanceGrantYear)
    }

    @Test
    fun `traveling refresh degrades identically on count and pool`() {
        val offStore = FakeAtomicStateStore()
        val authStore = FakeAtomicStateStore()
        seed(offStore, chances = 0, count = 9, grantYear = 0)
        seed(authStore, chances = 0, count = 9, grantYear = 0)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            makeService(offStore, seed = 7L, withProvider = false).refreshTravelingMerchant(12, 3)
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            makeService(authStore, seed = 7L, withProvider = true).refreshTravelingMerchant(12, 3)
        }

        val off = snapshot(offStore)
        val auth = snapshot(authStore)
        assertEquals("刷新计数两臂一致（9+1=10）", 10, auth.merchantRefreshCount)
        assertEquals(off.merchantRefreshCount, auth.merchantRefreshCount)
        assertEquals(12, auth.merchantLastRefreshYear)
        assertEquals("池规模两臂一致（同种子同分区抽取序）",
            off.travelingMerchantItems.size, auth.travelingMerchantItems.size)
    }

    @Test
    fun `acquisition refresh degrades identically on pool and year`() {
        val offStore = FakeAtomicStateStore()
        val authStore = FakeAtomicStateStore()
        seed(offStore, chances = 0, count = 0, grantYear = 0)
        seed(authStore, chances = 0, count = 0, grantYear = 0)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            makeService(offStore, seed = 7L, withProvider = false).refreshMerchantAcquisition(12, 1)
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            makeService(authStore, seed = 7L, withProvider = true).refreshMerchantAcquisition(12, 1)
        }

        val off = snapshot(offStore)
        val auth = snapshot(authStore)
        assertEquals(12, auth.merchantAcquisitionLastRefreshYear)
        assertEquals("收购池规模两臂一致", off.merchantAcquisitionItems.size, auth.merchantAcquisitionItems.size)
        assertTrue(auth.merchantAcquisitionItems.isNotEmpty())
    }
}
