package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * GameEngineSectIdentityOpsTest — 宗门标识/仓库驻守引擎入口语义守卫（§2.79）。
 *
 * 背景：w3-13 通道关闭配套把宗门改名（sectName + worldMapSects 玩家宗门名，
 * 二者均已关闭回导）与仓库驻守卸任（warehouseGarrisons 已关闭）从 UI delegate
 * 迁入引擎层，写入后经 rebaselineNativeMirror 全量重建 native 基线回导 C++。
 * JVM 无生产 .so ⇒ rebaseline 静默跳过（GameCoreBridge.isLoaded 短路），本类
 * 守护的是 Kotlin 写入语义与写前写后运行态（gate/Repository 清理）；真臂回导
 * 语义由桌面 JNI + 引擎全量对拍门禁承载。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineSectIdentityOpsTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var engine: GameEngine
    private lateinit var assignmentGate: DiscipleAssignmentGate

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        assignmentGate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        val battleFacadeMock = mock<com.xianxia.sect.core.engine.domain.battle.BattleFacade>()
        whenever(battleFacadeMock.assignmentGate).thenReturn(assignmentGate)
        engine = GameEngine(
            gameEngineCore = mock<GameEngineCore>(),
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mock(),
            battleFacade = battleFacadeMock
        )
    }

    /** GameEngine init 块消费面桩（DiscipleChatEffectNativeTxGateTest 同款）。 */
    private fun mockCultivationFacade(): com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade =
        mock<com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade>().also {
            whenever(it.cultivationService).thenReturn(mock())
            whenever(it.discipleService).thenReturn(mock())
            whenever(it.discipleFacade)
                .thenReturn(mock<com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade>())
            val mockProductionFacade = mock<com.xianxia.sect.core.engine.domain.production.ProductionFacade>()
            whenever(mockProductionFacade.productionSlots)
                .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            whenever(it.productionFacade).thenReturn(mockProductionFacade)
            val mockPC = mock<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>()
            whenever(mockPC.repository).thenReturn(mock())
            whenever(it.productionCoordinator).thenReturn(mockPC)
        }

    private fun seedWorld(playerSectName: String = "青云宗") {
        store.update {
            gameData = gameData.copy(
                sectName = playerSectName,
                worldMapSects = listOf(
                    WorldSect(id = "player", name = playerSectName, isPlayerSect = true),
                    WorldSect(id = "ai-1", name = "血煞宗", isPlayerSect = false)
                )
            )
        }
    }

    @Test
    fun `renameSect updates sectName and player sect only`() = runBlocking {
        seedWorld()
        engine.renameSect("太虚宗")
        val gd = store.gameDataSnapshot
        assertEquals("GameData.sectName 应更新为新名称", "太虚宗", gd.sectName)
        assertEquals("玩家宗门名称应更新", "太虚宗", gd.worldMapSects.first { it.isPlayerSect }.name)
        assertEquals("AI 宗门名称不应被修改", "血煞宗", gd.worldMapSects.first { !it.isPlayerSect }.name)
    }

    @Test
    fun `renameSect same-name write still applies`() = runBlocking {
        seedWorld()
        engine.renameSect("青云宗")
        val gd = store.gameDataSnapshot
        assertEquals("同名更新应保持不变", "青云宗", gd.sectName)
        assertEquals("玩家宗门名称应保持不变", "青云宗", gd.worldMapSects.first { it.isPlayerSect }.name)
    }

    @Test
    fun `removeWarehouseGarrison drops slot entry and releases gate`() = runBlocking {
        store.update {
            gameData = gameData.copy(
                warehouseGarrisons = listOf(
                    WarehouseGarrisonSlot(
                        buildingInstanceId = "b1", discipleId = "201",
                        discipleName = "驻守弟子", sectId = "player"
                    )
                )
            )
        }
        assignmentGate.confirmAssign(
            "201", SlotRef(SlotCategory.WAREHOUSE_GARRISON, "b1", "warehouse_b1")
        )
        engine.removeWarehouseGarrison("b1")
        assertEquals("槽位条目应被移除", 0, store.gameDataSnapshot.warehouseGarrisons.size)
        assertNull("gate 登记应被释放", assignmentGate.getAssignment("201"))
    }
}
