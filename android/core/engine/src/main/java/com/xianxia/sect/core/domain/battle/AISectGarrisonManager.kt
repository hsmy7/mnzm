package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.WorldSect

/**
 * AI 驻军管理：每月填充空槽 + 每年全量轮换。
 *
 * - 每月：只填充空槽（discipleId 为空或弟子已死），不替换已有守军。
 * - 每年：占领者最强 10 名弟子留守宗门，第 11 名起外派填满所有占领宗门的 garrison。
 *
 * 每个占领者只操作自己占领的宗门（按 occupierSectId 分组），不碰其他宗门。
 */
object AISectGarrisonManager {

    private const val GARRISON_SLOT_COUNT = 10

    // ═══════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════

    /**
     * 每月调用：填充 AI 占领宗门的空 garrison 槽位。
     * 只填 discipleId 为空或指向已死/不存在弟子的槽，不替换活着的守军。
     */
    fun fillEmptyGarrisonSlots(gameData: GameData): GameData {
        val playerSectId = gameData.worldMapSects.find { it.isPlayerSect }?.id ?: return gameData

        // 按 occupierSectId 分组被占领宗门（排除玩家自身和玩家占领的）
        val occupiedByAi = gameData.worldMapSects.filter { sect ->
            !sect.isPlayerSect &&
                sect.occupierSectId.isNotEmpty() &&
                sect.occupierSectId != playerSectId
        }
        if (occupiedByAi.isEmpty()) return gameData

        val groupedByOccupier = occupiedByAi.groupBy { it.occupierSectId }
        var updatedSects = gameData.worldMapSects

        for ((occupierId, occupiedSects) in groupedByOccupier) {
            val allOccupierDisciples = gameData.aiSectDisciples[occupierId] ?: continue
            val aliveMap = allOccupierDisciples.filter { it.isAlive }.associateBy { it.id }

            // 收集该占领者所有已驻守弟子 ID
            val alreadyGarrisoned = collectOccupierGarrisonedIds(gameData, occupierId)

            // 可用池：存活、未驻守的弟子，强者优先（realm 升序）
            val pool = allOccupierDisciples
                .filter { it.isAlive && it.id !in alreadyGarrisoned }
                .sortedBy { it.realm }
                .toMutableList()

            if (pool.isEmpty()) continue

            for (sect in occupiedSects) {
                val newSlots = sect.garrisonSlots.map { slot ->
                    if (isSlotVacant(slot, aliveMap) && pool.isNotEmpty()) {
                        val d = pool.removeAt(0)
                        createGarrisonSlot(slot.index, d)
                    } else {
                        slot
                    }
                }
                updatedSects = updatedSects.map { s ->
                    if (s.id == sect.id) s.copy(garrisonSlots = newSlots) else s
                }
            }
        }

        return gameData.copy(worldMapSects = updatedSects)
    }

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
            val allDisciples = gameData.aiSectDisciples[occupierId] ?: continue
            val aliveDisciples = allDisciples.filter { it.isAlive }
            if (aliveDisciples.isEmpty()) continue

            // 按 realm 升序排列（1 最强 → 9 最弱）
            val sorted = aliveDisciples.sortedBy { it.realm }

            // 前 10 名留守宗门，第 11 名起外派
            val pool = sorted.drop(10).toMutableList()

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

    /**
     * 收集某占领者在所有被占领宗门中已驻守的弟子 ID。
     */
    private fun collectOccupierGarrisonedIds(gameData: GameData, occupierSectId: String): Set<String> {
        return gameData.worldMapSects
            .filter { it.occupierSectId == occupierSectId }
            .flatMap { it.garrisonSlots }
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()
    }

    /**
     * 检查 garrison 槽位是否实质为空：
     * - discipleId 为空，或
     * - 指向的弟子在占领者池中不存在或已死亡。
     */
    private fun isSlotVacant(slot: GarrisonSlot, aliveMap: Map<String, Disciple>): Boolean {
        if (slot.discipleId.isEmpty()) return true
        val disciple = aliveMap[slot.discipleId] ?: return true
        return !disciple.isAlive
    }

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
