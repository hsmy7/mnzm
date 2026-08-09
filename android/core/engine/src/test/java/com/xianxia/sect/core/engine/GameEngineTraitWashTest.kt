package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitWashType
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
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
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
 * 洗炼天赋/体质/词条引擎入口测试（真实 JadeSymbolService + 固定种子 RNG + 真实 stateStore）。
 *
 * 覆盖：扣减与 gameData/runtimeState 同步、玉符不足（余额不变 + 不消耗随机序列）、
 * 非法参数/弟子不存在/死亡拒绝、保底必出上品、确认替换生效与非法产物拦截、
 * 以及最高风险回归——扣减后 [JadeSymbolService.checkpointNow] 玉符不回涨。
 *
 * 注意：必须 Robolectric 运行——DiscipleTables 的 String 列基于
 * android.util.SparseArray，纯 JVM 下（returnDefaultValues=true）put 静默无效。
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineTraitWashTest {

    /** 单调时钟 fake（玉符服务构造要求）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    private lateinit var store: FakeAtomicStateStore
    private lateinit var jadeService: JadeSymbolService
    private lateinit var rng: DeterministicRng
    private lateinit var engine: GameEngine

    private val washTypes = listOf(TraitWashType.TALENT, TraitWashType.PHYSIQUE, TraitWashType.AFFIX)

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
        rng = DeterministicRng.fromSeed(20260809L)
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

    private fun seedDisciple(
        id: Int = 1,
        talentIds: List<String> = emptyList(),
        physiqueIds: List<String> = emptyList(),
        affixIds: List<String> = emptyList()
    ) {
        store.update {
            discipleTables.insert(
                Disciple(
                    id = id.toString(),
                    name = "测试弟子$id",
                    realm = 9,
                    cultivation = 100.0,
                    spiritRootType = "fire",
                    talentIds = talentIds,
                    physiqueIds = physiqueIds,
                    affixIds = affixIds,
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

    private fun isSuccess(result: TraitWashResult): TraitWashResult.Success {
        assertTrue("期望 Success，实际 $result", result is TraitWashResult.Success)
        return result as TraitWashResult.Success
    }

    private fun isInsufficient(result: TraitWashResult): TraitWashResult.InsufficientJadeSymbols {
        assertTrue("期望 InsufficientJadeSymbols，实际 $result", result is TraitWashResult.InsufficientJadeSymbols)
        return result as TraitWashResult.InsufficientJadeSymbols
    }

    // ── 洗炼：玉符扣减 ──

    @Test
    fun `washTrait - 玉符充足扣1枚并返回合法产物`() = runBlocking {
        seedDisciple()
        seedJade(5)

        for (type in washTypes) {
            val success = isSuccess(engine.washTrait("1", type, 0))

            assertTrue(
                "产物数量应在 0~MAX 之间 (${type.displayName}): ${success.newIds.size}",
                success.newIds.size in 0..GameConfig.TraitWash.MAX_TRAIT_COUNT
            )
            assertEquals(
                "产物 id 必须全部可解析 (${type.displayName})",
                success.newIds.size,
                type.resolve(success.newIds).size
            )
            assertTrue(
                "保底计数应为 0 或 1 (${type.displayName}): ${success.newPityCount}",
                success.newPityCount in 0..1
            )
        }
    }

    @Test
    fun `washTrait - 扣减后 gameData 与 runtimeState 同步`() = runBlocking {
        seedDisciple()
        seedJade(5)

        isSuccess(engine.washTrait("1", TraitWashType.TALENT, 0))
        isSuccess(engine.washTrait("1", TraitWashType.PHYSIQUE, 0))
        isSuccess(engine.washTrait("1", TraitWashType.AFFIX, 0))

        assertEquals("三次洗炼应各扣 1 枚", 2, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 应同步（防止 checkpoint 覆盖回涨）",
            2, jadeService.runtimeState.value.total)
    }

    @Test
    fun `washTrait - 玉符不足返回Insufficient且余额不变`() = runBlocking {
        seedDisciple()
        seedJade(0)

        for (type in washTypes) {
            val insufficient = isInsufficient(engine.washTrait("1", type, 0))

            assertEquals(0, insufficient.current)
            assertEquals(GameConfig.TraitWash.WASH_JADE_COST, insufficient.required)
            assertEquals("余额不变", 0, store.gameDataSnapshot.jadeSymbols)
            assertEquals(0, jadeService.runtimeState.value.total)
        }
    }

    @Test
    fun `washTrait - 玉符不足不消耗随机序列`() = runBlocking {
        seedDisciple()
        seedJade(0)
        val snapshotBefore = rng.snapshot()

        for (type in washTypes) {
            isInsufficient(engine.washTrait("1", type, 0))
        }

        assertEquals("扣减失败时不得消耗 RNG draw（随机序列确定性保持）",
            snapshotBefore, rng.snapshot())
    }

    @Test
    fun `washTrait - 弟子不存在返回Error且不扣玉符`() = runBlocking {
        seedJade(5)

        for (type in washTypes) {
            val result = engine.washTrait("999", type, 0)

            assertTrue("期望 Error，实际 $result", result is TraitWashResult.Error)
            assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        }
    }

    @Test
    fun `washTrait - 非法保底计数返回Error且不扣玉符`() = runBlocking {
        seedDisciple()
        seedJade(5)

        val result = engine.washTrait("1", TraitWashType.TALENT, -1)

        assertTrue("期望 Error，实际 $result", result is TraitWashResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `washTrait - 死亡弟子拒绝洗炼且不扣玉符`() = runBlocking {
        seedDisciple()
        seedJade(5)
        store.update { discipleTables.markDead(1, 1) }

        for (type in washTypes) {
            val result = engine.washTrait("1", type, 0)

            assertTrue("死亡弟子应被拒绝，实际 $result", result is TraitWashResult.Error)
            assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        }
    }

    @Test
    fun `washTrait - 余额恰为1枚时洗炼成功扣至0`() = runBlocking {
        seedDisciple()
        seedJade(1)

        isSuccess(engine.washTrait("1", TraitWashType.AFFIX, 0))

        assertEquals("余额 1 时应可洗炼并扣至 0", 0, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0, jadeService.runtimeState.value.total)
    }

    // ── 洗炼：保底 ──

    @Test
    fun `washTrait - 保底计数达阈值时必出至少1个上品且计数归零`() = runBlocking {
        seedDisciple()
        seedJade(5)

        for (type in washTypes) {
            val success = isSuccess(engine.washTrait("1", type, GameConfig.TraitWash.WASH_PITY_THRESHOLD))
            val resolved = type.resolve(success.newIds)

            assertTrue(
                "保底结果必须含至少 1 个 3 阶 (${type.displayName})",
                resolved.any { it.rarity == GameConfig.TraitWash.TOP_RARITY }
            )
            assertEquals("保底后计数应归零 (${type.displayName})", 0, success.newPityCount)
        }
    }

    @Test
    fun `washTrait - 结果含上品时保底计数归零`() = runBlocking {
        seedDisciple()
        seedJade(5)

        // 多种子/多类型扫描：凡结果含 3 阶，newPityCount 必须为 0（pity<阈值时语义）。
        // 每次洗炼前重新播种 1 枚玉符——3 类型 × 10 次 = 30 次洗炼，一次播种不够
        for (type in washTypes) {
            repeat(10) {
                seedJade(1)
                val success = isSuccess(engine.washTrait("1", type, 0))
                val hasTop = type.resolve(success.newIds).any { it.rarity == GameConfig.TraitWash.TOP_RARITY }
                if (hasTop) {
                    assertEquals("含上品时计数应归零 (${type.displayName})", 0, success.newPityCount)
                }
            }
        }
    }

    // ── 确认替换 ──

    @Test
    fun `confirmTraitWash - 合法产物替换对应字段且其余字段不变`() = runBlocking {
        seedDisciple(
            talentIds = listOf("initial_talent"),
            physiqueIds = listOf("initial_physique"),
            affixIds = listOf("initial_affix")
        )
        // 生成语义 template 去重：同一 template 可能有多条目（如 r1_cult_speed/r2_cult_speed 同 template 同 rarity），
        // 合法产物必须 template 互异（引擎校验与生成逻辑一致），这里按 template 去重构造
        val newTalents = TalentDatabase.getByRarity(1)
            .distinctBy { TalentDatabase.getTalentDataById(it.id)?.template ?: it.id }
            .take(2).map { it.id }
        val newPhysiques = PhysiqueDatabase.getByRarity(1)
            .distinctBy { PhysiqueDatabase.getPhysiqueDataById(it.id)?.template ?: it.id }
            .take(1).map { it.id }
        val newAffixes = AffixDatabase.getByRarity(1)
            .distinctBy { AffixDatabase.getAffixDataById(it.id)?.template ?: it.id }
            .take(2).map { it.id }

        val r1 = engine.confirmTraitWash("1", TraitWashType.TALENT, newTalents)
        val r2 = engine.confirmTraitWash("1", TraitWashType.PHYSIQUE, newPhysiques)
        val r3 = engine.confirmTraitWash("1", TraitWashType.AFFIX, newAffixes)

        assertTrue("期望 Success，实际 $r1", r1 is TraitWashConfirmResult.Success)
        assertTrue("期望 Success，实际 $r2", r2 is TraitWashConfirmResult.Success)
        assertTrue("期望 Success，实际 $r3", r3 is TraitWashConfirmResult.Success)
        val disciple = assembleDisciple()
        assertEquals("天赋应被替换", newTalents, disciple.talentIds)
        assertEquals("体质应被替换", newPhysiques, disciple.physiqueIds)
        assertEquals("词条应被替换", newAffixes, disciple.affixIds)
        assertEquals("灵根不得被触碰", "fire", disciple.spiritRootType)
        assertEquals("姓名不得被触碰", "测试弟子1", disciple.name)
    }

    @Test
    fun `confirmTraitWash - 空产物可确认替换（洗炼掷出0个特质）`() = runBlocking {
        seedDisciple(talentIds = listOf("initial_talent"))

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, emptyList())

        assertTrue("空产物应可确认，实际 $result", result is TraitWashConfirmResult.Success)
        assertEquals("天赋应清空", emptyList<String>(), assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitWash - 非法产物返回Error且弟子不变`() = runBlocking {
        seedDisciple(talentIds = listOf("initial_talent"))

        val validId = TalentDatabase.getByRarity(1).first().id
        // 超上限（6 个）、未知 id、重复 id（template 重复）
        val invalidList = listOf(
            listOf("unknown_1", "unknown_2", "unknown_3", "unknown_4", "unknown_5", "unknown_6"),
            listOf("unknown_trait"),
            listOf(validId, validId)
        )

        for (invalid in invalidList) {
            val result = engine.confirmTraitWash("1", TraitWashType.TALENT, invalid)
            assertTrue("产物 $invalid 应被拒绝，实际 $result", result is TraitWashConfirmResult.Error)
            assertEquals("弟子天赋不得被非法产物修改", listOf("initial_talent"), assembleDisciple().talentIds)
        }
    }

    @Test
    fun `confirmTraitWash - 弟子不存在返回Error`() = runBlocking {
        val result = engine.confirmTraitWash("999", TraitWashType.TALENT, emptyList())

        assertTrue("期望 Error，实际 $result", result is TraitWashConfirmResult.Error)
    }

    @Test
    fun `confirmTraitWash - 死亡弟子拒绝替换且字段不变`() = runBlocking {
        seedDisciple(talentIds = listOf("initial_talent"))
        store.update { discipleTables.markDead(1, 1) }
        val newTalents = TalentDatabase.getByRarity(1).take(1).map { it.id }

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, newTalents)

        assertTrue("死亡弟子应被拒绝，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("死亡弟子天赋不得被替换", listOf("initial_talent"), assembleDisciple().talentIds)
    }

    // ── lifespan 同步（对抗性审查 2026-08-09 数据篡改者：洗炼前后寿命必须与新特质一致） ──

    @Test
    fun `confirmTraitWash - 洗入延年词条后 lifespan 按境界基准上调`() = runBlocking {
        val lifespanAffix = AffixDatabase.affixes.values.firstOrNull {
            it.effects.containsKey("lifespan") && !it.isNegative
        } ?: error("测试前提：需要正向 lifespan 词条")
        seedDisciple()
        val before = assembleDisciple().lifespan
        val bonus = lifespanAffix.effects["lifespan"] ?: 0.0

        val result = engine.confirmTraitWash("1", TraitWashType.AFFIX, listOf(lifespanAffix.id))

        assertTrue("期望 Success，实际 $result", result is TraitWashConfirmResult.Success)
        val expected = before + (GameConfig.Realm.get(9).maxAge * bonus).toInt()
        assertEquals("洗入延年应上调 lifespan（按境界基准折算）", expected, assembleDisciple().lifespan)
    }

    @Test
    fun `confirmTraitWash - 洗掉延年词条后 lifespan 按境界基准下调`() = runBlocking {
        val lifespanAffix = AffixDatabase.affixes.values.firstOrNull {
            it.effects.containsKey("lifespan") && !it.isNegative
        } ?: error("测试前提：需要正向 lifespan 词条")
        val otherAffix = AffixDatabase.getPositiveAffixes().firstOrNull {
            it.id != lifespanAffix.id && !it.effects.containsKey("lifespan")
        } ?: error("测试前提：需要无 lifespan 正向词条")
        seedDisciple(affixIds = listOf(lifespanAffix.id))
        val before = assembleDisciple().lifespan
        val bonus = lifespanAffix.effects["lifespan"] ?: 0.0

        val result = engine.confirmTraitWash("1", TraitWashType.AFFIX, listOf(otherAffix.id))

        assertTrue("期望 Success，实际 $result", result is TraitWashConfirmResult.Success)
        val expected = (before + (GameConfig.Realm.get(9).maxAge * -bonus).toInt()).coerceAtLeast(1)
        assertEquals("洗掉延年应下调 lifespan（按境界基准折算）", expected, assembleDisciple().lifespan)
    }

    @Test
    fun `confirmTraitWash - 死亡弟子返回已死亡文案`() = runBlocking {
        seedDisciple(talentIds = listOf("initial_talent"))
        store.update { discipleTables.markDead(1, 1) }

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, emptyList())

        assertTrue("期望 Error，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("失败原因要写明", "弟子已死亡", (result as TraitWashConfirmResult.Error).message)
    }

    // ── 关键回归：checkpoint 不回涨 ──

    @Test
    fun `washTrait - 扣减后 checkpointNow 玉符不回涨`() = runBlocking {
        // 最高风险：JadeSymbolService 运行时 totalCount 以绝对值覆盖写 GameData.jadeSymbols，
        // 若扣减未同步 totalCount，checkpoint 会把余额写回扣减前值（玉符回涨）
        seedDisciple()
        seedJade(5)

        isSuccess(engine.washTrait("1", TraitWashType.TALENT, 0))
        isSuccess(engine.washTrait("1", TraitWashType.PHYSIQUE, 0))
        isSuccess(engine.washTrait("1", TraitWashType.AFFIX, 0))
        assertEquals(2, store.gameDataSnapshot.jadeSymbols)

        jadeService.checkpointNow()

        assertEquals("checkpoint 后玉符不得回涨", 2, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 保持扣减后值", 2, jadeService.runtimeState.value.total)
    }
}
