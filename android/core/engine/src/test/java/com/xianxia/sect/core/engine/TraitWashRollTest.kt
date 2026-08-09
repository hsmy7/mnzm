package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.asKotlinRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洗炼天赋/体质/词条纯随机函数测试（不落盘，纯 JVM 可跑）。
 *
 * 单槽语义（2026-08-09 需求变更）：一次只洗炼一个目标特质。覆盖：保底路径目标槽必出上品、
 * 固定种子确定性、保底计数语义、排除模板过滤（新条目不与保留槽位 template 冲突）、
 * 池全排除 → 无候选（预检/抽取双兜底）、以及守卫——三类型 3 阶正向池非空（保底不变量前提）、
 * 单条抽取品阶分布有效（两种品阶情形均出现）。
 */
class TraitWashRollTest {

    private val washTypes = listOf(TraitWashType.TALENT, TraitWashType.PHYSIQUE, TraitWashType.AFFIX)

    /** 三类型 3 阶正向池的全部 template（与引擎保底池口径一致） */
    private fun TraitWashType.topPoolTemplates(): Set<String> = when (this) {
        TraitWashType.TALENT -> TalentDatabase.getPositiveByRarity(GameConfig.TraitWash.TOP_RARITY)
            .map { TalentDatabase.getTalentDataById(it.id)?.template ?: it.id }.toSet()
        TraitWashType.PHYSIQUE -> PhysiqueDatabase.getByRarity(GameConfig.TraitWash.TOP_RARITY)
            .filter { !it.isNegative }
            .map { PhysiqueDatabase.getPhysiqueDataById(it.id)?.template ?: it.id }.toSet()
        TraitWashType.AFFIX -> AffixDatabase.getByRarity(GameConfig.TraitWash.TOP_RARITY)
            .filter { !it.isNegative }
            .map { AffixDatabase.getAffixDataById(it.id)?.template ?: it.id }.toSet()
    }

    /** 三类型全量候选池的 template（用于"全排除 → 无候选"用例） */
    private fun TraitWashType.allPoolTemplates(): Set<String> = when (this) {
        // 候选池 = 正向 + 负面（退役天赋已被正向查询过滤，与 washCandidatePool 口径一致）
        TraitWashType.TALENT -> (TalentDatabase.getPositiveTalents() + TalentDatabase.getNegativeTalents())
            .map { TalentDatabase.getTalentDataById(it.id)?.template ?: it.id }.toSet()
        TraitWashType.PHYSIQUE -> (PhysiqueDatabase.getPositivePhysiques() + PhysiqueDatabase.getNegativePhysiques())
            .map { PhysiqueDatabase.getPhysiqueDataById(it.id)?.template ?: it.id }.toSet()
        TraitWashType.AFFIX -> (AffixDatabase.getPositiveAffixes() + AffixDatabase.getNegativeAffixes())
            .map { AffixDatabase.getAffixDataById(it.id)?.template ?: it.id }.toSet()
    }

    /** 抽取结果的 template（resolve 解析 id） */
    private fun TraitWashType.templateOf(id: String): String =
        resolve(listOf(id)).firstOrNull()?.template ?: id

    @Test
    fun `rollSingleTraitWash - 保底计数达阈值时任意种子目标必出上品且计数归零`() {
        for (type in washTypes) {
            repeat(50) { seed ->
                val roll = rollSingleTraitWash(
                    DeterministicRng.fromSeed(1000L + seed),
                    type,
                    excludedTemplates = emptySet(),
                    pityCount = GameConfig.TraitWash.WASH_PITY_THRESHOLD
                )
                val resolved = type.resolve(listOfNotNull(roll.newId))

                assertEquals(
                    "保底目标槽必须是 3 阶 (${type.displayName}, seed=$seed): $roll",
                    GameConfig.TraitWash.TOP_RARITY, resolved.first().rarity
                )
                assertEquals("保底后计数应归零 (${type.displayName})", 0, roll.newPityCount)
            }
        }
    }

    @Test
    fun `rollSingleTraitWash - 固定种子相同输入结果确定`() {
        for (type in washTypes) {
            repeat(20) { i ->
                val rng1 = DeterministicRng.fromSeed(20260809L)
                val rng2 = DeterministicRng.fromSeed(20260809L)
                val pity = i % 3
                val excluded = type.allPoolTemplates().take(i).toSet()

                val roll1 = rollSingleTraitWash(rng1, type, excluded, pity)
                val roll2 = rollSingleTraitWash(rng2, type, excluded, pity)

                assertEquals("同种子同输入必须同产物 (${type.displayName})", roll1, roll2)
            }
        }
    }

    @Test
    fun `rollSingleTraitWash - 保底计数语义：含上品归零、无上品递增`() {
        // 大量种子扫描：两种情形都必须出现，且计数语义与结果严格一致
        for (type in washTypes) {
            var sawHasTop = false
            var sawNoTop = false
            repeat(200) { seed ->
                val roll = rollSingleTraitWash(DeterministicRng.fromSeed(seed.toLong()), type, emptySet(), 0)
                val hasTop = roll.newId?.let { type.resolve(listOf(it)).first().rarity } ==
                    GameConfig.TraitWash.TOP_RARITY

                if (hasTop) {
                    sawHasTop = true
                    assertEquals("含上品时计数必须归零 (${type.displayName}, seed=$seed)", 0, roll.newPityCount)
                } else {
                    sawNoTop = true
                    assertEquals("无上品时计数必须递增 (${type.displayName}, seed=$seed)", 1, roll.newPityCount)
                }
            }
            assertTrue("两种情形都应出现（分布有效性）(${type.displayName})", sawHasTop && sawNoTop)
        }
    }

    @Test
    fun `rollSingleTraitWash - 排除模板不得出现在结果中（与保留槽位不冲突）`() {
        for (type in washTypes) {
            repeat(50) { seed ->
                // 用全部 3 阶正向池模板作排除（覆盖保底池，但普通池仍有大量非 3 阶候选）
                val excluded = type.topPoolTemplates()
                val roll = rollSingleTraitWash(
                    DeterministicRng.fromSeed(seed.toLong() + 30_000),
                    type,
                    excluded,
                    pityCount = 0
                )
                val newTemplate = roll.newId?.let { type.templateOf(it) }
                if (newTemplate != null) {
                    assertTrue(
                        "新条目不得使用被排除的 template (${type.displayName}, seed=$seed): " +
                            "$newTemplate in $excluded",
                        newTemplate !in excluded
                    )
                }
            }
        }
    }

    @Test
    fun `rollSingleTraitWash - 保底路径排除模板覆盖全保底池时返回null（放弃本次保底）`() {
        // 对抗性审查 2026-08-09 边界狂魔：usedTemplates 过滤后无候选时不得回退全池
        // （会选出与保留槽位 template 重复的条目 → confirm 校验拒绝 → 白洗玉符死胡同），
        // 也不得对空列表 .random() 抛异常（deduct 后事务内异常 = 玉符永久损失）——返回 null
        for (type in washTypes) {
            val nullResult = rollSingleTraitWash(
                DeterministicRng.fromSeed(1L),
                type,
                excludedTemplates = type.topPoolTemplates(),
                pityCount = GameConfig.TraitWash.WASH_PITY_THRESHOLD
            )
            assertNull(
                "保底池模板全被占用时必须返回 null，不得回退全池 (${type.displayName})",
                nullResult.newId
            )
        }
    }

    // ── Database 单条抽取（rollSingleXxx / hasXxxCandidates） ──

    @Test
    fun `Database单条抽取 - 普通路径恒产出可解析条目且不确定抽取不干扰候选预检`() {
        for (type in washTypes) {
            repeat(50) { seed ->
                val random = DeterministicRng.fromSeed(seed.toLong() + 50_000).asKotlinRandom()
                val entry = type.rollSingle(random, excludedTemplates = emptySet())
                assertTrue("单条抽取必须产出条目 (${type.displayName}, seed=$seed)", entry != null)
                assertTrue(
                    "单条抽取条目必须可解析 (${type.displayName}, seed=$seed)",
                    type.resolve(listOf(entry!!.id)).size == 1
                )
            }
        }
    }

    @Test
    fun `Database单条抽取 - 候选池全被排除时返回null且预检为false`() {
        for (type in washTypes) {
            val excluded = type.allPoolTemplates()

            assertFalse("全池被排除时预检必须为 false (${type.displayName})", type.hasRollCandidate(excluded))
            assertNull(
                "全池被排除时抽取必须返回 null (${type.displayName})",
                type.rollSingle(DeterministicRng.fromSeed(1L).asKotlinRandom(), excluded)
            )
        }
    }

    @Test
    fun `Database单条抽取 - 固定种子结果确定`() {
        for (type in washTypes) {
            val rng1 = DeterministicRng.fromSeed(20260809L).asKotlinRandom()
            val rng2 = DeterministicRng.fromSeed(20260809L).asKotlinRandom()
            val excluded = setOf("not-a-real-template")

            assertEquals(
                "同种子同输入必须同产物 (${type.displayName})",
                type.rollSingle(rng1, excluded),
                type.rollSingle(rng2, excluded)
            )
        }
    }

    // ── 守卫：保底不变量前提 ──

    @Test
    fun `guard - 三类型3阶正向池均非空（保底不变量前提）`() {
        // 保底依赖"3 阶正向池非空"，池为空时保底静默失效——必须显式报警
        assertTrue("天赋 3 阶正向池为空，保底不变量被破坏",
            TalentDatabase.getByRarity(GameConfig.TraitWash.TOP_RARITY).any { !it.isNegative })
        assertTrue("体质 3 阶正向池为空，保底不变量被破坏",
            PhysiqueDatabase.getByRarity(GameConfig.TraitWash.TOP_RARITY).any { !it.isNegative })
        assertTrue("词条 3 阶正向池为空，保底不变量被破坏",
            AffixDatabase.getByRarity(GameConfig.TraitWash.TOP_RARITY).any { !it.isNegative })
    }

    @Test
    fun `pickTopRarity - usedTemplates 覆盖全池时返回 null（放弃替换而非回退全池）`() {
        // 对抗性审查 2026-08-09 边界狂魔：原实现 usedTemplates 过滤后无候选时回退全池，
        // 会选出与产物其余槽位 template 重复的条目 → confirm 校验拒绝 → 玩家白洗 1 玉符死胡同；
        // 且池空时对空列表 .random() 抛异常（deduct 后事务内异常 = 玉符永久损失）。
        // 现语义：无候选返回 null，调用方放弃替换（保底尽力而为）。
        for (type in washTypes) {
            val nullResult = type.pickTopRarity(
                DeterministicRng.fromSeed(1L).asKotlinRandom(),
                usedTemplates = type.topPoolTemplates()
            )
            assertNull(
                "池模板全被占用时必须返回 null，不得回退全池（confirm 校验死胡同）(${type.displayName})",
                nullResult
            )
        }
    }

    @Test
    fun `guard - 天赋保底池不含退役天赋类型`() {
        // 对抗性审查 2026-08-09 数据篡改者：原保底池 getByRarity(3) 未过滤 DEPRECATED，
        // r6_manual_slot/r6_win_growth（rarity=3 退役超模天赋）可经保底路径重新流入——
        // 普通生成池已过滤（generateTalentsForDisciple），保底池必须与之一致。
        // 锚点：TalentType 枚举 + 与 TalentDatabase.DEPRECATED_TALENT_TYPES 同口径的集合，
        // 若退役集合变更本守卫失败，提示同步（守卫三要素：枚举驱动 + 显式集合 + 操作指引）。
        val deprecatedTypes = setOf(
            TalentDatabase.TalentType.CULT_SPEED,
            TalentDatabase.TalentType.BREAK_CHANCE,
            TalentDatabase.TalentType.LIFESPAN,
            TalentDatabase.TalentType.MANUAL_SLOT,
            TalentDatabase.TalentType.WIN_GROWTH
        )
        val poolData = TalentDatabase.getPositiveByRarity(GameConfig.TraitWash.TOP_RARITY)
            .mapNotNull { TalentDatabase.getTalentDataById(it.id) }
        assertTrue("天赋 3 阶正向池为空，保底不变量被破坏", poolData.isNotEmpty())
        val leaked = poolData.filter { it.type in deprecatedTypes }
        assertTrue(
            "保底池混入退役天赋: ${leaked.joinToString { "${it.name}(${it.type})" }}——" +
                "保底池必须过滤 DEPRECATED_TALENT_TYPES，与生成池口径一致（若退役集合变更，同步此处守卫）",
            leaked.isEmpty()
        )
    }

    @Test
    fun `guard - 洗炼单条候选池不含退役天赋类型（普通路径与保底口径一致）`() {
        // 单条抽取（rollSingleTalent）的候选池必须与生成池同口径过滤 DEPRECATED——
        // 否则退役超模条目可经普通洗炼路径重新流入
        for (type in listOf(TraitWashType.TALENT)) {
            repeat(200) { seed ->
                val roll = rollSingleTraitWash(DeterministicRng.fromSeed(seed.toLong() + 100_000), type, emptySet(), 0)
                roll.newId?.let { id ->
                    val data = TalentDatabase.getTalentDataById(id)
                    if (data != null) {
                        assertFalse(
                            "单条洗炼不得产出退役天赋 (seed=$seed): ${data.name}(${data.type})",
                            data.type in deprecatedTalentTypes()
                        )
                    }
                }
            }
        }
    }

    private fun deprecatedTalentTypes(): Set<TalentDatabase.TalentType> = setOf(
        TalentDatabase.TalentType.CULT_SPEED,
        TalentDatabase.TalentType.BREAK_CHANCE,
        TalentDatabase.TalentType.LIFESPAN,
        TalentDatabase.TalentType.MANUAL_SLOT,
        TalentDatabase.TalentType.WIN_GROWTH
    )
}
