package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.Seed
import java.util.UUID

object MerchantItemConverter {
    fun toEquipment(item: MerchantItem): Equipment {
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

    fun toEquipmentBatch(item: MerchantItem, quantity: Int): List<Equipment> {
        val template = EquipmentDatabase.getTemplateByName(item.name)
        return (1..quantity).map {
            if (template != null) {
                Equipment(
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
            } else {
                EquipmentDatabase.generateRandom(item.rarity, item.rarity).copy(
                    id = UUID.randomUUID().toString(),
                    rarity = item.rarity
                )
            }
        }
    }

    fun toManual(item: MerchantItem): Manual {
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

    fun toPill(item: MerchantItem): Pill {
        val template = PillRecipeDatabase.getRecipeByName(item.name)
        if (template != null) {
            return Pill(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                quantity = 1,
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
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
        val randomPill = ItemDatabase.generateRandomPill(minRarity = item.rarity, maxRarity = item.rarity)
        return randomPill.copy(quantity = 1)
    }

    fun toMaterial(item: MerchantItem): Material {
        val template = BeastMaterialDatabase.getMaterialByName(item.name)
        if (template != null) {
            return Material(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                quantity = 1,
                description = template.description,
                category = try { MaterialCategory.valueOf(template.category) } catch (e: IllegalArgumentException) { MaterialCategory.BEAST_HIDE }
            )
        }
        val randomMaterial = ItemDatabase.generateRandomMaterial(minRarity = item.rarity, maxRarity = item.rarity)
        return randomMaterial.copy(quantity = 1)
    }

    fun toHerb(item: MerchantItem): Herb {
        val template = HerbDatabase.getHerbByName(item.name)
        if (template != null) {
            return Herb(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                category = template.category,
                quantity = 1
            )
        }
        val herbTemplate = HerbDatabase.generateRandomHerb(minRarity = item.rarity, maxRarity = item.rarity)
        return Herb(
            id = UUID.randomUUID().toString(),
            name = herbTemplate.name,
            rarity = herbTemplate.rarity,
            description = herbTemplate.description,
            category = herbTemplate.category,
            quantity = 1
        )
    }

    fun toSeed(item: MerchantItem): Seed {
        val template = HerbDatabase.getSeedByName(item.name)
        if (template != null) {
            return Seed(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                growTime = template.growTime,
                yield = template.yield,
                quantity = 1
            )
        }
        val seedTemplate = HerbDatabase.generateRandomSeed(minRarity = item.rarity, maxRarity = item.rarity)
        return Seed(
            id = UUID.randomUUID().toString(),
            name = seedTemplate.name,
            rarity = seedTemplate.rarity,
            description = seedTemplate.description,
            growTime = seedTemplate.growTime,
            yield = seedTemplate.yield,
            quantity = 1
        )
    }

    fun getCapacityCheckParams(item: MerchantItem): CapacityCheckParams {
        return when (item.type.lowercase()) {
            "equipment" -> CapacityCheckParams.EquipmentParams(item.rarity)
            "manual" -> {
                val t = ManualDatabase.getByName(item.name)
                CapacityCheckParams.ManualParams(item.name, item.rarity, t?.type ?: ManualType.SUPPORT)
            }
            "pill" -> {
                val t = PillRecipeDatabase.getRecipeByName(item.name)
                CapacityCheckParams.PillParams(item.name, item.rarity, t?.category ?: PillCategory.HEALING)
            }
            "material" -> {
                val t = BeastMaterialDatabase.getMaterialByName(item.name)
                val cat = t?.category?.let { try { MaterialCategory.valueOf(it) } catch (e: IllegalArgumentException) { MaterialCategory.BEAST_HIDE } } ?: MaterialCategory.BEAST_HIDE
                CapacityCheckParams.MaterialParams(item.name, item.rarity, cat)
            }
            "herb" -> {
                val t = HerbDatabase.getHerbByName(item.name)
                CapacityCheckParams.HerbParams(item.name, item.rarity, t?.category ?: "spirit")
            }
            "seed" -> {
                val t = HerbDatabase.getSeedByName(item.name)
                CapacityCheckParams.SeedParams(item.name, item.rarity, t?.growTime ?: 12)
            }
            else -> CapacityCheckParams.Unknown
        }
    }

    sealed class CapacityCheckParams {
        data class EquipmentParams(val rarity: Int) : CapacityCheckParams()
        data class ManualParams(val name: String, val rarity: Int, val type: ManualType) : CapacityCheckParams()
        data class PillParams(val name: String, val rarity: Int, val category: PillCategory) : CapacityCheckParams()
        data class MaterialParams(val name: String, val rarity: Int, val category: MaterialCategory) : CapacityCheckParams()
        data class HerbParams(val name: String, val rarity: Int, val category: String) : CapacityCheckParams()
        data class SeedParams(val name: String, val rarity: Int, val growTime: Int) : CapacityCheckParams()
        object Unknown : CapacityCheckParams()
    }
}
