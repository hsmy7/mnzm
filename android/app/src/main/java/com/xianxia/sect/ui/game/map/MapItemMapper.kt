package com.xianxia.sect.ui.game.map

import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.engine.WorldMapGenerator
import com.xianxia.sect.core.model.WorldLevel
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
                            val waypoints = WorldMapGenerator.generatePathWaypoints(
                                from.x, from.y, to.x, to.y, from.id, to.id
                            )
                            paths.add(
                                MapPathData(
                                    fromId = id1,
                                    toId = id2,
                                    fromWorldX = from.x,
                                    fromWorldY = from.y,
                                    toWorldX = to.x,
                                    toWorldY = to.y,
                                    waypoints = waypoints
                                )
                            )
                        }
                    }
                }
            }
        }
        return paths
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

    fun fromLevels(levels: List<WorldLevel>): List<MapItem.Level> =
        levels.filter { !it.defeated }
            .map { level ->
                MapItem.Level(
                    id = level.id,
                    worldX = level.x,
                    worldY = level.y,
                    levelType = level.type,
                    beastType = level.beastType,
                    realm = level.realm,
                    realmLayer = level.realmLayer,
                    name = if (level.type == LevelType.BEAST) level.beastName else level.guardianName,
                    count = level.count,
                    caveImageIndex = level.caveImageIndex,
                    caveName = level.caveName,
                    defeated = level.defeated
                )
            }
}
