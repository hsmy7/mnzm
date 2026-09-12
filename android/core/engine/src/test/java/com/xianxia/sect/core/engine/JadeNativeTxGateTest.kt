package com.xianxia.sect.core.engine

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.WallClock
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * JadeNativeTxGateTest — 玉符/宗门升级落账族 native 臂门控降级守卫（batch-19）。
 *
 * 守护契约（双实现并行契约 + handover findings 13）：
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（JVM 无生产 .so，GameCoreBridge
 *   未加载）下 `upgradeSectLevel` / `claimSectLevelReward` 均回退 Kotlin 原
 *   实现，两侧**返回值与终态逐位一致**（回退臂语义与下沉前不可分）
 * - **镜像缺失不 NPE**：测试 mock 未 stub `stateSyncServiceRef` → 返回 null
 *   sync → native 臂先赋可空局部再判空（findings 13），不得抛
 *   NullPointerException（本测试类全部用例即为该契约的回归网）
 * - **玉符绝对值覆盖写不回涨**：`purchaseMerchantRefresh` /
 *   `purchaseBreakthroughBonus` 在 AUTHORITATIVE 无 .so 时回退 Kotlin 臂，
 *   运行时 totalCount 与 GameData.jadeSymbols 同步；`checkpointNow()` 之后
 *   余额不回涨（CLAUDE.md 13.3 / jade_tx.h 头注释红线）
 *
 * C++ 侧的判定序/RNG 面/零写入语义由 GTest `jade_tx_test.cpp`（17 用例）逐位守护；
 * 真机 native 臂对拍由 batch-22 物理设备验证批承担。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class JadeNativeTxGateTest {

    /** 单调时钟 fake（玉符服务构造要求）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var jadeService: JadeSymbolService
    private lateinit var engine: GameEngine

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        jadeService = JadeSymbolService(
            timeSource = FakeTimeSource(1_000_000L),
            stateStore = store,
            wallClock = WallClock { 1_700_000_000_000L }
        )
        // 注意：**不** stub stateSyncServiceRef —— native 臂必须可空判空降级
        // （handover findings 13：mock 未 stub 时返回 null 不得 NPE）
        val mockCore = mock<GameEngineCore>()
        whenever(mockCore.jadeSymbolServiceRef).thenReturn(jadeService)
        val mockRng = mock<GameRngManager>()
        whenever(mockRng.getRng(RngPartition.SYSTEM))
            .thenReturn(DeterministicRng.fromSeed(20260808L))
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mockRng,
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mockEconomyFacade(),
            battleFacade = mock()
        )
    }

    /** 构造期 Facade 访问器 stub 链（防 GameEngine 构造 NPE，对齐既有测试）。 */
    private fun mockCultivationFacade(): CultivationFacade = mock<CultivationFacade>().also {
        whenever(it.cultivationService).thenReturn(mock())
        whenever(it.discipleService).thenReturn(mock())
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(it.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(it.productionCoordinator).thenReturn(mockPC)
    }

    /** 构造期 Facade 访问器 stub 链（对齐既有测试）。 */
    private fun mockEconomyFacade(): EconomyFacade = mock<EconomyFacade>().also {
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(it.mailService).thenReturn(mock())
    }

    /** 播种玩家宗门（level=1 小型 → 升级目标 2 中型）。 */
    private fun seedPlayerSect(level: Int = SectLevel.SMALL) {
        store.update {
            gameData = gameData.copy(
                worldMapSects = listOf(
                    WorldSect(
                        id = "p1",
                        name = "我的宗门",
                        level = level,
                        levelName = SectLevel.levelName(level),
                        isPlayerSect = true
                    )
                )
            )
        }
    }

    /** 播种满足「化神及以上（realm<=5）」升级条件的存活弟子。 */
    private fun seedRealmFiveDisciple() {
        store.update {
            discipleTables.insert(
                Disciple(
                    id = "1",
                    name = "化神弟子",
                    realm = 5,
                    cultivation = 100.0,
                    spiritRootType = "fire",
                    combat = CombatAttributes(hpVariance = 0, mpVariance = 0)
                )
            )
        }
    }

    /** 播种玉符余额并从快照恢复运行时 totalCount（对齐生产 onLoopStart 语义）。 */
    private fun seedJade(count: Int) {
        store.update { gameData = gameData.copy(jadeSymbols = count) }
        jadeService.onLoopStart()
    }

    private fun playerSectLevel(): Int =
        store.gameDataSnapshot.worldMapSects.first { it.isPlayerSect }.level

    // ── 宗门升级：降级等价（flag OFF vs AUTHORITATIVE 无 .so） ──────────────

    @Test
    fun `upgradeSectLevel falls back identically when flag OFF and without so`() = runBlocking {
        seedPlayerSect()
        seedRealmFiveDisciple()

        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.upgradeSectLevel() }
        }
        val offLevel = playerSectLevel()

        // 复原到升级前
        seedPlayerSect()
        val authoritativeResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.upgradeSectLevel() }
        }
        val authoritativeLevel = playerSectLevel()

        // 回退臂语义逐位一致（且 AUTHORITATIVE 下 sync 缺失不得 NPE）
        assertEquals(offResult, authoritativeResult)
        assertEquals(offLevel, authoritativeLevel)
        assertTrue("两条路径均应完成升级", offResult is SectLevelUpgradeResult.Success)
        assertEquals(SectLevel.MEDIUM, authoritativeLevel)
    }

    @Test
    fun `upgradeSectLevel unmet conditions yields identical sealed result`() = runBlocking {
        seedPlayerSect()
        // 无弟子 → highestRealm 缺省 9 → 「化神及以上」条件不满足

        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.upgradeSectLevel() }
        }
        val authoritativeResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.upgradeSectLevel() }
        }

        assertEquals(offResult, authoritativeResult)
        assertTrue(offResult is SectLevelUpgradeResult.ConditionsNotMet)
        assertEquals(SectLevel.SMALL, playerSectLevel())   // 零写入
    }

    @Test
    fun `upgradeSectLevel at max level yields identical sealed result`() = runBlocking {
        seedPlayerSect(SectLevel.TOP)

        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.upgradeSectLevel() }
        }
        val authoritativeResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.upgradeSectLevel() }
        }

        assertEquals(offResult, authoritativeResult)
        assertEquals(SectLevelUpgradeResult.AlreadyMaxLevel, offResult)
    }

    // ── 宗门等级奖励领取：降级等价（AUTHORITATIVE 无 .so → Kotlin 回退臂） ──

    @Test
    fun `claimSectLevelReward falls back identically and records claim once`() = runBlocking {
        seedPlayerSect()

        val authoritativeResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.claimSectLevelReward(SectLevel.SMALL) }
        }
        val authoritativeRecords = store.gameDataSnapshot.sectLevelClaimRecords
        val authoritativeStones = store.gameDataSnapshot.spiritStones

        // 复原（清领取记录）后走 flag OFF 臂
        store.update { gameData = gameData.copy(sectLevelClaimRecords = emptyList()) }
        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.claimSectLevelReward(SectLevel.SMALL) }
        }

        assertEquals(offResult::class, authoritativeResult::class)
        assertEquals(store.gameDataSnapshot.spiritStones, authoritativeStones)
        assertEquals(
            authoritativeRecords.map { it.level },
            store.gameDataSnapshot.sectLevelClaimRecords.map { it.level }
        )
    }

    @Test
    fun `claimSectLevelReward within cooldown is rejected identically`() = runBlocking {
        seedPlayerSect()
        // 先领取一次（建立领取记录），随后立即再领 → AlreadyClaimed
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.claimSectLevelReward(SectLevel.SMALL) }
        }
        val firstRecords = store.gameDataSnapshot.sectLevelClaimRecords

        val authoritativeResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.claimSectLevelReward(SectLevel.SMALL) }
        }
        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.claimSectLevelReward(SectLevel.SMALL) }
        }

        assertEquals(offResult, authoritativeResult)
        assertTrue("冷却内重复领取应被拒绝", authoritativeResult is SectLevelClaimResult.AlreadyClaimed)
        // 领取记录未被追加第二条
        assertEquals(firstRecords.size, store.gameDataSnapshot.sectLevelClaimRecords.size)
    }

    // ── 玉符购买：降级 + 绝对值覆盖写不回涨 ───────────────────────────────

    @Test
    fun `purchaseMerchantRefresh falls back and balance never rebounds`() = runBlocking {
        seedJade(5)
        store.update { gameData = gameData.copy(merchantRefreshChances = 1) }

        val result = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.purchaseMerchantRefresh() }
        }

        assertTrue("AUTHORITATIVE 无 .so 应回退 Kotlin 臂并成功", result is MerchantRefreshResult.Success)
        assertEquals("玉符应扣 1 枚", 4, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 必须同步（防 checkpoint 覆盖回涨）",
            4, jadeService.runtimeState.value.total)
        // checkpointNow 后仍为 4（绝对值覆盖写不回涨）
        jadeService.checkpointNow()
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)
        assertEquals(4, store.gameDataSnapshot.merchantRefreshChances)
    }

    @Test
    fun `purchaseMerchantRefresh limit reached does not deduct identically`() = runBlocking {
        seedJade(5)
        store.update { gameData = gameData.copy(merchantRefreshChances = 999) }

        val authoritativeResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.purchaseMerchantRefresh() }
        }
        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.purchaseMerchantRefresh() }
        }

        assertEquals(offResult, authoritativeResult)
        assertEquals(MerchantRefreshResult.LimitReached, authoritativeResult)
        assertEquals("达上限不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `purchaseBreakthroughBonus falls back and balance never rebounds`() = runBlocking {
        store.update {
            discipleTables.insert(
                Disciple(
                    id = "1",
                    name = "测试弟子",
                    realm = 9,
                    cultivation = 100.0,
                    spiritRootType = "fire",
                    combat = CombatAttributes(hpVariance = 0, mpVariance = 0)
                )
            )
        }
        seedJade(5)

        val result = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.purchaseBreakthroughBonus("1") }
        }

        assertTrue("AUTHORITATIVE 无 .so 应回退 Kotlin 臂并成功",
            result is BreakthroughBonusResult.Success)
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)
        assertEquals(4, jadeService.runtimeState.value.total)
        assertEquals("0.15",
            store.persistentDiscipleTables.assemble(1).statusData["adBreakthroughBonus"])
        jadeService.checkpointNow()
        assertEquals("checkpointNow 之后玉符不得回涨", 4, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `purchaseBreakthroughBonus insufficient jade is rejected identically`() = runBlocking {
        store.update {
            discipleTables.insert(
                Disciple(
                    id = "1",
                    name = "测试弟子",
                    realm = 9,
                    cultivation = 100.0,
                    spiritRootType = "fire",
                    combat = CombatAttributes(hpVariance = 0, mpVariance = 0)
                )
            )
        }
        seedJade(0)

        val authoritativeResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.purchaseBreakthroughBonus("1") }
        }
        val offResult = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.purchaseBreakthroughBonus("1") }
        }

        assertEquals(offResult, authoritativeResult)
        assertTrue(authoritativeResult is BreakthroughBonusResult.InsufficientJadeSymbols)
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
        assertEquals("",
            store.persistentDiscipleTables.assemble(1).statusData["adBreakthroughBonus"] ?: "")
    }
}
