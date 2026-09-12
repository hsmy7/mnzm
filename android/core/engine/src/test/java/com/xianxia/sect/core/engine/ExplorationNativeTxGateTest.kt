package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * ExplorationNativeTxGateTest — 探索域 native 臂门控降级守卫（batch-13）。
 *
 * 守护契约：
 * - 降级契约：flag OFF / AUTHORITATIVE 下 JVM 无生产 .so（GameCoreBridge
 *   未加载）→ ExplorationNativeForward.tryForward 均返回 null → 调用方回退
 *   Kotlin 原实现，回退臂语义与下沉前逐字一致（双实现并行契约）
 * - 镜像守卫：测试 mock（未 stub stateSyncServiceRef）返回 null sync →
 *   先赋可空局部再判空（handover findings 13），不得 NPE
 * - 分舵驻守回退臂语义：分配写槽 + gate 登记 + 同宗幂等跳过；移除清槽 +
 *   gate 释放——下沉前后状态面不变
 *
 * 战斗执行 C++ 语义（AUTHORITATIVE + 生产桥）由 GTest exploration_tx_test.cpp
 * （同源 battle_execution.h）+ 真机对拍框架逐位守护。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class ExplorationNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine
    private lateinit var discipleFacade: DiscipleFacade
    private lateinit var mockCore: GameEngineCore
    private lateinit var mockPC: ProductionCoordinator

    companion object {
        private const val SECT_PLAYER = "sect_player"
        private const val DISCIPLE_A = "1"
    }

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()
        store.update {
            discipleTables.writeAllowed = true
            for (i in 1..2) {
                val id = i
                discipleTables.addId(id)
                discipleTables.names[id] = "弟子$i"
                discipleTables.statuses[id] = DiscipleStatus.IDLE
                discipleTables.isAlive[id] = 1
                discipleTables.realms[id] = 9
                discipleTables.realmLayers[id] = 1
                discipleTables.portraitRes[id] = "portrait_$id"
            }
            discipleTables.writeAllowed = false
        }
        store.update {
            gameData = gameData.copy(
                worldMapSects = listOf(
                    WorldSect(
                        id = SECT_PLAYER, isPlayerSect = true,
                        garrisonSlots = listOf(GarrisonSlot(index = 0))
                    )
                )
            )
        }
        setupEngine()
    }

    /** mock GameEngine（8 构造参数），仅 stateStore + assignmentGate 为真实实现 */
    private fun setupEngine() {
        discipleFacade = mock()
        mockCore = mock<GameEngineCore>()
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend kotlinx.coroutines.CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        whenever(mockCore.scopeForStateIn()).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val mockBattleFacade = mock<BattleFacade>()
        whenever(mockBattleFacade.assignmentGate).thenReturn(gate)
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val mockCultivationFacade = mock<CultivationFacade>()
        whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleFacade).thenReturn(discipleFacade)
        whenever(mockCultivationFacade.productionFacade).thenReturn(mockProductionFacade)
        mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(mockCultivationFacade.productionCoordinator).thenReturn(mockPC)
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        val mockEconomyFacade = mock<EconomyFacade>()
        whenever(mockEconomyFacade.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(mockEconomyFacade.mailService).thenReturn(mock())

        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock(),
            explorationFacade = mock<ExplorationFacade>(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mockBattleFacade
        )
    }

    // ── 转发器门控（flag / 镜像守卫）──────────────────────────────

    @Test
    fun `tryForward returns null when flag is OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertNull(
                ExplorationNativeForward.tryForward(engine, 1570) { }
            )
        }
    }

    @Test
    fun `tryForward returns null when sync service missing under AUTHORITATIVE`() = runTest {
        // JVM 环境：mock core 的 stateSyncServiceRef 未 stub → null sync →
        // 可空局部守卫返回 null（不得 NPE），桥未加载也走不到 native
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            assertNull(
                ExplorationNativeForward.tryForward(engine, 1570) { }
            )
        }
    }

    // ── 分舵驻守回退臂语义（flag OFF / AUTHORITATIVE 双模式等价）──

    @Test
    fun `garrison assign falls back to kotlin arm when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.assignGarrisonDisciple(SECT_PLAYER, 0, DISCIPLE_A)
        }
        val slot = store.gameDataSnapshot
            .worldMapSects.first { it.id == SECT_PLAYER }.garrisonSlots[0]
        assertEquals(DISCIPLE_A, slot.discipleId)
        assertEquals("弟子1", slot.discipleName)
        assertTrue(gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `garrison assign falls back when AUTHORITATIVE but bridge not loaded`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.assignGarrisonDisciple(SECT_PLAYER, 0, DISCIPLE_A)
        }
        val slot = store.gameDataSnapshot
            .worldMapSects.first { it.id == SECT_PLAYER }.garrisonSlots[0]
        assertEquals(DISCIPLE_A, slot.discipleId)
        assertTrue(gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `garrison assign same disciple idempotent skip keeps gate`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.assignGarrisonDisciple(SECT_PLAYER, 0, DISCIPLE_A)
            // 同一弟子重复分配 → 静默跳过（written=false），gate/槽位不变
            engine.assignGarrisonDisciple(SECT_PLAYER, 0, DISCIPLE_A)
        }
        val slot = store.gameDataSnapshot
            .worldMapSects.first { it.id == SECT_PLAYER }.garrisonSlots[0]
        assertEquals(DISCIPLE_A, slot.discipleId)
        assertTrue(gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `garrison remove falls back to kotlin arm and releases gate`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.assignGarrisonDisciple(SECT_PLAYER, 0, DISCIPLE_A)
            engine.removeGarrisonDisciple(SECT_PLAYER, 0)
        }
        val slot = store.gameDataSnapshot
            .worldMapSects.first { it.id == SECT_PLAYER }.garrisonSlots[0]
        assertTrue(slot.discipleId.isEmpty())
        assertFalse(gate.isAssigned(DISCIPLE_A))
        // 空 occupant 移除：静默幂等（不抛、无残留）
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.removeGarrisonDisciple(SECT_PLAYER, 0)
        }
        assertTrue(store.gameDataSnapshot
            .worldMapSects.first { it.id == SECT_PLAYER }.garrisonSlots[0].discipleId.isEmpty())
    }
}
