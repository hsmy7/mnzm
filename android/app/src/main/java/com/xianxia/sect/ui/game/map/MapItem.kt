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

    data class BattleTeam(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val isAtSect: Boolean,
        val sectWorldX: Float,
        val sectWorldY: Float
    ) : MapItem

    data class AIBattleTeam(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val attackerSectName: String,
        val attackerIsRighteous: Boolean,
        val defenderSectId: String
    ) : MapItem

    data class BattleIndicator(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val isBattling: Boolean
    ) : MapItem
}

data class MapPathData(
    val fromId: String,
    val toId: String,
    val fromWorldX: Float,
    val fromWorldY: Float,
    val toWorldX: Float,
    val toWorldY: Float
)
