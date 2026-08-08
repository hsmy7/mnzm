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
import com.xianxia.sect.core.model.SpiritRoot
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
 * 洗炼灵根引擎入口测试（真实 JadeSymbolService + 固定种子 RNG + 真实 stateStore）。
 *
 * 覆盖：扣减与 gameData/runtimeState 同步、玉符不足（余额不变 + 不消耗随机序列）、
 * 非法参数/弟子不存在、确认替换生效与非法产物拦截、以及最高风险回归——
 * 扣减后 [JadeSymbolService.checkpointNow] 玉符不回涨。
 *
 * 注意：必须 Robolectric 运行——DiscipleTables 的 String 列基于
 * android.util.SparseArray，纯 JVM 下（returnDefaultValues=true）put 静默无效。
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineSpiritRootWashTest {

    /** 单调时钟 fake（玉符服务构造要求）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    private lateinit var store: FakeAtomicStateStore
    private lateinit var jadeService: JadeSymbolService
    private lateinit var rng: DeterministicRng
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
        rng = DeterministicRng.fromSeed(20260808L)
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

    private fun seedDisciple(id: Int = 1) {
        // DiscipleTables 有写守卫：插入必须发生在 stateStore.update{} 事务内
        store.update {
            discipleTables.insert(
                Disciple(
                    id = id.toString(),
                    name = "测试弟子$id",
                    realm = 9,
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

    private fun assembleDisciple(id: Int = 1): Disciple = store.persistentDiscipleTables.assemble(id)

    private fun isSuccess(result: SpiritRootWashResult): SpiritRootWashResult.Success {
        assertTrue("期望 Success，实际 $result", result is SpiritRootWashResult.Success)
        return result as SpiritRootWashResult.Success
    }

    private fun isInsufficient(result: SpiritRootWashResult): SpiritRootWashResult.InsufficientJadeSymbols {
        assertTrue("期望 InsufficientJadeSymbols，实际 $result", result is SpiritRootWashResult.InsufficientJadeSymbols)
        return result as SpiritRootWashResult.InsufficientJadeSymbols
    }

    // ── 洗炼：玉符扣减 ──

    @Test
    fun `washSpiritRoot - 玉符充足扣1枚并返回合法产物`() = runBlocking {
        seedDisciple()
        seedJade(5)

        val success = isSuccess(engine.washSpiritRoot("1", 0))

        val elements = success.newRootType.split(",")
        assertTrue("产物元素数应在 1~2 之间: ${success.newRootType}", elements.size in 1..2)
        assertTrue(
            "产物元素必须在洗炼元素表内: ${success.newRootType}",
            elements.all { it in GameConfig.SpiritRoot.WASH_ELEMENT_KEYS }
        )
        assertTrue("保底计数应为 0 或 1: ${success.newPityCount}", success.newPityCount in 0..1)
    }

    @Test
    fun `washSpiritRoot - 扣减后 gameData 与 runtimeState 同步`() = runBlocking {
        seedDisciple()
        seedJade(5)

        isSuccess(engine.washSpiritRoot("1", 0))

        assertEquals("GameData 玉符应扣 1 枚", 4, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 应同步为 4（防止 checkpoint 覆盖回涨）",
            4, jadeService.runtimeState.value.total)
    }

    @Test
    fun `washSpiritRoot - 玉符不足返回Insufficient且余额不变`() = runBlocking {
        seedDisciple()
        seedJade(0)

        val insufficient = isInsufficient(engine.washSpiritRoot("1", 0))

        assertEquals(0, insufficient.current)
        assertEquals(GameConfig.SpiritRoot.WASH_JADE_COST, insufficient.required)
        assertEquals("余额不变", 0, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0, jadeService.runtimeState.value.total)
    }

    @Test
    fun `washSpiritRoot - 玉符不足不消耗随机序列`() = runBlocking {
        seedDisciple()
        seedJade(0)
        val snapshotBefore = rng.snapshot()

        isInsufficient(engine.washSpiritRoot("1", 0))

        assertEquals("扣减失败时不得消耗 RNG draw（随机序列确定性保持）",
            snapshotBefore, rng.snapshot())
    }

    @Test
    fun `washSpiritRoot - 弟子不存在返回Error且不扣玉符`() = runBlocking {
        seedJade(5)

        val result = engine.washSpiritRoot("999", 0)

        assertTrue("期望 Error，实际 $result", result is SpiritRootWashResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `washSpiritRoot - 非法保底计数返回Error且不扣玉符`() = runBlocking {
        seedDisciple()
        seedJade(5)

        val result = engine.washSpiritRoot("1", -1)

        assertTrue("期望 Error，实际 $result", result is SpiritRootWashResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    // ── 确认替换 ──

    @Test
    fun `confirmSpiritRootWash - 合法产物替换弟子灵根且名称即刻生效`() = runBlocking {
        seedDisciple()

        val result = engine.confirmSpiritRootWash("1", "metal,water")

        assertTrue("期望 Success，实际 $result", result is SpiritRootWashConfirmResult.Success)
        assertEquals("弟子灵根应被替换", "metal,water", assembleDisciple().spiritRootType)
        assertEquals("展示名称应解析为双灵根", "双灵根(金水)", SpiritRoot("metal,water").name)
    }

    @Test
    fun `confirmSpiritRootWash - 非法产物串返回Error且弟子不变`() = runBlocking {
        seedDisciple()
        val invalidTypes = listOf("metal,wood,water", "metal,xyz", "", "fire,fire,fire")

        for (invalid in invalidTypes) {
            val result = engine.confirmSpiritRootWash("1", invalid)
            assertTrue("产物 $invalid 应被拒绝，实际 $result", result is SpiritRootWashConfirmResult.Error)
            assertEquals("弟子灵根不得被非法产物修改", "fire", assembleDisciple().spiritRootType)
        }
    }

    @Test
    fun `confirmSpiritRootWash - 弟子不存在返回Error`() = runBlocking {
        val result = engine.confirmSpiritRootWash("999", "metal")

        assertTrue("期望 Error，实际 $result", result is SpiritRootWashConfirmResult.Error)
    }

    // ── 关键回归：checkpoint 不回涨 ──

    @Test
    fun `washSpiritRoot - 扣减后 checkpointNow 玉符不回涨`() = runBlocking {
        // 最高风险：JadeSymbolService 运行时 totalCount 以绝对值覆盖写 GameData.jadeSymbols，
        // 若扣减未同步 totalCount，checkpoint 会把余额写回扣减前值（玉符回涨）
        seedDisciple()
        seedJade(5)

        isSuccess(engine.washSpiritRoot("1", 0))
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)

        jadeService.checkpointNow()

        assertEquals("checkpoint 后玉符不得回涨", 4, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 保持扣减后值", 4, jadeService.runtimeState.value.total)
    }
}
