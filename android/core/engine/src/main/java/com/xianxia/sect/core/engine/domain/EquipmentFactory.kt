package com.xianxia.sect.core.engine.domain

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.registry.EquipAffixPool
import com.xianxia.sect.core.registry.EquipMainStatPool
import com.xianxia.sect.core.registry.EquipmentDatabase
import kotlin.random.Random

/**
 * 装备唯一产出入口（装备重构 B3，方案 §3.8）。
 *
 * 全部产出链（锻造/商店/掉落/秘境/试炼/任务/邮件/兑换码/外交/AI）一律经
 * [create] 生成实例：词条 roll（主词条部位池 → 3 副词条不放回）全部走
 * `RngPartition.EQUIPMENT`（跨端对拍基准：同一件装备内抽取顺序
 * ①主词条 ②副词条 ③等级强化，方案 §3.7，禁改）。
 *
 * **品阶境界约束（0.2-5 拍板，单点实现）**：产出品阶不得高于玩家当前境界
 * 可穿戴的最高品阶（`GameConfig.Realm.getMinRealmForRarity` 反查钳制），
 * 由 [EquipmentRarityGateTest] 守护（S17）。
 */
object EquipmentFactory {

    /**
     * 生成一件装备实例。
     *
     * @param setId 套装 id（"lietian"/"zifu"）
     * @param part 六部位
     * @param rarity 请求品阶（1..6；高于 [discipleRealm] 可穿上限时被钳制）
     * @param rng 装备 RNG（RngPartition.EQUIPMENT 流；抽取序：主词条 → 副词条）
     * @param discipleRealm 弟子当前境界（**值越小境界越高**；默认 1 = 顶境不设限。
     *   🔴 禁用 Int.MAX_VALUE 充当「不设限」——在本口径下它是最低境界，
     *   会把全部产出钳到 T1（B3 测试实证后修正）
     */
    fun create(
        setId: String,
        part: EquipmentSlot,
        rarity: Int,
        rng: Random,
        discipleRealm: Int = REALM_UNRESTRICTED
    ): EquipmentInstance {
        val piece = requirePiece(setId, part)
        val effectiveRarity = clampRarity(rarity, discipleRealm)
        val mainStat = EquipMainStatPool.rollMainStat(part, rng)
        val mainValue = EquipMainStatPool.mainStatValue(mainStat, part, effectiveRarity)
        val subStats = EquipAffixPool.rollSubStats(effectiveRarity, rng)
        return EquipmentInstance(
            name = piece.name,
            setId = piece.setId,
            part = part,
            growth = EquipGrowth(
                affix = EquipAffixSet(
                    mainStat = mainValue,
                    subStats = subStats,
                    subRolls = List(subStats.size) { 1 }
                )
            ),
            meta = EquipInstanceMeta(
                rarity = effectiveRarity,
                minRealm = GameConfig.Realm.getMinRealmForRarity(effectiveRarity),
                description = piece.description
            )
        )
    }

    /**
     * 按境界分层权重抽取产出品阶（0.2-5：低境界偏低品阶，T1–T5 内容不被跳过）。
     * 权重沿用旧 `generateRarity` 分层语义（在 [minRarity]..realmMax 区间内递进偏移）。
     */
    fun pickRarity(minRarity: Int, discipleRealm: Int, rng: Random): Int {
        val realmMax = maxWearableRarity(discipleRealm)
        val min = minRarity.coerceIn(1, realmMax)
        val roll = rng.nextDouble()
        val offset = when {
            roll < 0.5 -> 0
            roll < 0.75 -> 1
            roll < 0.9 -> 2
            roll < 0.97 -> 3
            roll < 0.99 -> 4
            else -> 5
        }
        return (min + offset).coerceAtMost(realmMax)
    }

    /** 弟子当前境界可穿戴的最高品阶（realm 值越小境界越高） */
    fun maxWearableRarity(discipleRealm: Int): Int {
        for (rarity in 6 downTo 1) {
            if (GameConfig.Realm.meetsRealmRequirement(discipleRealm, GameConfig.Realm.getMinRealmForRarity(rarity))) {
                return rarity
            }
        }
        return 1
    }

    /** 随机部件（同套装内六部位等权；产出链无部位偏好场景用） */
    fun pickPart(setId: String, rng: Random): EquipmentSlot {
        val parts = EquipmentDatabase.setPieces.filter { it.setId == setId }.map { it.part }
        require(parts.isNotEmpty()) { "套装 $setId 无部件定义" }
        return parts[rng.nextInt(parts.size)]
    }

    private fun requirePiece(setId: String, part: EquipmentSlot): EquipmentDatabase.SetPieceTemplate =
        EquipmentDatabase.setPieces.find { it.setId == setId && it.part == part }
            ?: error("无 [$setId/$part] 部件定义（EquipmentDatabase.setPieces 12 条之外）")

    /** 品阶境界钳制：rarity 高于可穿上限时收敛到可穿最高档 */
    private fun clampRarity(rarity: Int, discipleRealm: Int): Int {
        val requested = rarity.coerceIn(1, 6)
        val realmMax = maxWearableRarity(discipleRealm)
        return requested.coerceAtMost(realmMax)
    }

    /** 「不设限」境界哨兵（值越小境界越高：顶境可穿全部品阶；禁用 Int.MAX_VALUE，见 create KDoc） */
    const val REALM_UNRESTRICTED = 1
}
