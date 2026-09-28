package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

/**
 * 世界关卡管理器。
 *
 * 职责：
 * - 月度关卡刷新（每3月生成1~6个新关卡，含妖兽/洞府）
 * - 过期关卡清理（已击败或超时的关卡）
 * - 妖兽移动（未击败的妖兽每关在边界范围内随机移动）
 *
 * 纯函数设计：不持有可变状态，上一次刷新月由 [GameData.worldLevelLastRefreshMonth] 管理。
 * 所有随机操作使用分区 RNG（[RngPartition.EXPLORATION]），确保重放确定性。
 */
class WorldLevelManager @Inject constructor(
    private val rngManager: GameRngManager,
    private val levelGenerator: LevelGenerator
) {
    private val rng get() = rngManager.getRng(RngPartition.EXPLORATION)

    companion object {
        /**
         * 旧档读档锚定（缺陷 #4 修复，方案 §9.1）：字段加入前的存档
         * [GameData.worldLevelLastRefreshMonth] 恒 0，而月度刷新门
         * （[processMonthly]）以 `== 0` 作为「从未刷新」信号——读档后首个
         * 月结会额外整批生成关卡。检测到 0 且游戏已有进度（绝对月 > 1）时
         * 锚定为当前绝对月（下次刷新等满 3 个月，与
         * initSpiritMineLastSettledMonth 同型补丁）。
         * 纯函数；新档（绝对月 == 1）与已锚定档原样返回。
         */
        /** 新档起始绝对月（1 年 1 月 = 1*12+1；绝对月口径 year*12+month） */
        private const val NEW_GAME_ABSOLUTE_MONTH = 13

        fun anchorLastRefreshMonthForLegacySave(gd: GameData): GameData {
            if (gd.worldLevelLastRefreshMonth != 0) return gd
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth
            // 新档（1 年 1 月，进度未推进）保留  语义不锚定——
            // 否则首次读档后关卡首刷被推迟 3 个月（行为劣化）
            if (absoluteMonth <= NEW_GAME_ABSOLUTE_MONTH) return gd
            return gd.copy(worldLevelLastRefreshMonth = absoluteMonth)
        }
    }

    /**
     * 月度处理入口。
     *
     * 1. 清理已过期、已击败的关卡
     * 2. 距离上次刷新满3月时生成新关卡
     * 3. 移动所有活跃妖兽的位置
     *
     * @param gd 当前 [GameData]
     * @param playerAvgRealm 玩家存活弟子的平均境界，不为 null 时传给 LevelGenerator 做安全兜底
     * @return 更新后的 [GameData]（内含新的 worldLevels 和 worldLevelLastRefreshMonth）
     */
    fun processMonthly(gd: GameData, playerAvgRealm: Int? = null): GameData {
        val year = gd.gameYear
        val month = gd.gameMonth

        // 1. 清理过期关卡（每月都做）
        val remainingLevels = gd.worldLevels.filter { !it.checkExpired(year, month) }

        // 2. 每3个月刷新一次新关卡（数量1~6，持续4个月）
        val absoluteMonth = year * 12 + month
        val shouldRefresh = gd.worldLevelLastRefreshMonth == 0 ||
            (absoluteMonth - gd.worldLevelLastRefreshMonth) >= 3

        val levelsAfterRefresh = if (shouldRefresh) {
            val playerSect = gd.worldMapSects.find { it.isPlayerSect }
            if (playerSect == null) {
                // 无玩家宗门时不生成新关卡，但已完成的过期清理仍然生效
                return gd.copy(worldLevels = moveBeasts(year, month, remainingLevels))
            }
            val newLevels = levelGenerator.generateWorldLevels(
                existingSects = gd.worldMapSects,
                currentYear = year,
                currentMonth = month,
                existingLevels = remainingLevels,
                playerAvgRealm = playerAvgRealm
            )
            remainingLevels + newLevels
        } else {
            remainingLevels
        }

        // 3. 妖兽移动
        val movedLevels = moveBeasts(year, month, levelsAfterRefresh)

        return gd.copy(
            worldLevels = movedLevels,
            worldLevelLastRefreshMonth = if (shouldRefresh) absoluteMonth else gd.worldLevelLastRefreshMonth
        )
    }

    /**
     * 移动未击败、未过期的妖兽关卡。
     *
     * 每个活跃妖兽在 [GameConfig.WorldMap.BEAST_MOVE_DISTANCE] 范围内随机偏移，
     * 偏移后位置钳制在地图边界（[GameConfig.WorldMap.BORDER_PADDING]）以内。
     * 非妖兽关卡（洞府）和已击败/已过期的妖兽关卡保持原地不变。
     *
     * @param year   当前游戏年份
     * @param month  当前游戏月份
     * @param levels 所有关卡列表
     * @return 移动后的关卡列表
     */
    private fun moveBeasts(
        year: Int,
        month: Int,
        levels: List<WorldLevel>
    ): List<WorldLevel> {
        val minX = GameConfig.WorldMap.BORDER_PADDING.toFloat()
        val maxX = (GameConfig.WorldMap.MAP_WIDTH - GameConfig.WorldMap.BORDER_PADDING).toFloat()
        val minY = GameConfig.WorldMap.BORDER_PADDING.toFloat()
        val maxY = (GameConfig.WorldMap.MAP_HEIGHT - GameConfig.WorldMap.BORDER_PADDING).toFloat()

        return levels.map { level ->
            if (level.type != LevelType.BEAST || level.defeated || level.checkExpired(year, month)) {
                level
            } else {
                val angle = rng.nextDouble() * 2.0 * Math.PI
                val dist = rng.nextDouble() * GameConfig.WorldMap.BEAST_MOVE_DISTANCE
                level.copy(
                    x = (level.x + cos(angle) * dist).toFloat().coerceIn(minX, maxX),
                    y = (level.y + sin(angle) * dist).toFloat().coerceIn(minY, maxY)
                )
            }
        }
    }
}
