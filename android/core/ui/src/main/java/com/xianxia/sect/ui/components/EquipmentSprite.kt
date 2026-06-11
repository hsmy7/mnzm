package com.xianxia.sect.ui.components

import androidx.annotation.DrawableRes

/**
 * Registry for sprite drawable resource IDs.
 * The app module must call [initialize] at startup to provide the actual R.drawable values,
 * since library modules cannot reference the app module's R class.
 */
object SpriteResRegistry {
    var equipmentSprites: Map<String, Int> = emptyMap()
        private set
    var manualSprites: Map<Int, Int> = emptyMap()
        private set
    var pillSprites: Map<Int, Int> = emptyMap()
        private set
    @DrawableRes var spiritStoneRes: Int = 0
        private set
    var materialSprites: Map<String, Int> = emptyMap()
        private set
    var storageBagSprites: Map<Int, Int> = emptyMap()
        private set
    var sectIconSprites: Map<Int, Int> = emptyMap()
        private set
    var allEquipmentResIds: List<Int> = emptyList()
        private set

    fun initialize(
        equipmentSprites: Map<String, Int>,
        manualSprites: Map<Int, Int>,
        pillSprites: Map<Int, Int>,
        @DrawableRes spiritStoneRes: Int,
        materialSprites: Map<String, Int>,
        storageBagSprites: Map<Int, Int>,
        sectIconSprites: Map<Int, Int>,
        allEquipmentResIds: List<Int>
    ) {
        this.equipmentSprites = equipmentSprites
        this.manualSprites = manualSprites
        this.pillSprites = pillSprites
        this.spiritStoneRes = spiritStoneRes
        this.materialSprites = materialSprites
        this.storageBagSprites = storageBagSprites
        this.sectIconSprites = sectIconSprites
        this.allEquipmentResIds = allEquipmentResIds
    }
}

fun equipmentSpriteRes(name: String): Int? = SpriteResRegistry.equipmentSprites[name]

fun manualSpriteRes(rarity: Int): Int? = SpriteResRegistry.manualSprites[rarity]

fun pillSpriteRes(rarity: Int): Int? = SpriteResRegistry.pillSprites[rarity]

fun spiritStoneSpriteRes(): Int? = SpriteResRegistry.spiritStoneRes.takeIf { it != 0 }

fun materialSpriteRes(name: String): Int? {
    val baseName = name.removePrefix("凡").removePrefix("灵")
        .removePrefix("宝").removePrefix("玄")
        .removePrefix("地").removePrefix("天")
    return SpriteResRegistry.materialSprites[baseName]
}

fun allPillSpriteResIds(): List<Int> = (1..6).mapNotNull { pillSpriteRes(it) }

fun allManualSpriteResIds(): List<Int> = (1..6).mapNotNull { manualSpriteRes(it) }

fun storageBagSpriteRes(rarity: Int): Int? = SpriteResRegistry.storageBagSprites[rarity]
    ?: SpriteResRegistry.storageBagSprites[1]?.takeIf { it != 0 }

fun sectIconRes(level: Int): Int? = SpriteResRegistry.sectIconSprites[level]
    ?: SpriteResRegistry.sectIconSprites[0]?.takeIf { it != 0 }

fun allEquipmentSpriteResIds(): List<Int> = SpriteResRegistry.allEquipmentResIds

/**
 * 根据物品类型、名称和稀有度查找奖励卡片用的精灵图资源ID。
 * 由 UI 层在渲染 [RewardCardItem] 时调用。
 */
fun getRewardSprite(itemType: String, itemName: String, rarity: Int): Int? {
    return when (itemType) {
        "equipment" -> equipmentSpriteRes(itemName)
        "manual" -> manualSpriteRes(rarity)
        "pill" -> pillSpriteRes(rarity)
        "material" -> materialSpriteRes(itemName)
            ?: SpriteResRegistry.materialSprites.values.firstOrNull()
        "herb" -> pillSpriteRes(rarity)
        "spiritStones" -> spiritStoneSpriteRes()
        "storageBag" -> storageBagSpriteRes(rarity)
        else -> null
    }
}
