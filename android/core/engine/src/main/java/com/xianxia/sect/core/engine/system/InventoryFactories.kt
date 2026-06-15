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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 仓库物品工厂方法
 * 从 InventorySystem.kt 提取的无状态纯函数
 */
@Singleton
class InventoryFactories @Inject constructor(
    private val converter: MerchantItemConverter
) {

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
        val eq = converter.toEquipment(item)
        return eq.copy(quantity = 1)
    }

    fun createManualFromMerchantItem(item: MerchantItem): ManualStack {
        val manual = converter.toManual(item)
        return manual.copy(quantity = 1)
    }

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        converter.toPill(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        converter.toMaterial(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        converter.toHerb(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        converter.toSeed(item)

    // -- 向后兼容：companion 桥接 --

    companion object {
        @Volatile
        private var _instance: InventoryFactories? = null

        internal fun initialize(instance: InventoryFactories) {
            _instance = instance
        }

        private val instance: InventoryFactories
            get() = _instance ?: InventoryFactories(MerchantItemConverter.companionInstance).also { _instance = it }

        fun createEquipmentFromRecipe(recipe: ForgeRecipe) = instance.createEquipmentFromRecipe(recipe)
        fun createEquipmentFromMerchantItem(item: MerchantItem) = instance.createEquipmentFromMerchantItem(item)
        fun createManualFromMerchantItem(item: MerchantItem) = instance.createManualFromMerchantItem(item)
        fun createPillFromMerchantItem(item: MerchantItem) = instance.createPillFromMerchantItem(item)
        fun createMaterialFromMerchantItem(item: MerchantItem) = instance.createMaterialFromMerchantItem(item)
        fun createHerbFromMerchantItem(item: MerchantItem) = instance.createHerbFromMerchantItem(item)
        fun createSeedFromMerchantItem(item: MerchantItem) = instance.createSeedFromMerchantItem(item)
    }
}
