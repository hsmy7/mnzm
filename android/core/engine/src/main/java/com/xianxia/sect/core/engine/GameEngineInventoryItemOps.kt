package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed


fun GameEngine.createEquipmentStackFromRecipe(recipe: com.xianxia.sect.core.registry.ForgeRecipeDatabase
    .ForgeRecipe): EquipmentStack = inventoryFacade.createEquipmentStackFromRecipe(recipe)
fun GameEngine.createEquipmentStackFromMerchantItem(item: MerchantItem): EquipmentStack = inventoryFacade
    .createEquipmentStackFromMerchantItem(item)
fun GameEngine.createManualStackFromMerchantItem(item: MerchantItem): ManualStack = inventoryFacade
    .createManualStackFromMerchantItem(item)
fun GameEngine.createPillFromMerchantItem(item: MerchantItem): Pill = inventoryFacade.createPillFromMerchantItem(item)
fun GameEngine.createMaterialFromMerchantItem(item: MerchantItem): Material = inventoryFacade
    .createMaterialFromMerchantItem(item)
fun GameEngine.createHerbFromMerchantItem(item: MerchantItem): Herb = inventoryFacade.createHerbFromMerchantItem(item)
fun GameEngine.createSeedFromMerchantItem(item: MerchantItem): Seed = inventoryFacade.createSeedFromMerchantItem(item)
suspend fun GameEngine.openStorageBag(bagId: String): Pair<List<BattleRewardItem>,
    List<RewardCardItem>> = inventoryFacade.openStorageBag(bagId)
