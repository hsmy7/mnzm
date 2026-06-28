package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.serialization.NullSafeProtoBuf

internal class DiscipleConverter {

    fun convertDisciple(disciple: com.xianxia.sect.core.model.Disciple): SerializableDisciple {
        return SerializableDisciple(
            id = NullSafeProtoBuf.stringToProto(disciple.id),
            name = NullSafeProtoBuf.stringToProto(disciple.name),
            surname = NullSafeProtoBuf.stringToProto(disciple.surname),
            realm = disciple.realm ?: 0,
            realmLayer = disciple.realmLayer ?: 0,
            cultivation = disciple.cultivation ?: 0.0,
            spiritRootType = NullSafeProtoBuf.stringToProto(disciple.spiritRootType),
            age = disciple.age ?: 0,
            lifespan = disciple.lifespan ?: 0,
            isAlive = disciple.isAlive ?: true,
            gender = NullSafeProtoBuf.stringToProto(disciple.gender, "男"),
            partnerId = NullSafeProtoBuf.relationIdToProto(disciple.social.partnerId),
            partnerSectId = NullSafeProtoBuf.relationIdToProto(disciple.social.partnerSectId),
            parentId1 = NullSafeProtoBuf.relationIdToProto(disciple.social.parentId1),
            parentId2 = NullSafeProtoBuf.relationIdToProto(disciple.social.parentId2),
            lastChildYear = disciple.social.lastChildYear ?: 0,
            griefEndYear = NullSafeProtoBuf.griefEndYearToProto(disciple.social.griefEndYear),
            weaponId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.weaponId),
            armorId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.armorId),
            bootsId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.bootsId),
            accessoryId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.accessoryId),
            manualIds = NullSafeProtoBuf.listToProto(disciple.manualIds),
            talentIds = NullSafeProtoBuf.listToProto(disciple.talentIds),
            manualMasteries = NullSafeProtoBuf.mapToProto(disciple.manualMasteries),
            weaponNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.weaponNurture),
            armorNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.armorNurture),
            bootsNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.bootsNurture),
            accessoryNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.accessoryNurture),
            spiritStones = disciple.equipment.spiritStones ?: 0,
            soulPower = disciple.soulPower,
            storageBagItems = NullSafeProtoBuf.listToProto(disciple.equipment.storageBagItems)?.map { convertStorageBagItem(it) } ?: emptyList(),
            storageBagSpiritStones = disciple.equipment.storageBagSpiritStones ?: 0L,
            status = disciple.status.name,
            statusData = NullSafeProtoBuf.mapToProto(disciple.statusData),
            cultivationSpeedBonus = disciple.cultivationSpeedBonus ?: 0.0,
            cultivationSpeedDuration = disciple.cultivationSpeedDuration ?: 0,
            pillPhysicalAttackBonus = disciple.pillEffects.pillPhysicalAttackBonus ?: 0,
            pillMagicAttackBonus = disciple.pillEffects.pillMagicAttackBonus ?: 0,
            pillPhysicalDefenseBonus = disciple.pillEffects.pillPhysicalDefenseBonus ?: 0,
            pillMagicDefenseBonus = disciple.pillEffects.pillMagicDefenseBonus ?: 0,
            pillHpBonus = disciple.pillEffects.pillHpBonus ?: 0,
            pillMpBonus = disciple.pillEffects.pillMpBonus ?: 0,
            pillSpeedBonus = disciple.pillEffects.pillSpeedBonus ?: 0,
            pillCritRateBonus = disciple.pillEffects.pillCritRateBonus ?: 0.0,
            pillCritEffectBonus = disciple.pillEffects.pillCritEffectBonus ?: 0.0,
            pillCultivationSpeedBonus = disciple.pillEffects.pillCultivationSpeedBonus ?: 0.0,
            pillSkillExpSpeedBonus = disciple.pillEffects.pillSkillExpSpeedBonus ?: 0.0,
            pillNurtureSpeedBonus = disciple.pillEffects.pillNurtureSpeedBonus ?: 0.0,
            pillEffectDuration = disciple.pillEffects.pillEffectDuration ?: 0,
            activePillCategory = disciple.pillEffects.activePillCategory ?: "",
            totalCultivation = disciple.combat.totalCultivation ?: 0L,
            breakthroughCount = disciple.combat.breakthroughCount ?: 0,
            breakthroughFailCount = disciple.combat.breakthroughFailCount ?: 0,
            intelligence = disciple.skills.intelligence ?: 0,
            charm = disciple.skills.charm ?: 0,
            loyalty = disciple.skills.loyalty ?: 0,
            comprehension = disciple.skills.comprehension ?: 0,
            artifactRefining = disciple.skills.artifactRefining ?: 0,
            pillRefining = disciple.skills.pillRefining ?: 0,
            spiritPlanting = disciple.skills.spiritPlanting ?: 0,
            mining = disciple.skills.mining ?: 0,
            teaching = disciple.skills.teaching ?: 0,
            morality = disciple.skills.morality ?: 0,
            salaryPaidCount = disciple.skills.salaryPaidCount ?: 0,
            salaryMissedCount = disciple.skills.salaryMissedCount ?: 0,
            recruitedMonth = disciple.usage.recruitedMonth ?: 0,
            hpVariance = disciple.combat.hpVariance ?: 0,
            mpVariance = disciple.combat.mpVariance ?: 0,
            physicalAttackVariance = disciple.combat.physicalAttackVariance ?: 0,
            magicAttackVariance = disciple.combat.magicAttackVariance ?: 0,
            physicalDefenseVariance = disciple.combat.physicalDefenseVariance ?: 0,
            magicDefenseVariance = disciple.combat.magicDefenseVariance ?: 0,
            speedVariance = disciple.combat.speedVariance ?: 0,
            baseHp = disciple.combat.baseHp ?: 0,
            baseMp = disciple.combat.baseMp ?: 0,
            basePhysicalAttack = disciple.combat.basePhysicalAttack ?: 0,
            baseMagicAttack = disciple.combat.baseMagicAttack ?: 0,
            basePhysicalDefense = disciple.combat.basePhysicalDefense ?: 0,
            baseMagicDefense = disciple.combat.baseMagicDefense ?: 0,
            baseSpeed = disciple.combat.baseSpeed ?: 0,
            discipleType = NullSafeProtoBuf.stringToProto(disciple.discipleType, "outer"),
            usedFunctionalPillTypes = NullSafeProtoBuf.listToProto(disciple.usage.usedFunctionalPillTypes),
            usedExtendLifePillIds = NullSafeProtoBuf.listToProto(disciple.usage.usedExtendLifePillIds),
            usedPermanentPillKeys = disciple.usage.usedPermanentPillKeys.toList(),
            usedExtendLifePillTypes = disciple.usage.usedExtendLifePillTypes.toList(),
            activePillTypes = disciple.pillEffects.activePillTypes.toList(),
            hasReviveEffect = disciple.usage.hasReviveEffect ?: false,
            hasClearAllEffect = disciple.usage.hasClearAllEffect ?: false,
            currentHp = disciple.combat.currentHp,
            currentMp = disciple.combat.currentMp
        )
    }

    fun convertBackDisciple(data: SerializableDisciple): com.xianxia.sect.core.model.Disciple {
        val weaponId = NullSafeProtoBuf.equipmentIdFromProto(data.weaponId)
        val armorId = NullSafeProtoBuf.equipmentIdFromProto(data.armorId)
        val bootsId = NullSafeProtoBuf.equipmentIdFromProto(data.bootsId)
        val accessoryId = NullSafeProtoBuf.equipmentIdFromProto(data.accessoryId)

        val weaponNurture = NullSafeProtoBuf.nurtureDataFromProto(data.weaponNurture)
        val armorNurture = NullSafeProtoBuf.nurtureDataFromProto(data.armorNurture)
        val bootsNurture = NullSafeProtoBuf.nurtureDataFromProto(data.bootsNurture)
        val accessoryNurture = NullSafeProtoBuf.nurtureDataFromProto(data.accessoryNurture)

        val partnerId = NullSafeProtoBuf.relationIdFromProto(data.partnerId)
        val partnerSectId = NullSafeProtoBuf.relationIdFromProto(data.partnerSectId)
        val parentId1 = NullSafeProtoBuf.relationIdFromProto(data.parentId1)
        val parentId2 = NullSafeProtoBuf.relationIdFromProto(data.parentId2)

        val griefEndYear = NullSafeProtoBuf.griefEndYearFromProto(data.griefEndYear)

        return com.xianxia.sect.core.model.Disciple(
            id = data.id,
            name = data.name,
            surname = data.surname.ifEmpty { com.xianxia.sect.core.util.NameService.extractSurname(data.name) },
            realm = data.realm,
            realmLayer = data.realmLayer,
            cultivation = data.cultivation,
            spiritRootType = data.spiritRootType,
            age = data.age,
            lifespan = data.lifespan,
            isAlive = data.isAlive,
            gender = data.gender,
            manualIds = data.manualIds,
            talentIds = data.talentIds,
            manualMasteries = data.manualMasteries,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.DiscipleStatus.IDLE, "status", "Disciple"),
            statusData = data.statusData,
            cultivationSpeedBonus = data.cultivationSpeedBonus,
            cultivationSpeedDuration = data.cultivationSpeedDuration,
            discipleType = data.discipleType.ifEmpty { "outer" },
            soulPower = data.soulPower,
            combat = com.xianxia.sect.core.model.CombatAttributes(
                baseHp = data.baseHp,
                baseMp = data.baseMp,
                basePhysicalAttack = data.basePhysicalAttack,
                baseMagicAttack = data.baseMagicAttack,
                basePhysicalDefense = data.basePhysicalDefense,
                baseMagicDefense = data.baseMagicDefense,
                baseSpeed = data.baseSpeed,
                hpVariance = data.hpVariance,
                mpVariance = data.mpVariance,
                physicalAttackVariance = data.physicalAttackVariance,
                magicAttackVariance = data.magicAttackVariance,
                physicalDefenseVariance = data.physicalDefenseVariance,
                magicDefenseVariance = data.magicDefenseVariance,
                speedVariance = data.speedVariance,
                totalCultivation = data.totalCultivation,
                breakthroughCount = data.breakthroughCount,
                breakthroughFailCount = data.breakthroughFailCount,
                currentHp = data.currentHp,
                currentMp = data.currentMp
            ),
            pillEffects = com.xianxia.sect.core.model.PillEffects(
                pillPhysicalAttackBonus = data.pillPhysicalAttackBonus,
                pillMagicAttackBonus = data.pillMagicAttackBonus,
                pillPhysicalDefenseBonus = data.pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = data.pillMagicDefenseBonus,
                pillHpBonus = data.pillHpBonus,
                pillMpBonus = data.pillMpBonus,
                pillSpeedBonus = data.pillSpeedBonus,
                pillCritRateBonus = data.pillCritRateBonus,
                pillCritEffectBonus = data.pillCritEffectBonus,
                pillCultivationSpeedBonus = data.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = data.pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = data.pillNurtureSpeedBonus,
                pillEffectDuration = data.pillEffectDuration,
                activePillCategory = data.activePillCategory,
                activePillTypes = data.activePillTypes.toSet()
            ),
            equipment = com.xianxia.sect.core.model.EquipmentSet(
                weaponId = weaponId ?: "",
                armorId = armorId ?: "",
                bootsId = bootsId ?: "",
                accessoryId = accessoryId ?: "",
                weaponNurture = weaponNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                armorNurture = armorNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                bootsNurture = bootsNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                accessoryNurture = accessoryNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                storageBagItems = data.storageBagItems.map { convertBackStorageBagItem(it) },
                storageBagSpiritStones = data.storageBagSpiritStones,
                spiritStones = data.spiritStones
            ),
            social = com.xianxia.sect.core.model.SocialData(
                partnerId = partnerId,
                partnerSectId = partnerSectId,
                parentId1 = parentId1,
                parentId2 = parentId2,
                lastChildYear = data.lastChildYear,
                griefEndYear = griefEndYear
            ),
            skills = com.xianxia.sect.core.model.SkillStats(
                intelligence = data.intelligence,
                charm = data.charm,
                loyalty = data.loyalty,
                comprehension = data.comprehension,
                artifactRefining = data.artifactRefining,
                pillRefining = data.pillRefining,
                spiritPlanting = data.spiritPlanting,
                mining = data.mining,
                teaching = data.teaching,
                morality = data.morality,
                salaryPaidCount = data.salaryPaidCount,
                salaryMissedCount = data.salaryMissedCount
            ),
            usage = com.xianxia.sect.core.model.UsageTracking(
                usedPermanentPillKeys = data.usedPermanentPillKeys.toSet(),
                usedExtendLifePillTypes = data.usedExtendLifePillTypes.toSet(),
                usedFunctionalPillTypes = data.usedFunctionalPillTypes,
                usedExtendLifePillIds = data.usedExtendLifePillIds,
                recruitedMonth = data.recruitedMonth,
                hasReviveEffect = data.hasReviveEffect,
                hasClearAllEffect = data.hasClearAllEffect
            )
        )
    }

    fun convertEquipmentNurture(data: com.xianxia.sect.core.model.EquipmentNurtureData): SerializableEquipmentNurtureData {
        return SerializableEquipmentNurtureData(
            equipmentId = data.equipmentId ?: "",
            rarity = data.rarity ?: 0,
            nurtureLevel = data.nurtureLevel ?: 0,
            nurtureProgress = data.nurtureProgress ?: 0.0
        )
    }

    fun convertBackEquipmentNurture(data: SerializableEquipmentNurtureData): com.xianxia.sect.core.model.EquipmentNurtureData {
        return com.xianxia.sect.core.model.EquipmentNurtureData(
            equipmentId = data.equipmentId,
            rarity = data.rarity,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress
        )
    }

    fun convertStorageBagItem(item: com.xianxia.sect.core.model.StorageBagItem): SerializableStorageBagItem {
        return SerializableStorageBagItem(
            itemId = item.itemId ?: "",
            itemType = item.itemType ?: "",
            name = item.name ?: "",
            rarity = item.rarity ?: 0,
            quantity = item.quantity ?: 1,
            obtainedYear = item.obtainedYear ?: 1,
            obtainedMonth = item.obtainedMonth ?: 1,
            effect = item.effect?.let { convertItemEffect(it) } ?: SerializableItemEffect(),
            grade = item.grade ?: "",
            forgetYear = item.forgetYear ?: 0,
            forgetMonth = item.forgetMonth ?: 0,
            forgetPhase = item.forgetPhase ?: 0
        )
    }

    fun convertBackStorageBagItem(data: SerializableStorageBagItem): com.xianxia.sect.core.model.StorageBagItem {
        val effect = data.effect.takeIf { it.cultivationSpeedPercent != 0.0 || it.skillExpSpeedPercent != 0.0 || it.nurtureSpeedPercent != 0.0 || it.breakthroughChance != 0.0 || it.targetRealm != 0 || it.cultivationAdd != 0 || it.skillExpAdd != 0 || it.nurtureAdd != 0 || it.healMaxHpPercent != 0.0 || it.mpRecoverMaxMpPercent != 0.0 || it.hpAdd != 0 || it.mpAdd != 0 || it.extendLife != 0 || it.physicalAttackAdd != 0 || it.magicAttackAdd != 0 || it.physicalDefenseAdd != 0 || it.magicDefenseAdd != 0 || it.speedAdd != 0 || it.critRateAdd != 0.0 || it.critEffectAdd != 0.0 || it.intelligenceAdd != 0 || it.charmAdd != 0 || it.loyaltyAdd != 0 || it.comprehensionAdd != 0 || it.artifactRefiningAdd != 0 || it.pillRefiningAdd != 0 || it.spiritPlantingAdd != 0 || it.teachingAdd != 0 || it.moralityAdd != 0 || it.revive || it.clearAll || it.duration != 0 || it.minRealm != 9 || it.pillCategory.isNotEmpty() || it.pillType.isNotEmpty() }?.let { convertBackItemEffect(it) }

        return com.xianxia.sect.core.model.StorageBagItem(
            itemId = data.itemId,
            itemType = data.itemType,
            name = data.name,
            rarity = data.rarity,
            quantity = data.quantity,
            obtainedYear = data.obtainedYear,
            obtainedMonth = data.obtainedMonth,
            effect = effect,
            grade = data.grade.takeIf { it.isNotEmpty() },
            forgetYear = data.forgetYear.takeIf { it > 0 },
            forgetMonth = data.forgetMonth.takeIf { it > 0 },
            forgetPhase = if (data.forgetPhase > 2) (data.forgetPhase - 1) / 10 else data.forgetPhase
        )
    }

    fun convertItemEffect(effect: com.xianxia.sect.core.model.ItemEffect): SerializableItemEffect {
        return SerializableItemEffect(
            cultivationSpeedPercent = effect.cultivationSpeedPercent ?: 0.0,
            skillExpSpeedPercent = effect.skillExpSpeedPercent ?: 0.0,
            nurtureSpeedPercent = effect.nurtureSpeedPercent ?: 0.0,
            breakthroughChance = effect.breakthroughChance ?: 0.0,
            targetRealm = effect.targetRealm ?: 0,
            cultivationAdd = effect.cultivationAdd ?: 0,
            skillExpAdd = effect.skillExpAdd ?: 0,
            nurtureAdd = effect.nurtureAdd ?: 0,
            healMaxHpPercent = effect.healMaxHpPercent ?: 0.0,
            mpRecoverMaxMpPercent = effect.mpRecoverMaxMpPercent ?: 0.0,
            hpAdd = effect.hpAdd ?: 0,
            mpAdd = effect.mpAdd ?: 0,
            extendLife = effect.extendLife ?: 0,
            physicalAttackAdd = effect.physicalAttackAdd ?: 0,
            magicAttackAdd = effect.magicAttackAdd ?: 0,
            physicalDefenseAdd = effect.physicalDefenseAdd ?: 0,
            magicDefenseAdd = effect.magicDefenseAdd ?: 0,
            speedAdd = effect.speedAdd ?: 0,
            critRateAdd = effect.critRateAdd ?: 0.0,
            critEffectAdd = effect.critEffectAdd ?: 0.0,
            intelligenceAdd = effect.intelligenceAdd ?: 0,
            charmAdd = effect.charmAdd ?: 0,
            loyaltyAdd = effect.loyaltyAdd ?: 0,
            comprehensionAdd = effect.comprehensionAdd ?: 0,
            artifactRefiningAdd = effect.artifactRefiningAdd ?: 0,
            pillRefiningAdd = effect.pillRefiningAdd ?: 0,
            spiritPlantingAdd = effect.spiritPlantingAdd ?: 0,
            teachingAdd = effect.teachingAdd ?: 0,
            moralityAdd = effect.moralityAdd ?: 0,
            miningAdd = effect.miningAdd ?: 0,
            revive = effect.revive ?: false,
            clearAll = effect.clearAll ?: false,
            isAscension = effect.isAscension ?: false,
            duration = effect.duration ?: 0,
            cannotStack = effect.cannotStack ?: true,
            minRealm = effect.minRealm ?: 9,
            pillCategory = effect.pillCategory ?: "",
            pillType = effect.pillType ?: ""
        )
    }

    fun convertBackItemEffect(data: SerializableItemEffect): com.xianxia.sect.core.model.ItemEffect {
        return com.xianxia.sect.core.model.ItemEffect(
            cultivationSpeedPercent = data.cultivationSpeedPercent,
            skillExpSpeedPercent = data.skillExpSpeedPercent,
            nurtureSpeedPercent = data.nurtureSpeedPercent,
            breakthroughChance = data.breakthroughChance,
            targetRealm = data.targetRealm,
            cultivationAdd = data.cultivationAdd,
            skillExpAdd = data.skillExpAdd,
            nurtureAdd = data.nurtureAdd,
            healMaxHpPercent = data.healMaxHpPercent,
            mpRecoverMaxMpPercent = data.mpRecoverMaxMpPercent,
            hpAdd = data.hpAdd,
            mpAdd = data.mpAdd,
            extendLife = data.extendLife,
            physicalAttackAdd = data.physicalAttackAdd,
            magicAttackAdd = data.magicAttackAdd,
            physicalDefenseAdd = data.physicalDefenseAdd,
            magicDefenseAdd = data.magicDefenseAdd,
            speedAdd = data.speedAdd,
            critRateAdd = data.critRateAdd,
            critEffectAdd = data.critEffectAdd,
            intelligenceAdd = data.intelligenceAdd,
            charmAdd = data.charmAdd,
            loyaltyAdd = data.loyaltyAdd,
            comprehensionAdd = data.comprehensionAdd,
            artifactRefiningAdd = data.artifactRefiningAdd,
            pillRefiningAdd = data.pillRefiningAdd,
            spiritPlantingAdd = data.spiritPlantingAdd,
            teachingAdd = data.teachingAdd,
            moralityAdd = data.moralityAdd,
            miningAdd = data.miningAdd,
            revive = data.revive,
            clearAll = data.clearAll,
            isAscension = data.isAscension,
            duration = data.duration,
            cannotStack = data.cannotStack,
            minRealm = data.minRealm,
            pillCategory = data.pillCategory,
            pillType = data.pillType
        )
    }
}
