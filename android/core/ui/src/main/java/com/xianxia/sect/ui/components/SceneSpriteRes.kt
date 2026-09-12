package com.xianxia.sect.ui.components

// 地图/场景类精灵解析函数（物品域解析见 EquipmentSprite.kt）。

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

fun sectIconRes(level: Int): Int? =
    SpriteResRegistry.categoryResId(SpriteCategory.SECT_ICON, "sect_icon_$level")
        ?: SpriteResRegistry.categoryResId(SpriteCategory.SECT_ICON, "sect_icon_0")
            ?.takeIf { it != 0 }

/**
 * 通过 herbId 直接查找成长期精灵图资源ID（地图渲染用）。
 * 优先级：统一注册(ITEM分类,"growing_{herbId}") > fallbackToTier1 回退
 */
fun growingSpriteRes(herbId: String): Int? {
    return SpriteResRegistry.resolve("growing_$herbId")
        ?: run {
            val fallbackId = fallbackToTier1(herbId) ?: return null
            SpriteResRegistry.resolve("growing_$fallbackId")
        }
}
