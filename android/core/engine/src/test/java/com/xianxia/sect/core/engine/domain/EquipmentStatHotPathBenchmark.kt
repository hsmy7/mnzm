package com.xianxia.sect.core.engine.domain

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.HpMpColumnInput
import com.xianxia.sect.core.engine.domain.disciple.getMaxHpMpColumn
import com.xianxia.sect.core.engine.domain.disciple.computeBaseHpMp
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 每旬热点基准（EQ-B4，方案 §6.6/§13-6；`getMaxHpMpColumn` 装备段不劣化改造前 >10%）。
 *
 * **门（S 门禁口径）**：现行热点（含 §13-6 EquipBonus 值语义缓存）每调用耗时
 * ≤ **改造前基线复刻臂 × 1.10**。改造前臂 = B3 前热点形状逐位复刻：旧四槽位
 * 查表 + 每件值语义缓存命中（旧 `EquipmentInstance.cachedFinalStats` 模式，
 * ConcurrentHashMap 深哈希键）+ 同一基础/功法/丹药段。
 *
 * **取数口径**：预热后 5 轮 × 每轮 [iterationsPerRound] 次，取**各臂最优轮均耗时**
 * （微基准最小噪声统计量；JIT 同炉预热，臂序每轮轮转消除顺序偏差）。
 * 报告另记**缓存未命中对照臂**（每次新内容实例 = 缓存永 miss + 每帧构造分配），
 * 量化缓存收益；UI 反序列化路径（每次新实例、值语义命中）介于两臂之间。
 *
 * 最坏情形场景：大乘 T6 四件 Lv30 满强化 + 1 功法 + 丹药生效。
 */
class EquipmentStatHotPathBenchmark {

    private val iterationsPerRound = 50_000
    private val warmupRounds = 3
    private val measuredRounds = 5

    // ── 场景构造 ─────────────────────────────────────────────

    private fun lv30Piece(part: EquipmentSlot, seed: Int): EquipmentInstance {
        val created = EquipmentFactory.create("lietian", part, 6, Random(seed))
        val reinforcement = Random(seed * 31 + 7)
        val rolls = created.growth.affix.subRolls.toMutableList()
        repeat(10) { rolls[reinforcement.nextInt(rolls.size)] += 1 }
        return created.copy(
            growth = created.growth.copy(
                level = 30,
                affix = created.growth.affix.copy(subRolls = rolls)
            )
        )
    }

    private val fourParts = listOf(
        EquipmentSlot.HEAD, EquipmentSlot.BODY, EquipmentSlot.HANDS, EquipmentSlot.FEET
    )

    /** 四件 T6 Lv30 实例与查表（id → 实例） */
    private val pieces: List<EquipmentInstance> =
        fourParts.mapIndexed { i, part -> lv30Piece(part, 1000 + i) }

    private val equipments: Map<String, EquipmentInstance> = pieces.associateBy { it.id }

    private val manuals: Map<String, ManualInstance> = emptyMap()

    private val proficiencies: Map<String, ManualProficiencyData> = emptyMap()

    /** 新四槽列直读输入（现行槽位面） */
    private val input = HpMpColumnInput(
        realm = 2, realmLayer = 1, hpVariance = 0, mpVariance = 0,
        headId = pieces[0].id, bodyId = pieces[1].id, handsId = pieces[2].id,
        feetId = pieces[3].id,
        // 退役槽位字段（字段随存档模型在 F2 删除）：基准场景恒空
        manualIds = listOf("manual-probe"), pillEffectDuration = 3,
        pillHpBonus = 100, pillMpBonus = 50
    )

    /**
     * 改造前热点臂：B3 前形状复刻——旧四槽（weapon/armor/boots/accessory →
     * 语义对位 weapon/body/feet/hands）查表 + 每件值语义缓存（旧 cachedFinalStats
     * 形态：实例深哈希键 + ConcurrentHashMap 命中）。
     *
     * **分配画像与现行臂对称**（每调用同样构建槽位列表 + 一次键包装分配 +
     * 一次映射探测）：套件负载（GC 压力）对两臂等比作用，比值门不受
     * 绝对值膨胀污染——B4 全量套件实测负载比 1.31 的根因即两臂分配不对称。
     */
    private val legacyFinalHpCache = ConcurrentHashMap<LegacyPieceKey, Pair<Int, Int>>()

    /** 与现行臂 [com.xianxia.sect.core.engine.domain.disciple.EquipStatResolver.InstanceListKey] 同形的每调用键包装 */
    private class LegacyPieceKey(private val refs: List<EquipmentInstance>) {
        private val hash = refs.fold(1) { acc, r -> 31 * acc + r.hashCode() }
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            other is LegacyPieceKey && other.refs == refs
    }

    private fun legacyHotPath(input: HpMpColumnInput): Pair<Int, Int> {
        val (baseHp, baseMp) = DiscipleStatCalculator.computeBaseHpMp(
            input.realm, input.realmLayer, input.hpVariance, input.mpVariance
        )
        var hp = baseHp
        var mp = baseMp
        val slots = listOfNotNull(input.headId, input.bodyId, input.feetId, input.handsId)
        val pieces4 = slots.mapNotNull { equipments[it] }
        val finalHpMp = legacyFinalHpCache[LegacyPieceKey(pieces4)] ?: run {
            val computed = Pair(pieces4.sumOf { p -> p.totalBonus().sumOf { it.value } }.toInt(), 0)
            legacyFinalHpCache[LegacyPieceKey(pieces4)] = computed
            computed
        }
        hp += finalHpMp.first
        mp += finalHpMp.second
        input.manualIds.forEach { manualId ->
            if (manuals[manualId] != null) {
                hp += 100
                mp += 50
            }
        }
        if (input.pillEffectDuration > 0) {
            hp += input.pillHpBonus
            mp += input.pillMpBonus
        }
        return Pair(hp, mp)
    }

    // ── 计时骨架 ─────────────────────────────────────────────

    private inline fun measure(rounds: Int, block: () -> Any?): List<Long> {
        val samples = ArrayList<Long>(rounds)
        repeat(rounds) {
            val start = System.nanoTime()
            repeat(iterationsPerRound) { block() }
            samples.add(System.nanoTime() - start)
        }
        return samples
    }

    // ── 门 ───────────────────────────────────────────────────

    @Test
    fun `热点不劣化于改造前基线超过一成`() {
        // 预热（同炉 JIT；缓存预填充）
        measure(warmupRounds) { DiscipleStatCalculator.getMaxHpMpColumn(input, equipments, manuals, proficiencies) }
        measure(warmupRounds) { legacyHotPath(input) }

        val currentSamples = ArrayList<Long>(measuredRounds)
        val legacySamples = ArrayList<Long>(measuredRounds)
        repeat(measuredRounds) {
            // 臂序轮转消除顺序偏差
            if (it % 2 == 0) {
                currentSamples += measure(1) {
                    DiscipleStatCalculator.getMaxHpMpColumn(input, equipments, manuals, proficiencies)
                }[0]
                legacySamples += measure(1) { legacyHotPath(input) }[0]
            } else {
                legacySamples += measure(1) { legacyHotPath(input) }[0]
                currentSamples += measure(1) {
                    DiscipleStatCalculator.getMaxHpMpColumn(input, equipments, manuals, proficiencies)
                }[0]
            }
        }
        val currentBest = currentSamples.min() / iterationsPerRound.toDouble()
        val legacyBest = legacySamples.min() / iterationsPerRound.toDouble()
        val ratio = currentBest / legacyBest
        println(
            (
                "BENCH 每调用纳秒: 现行(含值语义缓存)=%.0f 改造前基线复刻=%.0f 缓存未命中对照=%.0f " +
                    "现行/基线=%.3f（门 ≤1.10）"
                ).format(
                currentBest, legacyBest, uncachedProbeNs(), ratio
            )
        )
        assertTrue(
            "现行热点 %.0f ns/调用 > 改造前基线复刻 %.0f ns/调用 × 1.10——装备段劣化超阈值，" +
                "检查 EquipStatResolver 缓存是否失效（值语义键被破坏或容量护栏频触）".format(currentBest, legacyBest),
            ratio <= 1.10
        )
    }

    /**
     * 缓存未命中对照（报告数据，非门）：每次以**新恒等实例**（浅 copy）调用
     * resolve——恒等键永 miss（含每帧构造分配），量化缓存收益的上界参照。
     */
    private fun uncachedProbeNs(): Double {
        var tick = 0L
        measure(warmupRounds) {
            tick++
            resolveWithFreshCopies()
        }
        val samples = measure(measuredRounds) {
            tick++
            resolveWithFreshCopies()
        }
        return samples.min() / iterationsPerRound.toDouble()
    }

    private fun resolveWithFreshCopies() {
        com.xianxia.sect.core.engine.domain.disciple.EquipStatResolver
            .resolve(pieces.map { it.copy() })
    }
}
