package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.pillCritEffectBonus
import com.xianxia.sect.core.model.pillCritRateBonus
import com.xianxia.sect.core.model.pillCultivationSpeedBonus
import com.xianxia.sect.core.model.pillEffectDuration
import com.xianxia.sect.core.model.pillHpBonus
import com.xianxia.sect.core.model.pillMagicAttackBonus
import com.xianxia.sect.core.model.pillMagicDefenseBonus
import com.xianxia.sect.core.model.pillMpBonus
import com.xianxia.sect.core.model.pillNurtureSpeedBonus
import com.xianxia.sect.core.model.pillPhysicalAttackBonus
import com.xianxia.sect.core.model.pillPhysicalDefenseBonus
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.pillSkillExpSpeedBonus
import com.xianxia.sect.core.model.pillSpeedBonus
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner



/**
 * 验证 CultivationCore.processRealtimeAutoPills() 的实时轨丹药自动服用逻辑。
 *
 * 核心约定：
 * - 突破丹不由 processRealtimeAutoPills 消费（由突破检测内联处理）
 * - 储物袋无丹药的弟子直接跳过，不 assemble
 * - 有丹药的弟子逐颗判断 canUsePill，符合条件的自动消费
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class CultivationCoreRealtimeAutoPillsTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var pillManager: DisciplePillManager

    @Before
    fun setUp() {
        pillManager = DisciplePillManager(PillEffectApplier())
        // C1（2026-08-31）：治疗丹满血判定经 disciple.maxHp（getBaseStats）——
        // 纯 JUnit 需手动绑定晚绑定属性计算器（对齐 Diff 对拍测试模式）
        com.xianxia.sect.core.model.DiscipleAggregate.statsProvider =
            object : com.xianxia.sect.core.model.DiscipleStatsProvider {
                override fun getBaseStats(disciple: com.xianxia.sect.core.model.Disciple) =
                    DiscipleStatCalculator.getBaseStats(disciple)
                override fun getBaseStats(aggregate: com.xianxia.sect.core.model.DiscipleAggregate) =
                    DiscipleStatCalculator.getBaseStats(aggregate)
                override fun getTalentEffects(disciple: com.xianxia.sect.core.model.Disciple) =
                    DiscipleStatCalculator.getTalentEffects(disciple)
                override fun getTalentEffects(aggregate: com.xianxia.sect.core.model.DiscipleAggregate) =
                    DiscipleStatCalculator.getTalentEffects(aggregate)
                override fun getStatsWithEquipment(
                    d: com.xianxia.sect.core.model.Disciple,
                    e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>
                ) = DiscipleStatCalculator.getStatsWithEquipment(d, e)
                override fun getStatsWithEquipment(
                    a: com.xianxia.sect.core.model.DiscipleAggregate,
                    e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>
                ) = DiscipleStatCalculator.getStatsWithEquipment(a, e)
                override fun getFinalStats(
                    d: com.xianxia.sect.core.model.Disciple,
                    e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
                    m: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                    p: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                    bloodRefinementPct: com.xianxia.sect.core.model.BloodRefinementPctTotal?
                ) = DiscipleStatCalculator.getFinalStats(d, e, m, p, bloodRefinementPct)
                override fun getFinalStats(
                    a: com.xianxia.sect.core.model.DiscipleAggregate,
                    e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
                    m: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                    p: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                    bloodRefinementPct: com.xianxia.sect.core.model.BloodRefinementPctTotal?
                ) = DiscipleStatCalculator.getFinalStats(a, e, m, p, bloodRefinementPct)
                override fun calculateCultivationSpeed(
                    d: com.xianxia.sect.core.model.Disciple,
                    manuals: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                    mps: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                    bb: Double, ab: Double, peb: Double, pmb: Double,
                    csb: Double, pcb: Double, gcp: Double, mdb: Double
                ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                    d, manuals, mps, bb, peb, pmb, csb, pcb, gcp
                )
                override fun calculateCultivationSpeed(
                    a: com.xianxia.sect.core.model.DiscipleAggregate,
                    manuals: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                    mps: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                    bb: Double, ab: Double, peb: Double, pmb: Double,
                    csb: Double, pcb: Double, gcp: Double, mdb: Double
                ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                    a, manuals, mps, bb, peb, pmb, csb, pcb, gcp
                )
                override fun getBreakthroughChance(
                    d: com.xianxia.sect.core.model.Disciple, iec: Int, oec: Int, pb: Double,
                    ab: Double, gcp: Double, mdb: Double
                ) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gcp, mdb)
                override fun getBreakthroughChance(
                    a: com.xianxia.sect.core.model.DiscipleAggregate, iec: Int, oec: Int, pb: Double,
                    ab: Double, gcp: Double, mdb: Double
                ) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gcp, mdb)
            }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────

    private fun stateWithDisciple(
        id: Int = 1,
        name: String = "测试弟子",
        realm: Int = 9,
        storageBagItems: List<StorageBagItem> = emptyList(),
        cultivation: Double = 0.0
    ): MutableGameState {
        val tables = DiscipleTables()
        val disciple = Disciple(
            id = id.toString(),
            name = name,
            realm = realm,
            cultivation = cultivation,
            equipment = EquipmentSet(storageBagItems = storageBagItems)
        )
        tables.insert(disciple)
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    private fun cultivationPill(
        itemId: String = "pill_cult_1",
        name: String = "修为丹",
        cultivationAdd: Int = 50,
        rarity: Int = 3
    ): StorageBagItem = StorageBagItem(
        itemId = itemId, itemType = "pill", name = name,
        rarity = rarity, quantity = 1,
        effect = ItemEffect(pillType = "cultivationAdd", cultivationAdd = cultivationAdd)
    )

    private fun breakthroughPill(
        itemId: String = "pill_bt_1",
        name: String = "突破丹",
        rarity: Int = 5,
        breakthroughChance: Double = 0.3,
        targetRealm: Int = 9
    ): StorageBagItem = StorageBagItem(
        itemId = itemId, itemType = "pill", name = name,
        rarity = rarity, quantity = 1,
        effect = ItemEffect(
            pillType = "breakthrough",
            breakthroughChance = breakthroughChance,
            targetRealm = targetRealm
        )
    )

    private fun healPill(
        itemId: String = "pill_heal_1",
        name: String = "疗伤丹",
        healPercent: Double = 50.0
    ): StorageBagItem = StorageBagItem(
        itemId = itemId, itemType = "pill", name = name,
        rarity = 2, quantity = 1,
        effect = ItemEffect(pillType = "", healMaxHpPercent = healPercent)
    )

    // ── 指纹检测：空储物袋跳过 ─────────────────────────────────────

    @Test
    fun `empty storage bag skipped without assemble`() {
        val state = stateWithDisciple(
            id = 1, storageBagItems = emptyList(), cultivation = 0.0
        )
        val id = 1
        val cultBefore = state.discipleTables.cultivations[id]

        PillsRealtime.process(state, pillManager)

        // 无丹药弟子 — 状态不变
        assertTrue(state.discipleTables.storageBagItems.getOrNull(id)?.isEmpty() ?: true)
        assertEquals(cultBefore, state.discipleTables.cultivations[id], 0.01)
    }

    @Test
    fun `storage bag with non-pill items only skipped`() {
        val equipmentOnly = listOf(
            StorageBagItem(
                itemId = "eq_1", itemType = "equipment_stack",
                name = "铁剑", rarity = 1
            )
        )
        val state = stateWithDisciple(id = 1, storageBagItems = equipmentOnly)

        PillsRealtime.process(state, pillManager)

        val items = state.discipleTables.storageBagItems.getOrNull(1) ?: emptyList()
        assertEquals(1, items.size)
        assertEquals("铁剑", items[0].name)
    }

    // ── 仅有突破丹 → 指纹跳过 ─────────────────────────────────────

    @Test
    fun `storage bag with only breakthrough pills skipped at fingerprint`() {
        val state = stateWithDisciple(
            id = 1,
            storageBagItems = listOf(breakthroughPill()),
            cultivation = 0.0
        )
        val cultBefore = state.discipleTables.cultivations[1]

        PillsRealtime.process(state, pillManager)

        // 突破丹仍在储物袋（指纹检测直接跳过，未 assemble）
        val items = state.discipleTables.storageBagItems.getOrNull(1) ?: emptyList()
        assertEquals("仅有突破丹时指纹跳过", 1, items.size)
        assertEquals("突破丹", items[0].name)
        assertEquals(cultBefore, state.discipleTables.cultivations[1], 0.01)
    }

    // ── 突破丹不消费 ──────────────────────────────────────────────

    @Test
    fun `breakthrough pill NOT consumed by realtime auto pills`() {
        val state = stateWithDisciple(
            id = 1, storageBagItems = listOf(breakthroughPill(targetRealm = 9))
        )

        PillsRealtime.process(state, pillManager)

        val items = state.discipleTables.storageBagItems.getOrNull(1) ?: emptyList()
        assertEquals("突破丹应保留给突破检测", 1, items.size)
        assertEquals("突破丹", items[0].name)
    }

    // ── 修为丹消费 ────────────────────────────────────────────────

    @Test
    fun `cultivation pill consumed and cultivation increases`() {
        val state = stateWithDisciple(
            id = 1,
            storageBagItems = listOf(cultivationPill(cultivationAdd = 30)),
            cultivation = 5.0
        )

        PillsRealtime.process(state, pillManager)

        // 丹药已消费
        val items = state.discipleTables.storageBagItems.getOrNull(1) ?: emptyList()
        assertTrue("修为丹应被消费", items.isEmpty())

        // 修为值增加（不超过 maxCultivation 上限）
        val newCult = state.discipleTables.cultivations[1]
        assertTrue("修为应增加（5 + 30 = 35, actual = $newCult）", newCult > 5.0)
    }

    // ── 多丹药排序 ────────────────────────────────────────────────

    @Test
    fun `multiple pills consumed in priority order`() {
        val pills = listOf(
            cultivationPill(itemId = "c1", cultivationAdd = 10, rarity = 2),
            breakthroughPill(),  // 不会被消费
            cultivationPill(itemId = "c2", cultivationAdd = 20, rarity = 5)
        )
        val state = stateWithDisciple(
            id = 1, storageBagItems = pills, cultivation = 0.0
        )

        PillsRealtime.process(state, pillManager)

        val newCult = state.discipleTables.cultivations[1]
        // 两种修为丹均被消费，修为增加
        assertTrue("修为应增加（actual = $newCult）", newCult > 0.0)

        // 突破丹保留
        val items = state.discipleTables.storageBagItems.getOrNull(1) ?: emptyList()
        assertEquals("仅剩突破丹", 1, items.size)
        assertEquals("突破丹", items[0].name)
    }

    private fun permanentAttrPill(
        itemId: String = "pill_perm_1",
        intelligenceAdd: Int = 5,
        tier: Int = 1,
        rarity: Int = 4
    ): StorageBagItem = StorageBagItem(
        itemId = itemId, itemType = "pill", name = "智慧丹",
        rarity = rarity, quantity = 1,
        effect = ItemEffect(
            pillType = "", tier = tier, intelligenceAdd = intelligenceAdd
        )
    )

    // ── 已服用一次性丹药 → 指纹跳过 ───────────────────────────────

    @Test
    fun `already used permanent pill skipped at fingerprint`() {
        val state = stateWithDisciple(
            id = 1,
            storageBagItems = listOf(permanentAttrPill(intelligenceAdd = 5, tier = 1)),
            cultivation = 0.0
        )
        // 标记该弟子已服用过同 tier+field 的属性丹
        val usedKeys = DisciplePillManager.buildUsedKeys(
            ItemEffect(tier = 1, intelligenceAdd = 1), tier = 1
        )
        state.discipleTables.usedPermanentPillKeys[1] = usedKeys

        PillsRealtime.process(state, pillManager)

        // 丹药未消费（指纹已排除）
        val items = state.discipleTables.storageBagItems.getOrNull(1) ?: emptyList()
        assertEquals("已服用的永久属性丹应被指纹跳过", 1, items.size)
    }

    // ── 治疗丹 ────────────────────────────────────────────────────

    @Test
    fun `heal pill consumed when injured`() {
        // C1（2026-08-31）：受伤（currentHp < maxHp）时自动服用治疗丹
        val state = stateWithDisciple(
            id = 1,
            storageBagItems = listOf(healPill(healPercent = 30.0)),
            cultivation = 0.0
        )
        val id = 1
        state.discipleTables.currentHps[id] = 50   // 受伤（maxHp > 50）

        PillsRealtime.process(state, pillManager)

        // 丹药被消费且 HP 恢复
        val items = state.discipleTables.storageBagItems.getOrNull(id) ?: emptyList()
        assertTrue("治疗丹应被消费", items.isEmpty())
        assertTrue("HP 应恢复（50 → 增加）", state.discipleTables.currentHps[id] > 50)
    }

    @Test
    fun `heal pill skipped at full hp`() {
        // C1（2026-08-31）：满血（-1 哨兵）不自动吃治疗丹，保留袋内
        val state = stateWithDisciple(
            id = 1,
            storageBagItems = listOf(healPill(healPercent = 30.0)),
            cultivation = 0.0
        )
        val id = 1
        state.discipleTables.currentHps[id] = -1   // 满血哨兵

        PillsRealtime.process(state, pillManager)

        val items = state.discipleTables.storageBagItems.getOrNull(id) ?: emptyList()
        assertEquals("满血时治疗丹应保留", 1, items.size)
    }

    // ── 修炼速度丹：单倍写回 pillEffects（2026-08 修复） ─────────────

    @Test
    fun `speed pill writes only pillEffects not legacy cultivationSpeedBonus`() {
        // 修复回归：修炼速度丹应用后只写入 pillEffects 体系，
        // 旧 cultivationSpeedBonus 组件列必须清零（防双写双倍生效）
        val speedPill = StorageBagItem(
            itemId = "pill_speed_1", itemType = "pill", name = "修炼速度丹",
            rarity = 3, quantity = 1,
            effect = ItemEffect(
                pillType = "cultivationSpeed",
                cultivationSpeedPercent = 0.5,
                duration = 9
            )
        )
        val state = stateWithDisciple(id = 1, storageBagItems = listOf(speedPill))
        // 预置旧字段残留（模拟双写时代旧档），服用后应被清零
        state.discipleTables.cultivationSpeedBonuses[1] = 0.5
        state.discipleTables.cultivationSpeedDurations[1] = 9

        PillsRealtime.process(state, pillManager)

        val tables = state.discipleTables
        assertEquals("旧 cultivationSpeedBonus 字段应清零", 0.0, tables.cultivationSpeedBonuses[1], 0.001)
        assertEquals("旧 cultivationSpeedDuration 字段应清零", 0, tables.cultivationSpeedDurations[1])
        assertEquals("丹药修炼速度加成应写入 pillEffects 体系", 0.5, tables.pillCultivationSpeedBonuses[1], 0.001)
        assertEquals("丹药持续时间应为9旬", 9, tables.pillEffectDurations[1])
        assertTrue("修炼速度丹应被消费", tables.storageBagItems.getOrNull(1).isNullOrEmpty())
    }
}

/**
 * 测试辅助：直接调用 CultivationCore.processRealtimeAutoPills 的纯逻辑。
 * 避免实例化含 7 个依赖的 CultivationCore。
 */
private object PillsRealtime {

    fun process(
        state: MutableGameState,
        pillManager: DisciplePillManager
    ) {
        val tables = state.discipleTables
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue

            // 指纹检测：排除突破丹 + 已服用过的一次性丹药
            if (!hasAutoUsablePills(tables = tables, id = id)) continue

            val disciple = tables.assemble(id)
            val result = pillManager.processAutoUsePills(disciple)
            if (result.disciple == disciple) continue

            // 丹药效果写回弟子表
            writeBackPillEffects(
                tables = tables,
                id = id,
                result = result
            )
        }
    }

    /** 指纹检测（process 拆分）：排除突破丹 + 已服用过的一次性丹药 */
    private fun hasAutoUsablePills(tables: DiscipleTables, id: Int): Boolean {
        val items = tables.storageBagItems.getOrNull(id) ?: return false
        val usedPermanentKeys =
            tables.usedPermanentPillKeys.getOrNull(id).orEmpty()
        val usedExtendLifeTypes =
            tables.usedExtendLifePillTypes.getOrNull(id).orEmpty()
        return items.any { item ->
            if (item.itemType != "pill") return@any false
            val effect = item.effect ?: return@any false
            when (DisciplePillManager.classify(effect)) {
                PillRule.BREAKTHROUGH -> return@any false
                PillRule.PERMANENT_BASE_ATTR -> {
                    val keys = DisciplePillManager.buildUsedKeys(
                        effect, effect.tier
                    )
                    keys.none { it in usedPermanentKeys }
                }
                PillRule.PERMANENT_LIFE ->
                    effect.pillType !in usedExtendLifeTypes
                else -> true
            }
        }
    }

    /** 丹药效果写回（process 拆分）：弟子表全字段回写 */
    private fun writeBackPillEffects(
        tables: DiscipleTables,
        id: Int,
        result: DisciplePillManager.PillUseResult
    ) {
        val d = result.disciple
        tables.storageBagItems[id] = d.equipment.storageBagItems
        tables.cultivations[id] = d.cultivation
        tables.manualMasteries[id] = d.manualMasteries
        // 2026-08 修复：镜像 AutoPillService.writePillResultToTables——
        // 旧 cultivationSpeedBonus 组件列清零（丹药加成收敛于 pillEffects 体系）
        tables.cultivationSpeedBonuses[id] = 0.0
        tables.cultivationSpeedDurations[id] = 0
        tables.lifespans[id] = d.lifespan
        tables.intelligences[id] = d.skills.intelligence
        tables.charms[id] = d.skills.charm
        tables.loyalties[id] = d.skills.loyalty
        tables.comprehensions[id] = d.skills.comprehension
        tables.artifactRefinings[id] = d.skills.artifactRefining
        tables.pillRefinings[id] = d.skills.pillRefining
        tables.spiritPlantings[id] = d.skills.spiritPlanting
        tables.teachings[id] = d.skills.teaching
        tables.moralities[id] = d.skills.morality
        tables.minings[id] = d.skills.mining
        tables.pillPhysicalAttackBonuses[id] = d.pillEffects.pillPhysicalAttackBonus
        tables.pillMagicAttackBonuses[id] = d.pillEffects.pillMagicAttackBonus
        tables.pillPhysicalDefenseBonuses[id] = d.pillEffects.pillPhysicalDefenseBonus
        tables.pillMagicDefenseBonuses[id] = d.pillEffects.pillMagicDefenseBonus
        tables.pillHpBonuses[id] = d.pillEffects.pillHpBonus
        tables.pillMpBonuses[id] = d.pillEffects.pillMpBonus
        tables.pillSpeedBonuses[id] = d.pillEffects.pillSpeedBonus
        tables.pillCritRateBonuses[id] = d.pillEffects.pillCritRateBonus
        tables.pillCritEffectBonuses[id] = d.pillEffects.pillCritEffectBonus
        tables.pillCultivationSpeedBonuses[id] = d.pillEffects.pillCultivationSpeedBonus
        tables.pillSkillExpSpeedBonuses[id] = d.pillEffects.pillSkillExpSpeedBonus
        tables.pillNurtureSpeedBonuses[id] = d.pillEffects.pillNurtureSpeedBonus
        tables.pillEffectDurations[id] = d.pillEffects.pillEffectDuration
        tables.activePillTypes[id] = d.pillEffects.activePillTypes
        tables.usedPermanentPillKeys[id] = d.usage.usedPermanentPillKeys
        tables.usedExtendLifePillTypes[id] = d.usage.usedExtendLifePillTypes
        tables.currentHps[id] = d.combat.currentHp
        tables.currentMps[id] = d.combat.currentMp
    }
}
