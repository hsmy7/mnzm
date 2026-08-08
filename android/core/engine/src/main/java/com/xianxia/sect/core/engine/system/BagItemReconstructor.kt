package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_EQUIPMENT
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_EQUIPMENT_STACK
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_HERB
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL_STACK
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MATERIAL
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_PILL
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_SEED
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import java.util.Locale

/** 袋条目模板重建结果（6 类型分派） */
sealed interface ReconstructedBagStack {
    data class Equipment(val stack: EquipmentStack) : ReconstructedBagStack
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
 * D-03 独立存储后，堆叠类袋条目（equipment_stack/manual_stack/pill/material/
 * herb/seed）持有 name/rarity/quantity + [com.xianxia.sect.core.model.BagStackedData]
 * 元数据，但缺完整堆叠数据（stats/category 等）——重建时按 name 查数据库模板补齐。
 *
 * 与旧 confiscate 实现对齐（模板优先），改进两点：
 * 1. minRealm 用条目 stackedData 保真（旧逻辑按 rarity 推导，丢失赏赐时的实际门槛）
 * 2. quantity 用条目数量（旧逻辑硬编码 1）
 *
 * 找不到模板返回 null（调用方按丢弃处理）。
 */
object BagItemReconstructor {

    fun reconstruct(item: StorageBagItem): ReconstructedBagStack? {
        return when (item.itemType.lowercase(Locale.ROOT)) {
            ITEM_TYPE_EQUIPMENT, ITEM_TYPE_EQUIPMENT_STACK -> reconstructEquipment(item)
            ITEM_TYPE_MANUAL, ITEM_TYPE_MANUAL_STACK -> reconstructManual(item)
            ITEM_TYPE_PILL -> reconstructPill(item)
            ITEM_TYPE_HERB -> reconstructHerb(item)
            ITEM_TYPE_SEED -> reconstructSeed(item)
            ITEM_TYPE_MATERIAL -> reconstructMaterial(item)
            else -> null
        }
    }

    private fun reconstructEquipment(item: StorageBagItem): ReconstructedBagStack? {
        val template = EquipmentDatabase.getTemplateByName(item.name) ?: return null
        val quantity = item.quantity.coerceAtLeast(1)
        val stack = EquipmentStack(
            name = template.name, slot = template.slot, rarity = template.rarity,
            physicalAttack = template.physicalAttack, magicAttack = template.magicAttack,
            physicalDefense = template.physicalDefense, magicDefense = template.magicDefense,
            speed = template.speed, hp = template.hp, mp = template.mp,
            description = template.description,
            // minRealm 用条目 stackedData 保真；0（空 BagStackedData() 默认值）视为
            // "未记录"回退 rarity 推导——对抗性审查：偷盗等路径写空 stackedData 时
            // 0 非 null 不触发回退，重建后成为"最高境界门槛"装备
            minRealm = item.stackedData?.minRealm?.takeIf { it > 0 }
                ?: GameConfig.Realm.getMinRealmForRarity(template.rarity),
            quantity = quantity
        )
        return ReconstructedBagStack.Equipment(stack)
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
