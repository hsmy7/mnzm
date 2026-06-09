package com.xianxia.sect.ui.game.map

import com.xianxia.sect.core.model.LevelType
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
