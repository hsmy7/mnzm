package com.xianxia.sect.ui.game.map

import com.xianxia.sect.core.model.AIBattleTeam
import com.xianxia.sect.core.model.BattleTeam
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.ExplorationStatus
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.WorldSect

object MapItemMapper {

    fun fromWorldSects(
        sects: List<WorldSect>,
        movableTargetIds: Set<String>
    ): List<MapItem.Sect> = sects.map { sect ->
        MapItem.Sect(
            id = sect.id,
            worldX = sect.x,
            worldY = sect.y,
            name = sect.name,
            level = sect.level,
            levelName = sect.levelName,
            isPlayerSect = sect.isPlayerSect,
            isRighteous = sect.isRighteous,
            isPlayerOccupied = sect.isPlayerOccupied,
            occupierSectId = sect.occupierSectId,
            isDiscovered = sect.discovered,
            isHighlighted = sect.id in movableTargetIds
        )
    }

    fun fromPaths(sects: List<WorldSect>): List<MapPathData> {
        val sectMap = sects.associateBy { it.id }
        val pathSet = mutableSetOf<Pair<String, String>>()
        val paths = mutableListOf<MapPathData>()

        sects.forEach { sect ->
            sect.connectedSectIds.forEach { connectedId ->
                if (connectedId in sectMap) {
                    val (id1, id2) = if (sect.id < connectedId) {
                        sect.id to connectedId
                    } else {
                        connectedId to sect.id
                    }
                    if (pathSet.add(id1 to id2)) {
                        val from = sectMap[id1]
                        val to = sectMap[id2]
                        if (from != null && to != null) {
                            paths.add(
                                MapPathData(
                                    fromId = id1,
                                    toId = id2,
                                    fromWorldX = from.x,
                                    fromWorldY = from.y,
                                    toWorldX = to.x,
                                    toWorldY = to.y
                                )
                            )
                        }
                    }
                }
            }
        }
        return paths
    }

    fun fromScoutTeams(teams: List<ExplorationTeam>): List<MapItem.ScoutTeam> =
        teams.filter { it.status == ExplorationStatus.SCOUTING && it.moveProgress < 1f }
            .map { team ->
                MapItem.ScoutTeam(
                    id = team.id,
                    worldX = team.currentX,
                    worldY = team.currentY,
                    name = team.name
                )
            }

    fun fromCaves(caves: List<CultivatorCave>): List<MapItem.Cave> =
        caves.filter { it.status != CaveStatus.EXPIRED && it.status != CaveStatus.EXPLORED }
            .map { cave ->
                MapItem.Cave(
                    id = cave.id,
                    worldX = cave.x,
                    worldY = cave.y,
                    name = cave.name
                )
            }

    fun fromCaveExplorationTeams(teams: List<CaveExplorationTeam>): List<MapItem.CaveExplorationTeam> =
        teams.filter { it.isMoving }
            .map { team ->
                MapItem.CaveExplorationTeam(
                    id = team.id,
                    worldX = team.currentX,
                    worldY = team.currentY,
                    startX = team.startX,
                    startY = team.startY,
                    targetX = team.targetX,
                    targetY = team.targetY
                )
            }

    fun fromBattleTeam(
        battleTeam: BattleTeam?,
        playerSect: WorldSect?
    ): List<MapItem.BattleTeam> {
        if (battleTeam == null) return emptyList()
        val items = mutableListOf<MapItem.BattleTeam>()
        if (battleTeam.isAtSect && playerSect != null) {
            items.add(
                MapItem.BattleTeam(
                    id = battleTeam.id,
                    worldX = playerSect.x,
                    worldY = playerSect.y,
                    isAtSect = true,
                    sectWorldX = playerSect.x,
                    sectWorldY = playerSect.y
                )
            )
        }
        if (battleTeam.status == "moving" && battleTeam.currentX > 0 && battleTeam.currentY > 0) {
            items.add(
                MapItem.BattleTeam(
                    id = battleTeam.id + "_moving",
                    worldX = battleTeam.currentX,
                    worldY = battleTeam.currentY,
                    isAtSect = false,
                    sectWorldX = 0f,
                    sectWorldY = 0f
                )
            )
        }
        return items
    }

    fun fromAIBattleTeams(
        aiTeams: List<AIBattleTeam>,
        worldSects: List<WorldSect>
    ): Pair<List<MapItem.AIBattleTeam>, List<MapItem.BattleIndicator>> {
        val teamItems = mutableListOf<MapItem.AIBattleTeam>()
        val battleIndicators = mutableListOf<MapItem.BattleIndicator>()

        aiTeams.filter { it.status == "moving" || it.status == "battling" }.forEach { aiTeam ->
            if (aiTeam.status == "moving") {
                val attackerSect = worldSects.find { it.id == aiTeam.attackerSectId }
                teamItems.add(
                    MapItem.AIBattleTeam(
                        id = aiTeam.id,
                        worldX = aiTeam.currentX,
                        worldY = aiTeam.currentY,
                        attackerSectName = aiTeam.attackerSectName,
                        attackerIsRighteous = attackerSect?.isRighteous ?: true,
                        defenderSectId = aiTeam.defenderSectId
                    )
                )
            }

            battleIndicators.add(
                MapItem.BattleIndicator(
                    id = "battle_${aiTeam.id}",
                    worldX = aiTeam.currentX,
                    worldY = aiTeam.currentY,
                    isBattling = aiTeam.status == "battling"
                )
            )
        }

        return teamItems to battleIndicators
    }
}
