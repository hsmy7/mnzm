package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.asKotlinRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洗炼天赋/体质/词条纯随机函数测试（不落盘，纯 JVM 可跑）。
 *
 * 覆盖：保底路径必出上品、固定种子确定性、保底计数语义、产物合法性
 * （全部可解析/template 无重复/数量不超上限）、以及两个守卫——
 * 三类型 3 阶正向池非空（保底不变量前提）、产物数量永不超配置上限（防配置漂移）。
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

    @Test
    fun `rollTraitWash - 保底计数达阈值时任意种子必出至少1个上品且计数归零`() {
        for (type in washTypes) {
            repeat(50) { seed ->
                val roll = rollTraitWash(
                    DeterministicRng.fromSeed(1000L + seed),
                    type,
                    GameConfig.TraitWash.WASH_PITY_THRESHOLD
                )
                val resolved = type.resolve(roll.ids)

                assertTrue(
                    "保底结果必须含 3 阶 (${type.displayName}, seed=$seed): $roll",
                    resolved.any { it.rarity == GameConfig.TraitWash.TOP_RARITY }
                )
                assertEquals("保底后计数应归零 (${type.displayName})", 0, roll.newPityCount)
            }
        }
    }

    @Test
    fun `rollTraitWash - 固定种子相同输入结果确定`() {
        for (type in washTypes) {
            repeat(20) { i ->
                val rng1 = DeterministicRng.fromSeed(20260809L)
                val rng2 = DeterministicRng.fromSeed(20260809L)
                val pity = i % 3

                val roll1 = rollTraitWash(rng1, type, pity)
                val roll2 = rollTraitWash(rng2, type, pity)

                assertEquals("同种子同输入必须同产物 (${type.displayName})", roll1, roll2)
            }
        }
    }

    @Test
    fun `rollTraitWash - 保底计数语义：含上品归零、无上品递增`() {
        // 大量种子扫描：两种情形都必须出现，且计数语义与结果严格一致
        for (type in washTypes) {
            var sawHasTop = false
            var sawNoTop = false
            repeat(200) { seed ->
                val roll = rollTraitWash(DeterministicRng.fromSeed(seed.toLong()), type, 0)
                val hasTop = type.resolve(roll.ids).any { it.rarity == GameConfig.TraitWash.TOP_RARITY }

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
    fun `rollTraitWash - 产物合法：全部可解析且template无重复且不超上限`() {
        for (type in washTypes) {
            repeat(200) { seed ->
                val roll = rollTraitWash(DeterministicRng.fromSeed(seed.toLong() + 5000), type, seed % 3)
                val resolved = type.resolve(roll.ids)

                assertEquals("产物 id 必须全部可解析 (${type.displayName}, seed=$seed)",
                    roll.ids.size, resolved.size)
                assertEquals("产物 template 不得重复 (${type.displayName}, seed=$seed)",
                    resolved.size, resolved.map { it.template }.distinct().size)
                assertTrue(
                    "产物数量不得超上限 (${type.displayName}, seed=$seed): ${roll.ids.size}",
                    roll.ids.size <= GameConfig.TraitWash.MAX_TRAIT_COUNT
                )
            }
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
    fun `guard - 产物数量分布永不超配置上限（防配置漂移）`() {
        // MAX_TRAIT_COUNT 必须 ≥ 生成分布的实际最大数量；若生成分布上限上调而配置未同步，
        // 此处大样本扫描会捕获（WeightedRoll 分布为 domain internal，引擎测试无法直接引用，
        // 用扫描代替——见 Guard 说明）
        for (type in washTypes) {
            var maxSeen = 0
            repeat(500) { seed ->
                val roll = rollTraitWash(DeterministicRng.fromSeed(seed.toLong() + 100_000), type, 0)
                maxSeen = maxOf(maxSeen, roll.ids.size)
                assertTrue(
                    "产物数量超上限 (${type.displayName}, seed=$seed): " +
                        "${roll.ids.size} > ${GameConfig.TraitWash.MAX_TRAIT_COUNT}",
                    roll.ids.size <= GameConfig.TraitWash.MAX_TRAIT_COUNT
                )
            }
            // 统计上 500 种子应覆盖到 5 个（1% 概率），若从未出现说明分布已漂移
            assertEquals("生成分布应能达到上限值（分布漂移报警）(${type.displayName})",
                GameConfig.TraitWash.MAX_TRAIT_COUNT, maxSeen)
        }
    }
}
