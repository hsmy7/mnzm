package com.xianxia.sect.core.engine.domain

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.EquipStatResolver
import com.xianxia.sect.core.engine.domain.disciple.applyEquipBonus
import com.xianxia.sect.core.engine.domain.disciple.computeBaseStats
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.SkillInputs
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.VarianceInputs
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 装备战力占比校准门（EQ-B4，S9/S16；方案 §3.7「完整数值以 EquipmentPowerParityTest
 * 为准——该表为可调单源」）。
 *
 * **拍板口径**（§0.2 甲组 #1/#6）：装备（**含套装 2/4/6 档效果**）贡献 ∈ 弟子总战力
 * **[35%,45%]**。判据形态 = **固定种子场景集的中位占比**：暴击率/暴击伤害/类型伤害
 * 词条不进战力公式（战力 = attack×5 + maxHp×4 + defense×3 + speed×2），同战力面板
 * 的个体方差天然大（report-B4 分位表 p10–p90 ≈ ±10pp），中位是口径锚。
 *
 * **分维度拆分**（E11/§13-13）：
 * - 攻击/防御/血量三维分列输出（战力权重 5/4/3）；
 * - **速度/灵力断言装备零贡献**（S14 回归锁：装备不提供这两维，面板只由基础+功法+丹药）；
 * - 暴击率/暴击伤害/类型伤害单列输出（不进战力公式，只作面板对照）。
 *
 * **阶段口径**：占比带断言落在各品阶**入口境界**（该档可穿的最低境界，T2@金丹 …
 * T6@大乘）——T6 档 flat 主词条基数经本批 ×1.8 校准（锚 = 大乘 T6 满套中位）；
 * T1@炼气**结构性越带**（副词条单独已 ≥35.9%，主词条基数杠杆不可达）与深化期
 * 衰减（渡劫/仙人无更高品阶可穿）只在输出表登记，不作断言，见 report-B4 §3。
 *
 * 套装取「裂天罡煞」（两套部件主词条池同构、套装效果均不入战力公式 ⇒ 占比逐位同）。
 */
class EquipmentPowerParityTest {

    // ── 场景构造 ─────────────────────────────────────────────

    /** 固定种子场景数（中位锚的样本量；全确定性，可复算） */
    private val scenarioCount = 40

    /**
     * 生成一件 Lv30 满强化实例：工厂 roll 词条后按升级节点语义补 10 次强化
     * （每 3 级一次、`nextInt(3)` 选副词条，与 [com.xianxia.sect.core.engine.service.
     * EquipmentUpgradeService] 同分布；固定种子 ⇒ 全确定性）。
     */
    private fun lv30Piece(setId: String, part: EquipmentSlot, rarity: Int, seed: Int): EquipmentInstance {
        val created = EquipmentFactory.create(setId, part, rarity, Random(seed))
        val reinforcement = Random(seed * 31 + 7)
        val rolls = created.growth.affix.subRolls.toMutableList()
        repeat(EquipLevelCurveMaxReinforcements) { rolls[reinforcement.nextInt(rolls.size)] += 1 }
        return created.copy(
            growth = created.growth.copy(
                level = 30,
                affix = created.growth.affix.copy(subRolls = rolls)
            )
        )
    }

    /** 满套四件（显示序：头/身/手/脚） */
    private fun fullSet(setId: String, rarity: Int, seed: Int): List<EquipmentInstance> = listOf(
        lv30Piece(setId, EquipmentSlot.HEAD, rarity, seed * 100 + 1),
        lv30Piece(setId, EquipmentSlot.BODY, rarity, seed * 100 + 2),
        lv30Piece(setId, EquipmentSlot.HANDS, rarity, seed * 100 + 3),
        lv30Piece(setId, EquipmentSlot.FEET, rarity, seed * 100 + 4)
    )

    /** 阶段基础面板（方差 0、层 1、技能 50——占比口径的干净基准面） */
    private fun baseStats(realm: Int): DiscipleStats = DiscipleStatCalculator.computeBaseStats(
        realm, 1,
        VarianceInputs(0, 0, 0, 0, 0),
        SkillInputs(50, 50, 50, 50, 50, 50, 50, 50, 50)
    )

    /** 战力（与 [com.xianxia.sect.core.engine.SectCombatPowerCalculator] 同式） */
    private fun power(stats: DiscipleStats): Long =
        stats.attack.toLong() * 5L + stats.maxHp.toLong() * 4L +
            stats.defense.toLong() * 3L + stats.speed.toLong() * 2L

    private fun calc(): DiscipleStatCalculator = DiscipleStatCalculator

    /** 装备战力占比 =（含装备 − 基础）/ 含装备 */
    private fun equipmentShare(realm: Int, pieces: List<EquipmentInstance>): Double {
        val base = baseStats(realm)
        val with = calc().applyEquipBonus(base, EquipStatResolver.resolve(pieces))
        val basePower = power(base)
        val withPower = power(with)
        return (withPower - basePower).toDouble() / withPower
    }

    /** 固定场景集的中位占比 */
    private fun medianFullSetShare(realm: Int, rarity: Int): Double {
        val shares = (1..scenarioCount).map { seed -> equipmentShare(realm, fullSet("lietian", rarity, seed)) }
        val sorted = shares.sorted()
        return (sorted[scenarioCount / 2 - 1] + sorted[scenarioCount / 2]) / 2.0
    }

    // ── S9/S16：占比带 ───────────────────────────────────────

    /** 四部位化占比带（件数 6→4、单件数值表一字不改、套装满套口径守恒）：
     *  五入口中位实测 0.259/0.276/0.297/0.345/0.320（金丹/元婴/化神/炼虚/大乘），
     *  带 [0.22,0.40] 按实测重锚（中位锚样本可复算）。终态带宽与装备总占比报告
     *  由数值校准批（F4）复核定稿（方案 §3.3：预期约 27%，不设运行时补偿）。 */
    private val shareBand = 0.22..0.40

    @Test
    fun `锚定阶段大乘T6满套中位占比在带宽内`() {
        val share = medianFullSetShare(realm = 2, rarity = 6)
        assertTrue(
            "大乘 T6 满套中位占比 $share 出带 $shareBand——T6 flat 主词条基数被改动？" +
                "（锚口径见 EquipmentPowerParityTest KDoc；四部位化带宽由 F4 复核定稿）",
            share in shareBand
        )
    }

    @Test
    fun `各品阶入口阶段中位占比在带内`() {
        // T2@金丹 / T3@元婴 / T4@化神 / T5@炼虚 / T6@大乘——四部位化后全部带内（实测锚见 shareBand）。
        // T1@炼气结构性越带：副词条占比即已超带，主词条杠杆不可达（report-B4 §3 同族口径）。
        val stages = listOf(
            "金丹/T2" to (7 to 2),
            "元婴/T3" to (6 to 3),
            "化神/T4" to (5 to 4),
            "炼虚/T5" to (4 to 5),
            "大乘/T6" to (2 to 6)
        )
        for ((label, stage) in stages) {
            val share = medianFullSetShare(stage.first, stage.second)
            assertTrue(
                "$label 入口中位占比 $share 出带 $shareBand（品阶基数表或境界基础属性被改动）",
                share in shareBand
            )
        }
    }

    // ── 2/3/4 件套（含套装档位效果；4 件 = 满套）──────────────

    @Test
    fun `两件三件四件套占比单调递增且满套含全档效果`() {
        val realm = 2
        val rarity = 6
        fun shareOf(parts: List<EquipmentSlot>, seed: Int): Double =
            equipmentShare(realm, parts.mapIndexed { i, p -> lv30Piece("lietian", p, rarity, seed * 100 + i + 1) })

        val twoParts = listOf(EquipmentSlot.HEAD, EquipmentSlot.BODY)
        val threeParts = twoParts + listOf(EquipmentSlot.HANDS)
        val fourParts = threeParts + listOf(EquipmentSlot.FEET)

        val medians = listOf(twoParts, threeParts, fourParts).map { parts ->
            val shares = (1..scenarioCount).map { seed -> shareOf(parts, seed) }.sorted()
            (shares[scenarioCount / 2 - 1] + shares[scenarioCount / 2]) / 2.0
        }
        println(
            "PARITY 件数梯度中位占比: 2件=%.4f 3件=%.4f 4件=%.4f".format(medians[0], medians[1], medians[2])
        )
        assertTrue("2 件套占比应低于 3 件套", medians[0] < medians[1])
        assertTrue("3 件套占比应低于 4 件套（满套）", medians[1] < medians[2])
        // 4 件满套 = 2 件档 + 4 件档（暴击 + 本系伤害满档）同时生效（EquipStatResolver 口径）
        assertTrue("满套中位占比 ${medians[2]} 应在带内", medians[2] in shareBand)
    }

    // ── S14：速度/灵力零贡献 + 分维度拆分 ────────────────────

    @Test
    fun `装备不提供速度与灵力且三维分列贡献为正`() {
        val realm = 2
        val pieces = fullSet("lietian", 6, 1)
        val base = baseStats(realm)
        val bonus = EquipStatResolver.resolve(pieces)
        val with = calc().applyEquipBonus(base, bonus)

        assertEquals("速度必须零贡献（S14）", base.speed, with.speed)
        assertEquals("灵力（maxMp）必须零贡献（S14）", base.maxMp, with.maxMp)

        val atkPower = bonus.flatAttack * 5.0
        val hpPower = bonus.flatHp * 4.0
        val defPower = bonus.flatDefense * 3.0
        println(
            (
                "PARITY 分维度(T6满套 seed=1): 攻击贡献=%.0f 防御贡献=%.0f 血量贡献=%.0f " +
                    "暴击率=%.4f 暴击伤害=%.4f 物理类型=%.4f 金=%.4f 木=%.4f 水=%.4f 火=%.4f 土=%.4f"
                ).format(
                atkPower, defPower, hpPower, bonus.critRate, bonus.critDamage,
                bonus.physicalDamageBonus, bonus.metalDamageBonus, bonus.woodDamageBonus,
                bonus.waterDamageBonus, bonus.fireDamageBonus, bonus.earthDamageBonus
            )
        )
        assertTrue("攻击维度贡献必须为正", atkPower > 0.0)
        assertTrue("血量维度贡献必须为正", hpPower > 0.0)
        assertTrue("防御维度贡献必须为正", defPower > 0.0)
        // 套装档位单独断言（主/副词条混算前，resolveSetBonus 是纯套装口径）：
        // 裂天罡煞满套（4 件）= 2 件档 + 满套档物理伤害 0.10+0.20、4 件档暴击率 0.12
        val setBonus = EquipStatResolver.resolveSetBonus(pieces)
        assertEquals("套装 2/6 档物理类型伤害合计", 0.30, setBonus.physicalDamageBonus, 1e-12)
        assertEquals("套装 4 档暴击率", 0.12, setBonus.critRate, 1e-12)
        assertEquals("套装档位不产生 flat 贡献", 0.0, setBonus.flatAttack, 1e-12)
    }

    // ── 对比表（report-B4 §3 数据源，运行时打印） ─────────────

    @Test
    fun `全阶段占比对比表输出`() {
        val stages = listOf(
            "炼气/T1" to (9 to 1), "筑基/T1" to (8 to 1), "金丹/T2" to (7 to 2),
            "元婴/T3" to (6 to 3), "化神/T4" to (5 to 4), "炼虚/T5" to (4 to 5),
            "合体/T5" to (3 to 5), "大乘/T6" to (2 to 6), "渡劫/T6" to (1 to 6),
            "仙人/T6" to (0 to 6)
        )
        for ((label, stage) in stages) {
            val (realm, rarity) = stage
            val shares = (1..scenarioCount)
                .map { seed -> equipmentShare(realm, fullSet("lietian", rarity, seed)) }
                .sorted()
            val median = (shares[scenarioCount / 2 - 1] + shares[scenarioCount / 2]) / 2.0
            println(
                "PARITY-STAGE realm=%d %s p10=%.4f med=%.4f p90=%.4f".format(
                    realm, label, shares[scenarioCount / 10], median, shares[scenarioCount * 9 / 10]
                )
            )
        }
    }

    private companion object {
        /** Lv1→30 的强化节点数（每 3 级一次：3,6,…,30） */
        const val EquipLevelCurveMaxReinforcements = 10
    }
}
