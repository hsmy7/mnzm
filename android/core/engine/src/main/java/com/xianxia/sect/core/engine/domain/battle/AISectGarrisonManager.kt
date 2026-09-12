package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot

/**
 * AI 驻军管理：每年全量轮换。
 *
 * - 每月填充（fillEmptyGarrisonSlots）已下沉 C++ AUTHORITATIVE 月结
 *   子事件 6c（P2-18 Stage 1，sect_defense_battle.h fillEmptyGarrisonSlots），
 *   原 Kotlin 实现随 PlayerDefenseProcessor 删除一并移除。
 * - 每年：占领者最强 10 名弟子留守宗门，第 11 名起外派填满所有占领宗门的 garrison
 *   （年变 runGarrisonAndReport 调用，保留 Kotlin）。
 *
 * 每个占领者只操作自己占领的宗门（按 occupierSectId 分组），不碰其他宗门。
 */
object AISectGarrisonManager {

    private const val GARRISON_SLOT_COUNT = 10

    // ═══════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════
    /**
     * 每年调用：全量轮换所有 AI 占领宗门的 garrison。
     * 占领者最强 10 名弟子留守宗门，第 11 名起依次填满所有占领宗门的 garrison。
     */
    fun rotateGarrisonSlots(gameData: GameData): GameData {
        val playerSectId = gameData.worldMapSects.find { it.isPlayerSect }?.id ?: return gameData

        // 按 occupier 分组
        val occupiedByAi = gameData.worldMapSects.filter { sect ->
            !sect.isPlayerSect &&
                sect.occupierSectId.isNotEmpty() &&
                sect.occupierSectId != playerSectId
        }
        if (occupiedByAi.isEmpty()) return gameData

        val groupedByOccupier = occupiedByAi.groupBy { it.occupierSectId }
        var updatedSects = gameData.worldMapSects

        for ((occupierId, occupiedSects) in groupedByOccupier) {
            // 按 realm 升序排列（1 最强 → 9 最弱）；前 10 名留守宗门，第 11 名起外派。
            // 无存活弟子（含宗门无弟子池）跳过
            val pool = gameData.aiSectDisciples[occupierId]
                ?.filter { it.isAlive }
                ?.takeIf { it.isNotEmpty() }
                ?.sortedBy { it.realm }
                ?.drop(10)
                ?.toMutableList()
                ?: continue

            for (sect in occupiedSects) {
                val newSlots = (0 until GARRISON_SLOT_COUNT).map { index ->
                    if (pool.isNotEmpty()) {
                        val d = pool.removeAt(0)
                        createGarrisonSlot(index, d)
                    } else {
                        GarrisonSlot(index = index)
                    }
                }
                updatedSects = updatedSects.map { s ->
                    if (s.id == sect.id) s.copy(garrisonSlots = newSlots) else s
                }
            }
        }

        return gameData.copy(worldMapSects = updatedSects)
    }

    // ═══════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════

    private fun createGarrisonSlot(index: Int, disciple: Disciple): GarrisonSlot {
        return GarrisonSlot(
            index = index,
            discipleId = disciple.id,
            discipleName = disciple.name,
            discipleRealm = disciple.realmName,
            discipleSpiritRootColor = disciple.spiritRoot.countColor,
            portraitRes = disciple.portraitRes
        )
    }
}
