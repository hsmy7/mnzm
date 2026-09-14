package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * GameEngineDiplomacyNativeGateTest — 预警阶段标记 native 臂门控降级守卫
 * （W4-B/B4，DIPLOMACY_WARNING_STAGE_TX=1843）。
 *
 * 守护契约：flag OFF 与 AUTHORITATIVE（JVM 无生产 .so；镜像服务未 stub → null）
 * 下 `markWarningStageShown` 均回退 Kotlin 原实现，两侧终态逐位一致（List 追加
 * 不去重），且可空判空降级不 NPE（handover findings 13）。
 *
 * C++ 侧语义由 GTest `diplomacy_selfheal_tx_test.cpp`（4 用例）逐位守护。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineDiplomacyNativeGateTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var engine: GameEngine

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        // 不 stub stateSyncServiceRef → native 臂可空判空降级（findings 13）
        val mockCore = mock<GameEngineCore>()
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mockEconomyFacade(),
            battleFacade = mock()
        )
    }

    /** 构造期 Facade 访问器 stub 链（防 GameEngine 构造 NPE，对齐 JadeNativeTxGateTest）。 */
    private fun mockCultivationFacade(): com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade =
        mock<com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade>().also {
            whenever(it.cultivationService).thenReturn(mock())
            whenever(it.discipleService).thenReturn(mock())
            val mockProductionFacade = mock<com.xianxia.sect.core.engine.domain.production.ProductionFacade>()
            whenever(mockProductionFacade.productionSlots)
                .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            whenever(it.productionFacade).thenReturn(mockProductionFacade)
            val mockPC = mock<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>()
            whenever(mockPC.repository).thenReturn(mock())
            whenever(it.productionCoordinator).thenReturn(mockPC)
        }

    private fun mockEconomyFacade(): com.xianxia.sect.core.engine.domain.economy.EconomyFacade =
        mock<com.xianxia.sect.core.engine.domain.economy.EconomyFacade>().also {
            whenever(it.inventoryFacade).thenReturn(mock())
            whenever(it.mailService).thenReturn(mock())
        }

    @Test
    fun `markWarningStageShown degrades identically and appends without dedup`() = runBlocking {
        val offIds = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.markWarningStageShown("stage_x")
            store.gameDataSnapshot.shownWarningStageIds.toList()
        }

        store.update { gameData = gameData.copy(shownWarningStageIds = emptyList()) }

        val authIds = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.markWarningStageShown("stage_x")
            store.gameDataSnapshot.shownWarningStageIds.toList()
        }

        assertEquals(listOf("stage_x"), offIds)
        assertEquals("两臂终态逐位一致（回退臂）", offIds, authIds)

        // 幂等展示防御在读取侧——追加不去重语义两臂一致（重复标记各追加一条）
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.markWarningStageShown("stage_x")
        }
        assertEquals(2, store.gameDataSnapshot.shownWarningStageIds.size)
    }
}
