package com.xianxia.sect.ui.components

import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.registry.HerbDatabase

fun equipmentSpriteRes(name: String): Int? =
    SpriteResRegistry.categoryResId(SpriteCategory.EQUIPMENT, name)

/**
 * 功法精灵图：按稀有度 key（manual_1..6）查找，无效稀有度回退到 1 品图
 * （与 [storageBagSpriteRes] 回退模式一致，防御邮件附件 rarity 缺失等边界）。
 */
fun manualSpriteRes(rarity: Int): Int? =
    SpriteResRegistry.categoryResId(SpriteCategory.MANUAL, "manual_$rarity")
        ?: SpriteResRegistry.categoryResId(SpriteCategory.MANUAL, "manual_1")
            ?.takeIf { it != 0 }

/**
 * 丹药精灵图：按稀有度 key（pill_1..6）查找，无效稀有度回退到 1 品图
 * （与 [storageBagSpriteRes] 回退模式一致，防御邮件附件 rarity 缺失等边界）。
 */
fun pillSpriteRes(rarity: Int): Int? =
    SpriteResRegistry.categoryResId(SpriteCategory.PILL, "pill_$rarity")
        ?: SpriteResRegistry.categoryResId(SpriteCategory.PILL, "pill_1")
            ?.takeIf { it != 0 }

fun spiritStoneSpriteRes(grade: SpiritStoneGrade = SpiritStoneGrade.LOW): Int? =
    SpriteResRegistry.categoryResId(SpriteCategory.SPIRIT_STONE, "spirit_stone_${grade.name.lowercase()}")
        ?.takeIf { it != 0 }

fun materialSpriteRes(name: String): Int? {
    val baseName = name.removePrefix("凡").removePrefix("灵")
        .removePrefix("宝").removePrefix("玄")
        .removePrefix("地").removePrefix("天")
    return SpriteResRegistry.categoryResId(SpriteCategory.MATERIAL, baseName)
}

/**
 * 将 tier2-6 的 herb/seed ID 回退到 tier1 等价物。
 * 例如 spiritGrass10 → spiritGrass1  （(10-1) % 3 + 1 = 1）
 * 例如 spiritFlower5 → spiritFlower2 （(5-1) % 3 + 1 = 2）
 */
fun fallbackToTier1(herbId: String): String? {
    val digits = herbId.takeLastWhile { it.isDigit() }
    // 空数字段（无尾缀数字）与 >9（Tier 4+ 无专属精灵图，不回退 → UI 显示"敬请期待"）
    // 同判无效
    val num = digits.toIntOrNull()?.takeIf { it <= 9 } ?: return null
    return herbId.dropLast(digits.length) + ((num - 1) % 3 + 1)
}

/**
 * 通过草药中文名查找草药精灵图资源ID。
 * 优先级：统一注册(ITEM分类,中文名) > fallbackToTier1 回退
 * 例如 "聚灵草" → R.drawable.herb_spiritgrass1
 */
fun herbSpriteRes(name: String): Int? {
    val herb = HerbDatabase.getHerbByName(name) ?: return null
    return SpriteResRegistry.resolve(name) ?: herbFallbackSpriteRes(herb.id)
}

/** 草药 tier1 回退解析：tier1 同类草药的中文名注册图 */
private fun herbFallbackSpriteRes(herbId: String): Int? =
    fallbackToTier1(herbId)
        ?.let { HerbDatabase.getHerbById(it) }
        ?.let { SpriteResRegistry.resolve(it.name) }

/**
 * 通过种子中文名查找种子精灵图资源ID。
 * 优先级：统一注册(ITEM分类,中文名) > fallbackToTier1 回退
 * 例如 "聚灵草种" → R.drawable.seed_spiritgrass1
 */
fun seedSpriteRes(seedName: String): Int? {
    val seed = HerbDatabase.getSeedByName(seedName) ?: return null
    return SpriteResRegistry.resolve(seedName) ?: seedFallbackSpriteRes(seed.id)
}

/** 种子 tier1 回退解析：种子 → 源草药 → tier1 草药 → tier1 种子名 */
private fun seedFallbackSpriteRes(seedId: String): Int? =
    HerbDatabase.getHerbIdFromSeedId(seedId)
        ?.let { fallbackToTier1(it) }
        ?.let { HerbDatabase.getSeedById("${it}Seed")?.name }
        ?.let { SpriteResRegistry.resolve(it) }

fun storageBagSpriteRes(rarity: Int): Int? =
    SpriteResRegistry.categoryResId(SpriteCategory.STORAGE_BAG, "bag_$rarity")
        ?: SpriteResRegistry.categoryResId(SpriteCategory.STORAGE_BAG, "bag_1")
            ?.takeIf { it != 0 }

/** 材料精灵解析 + 兜底： MATERIAL 首项作为缺图占位 */
private fun materialSpriteWithFallback(name: String): Int? =
    materialSpriteRes(name)
        ?: SpriteResRegistry.categoryResIds(SpriteCategory.MATERIAL).firstOrNull()

/** 灵石精灵解析：按显示名反查品级，未知名取低品 */
private fun spiritStoneSpriteResByName(displayName: String): Int? =
    spiritStoneSpriteRes(SpiritStoneGrade.fromDisplayName(displayName) ?: SpiritStoneGrade.LOW)

/**
 * 根据物品类型、名称和稀有度查找奖励卡片用的精灵图资源ID。
 * 由 UI 层在渲染 [RewardCardItem] 时调用。
 */
fun getRewardSprite(itemType: String, itemName: String, rarity: Int): Int? {
    return when (itemType) {
        "equipment" -> equipmentSpriteRes(itemName)
        "manual" -> manualSpriteRes(rarity)
        "pill" -> pillSpriteRes(rarity)
        "material" -> materialSpriteWithFallback(itemName)
        "herb" -> herbSpriteRes(itemName) ?: pillSpriteRes(rarity)
        "seed" -> seedSpriteRes(itemName)
            ?: SpriteResRegistry.categoryResIds(SpriteCategory.MATERIAL).firstOrNull()
        "spiritStones" -> spiritStoneSpriteResByName(itemName)
        "storageBag" -> storageBagSpriteRes(rarity)
        "beastMaterial" -> materialSpriteWithFallback(itemName)
        else -> null
    }
}
