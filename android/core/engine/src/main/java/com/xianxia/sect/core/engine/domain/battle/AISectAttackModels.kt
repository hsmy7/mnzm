package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed

/** AI 战斗胜负结果 */
enum class AIBattleWinner {
    ATTACKER, DEFENDER, DRAW
}

/** AI 战斗结算结果 */
data class AIBattleResult(
    val winner: AIBattleWinner,
    val deadAttackerIds: List<String>,
    val deadDefenderIds: List<String>,
    val canOccupy: Boolean,
    val turns: Int = 0,
    val survivorHpMap: Map<String, Int> = emptyMap(),
    val survivorMpMap: Map<String, Int> = emptyMap(),
    val defenderSurvivorHpMap: Map<String, Int> = emptyMap(),
    val defenderSurvivorMpMap: Map<String, Int> = emptyMap(),
    val rounds: List<BattleLogRound> = emptyList(),
    val teamMembers: List<BattleLogMember> = emptyList(),
    val enemies: List<BattleLogEnemy> = emptyList()
)

/** 玩家被攻破的灵石/材料损失 */
data class PlayerLootLossResult(
    val lostSpiritStones: Long,
    val lostMaterials: Map<String, Int>
)

/** 战争奖励 */
data class WarRewards(
    val spiritStones: Long,
    val equipmentStacks: List<EquipmentStack>,
    val manualStacks: List<ManualStack>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>
)

/** 宗门战争奖励配置 — 按宗门等级确定物品品阶范围和灵石价值 */
data class SectWarRewardConfig(
    val minRarity: Int,
    val maxRarity: Int,
    val spiritStoneValue: Long
)
