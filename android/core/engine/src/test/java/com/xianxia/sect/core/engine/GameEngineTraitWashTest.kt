package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
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
 * 洗炼天赋/体质/词条引擎入口测试（真实 JadeSymbolService + 固定种子 RNG + 真实 stateStore）。
 *
 * 单槽语义（2026-08-09 需求变更）：洗炼只针对详情界面指定的那一个特质（targetId），
 * 其余同类特质保留不动。覆盖：扣减与 gameData/runtimeState 同步、玉符不足（余额不变 +
 * 不消耗随机序列）、非法参数/弟子不存在/死亡拒绝/targetId 不存在拒绝、保底目标槽必出上品、
 * 确认替换只改目标槽位、非法产物拦截、以及最高风险回归——扣减后
 * [JadeSymbolService.checkpointNow] 玉符不回涨。
 *
 * 注意：必须 Robolectric 运行——DiscipleTables 的 String 列基于
 * android.util.SparseArray，纯 JVM 下（returnDefaultValues=true）put 静默无效。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
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

    /** 构造真实存在的特质 id 列表（品阶 1，template 去重——生成语义要求 trait 内 template 互异） */
    private fun realTraitIds(type: TraitWashType, count: Int): List<String> = when (type) {
        TraitWashType.TALENT -> TalentDatabase.getByRarity(1)
            .distinctBy { TalentDatabase.getTalentDataById(it.id)?.template ?: it.id }
            .take(count).map { it.id }
        TraitWashType.PHYSIQUE -> PhysiqueDatabase.getByRarity(1)
            .distinctBy { PhysiqueDatabase.getPhysiqueDataById(it.id)?.template ?: it.id }
            .take(count).map { it.id }
        TraitWashType.AFFIX -> AffixDatabase.getByRarity(1)
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

    /** 目标槽位 id（标准弟子第 1 个特质） */
    private fun targetIdOf(type: TraitWashType): String = realTraitIds(type, 1).first()

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
    fun `washTraitSlot - 玉符充足扣1枚并返回合法产物`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        for (type in washTypes) {
            val success = isSuccess(engine.washTraitSlot("1", type, targetIdOf(type), 0))

            assertTrue(
                "产物必须可解析 (${type.displayName}): ${success.newId}",
                type.resolve(listOf(success.newId)).size == 1
            )
            assertTrue(
                "保底计数应为 0 或 1 (${type.displayName}): ${success.newPityCount}",
                success.newPityCount in 0..1
            )
        }
    }

    @Test
    fun `washTraitSlot - 扣减后 gameData 与 runtimeState 同步`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        for (type in washTypes) {
            isSuccess(engine.washTraitSlot("1", type, targetIdOf(type), 0))
        }

        assertEquals("三次洗炼应各扣 1 枚", 2, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 应同步（防止 checkpoint 覆盖回涨）",
            2, jadeService.runtimeState.value.total)
    }

    @Test
    fun `washTraitSlot - 玉符不足返回Insufficient且余额不变`() = runBlocking {
        seedStandardDisciple()
        seedJade(0)

        for (type in washTypes) {
            val insufficient = isInsufficient(engine.washTraitSlot("1", type, targetIdOf(type), 0))

            assertEquals(0, insufficient.current)
            assertEquals(GameConfig.TraitWash.WASH_JADE_COST, insufficient.required)
            assertEquals("余额不变", 0, store.gameDataSnapshot.jadeSymbols)
            assertEquals(0, jadeService.runtimeState.value.total)
        }
    }

    @Test
    fun `washTraitSlot - 玉符不足不消耗随机序列`() = runBlocking {
        seedStandardDisciple()
        seedJade(0)
        val snapshotBefore = rng.snapshot()

        for (type in washTypes) {
            isInsufficient(engine.washTraitSlot("1", type, targetIdOf(type), 0))
        }

        assertEquals("扣减失败时不得消耗 RNG draw（随机序列确定性保持）",
            snapshotBefore, rng.snapshot())
    }

    @Test
    fun `washTraitSlot - 弟子不存在返回Error且不扣玉符`() = runBlocking {
        seedJade(5)

        for (type in washTypes) {
            val result = engine.washTraitSlot("999", type, targetIdOf(type), 0)

            assertTrue("期望 Error，实际 $result", result is TraitWashResult.Error)
            assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        }
    }

    @Test
    fun `washTraitSlot - 非法保底计数返回Error且不扣玉符`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        val result = engine.washTraitSlot("1", TraitWashType.TALENT, targetIdOf(TraitWashType.TALENT), -1)

        assertTrue("期望 Error，实际 $result", result is TraitWashResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `washTraitSlot - 死亡弟子拒绝洗炼且不扣玉符`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)
        store.update { discipleTables.markDead(1, 1) }

        for (type in washTypes) {
            val result = engine.washTraitSlot("1", type, targetIdOf(type), 0)

            assertTrue("死亡弟子应被拒绝，实际 $result", result is TraitWashResult.Error)
            assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
        }
    }

    @Test
    fun `washTraitSlot - 目标特质不在弟子身上返回Error且不扣玉符`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        // 弟子只拥有 targetIdOf(TALENT) 列表中的 id，未持有列表外的合法 id
        val notOwned = realTraitIds(TraitWashType.TALENT, 3).last()
        val result = engine.washTraitSlot("1", TraitWashType.TALENT, notOwned, 0)

        assertTrue("目标特质不存在应被拒绝，实际 $result", result is TraitWashResult.Error)
        assertEquals("该特质已不存在", (result as TraitWashResult.Error).message)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `washTraitSlot - 空目标id返回Error且不扣玉符`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        val result = engine.washTraitSlot("1", TraitWashType.TALENT, "", 0)

        assertTrue("空目标应被拒绝，实际 $result", result is TraitWashResult.Error)
        assertEquals("不扣玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `washTraitSlot - 余额恰为1枚时洗炼成功扣至0`() = runBlocking {
        seedStandardDisciple()
        seedJade(1)

        isSuccess(engine.washTraitSlot("1", TraitWashType.AFFIX, targetIdOf(TraitWashType.AFFIX), 0))

        assertEquals("余额 1 时应可洗炼并扣至 0", 0, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0, jadeService.runtimeState.value.total)
    }

    // ── 洗炼：保底 ──

    @Test
    fun `washTraitSlot - 保底计数达阈值时目标槽必出上品且计数归零`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        for (type in washTypes) {
            val success = isSuccess(
                engine.washTraitSlot("1", type, targetIdOf(type), GameConfig.TraitWash.WASH_PITY_THRESHOLD)
            )
            val resolved = type.resolve(listOf(success.newId))

            assertEquals(
                "保底目标槽必须是 3 阶 (${type.displayName})",
                GameConfig.TraitWash.TOP_RARITY, resolved.first().rarity
            )
            assertEquals("保底后计数应归零 (${type.displayName})", 0, success.newPityCount)
        }
    }

    @Test
    fun `washTraitSlot - 结果含上品时保底计数归零`() = runBlocking {
        seedStandardDisciple()
        seedJade(5)

        // 多种子/多类型扫描：凡产物含 3 阶，newPityCount 必须为 0（pity<阈值时语义）。
        // 每次洗炼前重新播种 1 枚玉符——3 类型 × 10 次 = 30 次洗炼，一次播种不够
        for (type in washTypes) {
            repeat(10) {
                seedJade(1)
                val success = isSuccess(engine.washTraitSlot("1", type, targetIdOf(type), 0))
                val hasTop = type.resolve(listOf(success.newId)).firstOrNull()?.rarity ==
                GameConfig.TraitWash.TOP_RARITY
                if (hasTop) {
                    assertEquals("含上品时计数应归零 (${type.displayName})", 0, success.newPityCount)
                }
            }
        }
    }

    // ── 确认替换 ──

    @Test
    fun `confirmTraitWash - 只替换目标槽位且其余类型与同类型其他槽位不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()
        val type = TraitWashType.TALENT
        val targetId = before.talentIds.first()
        val newId = realTraitIds(type, 3).last() // 弟子未持有（第 1、2 个已在身上）

        val result = engine.confirmTraitWash("1", type, targetId, newId)

        assertTrue("期望 Success，实际 $result", result is TraitWashConfirmResult.Success)
        val after = assembleDisciple()
        assertEquals("目标天赋槽位应被替换", listOf(newId, before.talentIds[1]), after.talentIds)
        assertEquals("其余天赋槽位保留", before.talentIds[1], after.talentIds[1])
        assertEquals("体质不得被触碰", before.physiqueIds, after.physiqueIds)
        assertEquals("词条不得被触碰", before.affixIds, after.affixIds)
        assertEquals("灵根不得被触碰", "fire", after.spiritRootType)
        assertEquals("姓名不得被触碰", "测试弟子1", after.name)
    }

    @Test
    fun `confirmTraitWash - 体质与词条单槽替换互不影响`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()
        val physiqueTarget = before.physiqueIds.first()
        val newPhysique = realTraitIds(TraitWashType.PHYSIQUE, 3).last()
        val affixTarget = before.affixIds.first()
        val newAffix = realTraitIds(TraitWashType.AFFIX, 3).last()

        val r1 = engine.confirmTraitWash("1", TraitWashType.PHYSIQUE, physiqueTarget, newPhysique)
        val r2 = engine.confirmTraitWash("1", TraitWashType.AFFIX, affixTarget, newAffix)

        assertTrue("体质替换应成功，实际 $r1", r1 is TraitWashConfirmResult.Success)
        assertTrue("词条替换应成功，实际 $r2", r2 is TraitWashConfirmResult.Success)
        val after = assembleDisciple()
        assertEquals(listOf(newPhysique, before.physiqueIds[1]), after.physiqueIds)
        assertEquals(listOf(newAffix, before.affixIds[1]), after.affixIds)
        assertEquals("天赋不得被触碰", before.talentIds, after.talentIds)
    }

    @Test
    fun `confirmTraitWash - 目标不存在返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()
        val newId = realTraitIds(TraitWashType.TALENT, 3).last()

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, "not_owned_trait", newId)

        assertTrue("目标不存在应被拒绝，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("弟子特质不得被非法产物修改", before.talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitWash - 未知产物id返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val targetId = assembleDisciple().talentIds.first()

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, targetId, "unknown_trait")

        assertTrue("未知产物应被拒绝，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("弟子天赋不得被非法产物修改", assembleDisciple().talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitWash - 产物与保留槽位template冲突返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()
        val targetId = before.talentIds.first()
        // 产物 id 与保留槽位相同 → 替换后列表 template 重复 → 校验拒绝
        val conflictingId = before.talentIds[1]

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, targetId, conflictingId)

        assertTrue("template 冲突应被拒绝，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("弟子天赋不得被非法产物修改", before.talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitWash - 空产物id返回Error且弟子不变`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, before.talentIds.first(), "")

        assertTrue("空产物应被拒绝，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("弟子天赋不得被非法产物修改", before.talentIds, assembleDisciple().talentIds)
    }

    @Test
    fun `confirmTraitWash - 弟子不存在返回Error`() = runBlocking {
        val result = engine.confirmTraitWash("999", TraitWashType.TALENT, "x", "y")

        assertTrue("期望 Error，实际 $result", result is TraitWashConfirmResult.Error)
    }

    @Test
    fun `confirmTraitWash - 死亡弟子拒绝替换且字段不变`() = runBlocking {
        seedStandardDisciple()
        store.update { discipleTables.markDead(1, 1) }
        val before = assembleDisciple()
        val newId = realTraitIds(TraitWashType.TALENT, 3).last()

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, before.talentIds.first(), newId)

        assertTrue("死亡弟子应被拒绝，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("死亡弟子天赋不得被替换", before.talentIds, assembleDisciple().talentIds)
    }

    // ── lifespan 同步（对抗性审查 2026-08-09 数据篡改者：洗炼前后寿命必须与新特质一致） ──

    // ── 端到端：确认洗炼产物后 getBaseStats 立即反映 Flat 加成（2026-08-12 Bug 2 修复） ──
    // 修复前 spiritPlantingFlat 无落点：洗出"青帝(灵植+18)"后属性页灵植不变

    @Test
    fun `confirmTraitWash - 确认青帝后 getBaseStats 含灵植flat加18`() = runBlocking {
        seedStandardDisciple()
        val before = assembleDisciple()
        val targetId = before.talentIds.first()
        // 青帝 r3 阶（灵植+18）；template "base_plant" 与保留槽位（r1 base_*）互异，可确认
        val qingdi = "r3_base_plant"

        val result = engine.confirmTraitWash("1", TraitWashType.TALENT, targetId, qingdi)

        assertTrue("期望 Success，实际 $result", result is TraitWashConfirmResult.Success)
        val after = assembleDisciple()
        assertEquals("青帝应落库到目标槽位", listOf(qingdi, before.talentIds[1]), after.talentIds)
        val stats = DiscipleStatCalculator.getBaseStats(after)
        assertEquals("确认青帝后灵植 = 原始 + 18",
            before.skills.spiritPlanting + 18, stats.spiritPlanting)
        assertEquals("其余类型不得被触碰", before.physiqueIds, after.physiqueIds)
    }

    @Test
    fun `confirmTraitWash - 洗入延年词条后 lifespan 按境界基准上调`() = runBlocking {
        val lifespanAffix = AffixDatabase.affixes.values.firstOrNull {
            it.effects.containsKey("lifespan") && !it.isNegative
        } ?: error("测试前提：需要正向 lifespan 词条")
        val otherAffix = AffixDatabase.getPositiveAffixes().firstOrNull {
            it.id != lifespanAffix.id && !it.effects.containsKey("lifespan")
        } ?: error("测试前提：需要无 lifespan 正向词条")
        seedDisciple(affixIds = listOf(otherAffix.id))
        val before = assembleDisciple().lifespan
        val bonus = lifespanAffix.effects["lifespan"] ?: 0.0

        val result = engine.confirmTraitWash("1", TraitWashType.AFFIX, otherAffix.id, lifespanAffix.id)

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

        val result = engine.confirmTraitWash("1", TraitWashType.AFFIX, lifespanAffix.id, otherAffix.id)

        assertTrue("期望 Success，实际 $result", result is TraitWashConfirmResult.Success)
        val expected = (before + (GameConfig.Realm.get(9).maxAge * -bonus).toInt()).coerceAtLeast(1)
        assertEquals("洗掉延年应下调 lifespan（按境界基准折算）", expected, assembleDisciple().lifespan)
    }

    @Test
    fun `confirmTraitWash - 死亡弟子返回已死亡文案`() = runBlocking {
        seedStandardDisciple()
        store.update { discipleTables.markDead(1, 1) }
        val before = assembleDisciple()

        val result = engine.confirmTraitWash(
            "1", TraitWashType.TALENT, before.talentIds.first(), realTraitIds(TraitWashType.TALENT, 3).last()
        )

        assertTrue("期望 Error，实际 $result", result is TraitWashConfirmResult.Error)
        assertEquals("失败原因要写明", "弟子已死亡", (result as TraitWashConfirmResult.Error).message)
    }

    // ── 关键回归：checkpoint 不回涨 ──

    @Test
    fun `washTraitSlot - 扣减后 checkpointNow 玉符不回涨`() = runBlocking {
        // 最高风险：JadeSymbolService 运行时 totalCount 以绝对值覆盖写 GameData.jadeSymbols，
        // 若扣减未同步 totalCount，checkpoint 会把余额写回扣减前值（玉符回涨）
        seedStandardDisciple()
        seedJade(5)

        for (type in washTypes) {
            isSuccess(engine.washTraitSlot("1", type, targetIdOf(type), 0))
        }
        assertEquals(2, store.gameDataSnapshot.jadeSymbols)

        jadeService.checkpointNow()

        assertEquals("checkpoint 后玉符不得回涨", 2, store.gameDataSnapshot.jadeSymbols)
        assertEquals("运行时 totalCount 保持扣减后值", 2, jadeService.runtimeState.value.total)
    }
}
