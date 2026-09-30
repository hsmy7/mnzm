package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_HERB
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL_STACK
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MATERIAL
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_PILL
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_SEED
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import java.util.Locale

/** 袋条目模板重建结果（分派；装备条目随 B3 不再重建——实例条目由物化器原样保留） */
sealed interface ReconstructedBagStack {
    data class Manual(val stack: ManualStack) : ReconstructedBagStack
    // 注意：data class 名与模型类同名，属性类型必须用全限定名（否则自引用解析为本类）
    data class Pill(val stack: com.xianxia.sect.core.model.Pill) : ReconstructedBagStack
    data class Herb(val stack: com.xianxia.sect.core.model.Herb) : ReconstructedBagStack
    data class Seed(val stack: com.xianxia.sect.core.model.Seed) : ReconstructedBagStack
    data class Material(val stack: com.xianxia.sect.core.model.Material) : ReconstructedBagStack
}

/**
 * 袋条目 → 仓库堆叠重建（纯函数）。
 *
 * 堆叠类袋条目（manual_stack/pill/material/herb/seed）持有 name/rarity/quantity +
 * [com.xianxia.sect.core.model.BagStackedData] 元数据，但缺完整堆叠数据
 * （stats/category 等）——重建时按 name 查数据库模板补齐。
 *
 * 装备条目（equipment/equipment_stack）随 B3 退役堆叠语义，不再重建（返回 null）；
 * 完整实例条目（equipment_instance）由物化器按 payload 原样保留，不走本重建器。
 *
 * 重建规则（模板优先）：
 * 1. minRealm 用条目 stackedData 保真（保留赏赐时的实际门槛）
 * 2. quantity 用条目数量
 *
 * 找不到模板返回 null（调用方按丢弃处理）。
 */
object BagItemReconstructor {

    fun reconstruct(item: StorageBagItem): ReconstructedBagStack? {
        return when (item.itemType.lowercase(Locale.ROOT)) {
            ITEM_TYPE_MANUAL, ITEM_TYPE_MANUAL_STACK -> reconstructManual(item)
            ITEM_TYPE_PILL -> reconstructPill(item)
            ITEM_TYPE_HERB -> reconstructHerb(item)
            ITEM_TYPE_SEED -> reconstructSeed(item)
            ITEM_TYPE_MATERIAL -> reconstructMaterial(item)
            // 装备条目不重建（含 equipment/equipment_stack）：B3 起无堆叠语义
            else -> null
        }
    }

    private fun reconstructManual(item: StorageBagItem): ReconstructedBagStack? {
        val template = ManualDatabase.getByName(item.name) ?: return null
        val stack = ManualDatabase.createFromTemplate(template).copy(quantity = item.quantity.coerceAtLeast(1))
        return ReconstructedBagStack.Manual(stack)
    }

    private fun reconstructPill(item: StorageBagItem): ReconstructedBagStack? {
        val template = ItemDatabase.getPillById(item.itemId)
            ?: ItemDatabase.getPillByName(item.name)
            ?: return null
        val pill = ItemDatabase.createPillFromTemplate(template, quantity = item.quantity.coerceAtLeast(1))
        return ReconstructedBagStack.Pill(pill)
    }

    private fun reconstructHerb(item: StorageBagItem): ReconstructedBagStack? {
        val template = HerbDatabase.getHerbByName(item.name)
        val herb = Herb(
            name = item.name, rarity = item.rarity,
            description = template?.description ?: "", category = template?.category ?: "",
            quantity = item.quantity.coerceAtLeast(1)
        )
        return ReconstructedBagStack.Herb(herb)
    }

    private fun reconstructSeed(item: StorageBagItem): ReconstructedBagStack? {
        val template = HerbDatabase.getSeedByName(item.name)
        val seed = Seed(
            name = item.name, rarity = item.rarity,
            description = template?.description ?: "",
            growTime = template?.growTime ?: 0, quantity = item.quantity.coerceAtLeast(1)
        )
        return ReconstructedBagStack.Seed(seed)
    }

    private fun reconstructMaterial(item: StorageBagItem): ReconstructedBagStack? {
        val template = BeastMaterialDatabase.getMaterialByName(item.name)
        val category = try {
            MaterialCategory.valueOf(template?.category ?: "BEAST_HIDE")
        } catch (_: IllegalArgumentException) {
            MaterialCategory.BEAST_HIDE
        }
        val material = Material(
            name = item.name, rarity = item.rarity,
            description = template?.description ?: "", category = category,
            quantity = item.quantity.coerceAtLeast(1)
        )
        return ReconstructedBagStack.Material(material)
    }
}
