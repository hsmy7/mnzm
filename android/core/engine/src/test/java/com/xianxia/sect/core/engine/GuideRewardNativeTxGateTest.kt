package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.config.InventoryConfig
import kotlinx.coroutines.runBlocking
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

/**
 * GuideRewardNativeTxGateTest — 引导领奖 native 臂门控降级守卫
 * （W4-D/D2 · w3-11，GUIDE_REWARD_CLAIM_TX=1830）。
 *
 * 守护契约（双实现并行契约 + handover findings 13）：
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（JVM 无生产 .so；镜像服务未 stub → null）
 *   下 `claimGuideReward` 均回退 Kotlin 原实现，终态一致（已领取标记 + 凡品储物袋×2）
 * - **镜像缺失不 NPE**：可空判空降级（findings 13 在新注入面上的回归网）
 * - **条件门**：条件不满足 ⇒ 两臂均不领取、不发放、不标记
 *
 * C++ 侧判定序/可行性预检/抽取位语义由 GTest `guide_reward_tx_test.cpp`
 * 逐位守护；真 .so 双臂逐位一致由桌面 JNI 重建 + 引擎全量门禁守护。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GuideRewardNativeTxGateTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var engine: GameEngine

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        val inventoryConfig = mock<InventoryConfig>()
        whenever(inventoryConfig.getMaxStackSize(any())).thenReturn(9999)
        val inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = inventoryConfig,
        )
        // 不 stub stateSyncServiceRef → native 臂可空判空降级（findings 13）
        val mockCore = mock<GameEngineCore>()
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mockEconomyFacade(inventorySystem),
            battleFacade = mock()
        )
    }

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

    private fun mockEconomyFacade(inventorySystem: InventorySystem): EconomyFacade =
        mock<EconomyFacade>().also {
            val inventoryFacade = mock<com.xianxia.sect.core.engine.domain.inventory.InventoryFacade>()
            whenever(inventoryFacade.inventorySystem).thenReturn(inventorySystem)
            whenever(it.inventoryFacade).thenReturn(inventoryFacade)
            whenever(it.mailService).thenReturn(mock())
        }

    /** 播种"任务 8 可领"：天枢殿×1 + 自动采矿开启计数 ≥1 */
    private fun seedClaimable() {
        store.update {
            gameData = gameData.copy(
                placedBuildings = listOf(
                    com.xianxia.sect.core.model.GridBuildingData(displayName = "天枢殿")
                ),
                guideCounters = mapOf("autoMineActivated" to 1L)
            )
        }
    }

    @Test
    fun `claimGuideReward degrades identically across flag and null-sync edges`() = runBlocking {
        seedClaimable()
        // flag OFF 臂（Kotlin 原路径）
        val off = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val claimed = engine.claimGuideReward(8)
            claimed to store.gameDataSnapshot
        }
        // 复位领取面后重跑 AUTHORITATIVE 臂（mockCore 镜像缺失 → 降级）
        store.update {
            gameData = gameData.copy(guideClaimedRewardIds = emptySet())
        }
        store.storageBags.value = emptyList()
        val auth = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val claimed = engine.claimGuideReward(8)
            claimed to store.gameDataSnapshot
        }

        assertTrue("flag OFF 臂应领取成功", off.first)
        assertTrue("AUTHORITATIVE 降级臂应领取成功", auth.first)
        // 已领取标记两臂一致
        assertEquals(setOf(8), off.second.guideClaimedRewardIds)
        assertEquals("两臂终态逐位一致（回退臂）", off.second.guideClaimedRewardIds, auth.second.guideClaimedRewardIds)
        // 凡品储物袋 ×2（rarity=1）两臂一致；UUID id 为镜像生成字段不逐位比对
        //（storageBags 为 store 级集合，与 C++ GameState 顶层同层）
        for ((label, bags) in listOf("off" to store.storageBags.value)) {
            assertEquals("$label 恰一条堆叠", 1, bags.size)
            assertEquals("$label 奖励名", StorageBag.TIER_NAMES[0], bags[0].name)
            assertEquals("$label 稀有度", 1, bags[0].rarity)
            assertEquals("$label 数量", 2, bags[0].quantity)
            assertTrue("$label id 非空", bags[0].id.isNotBlank())
        }
    }

    @Test
    fun `conditions unmet leaves both arms unclaimed`() = runBlocking {
        // 只开计数、不放天枢殿 → BuildingCount 条件不满足
        store.update {
            gameData = gameData.copy(guideCounters = mapOf("autoMineActivated" to 1L))
        }
        for (mode in listOf(NativeEngineFlag.Mode.OFF, NativeEngineFlag.Mode.AUTHORITATIVE)) {
            NativeEngineFlag.withMode(mode) {
                assertEquals("条件不满足不得领取（mode=$mode）", false, engine.claimGuideReward(8))
            }
        }
        assertEquals(0, store.gameDataSnapshot.guideClaimedRewardIds.size)
        assertEquals(0, store.storageBags.value.size)
    }
}
