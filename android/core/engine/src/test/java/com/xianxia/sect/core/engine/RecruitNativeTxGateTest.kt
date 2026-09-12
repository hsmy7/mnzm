package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.service.RecruitService
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import javax.inject.Provider

/**
 * RecruitNativeTxGateTest — 招募域 native 臂门控降级守卫（batch-16）。
 *
 * 守护契约：
 * - 降级契约：flag OFF / AUTHORITATIVE 下 JVM 无生产 .so（GameCoreBridge
 *   未加载）→ RECRUIT_REMOVE_TX / RECRUIT_REFRESH_TX / RECRUIT_AGE_TX 三臂
 *   均回退 Kotlin 原实现，回退臂语义与下沉前逐字一致（双实现并行契约）
 * - 镜像守卫：测试 mock（未 stub stateSyncServiceRef）返回 null sync →
 *   先赋可空局部再判空（handover findings 13），不得 NPE
 * - RecruitService 门控序：Provider 缺省（既有测试构造面零改动）→ 世界已
 *   导入守卫（worldMapSects 空 = 开机路径 → 必须回退 Kotlin 臂）→ 差值门
 *   预检（与 C++ kRecruitRefreshIntervalYears 同款，门内防双臂分叉）
 *
 * C++ 刷新生成链语义（SYSTEM 分区）由 GTest recruit_tx_test.cpp
 *（复用 year_settlement 权威链）+ 真机对拍框架逐位守护。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class RecruitNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var mockCore: GameEngineCore
    private lateinit var engine: GameEngine

    companion object {
        private const val RECRUIT_A = "recruit-a"
        private const val RECRUIT_B = "recruit-b"
    }

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        setupEngine()
    }

    /** mock GameEngine（8 构造参数），仅 stateStore 为真实实现 */
    private fun setupEngine() {
        mockCore = mock<GameEngineCore>()
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend kotlinx.coroutines.CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        whenever(mockCore.scopeForStateIn()).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val mockCultivationFacade = mock<CultivationFacade>()
        whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleFacade).thenReturn(mock<DiscipleFacade>())
        whenever(mockCultivationFacade.productionFacade).thenReturn(mock<ProductionFacade>())
        val mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(mockCultivationFacade.productionCoordinator).thenReturn(mockPC)
        val mockBattleFacade = mock<BattleFacade>()
        whenever(mockBattleFacade.assignmentGate).thenReturn(mock())

        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock(),
            explorationFacade = mock<ExplorationFacade>(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mock<EconomyFacade>(),
            battleFacade = mockBattleFacade
        )
    }

    private fun seedRecruitList() {
        store.update {
            gameData = gameData.copy(
                recruitList = listOf(
                    Disciple(
                        id = RECRUIT_A, name = "甲一", age = 20,
                        realm = 9, spiritRootType = "金"
                    ),
                    Disciple(
                        id = RECRUIT_B, name = "乙二", age = 22,
                        realm = 9, spiritRootType = "水"
                    )
                )
            )
        }
    }

    /** 真实 RecruitService（可注入 Provider；缺省 null = 既有测试构造面） */
    private fun recruitService(
        provider: Provider<GameEngineCore>? = null
    ): RecruitService {
        val rngManager = mock<GameRngManager>()
        whenever(rngManager.getRng(RngPartition.SYSTEM))
            .thenReturn(DeterministicRng.fromSeed(7))
        return RecruitService(store, DiscipleFactory(), rngManager, provider)
    }

    // ── removeFromRecruitList 门控降级（RECRUIT_REMOVE_TX）──────────────

    @Test
    fun `removeFromRecruitList falls back to kotlin arm when flag OFF`() = runTest {
        seedRecruitList()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.removeFromRecruitList(RECRUIT_A)
        }
        val ids = store.gameDataSnapshot.recruitList.map { it.id }
        assertEquals(listOf(RECRUIT_B), ids)
    }

    @Test
    fun `removeFromRecruitList falls back when AUTHORITATIVE but sync missing`() = runTest {
        seedRecruitList()
        // JVM 环境：mock core 的 stateSyncServiceRef 未 stub → null sync →
        // 可空局部守卫回退 Kotlin 臂（不得 NPE），桥未加载也走不到 native
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.removeFromRecruitList(RECRUIT_A)
        }
        val ids = store.gameDataSnapshot.recruitList.map { it.id }
        assertEquals(listOf(RECRUIT_B), ids)
    }

    @Test
    fun `removeFromRecruitList missing id is idempotent`() = runTest {
        seedRecruitList()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.removeFromRecruitList("no-such-id")
        }
        assertEquals(2, store.gameDataSnapshot.recruitList.size)
    }

    // ── RecruitService 门控降级（RECRUIT_REFRESH_TX / RECRUIT_AGE_TX）───

    @Test
    fun `refreshRecruitList falls back to kotlin arm when provider missing`() = runTest {
        // 既有测试构造面（3 参，Provider 缺省 null）→ native 臂关闭 → 回退臂
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            recruitService().refreshRecruitList(/*year=*/5)
        }
        val gd = store.gameDataSnapshot
        assertEquals(5, gd.lastRecruitYear)
        assertTrue("回退臂应生成候选弟子", gd.recruitList.isNotEmpty())
    }

    @Test
    fun `refreshRecruitList falls back when world not imported`() = runTest {
        // 开机路径守卫：worldMapSects 空（基线导入前）→ 必须走 Kotlin 臂，
        // 即使 flag AUTHORITATIVE + Provider 就绪（差值门也不满足，双保险）
        val service = recruitService(provider = Provider { mockCore })
        store.update {
            gameData = gameData.copy(lastRecruitYear = 0)
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            service.refreshRecruitList(/*year=*/1)
        }
        val gd = store.gameDataSnapshot
        assertEquals(1, gd.lastRecruitYear)
        assertTrue("开机路径应生成初始候选", gd.recruitList.isNotEmpty())
    }

    @Test
    fun `refreshRecruitList falls back when sync missing under AUTHORITATIVE`() = runTest {
        // 差值门满足 + 世界已导入 + Provider 就绪，但镜像服务 null → 回退臂
        val service = recruitService(provider = Provider { mockCore })
        store.update {
            gameData = gameData.copy(
                lastRecruitYear = 2,
                worldMapSects = listOf(WorldSect(id = "sect_player", isPlayerSect = true))
            )
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            service.refreshRecruitList(/*year=*/5)
        }
        val gd = store.gameDataSnapshot
        assertEquals(5, gd.lastRecruitYear)
        assertTrue("镜像缺失应回退 Kotlin 臂生成", gd.recruitList.isNotEmpty())
    }

    @Test
    fun `ageRecruitList falls back to kotlin arm and keeps semantics`() = runTest {
        // 老化 + 净化回退臂语义：age+1、超寿元移除（age 10000 → 10001 ≥ 上限）
        store.update {
            gameData = gameData.copy(
                recruitList = listOf(
                    Disciple(
                        id = "young", name = "幸存者", age = 20,
                        realm = 9, spiritRootType = "金", lifespan = 80
                    ),
                    Disciple(
                        id = "old", name = "寿终", age = 10000,
                        realm = 9, spiritRootType = "水", lifespan = 80
                    )
                )
            )
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            recruitService(provider = Provider { mockCore }).ageRecruitList(/*year=*/5)
        }
        val list = store.gameDataSnapshot.recruitList
        assertEquals(listOf("young"), list.map { it.id })
        assertEquals(21, list[0].age)
    }
}
