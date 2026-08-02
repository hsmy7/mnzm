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
        val defeated: Boolean,
        // 预计算妖兽属性（生成时含随机方差，用于战力显示）
        val beastMaxHp: Int = 0,
        val beastMaxMp: Int = 0,
        val beastPhysicalAttack: Int = 0,
        val beastMagicAttack: Int = 0,
        val beastPhysicalDefense: Int = 0,
        val beastMagicDefense: Int = 0,
        val beastSpeed: Int = 0
    ) : MapItem

    /** 远古秘境标记（精灵图，点击弹出详情） */
    data class SecretRealm(
        override val id: String,
        override val worldX: Float,
        override val worldY: Float,
        val name: String,
        val spawnYear: Int,
        val spriteIndex: Int
    ) : MapItem
}
