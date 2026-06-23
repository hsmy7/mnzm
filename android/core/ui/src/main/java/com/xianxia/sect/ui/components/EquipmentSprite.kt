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
    var herbSprites: Map<String, Int> = emptyMap()
        private set
    var seedSprites: Map<String, Int> = emptyMap()
        private set
    var growingSprites: Map<String, Int> = emptyMap()
        private set

    fun initialize(
        equipmentSprites: Map<String, Int>,
        manualSprites: Map<Int, Int>,
        pillSprites: Map<Int, Int>,
        @DrawableRes spiritStoneRes: Int,
        materialSprites: Map<String, Int>,
        storageBagSprites: Map<Int, Int>,
        sectIconSprites: Map<Int, Int>,
        allEquipmentResIds: List<Int>,
        herbSprites: Map<String, Int> = emptyMap(),
        seedSprites: Map<String, Int> = emptyMap(),
        growingSprites: Map<String, Int> = emptyMap()
    ) {
        this.equipmentSprites = equipmentSprites
        this.manualSprites = manualSprites
        this.pillSprites = pillSprites
        this.spiritStoneRes = spiritStoneRes
        this.materialSprites = materialSprites
        this.storageBagSprites = storageBagSprites
        this.sectIconSprites = sectIconSprites
        this.allEquipmentResIds = allEquipmentResIds
        this.herbSprites = herbSprites
        this.seedSprites = seedSprites
        this.growingSprites = growingSprites
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

/**
 * 将 tier2-6 的 herb/seed ID 回退到 tier1 等价物。
 * 例如 spiritGrass10 → spiritGrass1  （(10-1) % 3 + 1 = 1）
 * 例如 spiritFlower5 → spiritFlower2 （(5-1) % 3 + 1 = 2）
 */
fun fallbackToTier1(herbId: String): String? {
    val digits = herbId.takeLastWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    val num = digits.toIntOrNull() ?: return null
    val tier1Num = ((num - 1) % 3) + 1
    return herbId.dropLast(digits.length) + tier1Num
}

/**
 * 通过草药中文名查找草药精灵图资源ID。
 * 例如 "聚灵草" → R.drawable.herb_spiritgrass1
 */
fun herbSpriteRes(name: String): Int? {
    val herb = com.xianxia.sect.core.registry.HerbDatabase.getHerbByName(name)
        ?: return null
    return SpriteResRegistry.herbSprites[herb.id]
        ?: SpriteResRegistry.herbSprites[fallbackToTier1(herb.id) ?: return null]
}

/**
 * 通过种子中文名查找种子精灵图资源ID。
 * 例如 "聚灵草种" → R.drawable.seed_spiritgrass1
 */
fun seedSpriteRes(seedName: String): Int? {
    val seed = com.xianxia.sect.core.registry.HerbDatabase.getSeedByName(seedName)
        ?: return null
    val herbId = com.xianxia.sect.core.registry.HerbDatabase.getHerbIdFromSeedId(seed.id)
        ?: return null
    return SpriteResRegistry.seedSprites[herbId]
        ?: SpriteResRegistry.seedSprites[fallbackToTier1(herbId) ?: return null]
}

/**
 * 通过 herbId 直接查找成长期精灵图资源ID（地图渲染用）。
 */
fun growingSpriteRes(herbId: String): Int? {
    return SpriteResRegistry.growingSprites[herbId]
        ?: SpriteResRegistry.growingSprites[fallbackToTier1(herbId) ?: return null]
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
        "herb" -> herbSpriteRes(itemName) ?: pillSpriteRes(rarity)
        "seed" -> seedSpriteRes(itemName)
            ?: SpriteResRegistry.materialSprites.values.firstOrNull()
        "spiritStones" -> spiritStoneSpriteRes()
        "storageBag" -> storageBagSpriteRes(rarity)
        "beastMaterial" -> materialSpriteRes(itemName)
            ?: SpriteResRegistry.materialSprites.values.firstOrNull()
        else -> null
    }
}
