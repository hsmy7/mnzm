package com.xianxia.sect.ui.game.leaderboard

import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.taptap.LocalLeaderboardEntry

/**
 * 本地榜（天下宗门）纯组装函数，零 Android 依赖、确定性可单测。
 *
 * 数据源：
 * - 玩家宗门：玩家战力 + 宗门名（isPlayer = true）
 * - AI 宗门：aiSectCombatPowers（sectId → 战力）join worldMapSects（sectId → 宗门名）
 *   仅保留 worldMapSects 中真实存在的非玩家宗门；name 缺失时用 id 兜底
 * 排序：战力降序，同战力按名称升序（确定性）。
 */
object LocalLeaderboardComposer {

    /** 玩家宗门占位 id */
    const val PLAYER_SECT_ID = "__player__"

    fun compose(
        playerPower: Long,
        playerSectName: String,
        aiSectCombatPowers: Map<String, Long>,
        worldSects: List<WorldSect>
    ): List<LocalLeaderboardEntry> {
        val sectsById = worldSects.associateBy { it.id }
        val aiEntries = aiSectCombatPowers
            .filterKeys { sectId ->
                val sect = sectsById[sectId]
                sect != null && !sect.isPlayerSect
            }
            .map { (sectId, power) ->
                LocalLeaderboardEntry(
                    sectId = sectId,
                    name = sectsById[sectId]?.name?.takeIf { it.isNotBlank() } ?: sectId,
                    power = power.coerceAtLeast(0L),
                    isPlayer = false
                )
            }
        val playerEntry = LocalLeaderboardEntry(
            sectId = PLAYER_SECT_ID,
            name = playerSectName.ifBlank { "我的宗门" },
            power = playerPower.coerceAtLeast(0L),
            isPlayer = true
        )
        return (aiEntries + playerEntry)
            .sortedWith(compareByDescending<LocalLeaderboardEntry> { it.power }.thenBy { it.name })
    }
}
