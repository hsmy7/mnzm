package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed

/**
 * 仓库物品工厂方法
 * 从 InventorySystem.kt 提取的无状态纯函数
 */
object InventoryFactories {

    fun createEquipmentFromRecipe(recipe: ForgeRecipe): EquipmentStack {
        val template = EquipmentDatabase.getTemplateByName(recipe.name)
        if (template != null) {
            return EquipmentStack(
                id = java.util.UUID.randomUUID().toString(),
                name = template.name,
                slot = template.slot,
                rarity = recipe.rarity,
                physicalAttack = template.physicalAttack,
                magicAttack = template.magicAttack,
                physicalDefense = template.physicalDefense,
                magicDefense = template.magicDefense,
                speed = template.speed,
                hp = template.hp,
                mp = template.mp,
                description = template.description,
                minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity)
            )
        }
        return EquipmentDatabase.generateRandom(recipe.rarity, recipe.rarity).copy(
            id = java.util.UUID.randomUUID().toString(),
            rarity = recipe.rarity
        )
    }

    fun createEquipmentFromMerchantItem(item: MerchantItem): EquipmentStack {
        val eq = MerchantItemConverter.toEquipment(item)
        return eq.copy(quantity = 1)
    }

    fun createManualFromMerchantItem(item: MerchantItem): ManualStack {
        val manual = MerchantItemConverter.toManual(item)
        return manual.copy(quantity = 1)
    }

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        MerchantItemConverter.toPill(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        MerchantItemConverter.toMaterial(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        MerchantItemConverter.toHerb(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        MerchantItemConverter.toSeed(item)
}
