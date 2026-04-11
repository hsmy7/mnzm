package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.system.InventorySystem
import java.util.UUID

/**
 * 库存服务 - 负责物品管理和仓库操作
 *
 * 职责域：
 * - 装备/功法/丹药/材料/灵草/种子的 StateFlow 管理
 * - 物品添加/移除/转移的统一入口
 * - 仓库整理和排序
 * - 物品创建辅助方法
 */
class InventoryService(
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val _manuals: MutableStateFlow<List<Manual>>,
    private val _pills: MutableStateFlow<List<Pill>>,
    private val _materials: MutableStateFlow<List<Material>>,
    private val _herbs: MutableStateFlow<List<Herb>>,
    private val _seeds: MutableStateFlow<List<Seed>>,
    private val inventorySystem: InventorySystem,
    private val addEvent: (String, EventType) -> Unit
) {
    companion object {
        private const val TAG = "InventoryService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get equipment StateFlow
     */
    fun getEquipment(): StateFlow<List<Equipment>> = _equipment

    /**
     * Get manuals StateFlow
     */
    fun getManuals(): StateFlow<List<Manual>> = _manuals

    /**
     * Get pills StateFlow
     */
    fun getPills(): StateFlow<List<Pill>> = _pills

    /**
     * Get materials StateFlow
     */
    fun getMaterials(): StateFlow<List<Material>> = _materials

    /**
     * Get herbs StateFlow
     */
    fun getHerbs(): StateFlow<List<Herb>> = _herbs

    /**
     * Get seeds StateFlow
     */
    fun getSeeds(): StateFlow<List<Seed>> = _seeds

    // ==================== 装备管理 ====================

    /**
     * Add equipment to warehouse
     */
    fun addEquipment(equipment: Equipment) {
        _equipment.value = _equipment.value + equipment
    }

    /**
     * Remove equipment by ID
     */
    fun removeEquipment(equipmentId: String): Boolean {
        val current = _equipment.value
        val filtered = current.filter { it.id != equipmentId }
        if (filtered.size < current.size) {
            _equipment.value = filtered
            return true
        }
        return false
    }

    /**
     * Create equipment from recipe template
     */
    fun createEquipmentFromRecipe(recipe: ForgeRecipe): Equipment {
        val template = EquipmentDatabase.getTemplateByName(recipe.name)
        if (template != null) {
            return Equipment(
                id = UUID.randomUUID().toString(),
                name = template.name,
                slot = template.slot,
                rarity = recipe.equipmentRarity,
                physicalAttack = template.physicalAttack,
                magicAttack = template.magicAttack,
                physicalDefense = template.physicalDefense,
                magicDefense = template.magicDefense,
                speed = template.speed,
                hp = template.hp,
                mp = template.mp,
                description = template.description,
                minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.equipmentRarity)
            )
        }
        return EquipmentDatabase.generateRandom(recipe.equipmentRarity, recipe.equipmentRarity).copy(
            id = UUID.randomUUID().toString(),
            rarity = recipe.equipmentRarity
        )
    }

    /**
     * Create equipment from merchant item
     */
    fun createEquipmentFromMerchantItem(item: MerchantItem): Equipment {
        val template = EquipmentDatabase.getTemplateByName(item.name)
        if (template != null) {
            return Equipment(
                id = UUID.randomUUID().toString(),
                name = template.name,
                slot = template.slot,
                rarity = item.rarity,
                physicalAttack = template.physicalAttack,
                magicAttack = template.magicAttack,
                physicalDefense = template.physicalDefense,
                magicDefense = template.magicDefense,
                speed = template.speed,
                hp = template.hp,
                mp = template.mp,
                description = template.description,
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
        return EquipmentDatabase.generateRandom(item.rarity, item.rarity).copy(
            id = UUID.randomUUID().toString(),
            rarity = item.rarity
        )
    }

    // ==================== 功法管理 ====================

    /**
     * Add manual to warehouse
     */
    fun addManualToWarehouse(manual: Manual) {
        val currentManuals = _manuals.value
        val existingIndex = currentManuals.indexOfFirst { it.name == manual.name && it.rarity == manual.rarity }

        if (existingIndex >= 0) {
            // Stack with existing manual
            val existing = currentManuals[existingIndex]
            val updated = existing.copy(quantity = existing.quantity + manual.quantity)
            _manuals.value = currentManuals.toMutableList().also { it[existingIndex] = updated }
        } else {
            // Add new manual
            _manuals.value = currentManuals + manual
        }
    }

    /**
     * Remove manual from warehouse
     */
    fun removeManual(manualId: String, quantity: Int = 1): Boolean {
        val currentManuals = _manuals.value
        // Locate manual by ID to resolve its stacking identity (name + rarity),
        // then operate on the stack matched by name+rarity (consistent with addManualToWarehouse).
        val targetManual = currentManuals.find { it.id == manualId } ?: return false
        val index = currentManuals.indexOfFirst { it.name == targetManual.name && it.rarity == targetManual.rarity }
        if (index < 0) return false

        val manual = currentManuals[index]
        if (manual.quantity < quantity) return false

        val updatedQuantity = manual.quantity - quantity
        if (updatedQuantity <= 0) {
            _manuals.value = currentManuals.filter { it.name != targetManual.name || it.rarity != targetManual.rarity }
        } else {
            _manuals.value = currentManuals.toMutableList().also {
                it[index] = manual.copy(quantity = updatedQuantity)
            }
        }
        return true
    }

    /**
     * Create manual from merchant item
     */
    fun createManualFromMerchantItem(item: MerchantItem): Manual {
        val template = ManualDatabase.getByName(item.name)
        if (template != null) {
            return Manual(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                type = template.type,
                stats = template.stats,
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity),
                quantity = 1
            )
        }
        return ManualDatabase.generateRandom(item.rarity, item.rarity).copy(
            id = UUID.randomUUID().toString(),
            rarity = item.rarity
        )
    }

    // ==================== 丹药管理 ====================

    /**
     * Add pill to warehouse
     */
    fun addPillToWarehouse(pill: Pill) {
        val currentPills = _pills.value
        val existingIndex = currentPills.indexOfFirst { it.name == pill.name && it.rarity == pill.rarity }

        if (existingIndex >= 0) {
            // Stack with existing pill
            val existing = currentPills[existingIndex]
            val updated = existing.copy(quantity = existing.quantity + pill.quantity)
            _pills.value = currentPills.toMutableList().also { it[existingIndex] = updated }
        } else {
            // Add new pill
            _pills.value = currentPills + pill
        }
    }

    /**
     * Remove pill from warehouse
     */
    fun removePill(pillId: String, quantity: Int = 1): Boolean {
        val currentPills = _pills.value
        // Locate pill by ID to resolve its stacking identity (name + rarity),
        // then operate on the stack matched by name+rarity (consistent with addPillToWarehouse).
        val targetPill = currentPills.find { it.id == pillId } ?: return false
        val index = currentPills.indexOfFirst { it.name == targetPill.name && it.rarity == targetPill.rarity }
        if (index < 0) return false

        val pill = currentPills[index]
        if (pill.quantity < quantity) return false

        val updatedQuantity = pill.quantity - quantity
        if (updatedQuantity <= 0) {
            _pills.value = currentPills.filter { it.name != targetPill.name || it.rarity != targetPill.rarity }
        } else {
            _pills.value = currentPills.toMutableList().also {
                it[index] = pill.copy(quantity = updatedQuantity)
            }
        }
        return true
    }

    /**
     * Create pill from merchant item
     */
    fun createPillFromMerchantItem(item: MerchantItem): Pill {
        val template = PillRecipeDatabase.getRecipeByName(item.name)
        if (template != null) {
            return Pill(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = template.rarity,
                quantity = item.quantity,
                description = template.description,
                category = template.category,
                breakthroughChance = template.breakthroughChance,
                targetRealm = template.targetRealm,
                cultivationSpeed = template.cultivationSpeed,
                duration = template.effectDuration,
                cultivationPercent = template.cultivationPercent,
                skillExpPercent = template.skillExpPercent,
                extendLife = template.extendLife,
                physicalAttackPercent = template.physicalAttackPercent,
                magicAttackPercent = template.magicAttackPercent,
                physicalDefensePercent = template.physicalDefensePercent,
                magicDefensePercent = template.magicDefensePercent,
                hpPercent = template.hpPercent,
                mpPercent = template.mpPercent,
                speedPercent = template.speedPercent,
                healPercent = template.healPercent,
                healMaxHpPercent = template.healMaxHpPercent,
                heal = template.heal,
                battleCount = template.battleCount,
                mpRecoverMaxMpPercent = template.mpRecoverMaxMpPercent,
                minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
            )
        }

        // Fallback to random generation
        val randomPill = ItemDatabase.generateRandomPill(
            minRarity = item.rarity,
            maxRarity = item.rarity
        )
        return randomPill.copy(quantity = item.quantity)
    }

    // ==================== 材料管理 ====================

    /**
     * Add material to warehouse
     */
    fun addMaterialToWarehouse(material: Material) {
        val currentMaterials = _materials.value
        val existingIndex = currentMaterials.indexOfFirst { it.name == material.name && it.rarity == material.rarity }

        if (existingIndex >= 0) {
            // Stack with existing material
            val existing = currentMaterials[existingIndex]
            val updated = existing.copy(quantity = existing.quantity + material.quantity)
            _materials.value = currentMaterials.toMutableList().also { it[existingIndex] = updated }
        } else {
            // Add new material
            _materials.value = currentMaterials + material
        }
    }

    /**
     * Remove material from warehouse
     */
    fun removeMaterial(materialId: String, quantity: Int = 1): Boolean {
        val currentMaterials = _materials.value
        // Locate material by ID to resolve its stacking identity (name + rarity),
        // then operate on the stack matched by name+rarity (consistent with addMaterialToWarehouse).
        val targetMaterial = currentMaterials.find { it.id == materialId } ?: return false
        val index = currentMaterials.indexOfFirst { it.name == targetMaterial.name && it.rarity == targetMaterial.rarity }
        if (index < 0) return false

        val material = currentMaterials[index]
        if (material.quantity < quantity) return false

        val updatedQuantity = material.quantity - quantity
        if (updatedQuantity <= 0) {
            _materials.value = currentMaterials.filter { it.name != targetMaterial.name || it.rarity != targetMaterial.rarity }
        } else {
            _materials.value = currentMaterials.toMutableList().also {
                it[index] = material.copy(quantity = updatedQuantity)
            }
        }
        return true
    }

    /**
     * Create material from merchant item
     */
    fun createMaterialFromMerchantItem(item: MerchantItem): Material {
        val template = BeastMaterialDatabase.getMaterialByName(item.name)
        if (template != null) {
            return Material(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                quantity = item.quantity,
                description = template.description,
                category = try { MaterialCategory.valueOf(template.category) } catch (e: IllegalArgumentException) { MaterialCategory.BEAST_HIDE }
            )
        }

        // Fallback to random generation
        val randomMaterial = ItemDatabase.generateRandomMaterial(
            minRarity = item.rarity,
            maxRarity = item.rarity
        )
        return randomMaterial.copy(quantity = item.quantity)
    }

    // ==================== 灵草管理 ====================

    /**
     * Add herb to warehouse
     */
    fun addHerbToWarehouse(herb: Herb) {
        val currentHerbs = _herbs.value
        val existingIndex = currentHerbs.indexOfFirst { it.name == herb.name && it.rarity == herb.rarity }

        if (existingIndex >= 0) {
            // Stack with existing herb
            val existing = currentHerbs[existingIndex]
            val updated = existing.copy(quantity = existing.quantity + herb.quantity)
            _herbs.value = currentHerbs.toMutableList().also { it[existingIndex] = updated }
        } else {
            // Add new herb
            _herbs.value = currentHerbs + herb
        }
    }

    /**
     * Remove herb from warehouse
     */
    fun removeHerb(herbId: String, quantity: Int = 1): Boolean {
        val currentHerbs = _herbs.value
        // Locate herb by ID to resolve its stacking identity (name + rarity),
        // then operate on the stack matched by name+rarity (consistent with addHerbToWarehouse).
        val targetHerb = currentHerbs.find { it.id == herbId } ?: return false
        val index = currentHerbs.indexOfFirst { it.name == targetHerb.name && it.rarity == targetHerb.rarity }
        if (index < 0) return false

        val herb = currentHerbs[index]
        if (herb.quantity < quantity) return false

        val updatedQuantity = herb.quantity - quantity
        if (updatedQuantity <= 0) {
            _herbs.value = currentHerbs.filter { it.name != targetHerb.name || it.rarity != targetHerb.rarity }
        } else {
            _herbs.value = currentHerbs.toMutableList().also {
                it[index] = herb.copy(quantity = updatedQuantity)
            }
        }
        return true
    }

    /**
     * Create herb from merchant item
     */
    fun createHerbFromMerchantItem(item: MerchantItem): Herb {
        val template = HerbDatabase.getHerbByName(item.name)
        if (template != null) {
            return Herb(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                category = template.category,
                quantity = item.quantity
            )
        }

        // Fallback to random generation
        val herbTemplate = HerbDatabase.generateRandomHerb(
            minRarity = item.rarity,
            maxRarity = item.rarity
        )
        return Herb(
            id = UUID.randomUUID().toString(),
            name = herbTemplate.name,
            rarity = herbTemplate.rarity,
            description = herbTemplate.description,
            category = herbTemplate.category,
            quantity = item.quantity
        )
    }

    // ==================== 种子管理 ====================

    /**
     * Add seed to warehouse
     */
    fun addSeedToWarehouse(seed: Seed) {
        val currentSeeds = _seeds.value
        val existingIndex = currentSeeds.indexOfFirst { it.name == seed.name && it.rarity == seed.rarity }

        if (existingIndex >= 0) {
            // Stack with existing seed
            val existing = currentSeeds[existingIndex]
            val updated = existing.copy(quantity = existing.quantity + seed.quantity)
            _seeds.value = currentSeeds.toMutableList().also { it[existingIndex] = updated }
        } else {
            // Add new seed
            _seeds.value = currentSeeds + seed
        }
    }

    /**
     * Remove seed from warehouse
     */
    fun removeSeed(seedId: String, quantity: Int = 1): Boolean {
        val currentSeeds = _seeds.value
        // Locate seed by ID to resolve its stacking identity (name + rarity),
        // then operate on the stack matched by name+rarity (consistent with addSeedToWarehouse).
        val targetSeed = currentSeeds.find { it.id == seedId } ?: return false
        val index = currentSeeds.indexOfFirst { it.name == targetSeed.name && it.rarity == targetSeed.rarity }
        if (index < 0) return false

        val seed = currentSeeds[index]
        if (seed.quantity < quantity) return false

        val updatedQuantity = seed.quantity - quantity
        if (updatedQuantity <= 0) {
            _seeds.value = currentSeeds.filter { it.name != targetSeed.name || it.rarity != targetSeed.rarity }
        } else {
            _seeds.value = currentSeeds.toMutableList().also {
                it[index] = seed.copy(quantity = updatedQuantity)
            }
        }
        return true
    }

    /**
     * Create seed from merchant item
     */
    fun createSeedFromMerchantItem(item: MerchantItem): Seed {
        val template = HerbDatabase.getSeedByName(item.name)
        if (template != null) {
            return Seed(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                growTime = template.growTime,
                yield = template.yield,
                quantity = item.quantity
            )
        }

        // Fallback to random generation
        val seedTemplate = HerbDatabase.generateRandomSeed(
            minRarity = item.rarity,
            maxRarity = item.rarity
        )
        return Seed(
            id = UUID.randomUUID().toString(),
            name = seedTemplate.name,
            rarity = seedTemplate.rarity,
            description = seedTemplate.description,
            growTime = seedTemplate.growTime,
            yield = seedTemplate.yield,
            quantity = item.quantity
        )
    }

    // ==================== 仓库管理 ====================

    /**
     * Sort all warehouse items by rarity and name
     */
    fun sortWarehouse() {
        _equipment.value = _equipment.value.sortedWith(
            compareByDescending<Equipment> { it.rarity }.thenBy { it.name }
        )
        _manuals.value = _manuals.value.sortedWith(
            compareByDescending<Manual> { it.rarity }.thenBy { it.name }
        )
        _pills.value = _pills.value.sortedWith(
            compareByDescending<Pill> { it.rarity }.thenBy { it.name }
        )
        _materials.value = _materials.value.sortedWith(
            compareByDescending<Material> { it.rarity }.thenBy { it.name }
        )
        _herbs.value = _herbs.value.sortedWith(
            compareByDescending<Herb> { it.rarity }.thenBy { it.name }
        )
        _seeds.value = _seeds.value.sortedWith(
            compareByDescending<Seed> { it.rarity }.thenBy { it.name }
        )

        addEvent("仓库整理完成", EventType.INFO)
    }

    // ==================== 统计查询 ====================

    /**
     * Get total count of all items in warehouse
     */
    fun getTotalItemCount(): Int {
        return _equipment.value.size +
               _manuals.value.size +
               _pills.value.size +
               _materials.value.size +
               _herbs.value.size +
               _seeds.value.size
    }

    /**
     * Get item count by type
     */
    fun getItemCountByType(type: String): Int {
        return when (type.lowercase(java.util.Locale.getDefault())) {
            "equipment" -> _equipment.value.size
            "manual" -> _manuals.value.size
            "pill" -> _pills.value.size
            "material" -> _materials.value.size
            "herb" -> _herbs.value.size
            "seed" -> _seeds.value.size
            else -> 0
        }
    }

    /**
     * Check if player has enough spirit stones
     */
    fun hasEnoughSpiritStones(currentStones: Long, required: Long): Boolean {
        return currentStones >= required
    }

    /**
     * Deduct spirit stones
     */
    fun deductSpiritStones(_gameData: MutableStateFlow<GameData>, amount: Long): Long {
        val data = _gameData.value
        val newAmount = (data.spiritStones - amount).coerceAtLeast(0)
        _gameData.value = data.copy(spiritStones = newAmount)
        return newAmount
    }

    /**
     * Add spirit stones
     */
    fun addSpiritStones(_gameData: MutableStateFlow<GameData>, amount: Long): Long {
        val data = _gameData.value
        val newAmount = data.spiritStones + amount
        _gameData.value = data.copy(spiritStones = newAmount)
        return newAmount
    }
}
