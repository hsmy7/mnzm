package com.xianxia.sect.ui.game.map

import androidx.compose.ui.graphics.Color

sealed interface MapItem {
    val id: String
    val worldX: Float
    val worldY: Float

    data class Sect(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val name: String,
        val level: Int,
        val levelName: String,
        val isPlayerSect: Boolean,
        val isRighteous: Boolean,
        val isPlayerOccupied: Boolean,
        val occupierSectId: String?,
        val isDiscovered: Boolean,
        val isHighlighted: Boolean
    ) : MapItem

    data class ScoutTeam(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val name: String
    ) : MapItem

    data class Cave(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val name: String
    ) : MapItem

    data class CaveExplorationTeam(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val startX: Float,
        val startY: Float,
        val targetX: Float,
        val targetY: Float
    ) : MapItem

    data class Level(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val levelType: com.xianxia.sect.core.model.LevelType,
        val beastType: Int?,
        val realm: Int,
        val realmLayer: Int,
        val name: String,
        val count: Int,
        val caveImageIndex: Int,
        val caveName: String,
        val defeated: Boolean
    ) : MapItem
}

data class MapPathData(
    val fromId: String,
    val toId: String,
    val fromWorldX: Float,
    val fromWorldY: Float,
    val toWorldX: Float,
    val toWorldY: Float,
    val waypoints: List<Pair<Float, Float>> = emptyList()
)
