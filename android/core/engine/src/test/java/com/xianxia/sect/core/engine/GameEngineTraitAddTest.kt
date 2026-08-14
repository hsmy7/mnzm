package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitAdd
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
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
 * 新增天赋/体质/词条引擎入口测试（真实 JadeSymbolService + 固定种子 RNG + 真实 stateStore）。
 *
 * 覆盖：刷新扣减与 gameData/runtimeState 同步、刷新即持久化 pending（同弟子同类型覆盖、
 * 其他条目保留）、玉符不足（余额不变 + 不写 pending + 不消耗随机序列）、非法参数/弟子不存在/
 * 死亡拒绝、上限 5 拒绝（刷新与确认双路径）、确认新增追加到列表末尾 + 清除 pending、
 * 非法产物/重复/template 冲突拒绝、lifespan 同步、Flat 加成即时生效、以及最高风险回归——
 * 扣减后 [JadeSymbolService.checkpointNow] 玉符不回涨。
 *
 * 注意：必须 Robolectric 运行——DiscipleTables 的 String 列基于
 * android.util.SparseArray，纯 JVM 下（returnDefaultValues=true）put 静默无效。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineTraitAddTest {

    /** 单调时钟 fake（玉符服务构造要求）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    private lateinit var store: FakeAtomicStateStore
    private lateinit var jadeService: JadeSymbolService
    private lateinit var rng: DeterministicRng
    private lateinit var engine: GameEngine

    private val addTypes = listOf(TraitWashType.TALENT, TraitWashType.PHYSIQUE, TraitWashType.AFFIX)

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
        rng = DeterministicRng.fromSeed(20260815L)
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

    /** 构造真实存在的正向特质 id 列表（品阶 1，template 去重——生成语义要求 trait 内 template 互异） */
    private fun realTraitIds(type: TraitWashType, count: Int): List<String> = when (type) {
        TraitWashType.TALENT -> TalentDatabase.getByRarity(1)
            .filter { !it.isNegative }
            .distinctBy { TalentDatabase.getTalentDataById(it.id)?.template ?: it.id }
            .take(count).map { it.id }
        TraitWashType.PHYSIQUE -> PhysiqueDatabase.getByRarity(1)
            .filter { !it.isNegative }
            .distinctBy { PhysiqueDatabase.getPhysiqueDataById(it.id)?.template ?: it.id }
            .take(count).map { it.id }
        TraitWashType.AFFIX -> AffixDatabase.getByRarity(1)
            .filter { !it.isNegative }
            .distinctBy { AffixDatabase.getAffixDataById(it.id)?.template ?: it.id }
            .take(count).map { it.id }
    }

    /** 标准弟子：三类型各 2 个特质（1 号弟子） */
    private fun seedStandardDisciple(id: Int = 1) {
        store.update {
            discipleTables.insert(
                Disciple(
                    id = id.toString(),
                    name = "测试弟子$id",
                    realm = 9,
                    cultivation = 100.0,
                    spiritRootType = "fire",
                    talentIds = realTraitIds(TraitWashType.TALENT, 2),
                    physiqueIds = realTraitIds(TraitWashType.PHYSIQUE, 2),
                    affixIds = realTraitIds(TraitWashType.AFFIX, 2),
                    combat = CombatAttributes(hpVariance = 0, mpVariance = 0)
                )
            )
        }
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

    private fun isSuccess(result: TraitAddResult): TraitAddResult.Success {
        assertTrue("期望 Success，实际 $result", result is TraitAddResult.Success)
        return result as TraitAddResult.Success
    }

    private fun isInsufficient(result: TraitAddResult): TraitAddResult.InsufficientJadeSymbols {
        assertTrue("期望 InsufficientJadeSymbols，实际 $result", result is TraitAddResult.InsufficientJadeSymbols)
        return result as TraitAddResult.InsufficientJadeSymbols
    }

    private fun pendingOf(type: TraitWashType): com.xianxia.sect.core.model.PendingTraitAdd? =
        store.gameDataSnapshot.pendingTraitAdds.firstOrNull {
            it.discipleId == "1" && it.type == type.name
        }

    // ── 刷新：玉符扣减 + pending 持久化 ──

    @Test
    fun `rollTraitAdd - 玉符充足扣1枚返回无负面可解析产物并持久化pending`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        for (type in addTypes) {
            val success = isSuccess(engine.rollTraitAdd("1", type))

            val resolved = type.resolve(listOf(success.newId))
            assertEquals("产物必须可解析 (${type.displayName})", 1, resolved.size)
            val entry = resolved.first()
            assertTrue("刷新不得产出负面 (${type.displayName}): ${entry.id}", entry.rarity in 1..3)
            assertEquals(
                "刷新产物必须持久化到 pending (${type.displayName})",
                success.newId, pendingOf(type)?.traitId
            )
        }

        assertEquals("三次刷新应各扣 1 枚", 2, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 应同步（防止 checkpoint 覆盖回涨）",
            2, jadeService.runtimeState.value.total)
    }

    @Test
    fun `rollTraitAdd - 同弟子同类型继续刷新覆盖pending且其他条目保留`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        val first = isSuccess(engine.rollTraitAdd("1", TraitWashType.TALENT))
        val second = isSuccess(engine.rollTraitAdd("1", TraitWashType.TALENT))
        isSuccess(engine.rollTraitAdd("1", TraitWashType.PHYSIQUE))

        val pendings = store.gameDataSnapshot.pendingTraitAdds
        assertEquals("同弟子同类型只保留最新一条", 2, pendings.size)
        assertEquals("天赋 pending 应为最新刷新产物", second.newId,
            pendings.first { it.type == TraitWashType.TALENT.name }.traitId)
        assertTrue(
            "体质 pending 应保留",
            pendings.any { it.type == TraitWashType.PHYSIQUE.name }
        )
        // 同一类型重复刷新 id 可能相同（确定性种子），覆盖语义只断言条目数，不要求 id 不同
        assertEquals("三次刷新应各扣 1 枚", 2, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `rollTraitAdd - 玉符不足返回Insufficient且余额不变不写pending不消耗随机序列`() = runBlocking {
        seedStandardDisciple()
        seedJade(0)
        val snapshotBefore = rng.snapshot()

        for (type in addTypes) {
            val insufficient = isInsufficient(engine.rollTraitAdd("1", type))

            assertEquals(0, insufficient.current)
            assertEquals(TraitAdd.JADE_COST, insufficient.required)
            assertEquals("余额不变", 0, store.gameDataSnapshot.jadeSymbols)
            assertTrue("玉符不足不得写 pending", store.gameDataSnapshot.pendingTraitAdds.isEmpty())
        }

        assertEquals("扣减失败时不得消耗 RNG draw（随机序列确定性保持）",
            snapshotBefore, rng.snapshot())
    }

    @Test
    fun `rollTraitAdd - 弟子不存在返回Error且不扣玉符`() = runBlocking {
        seedJade(5)

        for (type in addTypes) {
            val result = engine.rollTraitAdd("999", type)

            assertTrue("期望 Error，实际 $result", result is TraitAddResult.Error)
            assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        }
    }

    @Test
    fun `rollTraitAdd - 非法弟子ID返回Error且不扣玉符`() = runBlocking {
        seedJade(5)

        val result = engine.rollTraitAdd("abc", TraitWashType.TALENT)

        assertTrue("期望 Error，实际 $result", result is TraitAddResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `rollTraitAdd - 死亡弟子拒绝刷新且不扣玉符`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)
        store.update { discipleTables.markDead(1, 1) }

        for (type in addTypes) {
            val result = engine.rollTraitAdd("1", type)

            assertTrue("死亡弟子应被拒绝，实际 $result", result is TraitAddResult.Error)
            assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        }
    }

    @Test
    fun `rollTraitAdd - 已达上限5个时拒绝刷新且不扣玉符`() = runBlocking {
        seedDisciple(talentIds = realTraitIds(TraitWashType.TALENT, TraitAdd.MAX_TRAITS_PER_CATEGORY))
        seedJade(5)

        val result = engine.rollTraitAdd("1", TraitWashType.TALENT)

        assertTrue("已达上限应被拒绝，实际 $result", result is TraitAddResult.Error)
        assertEquals("失败原因要写明", "该弟子天赋已满", (result as TraitAddResult.Error).message)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        assertTrue("上限拒绝不得写 pending", store.gameDataSnapshot.pendingTraitAdds.isEmpty())
    }

    @Test
    fun `rollTraitAdd - 余额恰为1枚时刷新成功扣至0`() = runBlocking {
        seedStandardDisciple()
        seedJade(1)

        isSuccess(engine.rollTraitAdd("1", TraitWashType.AFFIX))

        assertEquals("余额 1 时应可刷新并扣至 0", 0, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0, jadeService.runtimeState.value.total)
    }

    // ── 确认新增 ──

    @Test
    fun `confirmTraitAdd - 追加产物到列表末尾其余类型不变并清除pending`() = runBlocking {
        seedStandardDisciple()
        seedJade(3)

        val rolled = isSuccess(engine.rollTraitAdd("1", TraitWashType.TALENT))
        val before = assembleDisciple()
        val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, rolled.newId)

        assertTrue("期望 Success，实际 $result", result is TraitAddConfirmResult.Success)
        val after = assembleDisciple()
        assertEquals("新天赋应追加到列表末尾", before.talentIds + rolled.newId, after.talentIds)
        assertEquals("体质不得被触碰", before.physiqueIds, after.physiqueIds)
        assertEquals("词条不得被触碰", before.affixIds, after.affixIds)
        assertEquals("灵根不得被触碰", "fire", after.spiritRootType)
        assertTrue("确认后应清除 pending", store.gameDataSnapshot.pendingTraitAdds.isEmpty())
        assertEquals("确认新增不消耗玉符（刷新已扣）", 2, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `confirmTraitAdd - 未知产物id返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()

        val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, "unknown_trait")

        assertTrue("未知产物应被拒绝，实际 $result", result is TraitAddConfirmResult.Error)
        assertEquals("弟子天赋不得被非法产物修改", before.talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitAdd - 空产物id返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()

        val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, "")

        assertTrue("空产物应被拒绝，实际 $result", result is TraitAddConfirmResult.Error)
        assertEquals("弟子天赋不得被非法产物修改", before.talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitAdd - 已持有同id返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()

        val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, before.talentIds.first())

        assertTrue("重复 id 应被拒绝，实际 $result", result is TraitAddConfirmResult.Error)
        assertEquals("弟子天赋不得被重复新增", before.talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitAdd - 产物与现有槽位template冲突返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()
        // 与保留槽位同 template 的另一个 id（不同稀有度/同 template）→ 追加后 template 重复
        val conflicting = TalentDatabase.talents.values.firstOrNull {
            it.id !in before.talentIds &&
                (TalentDatabase.getTalentDataById(it.id)?.template
                    ?: it.id) == (TalentDatabase.getTalentDataById(before.talentIds.first())?.template
                    ?: before.talentIds.first())
        }

        if (conflicting != null) {
            val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, conflicting.id)
            assertTrue("template 冲突应被拒绝，实际 $result", result is TraitAddConfirmResult.Error)
            assertEquals("弟子天赋不得被非法产物修改", before.talentIds, assembleDisciple().talentIds)
        }
    }

    @Test
    fun `confirmTraitAdd - 弟子不存在返回Error`() = runBlocking {
        val result = engine.confirmTraitAdd("999", TraitWashType.TALENT, "x")

        assertTrue("期望 Error，实际 $result", result is TraitAddConfirmResult.Error)
    }

    @Test
    fun `confirmTraitAdd - 死亡弟子拒绝且字段不变`() = runBlocking {
        seedStandardDisciple()
        store.update { discipleTables.markDead(1, 1) }
        val before = assembleDisciple()

        val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, realTraitIds(TraitWashType.TALENT, 3).last())

        assertTrue("死亡弟子应被拒绝，实际 $result", result is TraitAddConfirmResult.Error)
        assertEquals("失败原因要写明", "弟子已死亡", (result as TraitAddConfirmResult.Error).message)
        assertEquals("死亡弟子天赋不得被新增", before.talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitAdd - 已达上限5个时拒绝确认且弟子不变`() = runBlocking {
        seedDisciple(talentIds = realTraitIds(TraitWashType.TALENT, TraitAdd.MAX_TRAITS_PER_CATEGORY))
        val before = assembleDisciple()

        val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, realTraitIds(TraitWashType.TALENT, 5).last())

        assertTrue("已达上限应被拒绝，实际 $result", result is TraitAddConfirmResult.Error)
        assertEquals("失败原因要写明", "该弟子天赋已满", (result as TraitAddConfirmResult.Error).message)
        assertEquals("已达上限时弟子不变", before.talentIds, assembleDisciple().talentIds)
    }

    // ── lifespan 同步（对齐洗炼：新增特质后寿命必须与新特质一致） ──

    @Test
    fun `confirmTraitAdd - 新增延年词条后 lifespan 按境界基准上调`() = runBlocking {
        val lifespanAffix = AffixDatabase.affixes.values.firstOrNull {
            it.effects.containsKey("lifespan") && !it.isNegative
        } ?: error("测试前提：需要正向 lifespan 词条")
        val otherAffix = AffixDatabase.getPositiveAffixes().firstOrNull {
            it.id != lifespanAffix.id && !it.effects.containsKey("lifespan")
        } ?: error("测试前提：需要无 lifespan 正向词条")
        seedDisciple(affixIds = listOf(otherAffix.id))
        val before = assembleDisciple().lifespan
        val bonus = lifespanAffix.effects["lifespan"] ?: 0.0

        val result = engine.confirmTraitAdd("1", TraitWashType.AFFIX, lifespanAffix.id)

        assertTrue("期望 Success，实际 $result", result is TraitAddConfirmResult.Success)
        val expected = before + (GameConfig.Realm.get(9).maxAge * bonus).toInt()
        assertEquals("新增延年应上调 lifespan（按境界基准折算）", expected, assembleDisciple().lifespan)
    }

    // ── 端到端：确认新增后 getBaseStats 立即反映 Flat 加成（对齐洗炼 2026-08-12 Bug 2 修复） ──

    @Test
    fun `confirmTraitAdd - 新增青帝后 getBaseStats 含灵植flat加18`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()
        // 青帝 r3 阶（灵植+18）；template "base_plant" 与保留槽位（r1 base_*）互异，可新增
        val qingdi = "r3_base_plant"

        val result = engine.confirmTraitAdd("1", TraitWashType.TALENT, qingdi)

        assertTrue("期望 Success，实际 $result", result is TraitAddConfirmResult.Success)
        val after = assembleDisciple()
        assertEquals("青帝应追加到天赋列表末尾", before.talentIds + qingdi, after.talentIds)
        val stats = DiscipleStatCalculator.getBaseStats(after)
        assertEquals("新增青帝后灵植 = 原始 + 18",
            before.skills.spiritPlanting + 18, stats.spiritPlanting)
        assertEquals("其余类型不得被触碰", before.physiqueIds, after.physiqueIds)
    }

    // ── 持久化语义：刷新后关闭界面（不确认），pending 仍在，可直接确认新增 ──

    @Test
    fun `rollTraitAdd - 刷新后不确认 pending 保留 可直接用该产物确认新增`() = runBlocking {
        seedStandardDisciple()
        seedJade(3)

        val rolled = isSuccess(engine.rollTraitAdd("1", TraitWashType.AFFIX))
        // 模拟关闭界面再打开：pending 仍在（未确认）
        val pendingId = pendingOf(TraitWashType.AFFIX)?.traitId
        assertEquals("未确认时 pending 必须保留", rolled.newId, pendingId)

        val confirm = engine.confirmTraitAdd("1", TraitWashType.AFFIX, pendingId!!)

        assertTrue("关闭后再打开应可直接确认新增，实际 $confirm", confirm is TraitAddConfirmResult.Success)
        assertEquals("词条应追加到列表末尾", assembleDisciple().affixIds.last(), rolled.newId)
        assertTrue("确认后 pending 清除", store.gameDataSnapshot.pendingTraitAdds.isEmpty())
    }

    // ── 关键回归：checkpoint 不回涨 ──

    @Test
    fun `rollTraitAdd - 扣减后 checkpointNow 玉符不回涨`() = runBlocking {
        // 最高风险：JadeSymbolService 运行时 totalCount 以绝对值覆盖写 GameData.jadeSymbols，
        // 若扣减未同步 totalCount，checkpoint 会把余额写回扣减前值（玉符回涨）
        seedStandardDisciple()
        seedJade(5)

        for (type in addTypes) {
            isSuccess(engine.rollTraitAdd("1", type))
        }
        assertEquals(2, store.gameDataSnapshot.jadeSymbols)

        jadeService.checkpointNow()

        assertEquals("checkpoint 后玉符不得回涨", 2, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 保持扣减后值", 2, jadeService.runtimeState.value.total)
    }
}
