package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
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
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 玉符购买玩法引擎入口测试（真实 JadeSymbolService + 真实 stateStore）。
 *
 * 覆盖：突破率加成扣减与累加上限、商人刷新次数扣减与钳制、上限先于扣款、
 * 玉符不足（余额不变 + statusData/次数无写入）、checkpoint 不回涨回归、
 * 弟子不存在/死亡拒绝、旧档残留广告值兼容。
 *
 * 注意：必须 Robolectric 运行——DiscipleTables 的 String 列基于
 * android.util.SparseArray，纯 JVM 下（returnDefaultValues=true）put 静默无效。
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineJadePurchaseTest {

    /** 单调时钟 fake（玉符服务构造要求）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

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
        val mockCore = mock<GameEngineCore>()
        whenever(mockCore.jadeSymbolServiceRef).thenReturn(jadeService)
        val rng = DeterministicRng.fromSeed(20260808L)
        val mockRng = mock<GameRngManager>()
        whenever(mockRng.getRng(RngPartition.SYSTEM)).thenReturn(rng)
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

    /** 构造期 Facade 访问器 stub 链（防 GameEngine 构造 NPE，对齐 GameEngineCoordinationTest）。 */
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

    /** 构造期 Facade 访问器 stub 链（对齐 GameEngineCoordinationTest）。 */
    private fun mockEconomyFacade(): EconomyFacade = mock<EconomyFacade>().also {
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(it.mailService).thenReturn(mock())
    }

    private fun seedDisciple(id: Int = 1, statusData: Map<String, String> = emptyMap()) {
        // DiscipleTables 有写守卫：插入必须发生在 stateStore.update{} 事务内
        store.update {
            discipleTables.insert(
                Disciple(
                    id = id.toString(),
                    name = "测试弟子$id",
                    realm = 9,
                    cultivation = 100.0,
                    spiritRootType = "fire",
                    combat = CombatAttributes(hpVariance = 0, mpVariance = 0),
                    statusData = statusData
                )
            )
        }
    }

    /** 播种玉符余额并从快照恢复运行时 totalCount（对齐生产 onLoopStart 语义）。 */
    private fun seedJade(count: Int) {
        store.update { gameData = gameData.copy(jadeSymbols = count) }
        jadeService.onLoopStart()
    }

    private fun assembleDisciple(id: Int = 1): Disciple = store.persistentDiscipleTables.assemble(id)

    private fun breakthroughBonusOf(id: Int = 1): String? =
        assembleDisciple(id).statusData["adBreakthroughBonus"]

    private fun isSuccess(result: BreakthroughBonusResult) {
        assertTrue("期望 Success，实际 $result", result is BreakthroughBonusResult.Success)
    }

    private fun isInsufficient(result: BreakthroughBonusResult): BreakthroughBonusResult.InsufficientJadeSymbols {
        assertTrue("期望 InsufficientJadeSymbols，实际 $result", result is BreakthroughBonusResult.InsufficientJadeSymbols)
        return result as BreakthroughBonusResult.InsufficientJadeSymbols
    }

    private fun isMerchantSuccess(result: MerchantRefreshResult) {
        assertTrue("期望 Success，实际 $result", result is MerchantRefreshResult.Success)
    }

    private fun isMerchantInsufficient(result: MerchantRefreshResult): MerchantRefreshResult.InsufficientJadeSymbols {
        assertTrue("期望 InsufficientJadeSymbols，实际 $result", result is MerchantRefreshResult.InsufficientJadeSymbols)
        return result as MerchantRefreshResult.InsufficientJadeSymbols
    }

    // ── 突破率：玉符扣减与累加 ──

    @Test
    fun `purchaseBreakthroughBonus - 玉符充足扣1枚并写入百分之15加成`() = runBlocking {
        seedDisciple()
        seedJade(5)

        isSuccess(engine.purchaseBreakthroughBonus("1"))

        assertEquals("GameData 玉符应扣 1 枚", 4, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 应同步为 4（防止 checkpoint 覆盖回涨）",
            4, jadeService.runtimeState.value.total)
        assertEquals("statusData 应写入 0.15",
            GameConfig.JadePurchase.BREAKTHROUGH_BONUS_PER_JADE.toString(), breakthroughBonusOf())
    }

    @Test
    fun `purchaseBreakthroughBonus - 二次购买累加至百分之30上限`() = runBlocking {
        seedDisciple()
        seedJade(5)

        isSuccess(engine.purchaseBreakthroughBonus("1"))
        isSuccess(engine.purchaseBreakthroughBonus("1"))

        assertEquals("两次购买应为 0.30", "0.3", breakthroughBonusOf())
        assertEquals("两次购买共扣 2 枚玉符", 3, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `purchaseBreakthroughBonus - 已达上限返回LimitReached且不扣玉符`() = runBlocking {
        seedDisciple(statusData = mapOf("adBreakthroughBonus" to "0.30"))
        seedJade(5)

        val result = engine.purchaseBreakthroughBonus("1")

        assertTrue("期望 LimitReached，实际 $result", result is BreakthroughBonusResult.LimitReached)
        assertEquals("达上限不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        assertEquals("加成保持上限不变", "0.30", breakthroughBonusOf())
    }

    @Test
    fun `purchaseBreakthroughBonus - 玉符不足返回Insufficient且余额不变无写入`() = runBlocking {
        seedDisciple()
        seedJade(0)

        val insufficient = isInsufficient(engine.purchaseBreakthroughBonus("1"))

        assertEquals(0, insufficient.current)
        assertEquals(GameConfig.JadePurchase.COST, insufficient.required)
        assertEquals("余额不变", 0, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0, jadeService.runtimeState.value.total)
        assertTrue("失败时不得写入 statusData", breakthroughBonusOf() == null)
    }

    @Test
    fun `purchaseBreakthroughBonus - 扣减后checkpointNow玉符不回涨`() = runBlocking {
        // 最高风险：JadeSymbolService 运行时 totalCount 以绝对值覆盖写 GameData.jadeSymbols，
        // 若扣减未同步 totalCount，checkpoint 会把余额写回扣减前值（玉符回涨）
        seedDisciple()
        seedJade(5)

        isSuccess(engine.purchaseBreakthroughBonus("1"))
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)

        jadeService.checkpointNow()

        assertEquals("checkpoint 后玉符不得回涨", 4, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 保持扣减后值", 4, jadeService.runtimeState.value.total)
    }

    @Test
    fun `purchaseBreakthroughBonus - 弟子不存在返回Error且不扣玉符`() = runBlocking {
        seedJade(5)

        val result = engine.purchaseBreakthroughBonus("999")

        assertTrue("期望 Error，实际 $result", result is BreakthroughBonusResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `purchaseBreakthroughBonus - 死亡弟子返回Error且不扣玉符`() = runBlocking {
        seedDisciple()
        seedJade(5)
        store.update { discipleTables.markDead(1, 1) }

        val result = engine.purchaseBreakthroughBonus("1")

        assertTrue("死亡弟子应被拒绝，实际 $result", result is BreakthroughBonusResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `purchaseBreakthroughBonus - 非法ID返回Error且不扣玉符`() = runBlocking {
        seedJade(5)

        val result = engine.purchaseBreakthroughBonus("abc")

        assertTrue("期望 Error，实际 $result", result is BreakthroughBonusResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `purchaseBreakthroughBonus - 旧档残留广告值百分之5时购买得百分之20`() = runBlocking {
        // 兼容性：旧广告机制遗留的 statusData 值（每次 +0.05）在玉符化后继续累加，
        // 第一次购买应在 0.05 基础上 +0.15 = 0.20（不重复置零）
        seedDisciple(statusData = mapOf("adBreakthroughBonus" to "0.05"))
        seedJade(5)

        isSuccess(engine.purchaseBreakthroughBonus("1"))

        assertEquals("旧残留值上累加 0.15 应为 0.20", "0.2", breakthroughBonusOf())
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)
    }

    // ── 商人：刷新次数 ──

    private fun seedRefreshChances(n: Int) {
        store.update { gameData = gameData.copy(merchantRefreshChances = n) }
    }

    @Test
    fun `purchaseMerchantRefresh - 玉符充足1次变4次`() = runBlocking {
        seedJade(5)
        seedRefreshChances(1)

        isMerchantSuccess(engine.purchaseMerchantRefresh())

        assertEquals("1 次应变为 4 次", 4, store.gameDataSnapshot.merchantRefreshChances)
        assertEquals("玉符应扣 1 枚", 4, store.gameDataSnapshot.jadeSymbols)
        assertEquals(4, jadeService.runtimeState.value.total)
    }

    @Test
    fun `purchaseMerchantRefresh - 接近上限998时钳制至999`() = runBlocking {
        seedJade(5)
        seedRefreshChances(998)

        isMerchantSuccess(engine.purchaseMerchantRefresh())

        assertEquals("998+3 应钳制至 999", 999, store.gameDataSnapshot.merchantRefreshChances)
    }

    @Test
    fun `purchaseMerchantRefresh - 已达999上限返回LimitReached且不扣玉符`() = runBlocking {
        seedJade(5)
        seedRefreshChances(999)

        val result = engine.purchaseMerchantRefresh()

        assertTrue("期望 LimitReached，实际 $result", result is MerchantRefreshResult.LimitReached)
        assertEquals("达上限不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        assertEquals("次数保持 999", 999, store.gameDataSnapshot.merchantRefreshChances)
    }

    @Test
    fun `purchaseMerchantRefresh - 玉符不足返回Insufficient且次数不变`() = runBlocking {
        seedJade(0)
        seedRefreshChances(1)

        val insufficient = isMerchantInsufficient(engine.purchaseMerchantRefresh())

        assertEquals(0, insufficient.current)
        assertEquals(GameConfig.JadePurchase.COST, insufficient.required)
        assertEquals("次数不变", 1, store.gameDataSnapshot.merchantRefreshChances)
        assertEquals(0, jadeService.runtimeState.value.total)
    }

    @Test
    fun `purchaseMerchantRefresh - 扣减后checkpointNow玉符不回涨`() = runBlocking {
        seedJade(5)
        seedRefreshChances(1)

        isMerchantSuccess(engine.purchaseMerchantRefresh())
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)

        jadeService.checkpointNow()

        assertEquals("checkpoint 后玉符不得回涨", 4, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 保持扣减后值", 4, jadeService.runtimeState.value.total)
        assertEquals("刷新次数保持 4", 4, store.gameDataSnapshot.merchantRefreshChances)
    }
}
