package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import java.util.UUID

object MerchantItemConverter {
    fun toEquipment(item: MerchantItem): EquipmentStack {
        val template = EquipmentDatabase.getTemplateByName(item.name)
        if (template != null) {
            return EquipmentStack(
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

    fun toEquipmentBatch(item: MerchantItem, quantity: Int): EquipmentStack {
        val template = EquipmentDatabase.getTemplateByName(item.name)
        if (template != null) {
            return EquipmentStack(
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
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity),
                quantity = quantity
            )
        }
        return EquipmentDatabase.generateRandom(item.rarity, item.rarity).copy(
            id = UUID.randomUUID().toString(),
            rarity = item.rarity,
            quantity = quantity
        )
    }

    fun toManual(item: MerchantItem): ManualStack {
        val template = ManualDatabase.getByName(item.name)
        if (template != null) {
            val skillBuffsJson = template.skillBuffs.joinToString("|") { buff ->
                "${buff.type},${buff.value},${buff.duration}"
            }
            return ManualStack(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                type = template.type,
                stats = template.stats,
                skillName = template.skillName,
                skillDescription = template.skillDescription,
                skillType = template.skillType,
                skillDamageType = template.skillDamageType,
                skillHits = template.skillHits,
                skillDamageMultiplier = template.skillDamageMultiplier,
                skillCooldown = template.skillCooldown,
                skillMpCost = template.skillMpCost,
                skillHealPercent = template.skillHealPercent,
                skillHealType = template.skillHealType,
                skillBuffType = template.skillBuffType,
                skillBuffValue = template.skillBuffValue,
                skillBuffDuration = template.skillBuffDuration,
                skillBuffsJson = skillBuffsJson,
                skillIsAoe = template.skillIsAoe,
                skillTargetScope = template.skillTargetScope,
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
        val grade = item.grade?.let { gradeName ->
            PillGrade.entries.find { it.displayName == gradeName } ?: PillGrade.MEDIUM
        } ?: PillGrade.MEDIUM
        val template = PillRecipeDatabase.getRecipeByNameAndGrade(item.name, grade)
            ?: PillRecipeDatabase.getRecipeByName(item.name)
        if (template != null) {
            return Pill(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                quantity = 1,
                description = template.description,
                category = template.category,
                grade = grade,
                effects = PillEffect(
                    breakthroughChance = template.breakthroughChance,
                    targetRealm = template.targetRealm,
                    cultivationSpeedPercent = template.cultivationSpeedPercent,
                    duration = template.duration,
                    cultivationAdd = template.cultivationAdd,
                    skillExpAdd = template.skillExpAdd,
                    nurtureAdd = template.nurtureAdd,
                    extendLife = template.extendLife,
                    physicalAttackAdd = template.physicalAttackAdd,
                    magicAttackAdd = template.magicAttackAdd,
                    physicalDefenseAdd = template.physicalDefenseAdd,
                    magicDefenseAdd = template.magicDefenseAdd,
                    hpAdd = template.hpAdd,
                    mpAdd = template.mpAdd,
                    speedAdd = template.speedAdd,
                    critRateAdd = template.critRateAdd,
                    critEffectAdd = template.critEffectAdd,
                    intelligenceAdd = template.intelligenceAdd,
                    charmAdd = template.charmAdd,
                    loyaltyAdd = template.loyaltyAdd,
                    comprehensionAdd = template.comprehensionAdd,
                    artifactRefiningAdd = template.artifactRefiningAdd,
                    pillRefiningAdd = template.pillRefiningAdd,
                    spiritPlantingAdd = template.spiritPlantingAdd,
                    teachingAdd = template.teachingAdd,
                    moralityAdd = template.moralityAdd
                ),
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
        val randomPill = ItemDatabase.generateRandomPill(minRarity = item.rarity, maxRarity = item.rarity)
        return randomPill.copy(quantity = 1, grade = grade)
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
                val grade = item.grade?.let { gradeName ->
                    PillGrade.entries.find { it.displayName == gradeName } ?: PillGrade.MEDIUM
                } ?: PillGrade.MEDIUM
                CapacityCheckParams.PillParams(item.name, item.rarity, t?.category ?: PillCategory.FUNCTIONAL, grade)
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
        data class PillParams(val name: String, val rarity: Int, val category: PillCategory, val grade: PillGrade = PillGrade.MEDIUM) : CapacityCheckParams()
        data class MaterialParams(val name: String, val rarity: Int, val category: MaterialCategory) : CapacityCheckParams()
        data class HerbParams(val name: String, val rarity: Int, val category: String) : CapacityCheckParams()
        data class SeedParams(val name: String, val rarity: Int, val growTime: Int) : CapacityCheckParams()
        object Unknown : CapacityCheckParams()
    }
}
