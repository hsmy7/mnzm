package com.xianxia.sect.core.config

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.model.RewardCardItem
import java.util.UUID

/**
 * ## SectLevelRewardConfig — 宗门等级奖励与升级条件配置
 *
 * 集中管理四个宗门等级的：
 * - 每周奖励物品清单
 * - 晋升至下一等级的条件定义
 *
 * 奖励周期：现实时间 7 天（通过 [SectLevelClaimRecord.claimedAtEpochMs] 判定）。
 * 升级为手动操作，条件满足后玩家通过 UI 按钮触发。
 */

/** 升级条件状态（用于 UI 驱动） */
data class UpgradeConditionState(
    val description: String,   // 条件描述文本（如"拥有一名化神及以上境界弟子"）
    val isMet: Boolean         // 是否已满足
)

object SectLevelRewardConfig {

    // ==================== 奖励定义 ====================

    /**
     * 获取指定等级的奖励卡片列表（用于 UI 展示）。
     */
    fun getRewardCards(level: Int): List<RewardCardItem> = when (level) {
        SectLevel.SMALL -> listOf(
            RewardCardItem(
                itemName = "随机凡品兽血",
                itemType = "beastMaterial",
                rarity = 1,
                quantity = 20
            ),
            RewardCardItem(
                itemName = "灵石",
                itemType = "spiritStones",
                rarity = 1,
                quantity = 100_000
            )
        )
        SectLevel.MEDIUM -> listOf(
            RewardCardItem(
                itemName = "随机凡品兽血",
                itemType = "beastMaterial",
                rarity = 1,
                quantity = 50
            ),
            RewardCardItem(
                itemName = "凡品储物袋",
                itemType = "storageBag",
                rarity = 1,
                quantity = 5
            ),
            RewardCardItem(
                itemName = "灵石",
                itemType = "spiritStones",
                rarity = 1,
                quantity = 200_000
            )
        )
        SectLevel.LARGE -> listOf(
            RewardCardItem(
                itemName = "随机凡品兽血",
                itemType = "beastMaterial",
                rarity = 1,
                quantity = 50
            ),
            RewardCardItem(
                itemName = "灵品储物袋",
                itemType = "storageBag",
                rarity = 2,
                quantity = 5
            ),
            RewardCardItem(
                itemName = "灵石",
                itemType = "spiritStones",
                rarity = 1,
                quantity = 500_000
            )
        )
        SectLevel.TOP -> listOf(
            RewardCardItem(
                itemName = "随机宝品兽血",
                itemType = "beastMaterial",
                rarity = 3,
                quantity = 50
            ),
            RewardCardItem(
                itemName = "宝品储物袋",
                itemType = "storageBag",
                rarity = 3,
                quantity = 5
            ),
            RewardCardItem(
                itemName = "灵石",
                itemType = "spiritStones",
                rarity = 1,
                quantity = 1_000_000
            )
        )
        else -> emptyList()
    }

    // ==================== 升级条件定义 ====================

    /**
     * 获取晋升至 [level] 等级需要满足的条件。
     *
     * 例如 level=MEDIUM 返回「拥有化神弟子」条件。
     * level=SMALL 无升级条件（初始等级），返回空列表。
     */
    fun getUpgradeConditionsForLevel(targetLevel: Int): List<UpgradeConditionDef> =
        when (targetLevel) {
            SectLevel.MEDIUM -> listOf(
                UpgradeConditionDef(
                    "拥有一名化神及以上境界弟子",
                    UpgradeConditionDef.hasDiscipleRealmAtMost(5)
                )
            )
            SectLevel.LARGE -> listOf(
                UpgradeConditionDef(
                    "拥有一名炼虚及以上境界弟子",
                    UpgradeConditionDef.hasDiscipleRealmAtMost(4)
                ),
                UpgradeConditionDef(
                    "至少占领了一座小型宗门",
                    UpgradeConditionDef.hasOccupiedSectOfLevel(SectLevel.SMALL)
                )
            )
            SectLevel.TOP -> listOf(
                UpgradeConditionDef(
                    "拥有一名大乘及以上境界弟子",
                    UpgradeConditionDef.hasDiscipleRealmAtMost(2)
                ),
                UpgradeConditionDef(
                    "至少占领了一座中型宗门",
                    UpgradeConditionDef.hasOccupiedSectOfLevel(SectLevel.MEDIUM)
                )
            )
            else -> emptyList() // SMALL 无需升级条件
        }

    /**
     * 获取 [targetLevel] 的升级条件并附上当前满足状态。
     *
     * @param targetLevel 目标等级
     * @param highestRealm 当前弟子最高境界（最小 realm 值）
     * @param occupiedSectLevels 已被玩家占领的宗门等级列表
     */
    fun getUpgradeConditionStates(
        targetLevel: Int,
        highestRealm: Int,
        occupiedSectLevels: List<Int>
    ): List<UpgradeConditionState> {
        val conditions = getUpgradeConditionsForLevel(targetLevel)
        return conditions.map { def ->
            val met = def.evaluate(highestRealm, occupiedSectLevels)
            UpgradeConditionState(description = def.description, isMet = met)
        }
    }
}

/**
 * 升级条件定义（惰性求值）。
 *
 * [evaluate] 接收当前游戏状态，返回条件是否满足。
 */
class UpgradeConditionDef(
    val description: String,
    private val predicate: (highestRealm: Int, occupiedSectLevels: List<Int>) -> Boolean
) {
    fun evaluate(highestRealm: Int, occupiedSectLevels: List<Int>): Boolean =
        predicate(highestRealm, occupiedSectLevels)

    companion object {
        // 辅助判定函数
        fun hasDiscipleRealmAtMost(maxRealm: Int): (Int, List<Int>) -> Boolean =
            { highestRealm, _ -> highestRealm <= maxRealm }

        fun hasOccupiedSectOfLevel(level: Int): (Int, List<Int>) -> Boolean =
            { _, occupiedLevels -> level in occupiedLevels }
    }
}
