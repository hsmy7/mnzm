package com.xianxia.sect.ui.components

import androidx.annotation.DrawableRes
import com.xianxia.sect.core.model.SpiritStoneGrade

/**
 * 精灵图分类 — 决定预加载优先级。
 * priority: 0 = L0（首屏必须），1 = L1（重要界面），2 = L2（后台加载）
 */
enum class SpriteCategory(val priority: Int) {
    UI(0),
    PORTRAIT(0),
    ITEM(1),
    BUILDING(1),
    BEAST(2),
    CAVE(2),
    HEAVENLY_TRIAL(2),
    BACKGROUND(1),
    MAP(1)
}

/**
 * Registry for sprite drawable resource IDs.
 * The app module must call [initialize] at startup to provide the actual R.drawable values,
 * since library modules cannot reference the app module's R class.
 *
 * 新增精灵图分类通过 [register] 统一注册，通过 [resolve] 按名称查找，
 * 通过 [categoryResIds] 供 ResourcePreloader 自动发现。
 */
object SpriteResRegistry {

    /** 按分类存储 name→resId 映射（统一注册入口） */
    private val categoryMaps = mutableMapOf<SpriteCategory, Map<String, Int>>()

    /**
     * 注册一个精灵图分类下的所有资源映射。
     * 应在 [XianxiaApplication.onCreate] 中调用。
     */
    fun register(category: SpriteCategory, sprites: Map<String, Int>) {
        categoryMaps[category] = sprites
    }

    /**
     * 遍历所有已注册分类，按名称查找精灵图资源 ID。
     * @return 找到的第一个匹配 resId，未找到返回 null
     */
    fun resolve(name: String): Int? {
        for ((_, sprites) in categoryMaps) {
            sprites[name]?.let { return it }
        }
        // 回退到旧版分类映射
        equipmentSprites[name]?.let { return it }
        materialSprites[name]?.let { return it }
        herbSprites[name]?.let { return it }
        seedSprites[name]?.let { return it }
        growingSprites[name]?.let { return it }
        return null
    }

    /**
     * 获取指定分类下的所有资源 ID。
     * 供 ResourcePreloader 按分类预加载。
     */
    fun categoryResIds(category: SpriteCategory): List<Int> =
        categoryMaps[category]?.values?.toList() ?: emptyList()

    /**
     * 获取所有已注册分类的精灵图资源 ID（去重）。
     */
    fun allResIds(): List<Int> =
        categoryMaps.values.flatMap { it.values }.distinct()
    var equipmentSprites: Map<String, Int> = emptyMap()
        private set
    var manualSprites: Map<Int, Int> = emptyMap()
        private set
    var pillSprites: Map<Int, Int> = emptyMap()
        private set
    var spiritStoneSprites: Map<SpiritStoneGrade, Int> = emptyMap()
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
        spiritStoneSprites: Map<SpiritStoneGrade, Int>,
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
        this.spiritStoneSprites = spiritStoneSprites
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

fun spiritStoneSpriteRes(grade: SpiritStoneGrade = SpiritStoneGrade.LOW): Int? =
    SpriteResRegistry.spiritStoneSprites[grade]?.takeIf { it != 0 }

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
    if (num > 6) return null  // Tier 3+ 无专属精灵图，不回退，返回 null → UI 显示"敬请期待"
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

/** 通过 beastType 索引查找妖兽精灵图资源 ID（0=tiger, 1=wolf, ...） */
private val beastNames =
    listOf("tiger", "wolf", "snake", "bear", "eagle", "fox", "dragon", "turtle")

fun beastSpriteRes(beastType: Int): Int? {
    val name = beastNames.getOrNull(beastType) ?: return null
    return SpriteResRegistry.resolve(name)
}

/** 通过洞穴索引查找洞穴精灵图资源 ID */
fun caveSpriteRes(index: Int): Int? {
    val name = "cave_${index + 1}"
    return SpriteResRegistry.resolve(name)
}

/** 通过名称查找天劫试炼精灵图资源 ID */
fun heavenlyTrialSpriteRes(name: String): Int? =
    SpriteResRegistry.resolve(name)

/** 通过名称查找背景精灵图资源 ID */
fun backgroundRes(name: String): Int? =
    SpriteResRegistry.resolve(name)

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
        "spiritStones" -> {
            val grade = when {
                itemName.contains("上品") -> SpiritStoneGrade.HIGH
                itemName.contains("中品") -> SpiritStoneGrade.MID
                else -> SpiritStoneGrade.LOW
            }
            spiritStoneSpriteRes(grade)
        }
        "storageBag" -> storageBagSpriteRes(rarity)
        "beastMaterial" -> materialSpriteRes(itemName)
            ?: SpriteResRegistry.materialSprites.values.firstOrNull()
        else -> null
    }
}
