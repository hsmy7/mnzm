package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * 妖兽攻击检测器。
 *
 * 每月检测未击败、未过期的妖兽关卡是否接近玩家宗门（含占领宗门），
 * 根据距离触发攻击概率判定，返回待处理的攻击预警列表。
 *
 * 纯函数设计：不持有可变状态，不直接写入 [GameStateStore]，
 * 调用方负责将返回的预警列表通过 [GameStateStore.setPendingBeastAttacks] 写入。
 */
class BeastAttackDetector @Inject constructor(
    private val rngManager: GameRngManager
) {

    /**
     * 检测所有妖兽关卡对玩家宗门的攻击意图。
     *
     * 遍历所有活跃的妖兽关卡，计算其与最近玩家宗门（含占领宗门）的距离，
     * 若距离小于 [GameConfig.WorldMap.BEAST_ATTACK_RADIUS]，按距离线性插值概率判定
     * 是否触发攻击预警。
     *
     * @param gd 当前 [GameData]
     * @return 待处理的攻击预警列表，无攻击时返回空列表
     */
    fun detectAttacks(gd: GameData): List<PendingBeastAttack> {
        val year = gd.gameYear
        val month = gd.gameMonth
        val rng = rngManager.getRng(RngPartition.EXPLORATION)

        val targets = gd.worldMapSects.filter { it.isPlayerSect || it.isPlayerOccupied }
        if (targets.isEmpty()) return emptyList()

        val pending = mutableListOf<PendingBeastAttack>()
        val radius = GameConfig.WorldMap.BEAST_ATTACK_RADIUS

        for (level in gd.worldLevels) {
            if (level.type != LevelType.BEAST || level.defeated || level.checkExpired(year, month)) {
                continue
            }

            var nearestSect: WorldSect? = null
            var nearestDist = Float.MAX_VALUE

            for (sect in targets) {
                val dx = level.x - sect.x
                val dy = level.y - sect.y
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist < nearestDist) {
                    nearestDist = dist
                    nearestSect = sect
                }
            }

            val sect = nearestSect ?: continue
            if (nearestDist >= radius) continue

            // 距离越近攻击概率越高：prob = baseProb * (1 - dist/radius)
            val prob = GameConfig.WorldMap.BEAST_ATTACK_BASE_PROB * (1.0 - nearestDist / radius)
            if (rng.nextDouble() < prob) {
                pending.add(
                    PendingBeastAttack(
                        beastLevel = level,
                        targetSectId = sect.id,
                        targetSectName = sect.name,
                        distance = nearestDist
                    )
                )
            }
        }

        return pending
    }
}
