package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveDataConverter @Inject constructor() {
    companion object {
        private const val TAG = "SaveDataConverter"
    }

    fun toSerializable(saveData: com.xianxia.sect.data.model.SaveData): SerializableSaveData {
        val gameDataWithSlots = saveData.gameData.copy(
            productionSlots = saveData.productionSlots
        )
        return SerializableSaveData(
            version = saveData.version ?: SchemaVersion.CURRENT.toString(),
            timestamp = saveData.timestamp ?: System.currentTimeMillis(),
            gameData = convertGameData(gameDataWithSlots),
            disciples = saveData.disciples?.map { convertDisciple(it) } ?: emptyList(),
            equipment = saveData.equipmentInstances?.map { convertEquipment(it) } ?: emptyList(),
            manuals = saveData.manualInstances?.map { convertManual(it) } ?: emptyList(),
            pills = saveData.pills?.map { convertPill(it) } ?: emptyList(),
            materials = saveData.materials?.map { convertMaterial(it) } ?: emptyList(),
            herbs = saveData.herbs?.map { convertHerb(it) } ?: emptyList(),
            seeds = saveData.seeds?.map { convertSeed(it) } ?: emptyList(),
            teams = saveData.teams?.map { convertTeam(it) } ?: emptyList(),
            battleLogs = saveData.battleLogs?.map { convertBattleLog(it) } ?: emptyList(),
            alliances = saveData.alliances?.map { convertAlliance(it) } ?: emptyList()
        )
    }

    fun fromSerializable(data: SerializableSaveData): com.xianxia.sect.data.model.SaveData {
        return com.xianxia.sect.data.model.SaveData(
            version = data.version,
            timestamp = data.timestamp,
            gameData = convertBackGameData(data.gameData),
            disciples = data.disciples.map { convertBackDisciple(it) },
            equipmentStacks = emptyList(),
            equipmentInstances = data.equipment.map { convertBackEquipment(it) },
            manualStacks = emptyList(),
            manualInstances = data.manuals.map { convertBackManual(it) },
            pills = data.pills.map { convertBackPill(it) },
            materials = data.materials.map { convertBackMaterial(it) },
            herbs = data.herbs.map { convertBackHerb(it) },
            seeds = data.seeds.map { convertBackSeed(it) },
            teams = data.teams.map { convertBackTeam(it) },
            battleLogs = data.battleLogs.map { convertBackBattleLog(it) },
            alliances = data.alliances.map { convertBackAlliance(it) },
            productionSlots = data.gameData?.productionSlots?.map { convertBackProductionSlot(it) } ?: emptyList()
        )
    }

    private fun convertGameData(gameData: com.xianxia.sect.core.model.GameData?): SerializableGameData {
        if (gameData == null) return SerializableGameData()

        return SerializableGameData(
            sectName = gameData.sectName ?: "青云宗",
            currentSlot = gameData.currentSlot ?: 1,
            gameYear = gameData.gameYear ?: 1,
            gameMonth = gameData.gameMonth ?: 1,
            gameDay = gameData.gameDay ?: 1,
            spiritStones = gameData.spiritStones ?: 1000,
            spiritHerbs = gameData.spiritHerbs ?: 0,
            autoSaveIntervalMonths = gameData.autoSaveIntervalMonths ?: 3,
            monthlySalary = gameData.monthlySalary ?: emptyMap(),
            monthlySalaryEnabled = gameData.monthlySalaryEnabled ?: emptyMap(),
            worldMapSects = gameData.worldMapSects?.map { convertWorldSect(it, gameData.sectDetails?.get(it.id)) } ?: emptyList(),
            sectDetails = gameData.sectDetails?.mapValues { convertSectDetail(it.value) } ?: emptyMap(),
            exploredSects = gameData.exploredSects?.mapValues { convertExploredSectInfo(it.value) } ?: emptyMap(),
            scoutInfo = gameData.scoutInfo?.mapValues { convertSectScoutInfo(it.value) } ?: emptyMap(),
            manualProficiencies = gameData.manualProficiencies?.mapValues {
                it.value.map { prof -> convertManualProficiency(prof) }
            } ?: emptyMap(),
            travelingMerchantItems = gameData.travelingMerchantItems?.map { convertMerchantItem(it) } ?: emptyList(),
            merchantLastRefreshYear = gameData.merchantLastRefreshYear ?: 0,
            merchantRefreshCount = gameData.merchantRefreshCount ?: 0,
            playerListedItems = gameData.playerListedItems?.map { convertMerchantItem(it) } ?: emptyList(),
            recruitList = gameData.recruitList?.map { convertDisciple(it) } ?: emptyList(),
            lastRecruitYear = gameData.lastRecruitYear ?: 0,
            cultivatorCaves = gameData.cultivatorCaves?.map { convertCultivatorCave(it) } ?: emptyList(),
            caveExplorationTeams = gameData.caveExplorationTeams?.map { convertCaveExplorationTeam(it) } ?: emptyList(),
            aiCaveTeams = gameData.aiCaveTeams?.map { convertAICaveTeam(it) } ?: emptyList(),
            unlockedRecipes = gameData.unlockedRecipes ?: emptyList(),
            unlockedManuals = gameData.unlockedManuals ?: emptyList(),
            lastSaveTime = gameData.lastSaveTime ?: 0L,
            elderSlots = convertElderSlots(gameData.elderSlots),
            spiritMineSlots = gameData.spiritMineSlots?.map { convertSpiritMineSlot(it) } ?: emptyList(),
            librarySlots = gameData.librarySlots?.map { convertLibrarySlot(it) } ?: emptyList(),
            residenceSlots = gameData.residenceSlots.map { convertResidenceSlot(it) },
            productionSlots = gameData.productionSlots?.map { convertProductionSlot(it) } ?: emptyList(),
            alliances = gameData.alliances?.map { convertAlliance(it) } ?: emptyList(),
            sectRelations = gameData.sectRelations?.map { convertSectRelation(it) } ?: emptyList(),
            playerAllianceSlots = gameData.playerAllianceSlots ?: 3,
            sectPolicies = convertSectPolicies(gameData.sectPolicies),
            usedRedeemCodes = gameData.usedRedeemCodes ?: emptyList(),
            playerProtectionEnabled = gameData.playerProtectionEnabled ?: true,
            playerProtectionStartYear = gameData.playerProtectionStartYear ?: 1,
            playerHasAttackedAI = gameData.playerHasAttackedAI ?: false,
            activeMissions = gameData.activeMissions?.map { convertActiveMission(it) } ?: emptyList(),
            availableMissions = gameData.availableMissions?.map { convertMission(it) } ?: emptyList(),
            aiSectDisciples = gameData.aiSectDisciples?.map { (sectId, disciples) ->
                SerializableAiSectDiscipleEntry(
                    sectId = sectId,
                    disciples = disciples.map { convertDisciple(it) }
                )
            } ?: emptyList(),
            spiritMineExpansions = gameData.spiritMineExpansions
        )
    }

    private fun convertBackGameData(data: SerializableGameData): com.xianxia.sect.core.model.GameData {
        return com.xianxia.sect.core.model.GameData(
            sectName = data.sectName,
            currentSlot = data.currentSlot,
            gameYear = data.gameYear,
            gameMonth = data.gameMonth,
            gameDay = data.gameDay,
            spiritStones = data.spiritStones,
            spiritHerbs = data.spiritHerbs,
            autoSaveIntervalMonths = data.autoSaveIntervalMonths,
            monthlySalary = data.monthlySalary,
            monthlySalaryEnabled = data.monthlySalaryEnabled,
            worldMapSects = data.worldMapSects.map { convertBackWorldSect(it) },
            sectDetails = if (data.sectDetails.isNotEmpty()) {
                data.sectDetails.mapValues { convertBackSectDetail(it.value) }
            } else {
                data.worldMapSects.associate { it.id to extractSectDetailFromWorldSect(it) }
            },
            exploredSects = data.exploredSects.mapValues { convertBackExploredSectInfo(it.value) },
            scoutInfo = data.scoutInfo.mapValues { convertBackSectScoutInfo(it.value) },
            manualProficiencies = data.manualProficiencies.mapValues {
                it.value.map { prof -> convertBackManualProficiency(prof) }
            },
            travelingMerchantItems = data.travelingMerchantItems.map { convertBackMerchantItem(it) },
            merchantLastRefreshYear = data.merchantLastRefreshYear,
            merchantRefreshCount = data.merchantRefreshCount,
            playerListedItems = data.playerListedItems.map { convertBackMerchantItem(it) },
            recruitList = data.recruitList.map { convertBackDisciple(it) },
            lastRecruitYear = data.lastRecruitYear,
            cultivatorCaves = data.cultivatorCaves.map { convertBackCultivatorCave(it) },
            caveExplorationTeams = data.caveExplorationTeams.map { convertBackCaveExplorationTeam(it) },
            aiCaveTeams = data.aiCaveTeams.map { convertBackAICaveTeam(it) },
            unlockedRecipes = data.unlockedRecipes,
            unlockedManuals = data.unlockedManuals,
            lastSaveTime = data.lastSaveTime,
            elderSlots = convertBackElderSlots(data.elderSlots),
            spiritMineSlots = data.spiritMineSlots.map { convertBackSpiritMineSlot(it) },
            librarySlots = data.librarySlots.map { convertBackLibrarySlot(it) },
            residenceSlots = data.residenceSlots.map { convertBackResidenceSlot(it) },
            productionSlots = data.productionSlots.map { convertBackProductionSlot(it) },
            alliances = data.alliances.map { convertBackAlliance(it) },
            sectRelations = data.sectRelations.map { convertBackSectRelation(it) },
            playerAllianceSlots = data.playerAllianceSlots,
            sectPolicies = convertBackSectPolicies(data.sectPolicies),
            usedRedeemCodes = data.usedRedeemCodes,
            playerProtectionEnabled = data.playerProtectionEnabled,
            playerProtectionStartYear = data.playerProtectionStartYear,
            playerHasAttackedAI = data.playerHasAttackedAI,
            activeMissions = data.activeMissions.map { convertBackActiveMission(it) },
            availableMissions = data.availableMissions.map { convertBackMission(it) },
            aiSectDisciples = data.aiSectDisciples.associate { entry ->
                entry.sectId to entry.disciples.map { convertBackDisciple(it) }
            },
            spiritMineExpansions = data.spiritMineExpansions
        )
    }

    private fun convertDisciple(disciple: com.xianxia.sect.core.model.Disciple): SerializableDisciple {
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
            hasReviveEffect = disciple.usage.hasReviveEffect ?: false,
            hasClearAllEffect = disciple.usage.hasClearAllEffect ?: false,
            currentHp = disciple.combat.currentHp,
            currentMp = disciple.combat.currentMp
        )
    }

    private fun convertBackDisciple(data: SerializableDisciple): com.xianxia.sect.core.model.Disciple {
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
                activePillCategory = data.activePillCategory
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
                usedFunctionalPillTypes = data.usedFunctionalPillTypes,
                usedExtendLifePillIds = data.usedExtendLifePillIds,
                recruitedMonth = data.recruitedMonth,
                hasReviveEffect = data.hasReviveEffect,
                hasClearAllEffect = data.hasClearAllEffect
            )
        )
    }

    private fun convertEquipmentNurture(data: com.xianxia.sect.core.model.EquipmentNurtureData): SerializableEquipmentNurtureData {
        return SerializableEquipmentNurtureData(
            equipmentId = data.equipmentId ?: "",
            rarity = data.rarity ?: 0,
            nurtureLevel = data.nurtureLevel ?: 0,
            nurtureProgress = data.nurtureProgress ?: 0.0
        )
    }

    private fun convertBackEquipmentNurture(data: SerializableEquipmentNurtureData): com.xianxia.sect.core.model.EquipmentNurtureData {
        return com.xianxia.sect.core.model.EquipmentNurtureData(
            equipmentId = data.equipmentId,
            rarity = data.rarity,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress
        )
    }

    private fun convertStorageBagItem(item: com.xianxia.sect.core.model.StorageBagItem): SerializableStorageBagItem {
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
            forgetDay = item.forgetDay ?: 0
        )
    }

    private fun convertBackStorageBagItem(data: SerializableStorageBagItem): com.xianxia.sect.core.model.StorageBagItem {
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
            forgetDay = data.forgetDay.takeIf { it > 0 }
        )
    }

    private fun convertItemEffect(effect: com.xianxia.sect.core.model.ItemEffect): SerializableItemEffect {
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

    private fun convertBackItemEffect(data: SerializableItemEffect): com.xianxia.sect.core.model.ItemEffect {
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

    private fun convertEquipment(equipment: com.xianxia.sect.core.model.EquipmentInstance): SerializableEquipment {
        return SerializableEquipment(
            id = equipment.id,
            name = equipment.name,
            type = equipment.slot.name,
            rarity = equipment.rarity,
            level = equipment.nurtureLevel,
            stats = mapOf(
                "physicalAttack" to equipment.physicalAttack,
                "magicAttack" to equipment.magicAttack,
                "physicalDefense" to equipment.physicalDefense,
                "magicDefense" to equipment.magicDefense,
                "speed" to equipment.speed,
                "hp" to equipment.hp,
                "mp" to equipment.mp
            ),
            description = equipment.description,
            critChance = equipment.critChance,
            isEquipped = equipment.isEquipped,
            nurtureLevel = equipment.nurtureLevel,
            nurtureProgress = equipment.nurtureProgress,
            minRealm = equipment.minRealm,
            ownerId = equipment.ownerId ?: "",
            quantity = 1
        )
    }

    private fun convertBackEquipment(data: SerializableEquipment): com.xianxia.sect.core.model.EquipmentInstance {
        val ownerId = data.ownerId.ifEmpty { null }

        return com.xianxia.sect.core.model.EquipmentInstance(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            slot = safeEnumValueOf(data.type, com.xianxia.sect.core.model.EquipmentSlot.WEAPON, "type", "Equipment"),
            physicalAttack = data.stats["physicalAttack"] ?: 0,
            magicAttack = data.stats["magicAttack"] ?: 0,
            physicalDefense = data.stats["physicalDefense"] ?: 0,
            magicDefense = data.stats["magicDefense"] ?: 0,
            speed = data.stats["speed"] ?: 0,
            hp = data.stats["hp"] ?: 0,
            mp = data.stats["mp"] ?: 0,
            description = data.description,
            critChance = data.critChance,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress,
            minRealm = data.minRealm ?: 9,
            ownerId = ownerId,
            isEquipped = data.isEquipped
        )
    }

    private fun convertManual(manual: com.xianxia.sect.core.model.ManualInstance): SerializableManual {
        return SerializableManual(
            id = manual.id,
            name = manual.name,
            type = manual.type.name,
            rarity = manual.rarity,
            stats = manual.stats,
            description = manual.description
        )
    }

    private fun convertBackManual(data: SerializableManual): com.xianxia.sect.core.model.ManualInstance {
        return com.xianxia.sect.core.model.ManualInstance(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.ManualType.MIND, "type", "Manual"),
            stats = data.stats,
            description = data.description,
            ownerId = null,
            isLearned = false
        )
    }

    private fun convertPill(pill: com.xianxia.sect.core.model.Pill): SerializablePill {
        return SerializablePill(
            id = pill.id,
            name = pill.name,
            type = pill.category.name,
            rarity = pill.rarity,
            effects = SerializablePillEffect(
                breakthroughChance = pill.effects.breakthroughChance,
                targetRealm = pill.effects.targetRealm,
                isAscension = pill.effects.isAscension,
                cultivationSpeedPercent = pill.effects.cultivationSpeedPercent,
                skillExpSpeedPercent = pill.effects.skillExpSpeedPercent,
                nurtureSpeedPercent = pill.effects.nurtureSpeedPercent,
                cultivationAdd = pill.effects.cultivationAdd,
                skillExpAdd = pill.effects.skillExpAdd,
                nurtureAdd = pill.effects.nurtureAdd,
                duration = pill.effects.duration,
                cannotStack = pill.effects.cannotStack,
                physicalAttackAdd = pill.effects.physicalAttackAdd,
                magicAttackAdd = pill.effects.magicAttackAdd,
                physicalDefenseAdd = pill.effects.physicalDefenseAdd,
                magicDefenseAdd = pill.effects.magicDefenseAdd,
                hpAdd = pill.effects.hpAdd,
                mpAdd = pill.effects.mpAdd,
                speedAdd = pill.effects.speedAdd,
                critRateAdd = pill.effects.critRateAdd,
                critEffectAdd = pill.effects.critEffectAdd,
                extendLife = pill.effects.extendLife,
                intelligenceAdd = pill.effects.intelligenceAdd,
                charmAdd = pill.effects.charmAdd,
                loyaltyAdd = pill.effects.loyaltyAdd,
                comprehensionAdd = pill.effects.comprehensionAdd,
                artifactRefiningAdd = pill.effects.artifactRefiningAdd,
                pillRefiningAdd = pill.effects.pillRefiningAdd,
                spiritPlantingAdd = pill.effects.spiritPlantingAdd,
                teachingAdd = pill.effects.teachingAdd,
                moralityAdd = pill.effects.moralityAdd,
                miningAdd = pill.effects.miningAdd,
                healMaxHpPercent = pill.effects.healMaxHpPercent,
                mpRecoverMaxMpPercent = pill.effects.mpRecoverMaxMpPercent,
                revive = pill.effects.revive,
                clearAll = pill.effects.clearAll
            ),
            description = pill.description,
            quantity = pill.quantity,
            category = pill.category.name,
            grade = pill.grade.name,
            minRealm = pill.minRealm,
            isLocked = pill.isLocked
        )
    }

    private fun convertBackPill(data: SerializablePill): com.xianxia.sect.core.model.Pill {
        val pillEffect = if (data.effects != SerializablePillEffect() || data.effectsMap.isEmpty()) {
            data.effects
        } else {
            migrateEffectsMapToPillEffect(data.effectsMap)
        }

        return com.xianxia.sect.core.model.Pill(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            category = safeEnumValueOf(
                    if (data.category == "CULTIVATION" && data.type.isNotEmpty()) data.type else data.category,
                    com.xianxia.sect.core.model.PillCategory.CULTIVATION, "category", "Pill"
                ),
            effects = com.xianxia.sect.core.model.PillEffect(
                breakthroughChance = pillEffect.breakthroughChance,
                targetRealm = pillEffect.targetRealm,
                isAscension = pillEffect.isAscension,
                cultivationSpeedPercent = pillEffect.cultivationSpeedPercent,
                skillExpSpeedPercent = pillEffect.skillExpSpeedPercent,
                nurtureSpeedPercent = pillEffect.nurtureSpeedPercent,
                cultivationAdd = pillEffect.cultivationAdd,
                skillExpAdd = pillEffect.skillExpAdd,
                nurtureAdd = pillEffect.nurtureAdd,
                duration = pillEffect.duration,
                cannotStack = pillEffect.cannotStack,
                physicalAttackAdd = pillEffect.physicalAttackAdd,
                magicAttackAdd = pillEffect.magicAttackAdd,
                physicalDefenseAdd = pillEffect.physicalDefenseAdd,
                magicDefenseAdd = pillEffect.magicDefenseAdd,
                hpAdd = pillEffect.hpAdd,
                mpAdd = pillEffect.mpAdd,
                speedAdd = pillEffect.speedAdd,
                critRateAdd = pillEffect.critRateAdd,
                critEffectAdd = pillEffect.critEffectAdd,
                extendLife = pillEffect.extendLife,
                intelligenceAdd = pillEffect.intelligenceAdd,
                charmAdd = pillEffect.charmAdd,
                loyaltyAdd = pillEffect.loyaltyAdd,
                comprehensionAdd = pillEffect.comprehensionAdd,
                artifactRefiningAdd = pillEffect.artifactRefiningAdd,
                pillRefiningAdd = pillEffect.pillRefiningAdd,
                spiritPlantingAdd = pillEffect.spiritPlantingAdd,
                teachingAdd = pillEffect.teachingAdd,
                moralityAdd = pillEffect.moralityAdd,
                miningAdd = pillEffect.miningAdd,
                healMaxHpPercent = pillEffect.healMaxHpPercent,
                mpRecoverMaxMpPercent = pillEffect.mpRecoverMaxMpPercent,
                revive = pillEffect.revive,
                clearAll = pillEffect.clearAll
            ),
            minRealm = data.minRealm,
            grade = safeEnumValueOf(data.grade, com.xianxia.sect.core.model.PillGrade.MEDIUM, "grade", "Pill"),
            description = data.description,
            quantity = data.quantity,
            isLocked = data.isLocked
        )
    }

    @Suppress("DEPRECATION")
    private fun migrateEffectsMapToPillEffect(effectsMap: Map<String, Double>): SerializablePillEffect {
        return SerializablePillEffect(
            breakthroughChance = effectsMap["breakthroughChance"] ?: 0.0,
            targetRealm = (effectsMap["targetRealm"] ?: 0.0).toInt(),
            isAscension = (effectsMap["isAscension"] ?: 0.0) > 0.5,
            cultivationSpeedPercent = effectsMap["cultivationSpeedPercent"] ?: 0.0,
            skillExpSpeedPercent = effectsMap["skillExpSpeedPercent"] ?: 0.0,
            nurtureSpeedPercent = effectsMap["nurtureSpeedPercent"] ?: 0.0,
            cultivationAdd = (effectsMap["cultivationAdd"] ?: 0.0).toInt(),
            skillExpAdd = (effectsMap["skillExpAdd"] ?: 0.0).toInt(),
            nurtureAdd = (effectsMap["nurtureAdd"] ?: 0.0).toInt(),
            duration = (effectsMap["duration"] ?: 0.0).toInt(),
            cannotStack = (effectsMap["cannotStack"] ?: 0.0) > 0.5,
            physicalAttackAdd = (effectsMap["physicalAttackAdd"] ?: 0.0).toInt(),
            magicAttackAdd = (effectsMap["magicAttackAdd"] ?: 0.0).toInt(),
            physicalDefenseAdd = (effectsMap["physicalDefenseAdd"] ?: 0.0).toInt(),
            magicDefenseAdd = (effectsMap["magicDefenseAdd"] ?: 0.0).toInt(),
            hpAdd = (effectsMap["hpAdd"] ?: 0.0).toInt(),
            mpAdd = (effectsMap["mpAdd"] ?: 0.0).toInt(),
            speedAdd = (effectsMap["speedAdd"] ?: 0.0).toInt(),
            critRateAdd = effectsMap["critRateAdd"] ?: 0.0,
            critEffectAdd = effectsMap["critEffectAdd"] ?: 0.0,
            extendLife = (effectsMap["extendLife"] ?: 0.0).toInt(),
            intelligenceAdd = (effectsMap["intelligenceAdd"] ?: 0.0).toInt(),
            charmAdd = (effectsMap["charmAdd"] ?: 0.0).toInt(),
            loyaltyAdd = (effectsMap["loyaltyAdd"] ?: 0.0).toInt(),
            comprehensionAdd = (effectsMap["comprehensionAdd"] ?: 0.0).toInt(),
            artifactRefiningAdd = (effectsMap["artifactRefiningAdd"] ?: 0.0).toInt(),
            pillRefiningAdd = (effectsMap["pillRefiningAdd"] ?: 0.0).toInt(),
            spiritPlantingAdd = (effectsMap["spiritPlantingAdd"] ?: 0.0).toInt(),
            teachingAdd = (effectsMap["teachingAdd"] ?: 0.0).toInt(),
            moralityAdd = (effectsMap["moralityAdd"] ?: 0.0).toInt(),
            miningAdd = (effectsMap["miningAdd"] ?: 0.0).toInt(),
            healMaxHpPercent = effectsMap["healMaxHpPercent"] ?: 0.0,
            mpRecoverMaxMpPercent = effectsMap["mpRecoverMaxMpPercent"] ?: 0.0,
            revive = (effectsMap["revive"] ?: 0.0) > 0.5,
            clearAll = (effectsMap["clearAll"] ?: 0.0) > 0.5
        )
    }

    private fun convertMaterial(material: com.xianxia.sect.core.model.Material): SerializableMaterial {
        return SerializableMaterial(
            id = material.id,
            name = material.name,
            type = material.category.name,
            rarity = material.rarity,
            quantity = material.quantity,
            description = material.description
        )
    }

    private fun convertBackMaterial(data: SerializableMaterial): com.xianxia.sect.core.model.Material {
        return com.xianxia.sect.core.model.Material(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            category = safeEnumValueOf(data.type, com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE, "type", "Material"),
            quantity = data.quantity,
            description = data.description
        )
    }

    private fun convertHerb(herb: com.xianxia.sect.core.model.Herb): SerializableHerb {
        return SerializableHerb(
            id = herb.id,
            name = herb.name,
            rarity = herb.rarity,
            quantity = herb.quantity,
            age = 0,
            description = herb.description
        )
    }

    private fun convertBackHerb(data: SerializableHerb): com.xianxia.sect.core.model.Herb {
        return com.xianxia.sect.core.model.Herb(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            quantity = data.quantity,
            description = data.description
        )
    }

    private fun convertSeed(seed: com.xianxia.sect.core.model.Seed): SerializableSeed {
        return SerializableSeed(
            id = seed.id,
            name = seed.name,
            rarity = seed.rarity,
            growTime = seed.growTime,
            yieldMin = seed.yield,
            yieldMax = seed.yield,
            quantity = seed.quantity,
            description = seed.description
        )
    }

    private fun convertBackSeed(data: SerializableSeed): com.xianxia.sect.core.model.Seed {
        return com.xianxia.sect.core.model.Seed(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            growTime = data.growTime,
            yield = data.yieldMin,
            quantity = data.quantity,
            description = data.description
        )
    }

    private fun convertTeam(team: com.xianxia.sect.core.model.ExplorationTeam): SerializableExplorationTeam {
        return SerializableExplorationTeam(
            id = team.id,
            name = team.name,
            memberIds = team.memberIds,
            status = team.status.name,
            targetSectId = team.scoutTargetSectId ?: "",
            startYear = team.startYear,
            startMonth = team.startMonth,
            duration = team.duration,
            currentProgress = team.progress
        )
    }

    private fun convertBackTeam(data: SerializableExplorationTeam): com.xianxia.sect.core.model.ExplorationTeam {
        return com.xianxia.sect.core.model.ExplorationTeam(
            id = data.id,
            name = data.name,
            memberIds = data.memberIds,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.duration,
            status = safeEnumValueOfIgnoreCase(data.status, com.xianxia.sect.core.model.ExplorationStatus.TRAVELING, "status", "ExplorationTeam"),
            progress = data.currentProgress,
            scoutTargetSectId = data.targetSectId
        )
    }

    private fun convertBattleLog(log: com.xianxia.sect.core.model.BattleLog): SerializableBattleLog {
        return SerializableBattleLog(
            id = log.id,
            timestamp = log.timestamp,
            gameYear = log.year,
            gameMonth = log.month,
            attackerSectId = "",
            attackerSectName = log.attackerName,
            defenderSectId = "",
            defenderSectName = log.defenderName,
            result = log.result.name,
            rounds = log.rounds.map { convertBattleLogRound(it) },
            attackerMembers = log.teamMembers.map { convertBattleLogMember(it) },
            defenderMembers = log.enemies.map { convertBattleLogEnemy(it) },
            rewards = emptyMap(),
            type = log.type.name,
            details = log.details,
            drops = log.drops,
            dungeonName = log.dungeonName
        )
    }

    private fun convertBackBattleLog(data: SerializableBattleLog): com.xianxia.sect.core.model.BattleLog {
        return com.xianxia.sect.core.model.BattleLog(
            id = data.id,
            timestamp = data.timestamp,
            year = data.gameYear,
            month = data.gameMonth,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.BattleType.PVE, "type", "BattleLog"),
            attackerName = data.attackerSectName,
            defenderName = data.defenderSectName,
            result = safeEnumValueOf(data.result, com.xianxia.sect.core.model.BattleResult.DRAW, "result", "BattleLog"),
            details = data.details,
            drops = data.drops,
            dungeonName = data.dungeonName,
            rounds = data.rounds.map { convertBackBattleLogRound(it) },
            teamMembers = data.attackerMembers.map { convertBackBattleLogMember(it) },
            enemies = data.defenderMembers.map { convertBackBattleLogEnemy(it) }
        )
    }

    private fun convertBattleLogRound(round: com.xianxia.sect.core.model.BattleLogRound): SerializableBattleLogRound {
        return SerializableBattleLogRound(
            roundNumber = round.roundNumber,
            actions = round.actions.map { convertBattleLogAction(it) }
        )
    }

    private fun convertBackBattleLogRound(data: SerializableBattleLogRound): com.xianxia.sect.core.model.BattleLogRound {
        return com.xianxia.sect.core.model.BattleLogRound(
            roundNumber = data.roundNumber,
            actions = data.actions.map { convertBackBattleLogAction(it) }
        )
    }

    private fun convertBattleLogAction(action: com.xianxia.sect.core.model.BattleLogAction): SerializableBattleLogAction {
        return SerializableBattleLogAction(
            actorId = "",
            actorName = action.attacker,
            attackerType = action.attackerType,
            targetId = "",
            targetName = action.target,
            skillName = action.skillName ?: "",
            damage = action.damage,
            isCritical = action.isCrit,
            effect = action.message,
            type = action.type,
            damageType = action.damageType,
            isKill = action.isKill
        )
    }

    private fun convertBackBattleLogAction(data: SerializableBattleLogAction): com.xianxia.sect.core.model.BattleLogAction {
        return com.xianxia.sect.core.model.BattleLogAction(
            type = data.type,
            attacker = data.actorName,
            attackerType = data.attackerType,
            target = data.targetName,
            damage = data.damage,
            damageType = data.damageType,
            isCrit = data.isCritical,
            isKill = data.isKill,
            message = data.effect,
            skillName = data.skillName
        )
    }

    private fun convertBattleLogMember(member: com.xianxia.sect.core.model.BattleLogMember): SerializableBattleLogMember {
        return SerializableBattleLogMember(
            discipleId = member.id,
            name = member.name,
            realm = member.realm,
            isAlive = member.isAlive,
            remainingHp = member.hp,
            maxHp = member.maxHp,
            remainingMp = member.mp,
            maxMp = member.maxMp,
            portraitRes = member.portraitRes
        )
    }

    private fun convertBackBattleLogMember(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogMember {
        return com.xianxia.sect.core.model.BattleLogMember(
            id = data.discipleId,
            name = data.name,
            realm = data.realm,
            hp = data.remainingHp,
            maxHp = data.maxHp,
            mp = data.remainingMp,
            maxMp = data.maxMp,
            isAlive = data.isAlive,
            portraitRes = data.portraitRes
        )
    }

    private fun convertBattleLogEnemy(enemy: com.xianxia.sect.core.model.BattleLogEnemy): SerializableBattleLogMember {
        return SerializableBattleLogMember(
            discipleId = enemy.id,
            name = enemy.name,
            realm = enemy.realm,
            isAlive = enemy.isAlive,
            remainingHp = enemy.hp,
            maxHp = enemy.maxHp,
            portraitRes = enemy.portraitRes
        )
    }

    private fun convertBackBattleLogEnemy(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogEnemy {
        return com.xianxia.sect.core.model.BattleLogEnemy(
            id = data.discipleId,
            name = data.name,
            realm = data.realm,
            hp = data.remainingHp,
            maxHp = data.maxHp,
            isAlive = data.isAlive,
            portraitRes = data.portraitRes
        )
    }

    private fun convertAlliance(alliance: com.xianxia.sect.core.model.Alliance): SerializableAlliance {
        return SerializableAlliance(
            id = alliance.id ?: "",
            sectIds = alliance.sectIds ?: emptyList(),
            startYear = alliance.startYear ?: 0,
            initiatorId = alliance.initiatorId ?: "",
            envoyDiscipleId = alliance.envoyDiscipleId ?: ""
        )
    }

    private fun convertBackAlliance(data: SerializableAlliance): com.xianxia.sect.core.model.Alliance {
        return com.xianxia.sect.core.model.Alliance(
            id = data.id,
            sectIds = data.sectIds,
            startYear = data.startYear,
            initiatorId = data.initiatorId,
            envoyDiscipleId = data.envoyDiscipleId
        )
    }

    private fun convertWorldSect(sect: com.xianxia.sect.core.model.WorldSect, detail: com.xianxia.sect.core.model.SectDetail?): SerializableWorldSect {
        return SerializableWorldSect(
            id = sect.id ?: "",
            name = sect.name ?: "",
            level = sect.level ?: 0,
            levelName = sect.levelName ?: "",
            x = sect.x ?: 0f,
            y = sect.y ?: 0f,
            distance = sect.distance ?: 0,
            isPlayerSect = sect.isPlayerSect ?: false,
            discovered = sect.discovered ?: false,
            isKnown = sect.isKnown ?: false,
            relation = sect.relation ?: 0,
            disciples = sect.disciples ?: emptyMap(),
            maxRealm = sect.maxRealm ?: 0,
            connectedSectIds = sect.connectedSectIds ?: emptyList(),
            isOccupied = sect.isOccupied ?: false,
            occupierTeamId = sect.occupierTeamId ?: "",
            occupierTeamName = sect.occupierTeamName ?: "",
            mineSlots = detail?.mineSlots?.map { convertMineSlot(it) } ?: emptyList(),
            occupationTime = detail?.occupationTime ?: 0L,
            isOwned = detail?.isOwned ?: false,
            expiryYear = detail?.expiryYear ?: 0,
            expiryMonth = detail?.expiryMonth ?: 0,
            scoutInfo = detail?.scoutInfo?.let { convertSectScoutInfo(it) } ?: SerializableSectScoutInfo(sectId="", sectName="", scoutYear=0, scoutMonth=0, discipleCount=0, maxRealm=0, isKnown=false, expiryYear=0, expiryMonth=0),
            tradeItems = detail?.tradeItems?.map { convertMerchantItem(it) } ?: emptyList(),
            tradeLastRefreshYear = detail?.tradeLastRefreshYear ?: 0,
            lastGiftYear = detail?.lastGiftYear ?: 0,
            allianceId = sect.allianceId ?: "",
            allianceStartYear = sect.allianceStartYear ?: 0,
            isRighteous = sect.isRighteous ?: true,
            isPlayerOccupied = sect.isPlayerOccupied ?: false,
            isUnderAttack = sect.isUnderAttack ?: false,
            attackerSectId = sect.attackerSectId ?: "",
            occupierSectId = sect.occupierSectId ?: "",
            warehouse = detail?.warehouse?.let { convertSectWarehouse(it) } ?: SerializableSectWarehouse(),
            giftPreference = detail?.giftPreference?.name ?: "NONE",
        )
    }

    private fun convertBackWorldSect(data: SerializableWorldSect): com.xianxia.sect.core.model.WorldSect {
        val occupierTeamId = data.occupierTeamId.ifEmpty { "" }
        val allianceId = data.allianceId.ifEmpty { "" }
        val attackerSectId = data.attackerSectId.ifEmpty { "" }
        val occupierSectId = data.occupierSectId.ifEmpty { "" }
        return com.xianxia.sect.core.model.WorldSect(
            id = data.id,
            name = data.name,
            level = data.level,
            levelName = data.levelName,
            x = data.x,
            y = data.y,
            distance = data.distance,
            isPlayerSect = data.isPlayerSect,
            discovered = data.discovered,
            isKnown = data.isKnown,
            relation = data.relation,
            disciples = data.disciples,
            maxRealm = data.maxRealm,
            connectedSectIds = data.connectedSectIds,
            isOccupied = data.isOccupied,
            occupierTeamId = occupierTeamId,
            occupierTeamName = data.occupierTeamName,
            allianceId = allianceId,
            allianceStartYear = data.allianceStartYear,
            isRighteous = data.isRighteous,
            isPlayerOccupied = data.isPlayerOccupied,
            isUnderAttack = data.isUnderAttack,
            attackerSectId = attackerSectId,
            occupierSectId = occupierSectId,
        )
    }

    private fun extractSectDetailFromWorldSect(data: SerializableWorldSect): com.xianxia.sect.core.model.SectDetail {
        val scoutInfo = data.scoutInfo.takeIf { it.sectId.isNotEmpty() }?.let { convertBackSectScoutInfo(it) } ?: com.xianxia.sect.core.model.SectScoutInfo()

        return com.xianxia.sect.core.model.SectDetail(
            sectId = data.id,
            mineSlots = data.mineSlots.map { convertBackMineSlot(it) },
            occupationTime = data.occupationTime,
            isOwned = data.isOwned,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth,
            scoutInfo = scoutInfo,
            tradeItems = data.tradeItems.map { convertBackMerchantItem(it) },
            tradeLastRefreshYear = data.tradeLastRefreshYear,
            lastGiftYear = data.lastGiftYear,
            warehouse = convertBackSectWarehouse(data.warehouse),
            giftPreference = try {
                com.xianxia.sect.core.model.GiftPreferenceType.valueOf(data.giftPreference)
            } catch (e: Exception) {
                com.xianxia.sect.core.model.GiftPreferenceType.NONE
            }
        )
    }

    private fun convertSectDetail(detail: com.xianxia.sect.core.model.SectDetail): SerializableSectDetail {
        return SerializableSectDetail(
            sectId = detail.sectId,
            mineSlots = detail.mineSlots.map { convertMineSlot(it) },
            occupationTime = detail.occupationTime,
            isOwned = detail.isOwned,
            expiryYear = detail.expiryYear,
            expiryMonth = detail.expiryMonth,
            scoutInfo = convertSectScoutInfo(detail.scoutInfo),
            tradeItems = detail.tradeItems.map { convertMerchantItem(it) },
            tradeLastRefreshYear = detail.tradeLastRefreshYear,
            lastGiftYear = detail.lastGiftYear,
            warehouse = convertSectWarehouse(detail.warehouse),
            giftPreference = detail.giftPreference.name
        )
    }

    private fun convertBackSectDetail(data: SerializableSectDetail): com.xianxia.sect.core.model.SectDetail {
        val scoutInfo = data.scoutInfo.takeIf { it.sectId.isNotEmpty() }?.let { convertBackSectScoutInfo(it) } ?: com.xianxia.sect.core.model.SectScoutInfo()

        return com.xianxia.sect.core.model.SectDetail(
            sectId = data.sectId,
            mineSlots = data.mineSlots.map { convertBackMineSlot(it) },
            occupationTime = data.occupationTime,
            isOwned = data.isOwned,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth,
            scoutInfo = scoutInfo,
            tradeItems = data.tradeItems.map { convertBackMerchantItem(it) },
            tradeLastRefreshYear = data.tradeLastRefreshYear,
            lastGiftYear = data.lastGiftYear,
            warehouse = convertBackSectWarehouse(data.warehouse),
            giftPreference = try {
                com.xianxia.sect.core.model.GiftPreferenceType.valueOf(data.giftPreference)
            } catch (e: Exception) {
                com.xianxia.sect.core.model.GiftPreferenceType.NONE
            }
        )
    }

    private fun convertMineSlot(slot: com.xianxia.sect.core.model.MineSlot): SerializableMineSlot {
        return SerializableMineSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            output = slot.output ?: 0,
            efficiency = slot.efficiency ?: 1.0,
            isActive = slot.isActive ?: false
        )
    }

    private fun convertBackMineSlot(data: SerializableMineSlot): com.xianxia.sect.core.model.MineSlot {
        return com.xianxia.sect.core.model.MineSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            output = data.output,
            efficiency = data.efficiency,
            isActive = data.isActive
        )
    }

    private fun convertSectWarehouse(warehouse: com.xianxia.sect.core.model.SectWarehouse?): SerializableSectWarehouse {
        if (warehouse == null) return SerializableSectWarehouse()
        return SerializableSectWarehouse(
            items = warehouse.items?.map { convertWarehouseItem(it) } ?: emptyList(),
            spiritStones = warehouse.spiritStones ?: 0L
        )
    }

    private fun convertBackSectWarehouse(data: SerializableSectWarehouse): com.xianxia.sect.core.model.SectWarehouse {
        return com.xianxia.sect.core.model.SectWarehouse(
            items = data.items.map { convertBackWarehouseItem(it) },
            spiritStones = data.spiritStones
        )
    }

    private fun convertWarehouseItem(item: com.xianxia.sect.core.model.WarehouseItem): SerializableWarehouseItem {
        return SerializableWarehouseItem(
            itemId = item.itemId ?: "",
            itemName = item.itemName ?: "",
            itemType = item.itemType ?: "",
            rarity = item.rarity ?: 0,
            quantity = item.quantity ?: 0
        )
    }

    private fun convertBackWarehouseItem(data: SerializableWarehouseItem): com.xianxia.sect.core.model.WarehouseItem {
        return com.xianxia.sect.core.model.WarehouseItem(
            itemId = data.itemId,
            itemName = data.itemName,
            itemType = data.itemType,
            rarity = data.rarity,
            quantity = data.quantity
        )
    }

    private fun convertSectScoutInfo(info: com.xianxia.sect.core.model.SectScoutInfo): SerializableSectScoutInfo {
        return SerializableSectScoutInfo(
            sectId = info.sectId ?: "",
            sectName = info.sectName ?: "",
            scoutYear = info.scoutYear ?: 0,
            scoutMonth = info.scoutMonth ?: 0,
            discipleCount = info.discipleCount ?: 0,
            maxRealm = info.maxRealm ?: 0,
            resources = info.resources ?: emptyMap(),
            isKnown = info.isKnown ?: false,
            disciples = info.disciples ?: emptyMap(),
            expiryYear = info.expiryYear ?: 0,
            expiryMonth = info.expiryMonth ?: 0
        )
    }

    private fun convertBackSectScoutInfo(data: SerializableSectScoutInfo): com.xianxia.sect.core.model.SectScoutInfo {
        return com.xianxia.sect.core.model.SectScoutInfo(
            sectId = data.sectId,
            sectName = data.sectName,
            scoutYear = data.scoutYear,
            scoutMonth = data.scoutMonth,
            discipleCount = data.discipleCount,
            maxRealm = data.maxRealm,
            resources = data.resources,
            isKnown = data.isKnown,
            disciples = data.disciples,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth
        )
    }

    private fun convertExploredSectInfo(info: com.xianxia.sect.core.model.ExploredSectInfo): SerializableExploredSectInfo {
        return SerializableExploredSectInfo(
            sectId = info.sectId ?: "",
            sectName = info.sectName ?: "",
            year = info.year ?: 0,
            month = info.month ?: 0,
            duration = info.duration ?: 0,
            memberIds = info.memberIds ?: emptyList(),
            memberNames = info.memberNames ?: emptyList(),
            events = info.events ?: emptyList(),
            rewards = info.rewards ?: emptyList(),
            battleCount = info.battleCount ?: 0,
            casualties = info.casualties ?: 0,
            discipleCount = info.discipleCount ?: 0,
            maxRealm = info.maxRealm ?: 0
        )
    }

    private fun convertBackExploredSectInfo(data: SerializableExploredSectInfo): com.xianxia.sect.core.model.ExploredSectInfo {
        return com.xianxia.sect.core.model.ExploredSectInfo(
            sectId = data.sectId,
            sectName = data.sectName,
            year = data.year,
            month = data.month,
            duration = data.duration,
            memberIds = data.memberIds,
            memberNames = data.memberNames,
            events = data.events,
            rewards = data.rewards,
            battleCount = data.battleCount,
            casualties = data.casualties,
            discipleCount = data.discipleCount,
            maxRealm = data.maxRealm
        )
    }

    private fun convertMerchantItem(item: com.xianxia.sect.core.model.MerchantItem): SerializableMerchantItem {
        return SerializableMerchantItem(
            id = item.id ?: "",
            name = item.name ?: "",
            type = item.type ?: "",
            itemId = item.itemId ?: "",
            rarity = item.rarity ?: 0,
            price = item.price,
            quantity = item.quantity ?: 0,
            description = item.description ?: "",
            obtainedYear = item.obtainedYear ?: 1,
            obtainedMonth = item.obtainedMonth ?: 1,
            grade = item.grade ?: ""
        )
    }

    private fun convertBackMerchantItem(data: SerializableMerchantItem): com.xianxia.sect.core.model.MerchantItem {
        return com.xianxia.sect.core.model.MerchantItem(
            id = data.id,
            name = data.name,
            type = data.type,
            itemId = data.itemId,
            rarity = data.rarity,
            price = data.price,
            quantity = data.quantity,
            description = data.description,
            obtainedYear = data.obtainedYear,
            obtainedMonth = data.obtainedMonth,
            grade = data.grade.takeIf { it.isNotEmpty() }
        )
    }

    private fun convertPlantSlot(slot: com.xianxia.sect.core.model.PlantSlotData): SerializablePlantSlotData {
        return SerializablePlantSlotData(
            index = slot.index ?: 0,
            status = slot.status ?: "empty",
            seedId = slot.seedId ?: "",
            seedName = slot.seedName ?: "",
            startYear = slot.startYear ?: 0,
            startMonth = slot.startMonth ?: 0,
            growTime = slot.growTime ?: 0,
            expectedYield = slot.expectedYield ?: 0,
            harvestAmount = slot.expectedYield ?: 0,
            harvestHerbId = slot.seedId ?: ""
        )
    }

    private fun convertBackPlantSlot(data: SerializablePlantSlotData): com.xianxia.sect.core.model.PlantSlotData {
        return com.xianxia.sect.core.model.PlantSlotData(
            index = data.index,
            status = data.status,
            seedId = data.seedId,
            seedName = data.seedName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            growTime = data.growTime,
            expectedYield = data.expectedYield,
            harvestAmount = data.expectedYield,
            harvestHerbId = data.seedId
        )
    }

    private fun convertManualProficiency(prof: com.xianxia.sect.core.model.ManualProficiencyData): SerializableManualProficiencyData {
        return SerializableManualProficiencyData(
            manualId = prof.manualId ?: "",
            manualName = prof.manualName ?: "",
            proficiency = prof.proficiency ?: 0.0,
            maxProficiency = prof.maxProficiency ?: 0,
            level = prof.level ?: 0,
            masteryLevel = prof.masteryLevel ?: 0
        )
    }

    private fun convertBackManualProficiency(data: SerializableManualProficiencyData): com.xianxia.sect.core.model.ManualProficiencyData {
        return com.xianxia.sect.core.model.ManualProficiencyData(
            manualId = data.manualId,
            manualName = data.manualName,
            proficiency = data.proficiency,
            maxProficiency = data.maxProficiency,
            level = data.level,
            masteryLevel = data.masteryLevel
        )
    }

    private fun convertCultivatorCave(cave: com.xianxia.sect.core.model.CultivatorCave): SerializableCultivatorCave {
        return SerializableCultivatorCave(
            id = cave.id,
            name = cave.name,
            level = cave.ownerRealm,
            x = cave.x,
            y = cave.y,
            ownerSectId = "",
            ownerSectName = cave.ownerRealmName,
            discovered = cave.isExplored,
            spawnYear = cave.spawnYear,
            spawnMonth = cave.spawnMonth,
            expiryYear = cave.expiryYear,
            expiryMonth = cave.expiryMonth,
            exploredByTeamId = cave.exploredByTeamId ?: "",
            status = cave.status.name,
            canOperate = cave.canOperate,
            isOwned = cave.isOwned,
            connectedSects = cave.connectedSects,
            mineSlots = cave.mineSlots.map { convertMineSlot(it) },
            occupationTime = cave.occupationTime
        )
    }

    private fun convertBackCultivatorCave(data: SerializableCultivatorCave): com.xianxia.sect.core.model.CultivatorCave {
        return com.xianxia.sect.core.model.CultivatorCave(
            id = data.id,
            name = data.name,
            ownerRealm = data.level,
            ownerRealmName = data.ownerSectName,
            x = data.x,
            y = data.y,
            spawnYear = data.spawnYear,
            spawnMonth = data.spawnMonth,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth,
            isExplored = data.discovered,
            exploredByTeamId = data.exploredByTeamId.ifEmpty { null },
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.CaveStatus.AVAILABLE, "status", "CultivatorCave"),
            canOperate = data.canOperate,
            isOwned = data.isOwned,
            connectedSects = data.connectedSects,
            mineSlots = data.mineSlots.map { convertBackMineSlot(it) },
            occupationTime = data.occupationTime
        )
    }

    private fun convertCaveExplorationTeam(team: com.xianxia.sect.core.model.CaveExplorationTeam): SerializableCaveExplorationTeam {
        return SerializableCaveExplorationTeam(
            id = team.id,
            name = team.caveName,
            memberIds = team.memberIds,
            targetCaveId = team.caveId,
            status = team.status.name,
            startYear = team.startYear,
            startMonth = team.startMonth,
            duration = team.duration
        )
    }

    private fun convertBackCaveExplorationTeam(data: SerializableCaveExplorationTeam): com.xianxia.sect.core.model.CaveExplorationTeam {
        return com.xianxia.sect.core.model.CaveExplorationTeam(
            id = data.id,
            caveId = data.targetCaveId,
            caveName = data.name,
            memberIds = data.memberIds,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.duration,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.CaveExplorationStatus.TRAVELING, "status", "CaveExplorationTeam")
        )
    }

    private fun convertAICaveTeam(team: com.xianxia.sect.core.model.AICaveTeam): SerializableAICaveTeam {
        return SerializableAICaveTeam(
            id = team.id,
            sectId = team.sectId,
            sectName = team.sectName,
            targetCaveId = team.caveId,
            disciples = team.disciples.map { convertAICaveDisciple(it) },
            status = team.status.name,
            startYear = 0,
            startMonth = 0,
            memberCount = team.memberCount,
            avgRealm = team.avgRealm,
            avgRealmName = team.avgRealmName
        )
    }

    private fun convertBackAICaveTeam(data: SerializableAICaveTeam): com.xianxia.sect.core.model.AICaveTeam {
        return com.xianxia.sect.core.model.AICaveTeam(
            id = data.id,
            caveId = data.targetCaveId,
            sectId = data.sectId,
            sectName = data.sectName,
            disciples = data.disciples.map { convertBackAICaveDisciple(it) },
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.AITeamStatus.EXPLORING, "status", "AICaveTeam"),
            memberCount = data.memberCount,
            avgRealm = data.avgRealm,
            avgRealmName = data.avgRealmName
        )
    }

    private fun convertAICaveDisciple(disciple: com.xianxia.sect.core.model.AICaveDisciple): SerializableAICaveDisciple {
        return SerializableAICaveDisciple(
            id = disciple.id,
            name = disciple.name,
            realm = disciple.realm,
            realmName = disciple.realmName,
            hp = disciple.hp,
            maxHp = disciple.maxHp,
            mp = disciple.mp,
            maxMp = disciple.maxMp,
            physicalAttack = disciple.physicalAttack,
            magicAttack = disciple.magicAttack,
            physicalDefense = disciple.physicalDefense,
            magicDefense = disciple.magicDefense,
            speed = disciple.speed,
            critRate = disciple.critRate,
            equipments = disciple.equipments.map { convertAIRandomEquipment(it) },
            manuals = disciple.manuals.map { convertAIRandomManual(it) }
        )
    }

    private fun convertBackAICaveDisciple(data: SerializableAICaveDisciple): com.xianxia.sect.core.model.AICaveDisciple {
        return com.xianxia.sect.core.model.AICaveDisciple(
            id = data.id,
            name = data.name,
            realm = data.realm,
            realmName = data.realmName,
            hp = data.hp,
            maxHp = data.maxHp,
            mp = data.mp,
            maxMp = data.maxMp,
            physicalAttack = data.physicalAttack,
            magicAttack = data.magicAttack,
            physicalDefense = data.physicalDefense,
            magicDefense = data.magicDefense,
            speed = data.speed,
            critRate = data.critRate,
            equipments = data.equipments.map { convertBackAIRandomEquipment(it) },
            manuals = data.manuals.map { convertBackAIRandomManual(it) }
        )
    }

    private fun convertAIRandomEquipment(equipment: com.xianxia.sect.core.model.AIRandomEquipment): SerializableAIRandomEquipment {
        return SerializableAIRandomEquipment(
            slot = equipment.slot.name,
            name = equipment.name,
            rarity = equipment.rarity,
            nurtureLevel = equipment.nurtureLevel,
            physicalAttack = equipment.physicalAttack,
            magicAttack = equipment.magicAttack,
            physicalDefense = equipment.physicalDefense,
            magicDefense = equipment.magicDefense,
            speed = equipment.speed,
            hp = equipment.hp,
            mp = equipment.mp
        )
    }

    private fun convertBackAIRandomEquipment(data: SerializableAIRandomEquipment): com.xianxia.sect.core.model.AIRandomEquipment {
        return com.xianxia.sect.core.model.AIRandomEquipment(
            slot = safeEnumValueOf(data.slot, com.xianxia.sect.core.model.EquipmentSlot.WEAPON, "slot", "AIRandomEquipment"),
            name = data.name,
            rarity = data.rarity,
            nurtureLevel = data.nurtureLevel,
            physicalAttack = data.physicalAttack,
            magicAttack = data.magicAttack,
            physicalDefense = data.physicalDefense,
            magicDefense = data.magicDefense,
            speed = data.speed,
            hp = data.hp,
            mp = data.mp
        )
    }

    private fun convertAIRandomManual(manual: com.xianxia.sect.core.model.AIRandomManual): SerializableAIRandomManual {
        return SerializableAIRandomManual(
            name = manual.name,
            rarity = manual.rarity,
            mastery = manual.mastery,
            stats = manual.stats
        )
    }

    private fun convertBackAIRandomManual(data: SerializableAIRandomManual): com.xianxia.sect.core.model.AIRandomManual {
        return com.xianxia.sect.core.model.AIRandomManual(
            name = data.name,
            rarity = data.rarity,
            mastery = data.mastery,
            stats = data.stats
        )
    }

    private fun convertElderSlots(slots: com.xianxia.sect.core.model.ElderSlots?): SerializableElderSlots {
        if (slots == null) return SerializableElderSlots()
        return SerializableElderSlots(
            viceSectMaster = slots.viceSectMaster ?: "",
            herbGardenElder = slots.herbGardenElder ?: "",
            alchemyElder = slots.alchemyElder ?: "",
            forgeElder = slots.forgeElder ?: "",
            outerElder = slots.outerElder ?: "",
            preachingElder = slots.preachingElder ?: "",
            preachingMasters = slots.preachingMasters?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            lawEnforcementElder = slots.lawEnforcementElder ?: "",
            lawEnforcementDisciples = slots.lawEnforcementDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            lawEnforcementReserveDisciples = slots.lawEnforcementReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            innerElder = slots.innerElder ?: "",
            qingyunPreachingElder = slots.qingyunPreachingElder ?: "",
            qingyunPreachingMasters = slots.qingyunPreachingMasters?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            herbGardenDisciples = slots.herbGardenDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            alchemyDisciples = slots.alchemyDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            forgeDisciples = slots.forgeDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            herbGardenReserveDisciples = slots.herbGardenReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            alchemyReserveDisciples = slots.alchemyReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            forgeReserveDisciples = slots.forgeReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            spiritMineDeaconDisciples = slots.spiritMineDeaconDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList()
        )
    }

    private fun convertBackElderSlots(data: SerializableElderSlots): com.xianxia.sect.core.model.ElderSlots {
        return com.xianxia.sect.core.model.ElderSlots(
            viceSectMaster = data.viceSectMaster,
            herbGardenElder = data.herbGardenElder,
            alchemyElder = data.alchemyElder,
            forgeElder = data.forgeElder,
            outerElder = data.outerElder,
            preachingElder = data.preachingElder,
            preachingMasters = data.preachingMasters.map { convertBackDirectDiscipleSlot(it) },
            lawEnforcementElder = data.lawEnforcementElder,
            lawEnforcementDisciples = data.lawEnforcementDisciples.map { convertBackDirectDiscipleSlot(it) },
            lawEnforcementReserveDisciples = data.lawEnforcementReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            innerElder = data.innerElder,
            qingyunPreachingElder = data.qingyunPreachingElder,
            qingyunPreachingMasters = data.qingyunPreachingMasters.map { convertBackDirectDiscipleSlot(it) },
            herbGardenDisciples = data.herbGardenDisciples.map { convertBackDirectDiscipleSlot(it) },
            alchemyDisciples = data.alchemyDisciples.map { convertBackDirectDiscipleSlot(it) },
            forgeDisciples = data.forgeDisciples.map { convertBackDirectDiscipleSlot(it) },
            herbGardenReserveDisciples = data.herbGardenReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            alchemyReserveDisciples = data.alchemyReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            forgeReserveDisciples = data.forgeReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            spiritMineDeaconDisciples = data.spiritMineDeaconDisciples.map { convertBackDirectDiscipleSlot(it) }
        )
    }

    private fun convertDirectDiscipleSlot(slot: com.xianxia.sect.core.model.DirectDiscipleSlot): SerializableDirectDiscipleSlot {
        return SerializableDirectDiscipleSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            discipleRealm = slot.discipleRealm ?: "",
            discipleSpiritRootColor = slot.discipleSpiritRootColor ?: "",
            sectId = slot.sectId ?: ""
        )
    }

    private fun convertBackDirectDiscipleSlot(data: SerializableDirectDiscipleSlot): com.xianxia.sect.core.model.DirectDiscipleSlot {
        return com.xianxia.sect.core.model.DirectDiscipleSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            discipleRealm = data.discipleRealm,
            discipleSpiritRootColor = data.discipleSpiritRootColor,
            sectId = data.sectId
        )
    }

    private fun convertSpiritMineSlot(slot: com.xianxia.sect.core.model.SpiritMineSlot): SerializableSpiritMineSlot {
        return SerializableSpiritMineSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            output = slot.output ?: 0,
            sectId = slot.sectId ?: ""
        )
    }

    private fun convertBackSpiritMineSlot(data: SerializableSpiritMineSlot): com.xianxia.sect.core.model.SpiritMineSlot {
        return com.xianxia.sect.core.model.SpiritMineSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            output = data.output,
            sectId = data.sectId
        )
    }

    private fun convertLibrarySlot(slot: com.xianxia.sect.core.model.LibrarySlot): SerializableLibrarySlot {
        return SerializableLibrarySlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: ""
        )
    }

    private fun convertBackLibrarySlot(data: SerializableLibrarySlot): com.xianxia.sect.core.model.LibrarySlot {
        return com.xianxia.sect.core.model.LibrarySlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName
        )
    }

    private fun convertResidenceSlot(slot: com.xianxia.sect.core.model.ResidenceSlot): SerializableResidenceSlot {
        return SerializableResidenceSlot(
            buildingInstanceId = slot.buildingInstanceId,
            slotIndex = slot.slotIndex,
            discipleId = slot.discipleId,
            discipleName = slot.discipleName
        )
    }

    private fun convertBackResidenceSlot(data: SerializableResidenceSlot): com.xianxia.sect.core.model.ResidenceSlot {
        return com.xianxia.sect.core.model.ResidenceSlot(
            buildingInstanceId = data.buildingInstanceId,
            slotIndex = data.slotIndex,
            discipleId = data.discipleId,
            discipleName = data.discipleName
        )
    }

    private fun convertBuildingSlot(slot: com.xianxia.sect.core.model.BuildingSlot): SerializableBuildingSlot {
        return SerializableBuildingSlot(
            id = slot.id,
            type = slot.type.name,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName,
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            progress = 0.0,
            status = slot.status.name,
            startYear = slot.startYear,
            startMonth = slot.startMonth,
            resultItemId = "",
            resultQuantity = 0
        )
    }

    private fun convertBackBuildingSlot(data: SerializableBuildingSlot): com.xianxia.sect.core.model.BuildingSlot {
        return com.xianxia.sect.core.model.BuildingSlot(
            id = data.id,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.production.SlotType.IDLE, "type", "BuildingSlot"),
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            recipeId = data.recipeId,
            recipeName = data.recipeName,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.SlotStatus.IDLE, "status", "BuildingSlot")
        )
    }

    private fun convertAlchemySlot(slot: com.xianxia.sect.core.model.AlchemySlot): SerializableAlchemySlot {
        return SerializableAlchemySlot(
            id = slot.id,
            discipleId = "",
            discipleName = "",
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            progress = 0.0,
            status = slot.status.name,
            startYear = slot.startYear,
            startMonth = slot.startMonth,
            resultItemId = "",
            resultQuantity = 0
        )
    }

    private fun convertBackAlchemySlot(data: SerializableAlchemySlot): com.xianxia.sect.core.model.AlchemySlot {
        return com.xianxia.sect.core.model.AlchemySlot(
            id = data.id,
            recipeId = data.recipeId,
            recipeName = data.recipeName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.AlchemySlotStatus.IDLE, "status", "AlchemySlot")
        )
    }

    private fun convertProductionSlot(slot: com.xianxia.sect.core.model.production.ProductionSlot): SerializableProductionSlot {
        return SerializableProductionSlot(
            id = slot.id,
            slotIndex = slot.slotIndex,
            buildingType = slot.buildingType.name,
            buildingId = slot.buildingId,
            status = slot.status.name,
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            startYear = slot.startYear,
            startMonth = slot.startMonth,
            duration = slot.duration,
            assignedDiscipleId = slot.assignedDiscipleId ?: "",
            assignedDiscipleName = slot.assignedDiscipleName,
            successRate = slot.successRate,
            outputItemId = slot.outputItemId ?: "",
            outputItemName = slot.outputItemName,
            outputItemRarity = slot.outputItemRarity
        )
    }

    private fun convertBackProductionSlot(data: SerializableProductionSlot): com.xianxia.sect.core.model.production.ProductionSlot {
        return com.xianxia.sect.core.model.production.ProductionSlot(
            id = data.id,
            slotIndex = data.slotIndex,
            buildingType = safeEnumValueOf(data.buildingType, com.xianxia.sect.core.model.production.BuildingType.ALCHEMY, "buildingType", "ProductionSlot"),
            buildingId = data.buildingId,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE, "status", "ProductionSlot"),
            recipeId = data.recipeId,
            recipeName = data.recipeName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.duration,
            assignedDiscipleId = data.assignedDiscipleId,
            assignedDiscipleName = data.assignedDiscipleName,
            successRate = data.successRate,
            outputItemId = data.outputItemId,
            outputItemName = data.outputItemName,
            outputItemRarity = data.outputItemRarity
        )
    }

    private fun convertSectRelation(relation: com.xianxia.sect.core.model.SectRelation): SerializableSectRelation {
        return SerializableSectRelation(
            sectId1 = relation.sectId1 ?: "",
            sectId2 = relation.sectId2 ?: "",
            favor = relation.favor ?: 0,
            lastInteractionYear = relation.lastInteractionYear ?: 0,
            noGiftYears = relation.noGiftYears ?: 0
        )
    }

    private fun convertBackSectRelation(data: SerializableSectRelation): com.xianxia.sect.core.model.SectRelation {
        return com.xianxia.sect.core.model.SectRelation(
            sectId1 = data.sectId1,
            sectId2 = data.sectId2,
            favor = data.favor,
            lastInteractionYear = data.lastInteractionYear,
            noGiftYears = data.noGiftYears
        )
    }

    private fun convertSectPolicies(policies: com.xianxia.sect.core.model.SectPolicies?): SerializableSectPolicies {
        if (policies == null) return SerializableSectPolicies()
        return SerializableSectPolicies(
            spiritMineBoost = policies.spiritMineBoost ?: false,
            enhancedSecurity = policies.enhancedSecurity ?: false,
            alchemyIncentive = policies.alchemyIncentive ?: false,
            forgeIncentive = policies.forgeIncentive ?: false,
            herbCultivation = policies.herbCultivation ?: false,
            cultivationSubsidy = policies.cultivationSubsidy ?: false,
            manualResearch = policies.manualResearch ?: false,
            autoPlant = policies.autoPlant ?: false,
            autoAlchemy = policies.autoAlchemy ?: false,
            autoForge = policies.autoForge ?: false
        )
    }

    private fun convertBackSectPolicies(data: SerializableSectPolicies): com.xianxia.sect.core.model.SectPolicies {
        return com.xianxia.sect.core.model.SectPolicies(
            spiritMineBoost = data.spiritMineBoost,
            enhancedSecurity = data.enhancedSecurity,
            alchemyIncentive = data.alchemyIncentive,
            forgeIncentive = data.forgeIncentive,
            herbCultivation = data.herbCultivation,
            cultivationSubsidy = data.cultivationSubsidy,
            manualResearch = data.manualResearch,
            autoPlant = data.autoPlant,
            autoAlchemy = data.autoAlchemy,
            autoForge = data.autoForge
        )
    }

    private fun convertActiveMission(mission: com.xianxia.sect.core.model.ActiveMission): SerializableActiveMission {
        return SerializableActiveMission(
            id = mission.id,
            missionId = mission.missionId,
            name = mission.missionName,
            description = "",
            startYear = mission.startYear,
            startMonth = mission.startMonth,
            assignedDisciples = mission.discipleIds,
            progress = 0,
            targetProgress = mission.duration,
            status = "ACTIVE",
            missionType = mission.template.name,
            difficulty = mission.difficulty.ordinal,
            discipleNames = mission.discipleNames,
            discipleRealms = mission.discipleRealms,
            spiritStones = mission.rewards.spiritStones,
            spiritStonesMax = mission.rewards.spiritStonesMax,
            materialCountMin = mission.rewards.materialCountMin,
            materialCountMax = mission.rewards.materialCountMax,
            materialMinRarity = mission.rewards.materialMinRarity,
            materialMaxRarity = mission.rewards.materialMaxRarity,
            pillCountMin = mission.rewards.pillCountMin,
            pillCountMax = mission.rewards.pillCountMax,
            pillMinRarity = mission.rewards.pillMinRarity,
            pillMaxRarity = mission.rewards.pillMaxRarity,
            equipmentChance = mission.rewards.equipmentChance,
            equipmentMinRarity = mission.rewards.equipmentMinRarity,
            equipmentMaxRarity = mission.rewards.equipmentMaxRarity,
            manualChance = mission.rewards.manualChance,
            manualMinRarity = mission.rewards.manualMinRarity,
            manualMaxRarity = mission.rewards.manualMaxRarity,
            baseSpiritStones = mission.rewards.baseSpiritStones,
            baseMaterialCountMin = mission.rewards.baseMaterialCountMin,
            baseMaterialCountMax = mission.rewards.baseMaterialCountMax,
            baseMaterialMinRarity = mission.rewards.baseMaterialMinRarity,
            baseMaterialMaxRarity = mission.rewards.baseMaterialMaxRarity
        )
    }

    private fun convertBackActiveMission(data: SerializableActiveMission): com.xianxia.sect.core.model.ActiveMission {
        val difficulty = com.xianxia.sect.core.model.MissionDifficulty.entries.getOrNull(data.difficulty)
            ?: com.xianxia.sect.core.model.MissionDifficulty.SIMPLE
        val template = migrateMissionTemplate(data.missionType)

        return com.xianxia.sect.core.model.ActiveMission(
            id = data.id,
            missionId = data.missionId,
            missionName = data.name,
            template = template,
            difficulty = difficulty,
            discipleIds = data.assignedDisciples,
            discipleNames = data.discipleNames.ifEmpty { data.assignedDisciples.map { "" } },
            discipleRealms = data.discipleRealms,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.targetProgress,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = data.spiritStones,
                spiritStonesMax = data.spiritStonesMax,
                materialCountMin = data.materialCountMin,
                materialCountMax = data.materialCountMax,
                materialMinRarity = data.materialMinRarity,
                materialMaxRarity = data.materialMaxRarity,
                pillCountMin = data.pillCountMin,
                pillCountMax = data.pillCountMax,
                pillMinRarity = data.pillMinRarity,
                pillMaxRarity = data.pillMaxRarity,
                equipmentChance = data.equipmentChance,
                equipmentMinRarity = data.equipmentMinRarity,
                equipmentMaxRarity = data.equipmentMaxRarity,
                manualChance = data.manualChance,
                manualMinRarity = data.manualMinRarity,
                manualMaxRarity = data.manualMaxRarity,
                baseSpiritStones = data.baseSpiritStones,
                baseMaterialCountMin = data.baseMaterialCountMin,
                baseMaterialCountMax = data.baseMaterialCountMax,
                baseMaterialMinRarity = data.baseMaterialMinRarity,
                baseMaterialMaxRarity = data.baseMaterialMaxRarity
            ),
            missionType = template.missionType,
            enemyType = template.enemyType,
            triggerChance = template.triggerChance
        )
    }

    private fun convertMission(mission: com.xianxia.sect.core.model.Mission): SerializableMission {
        return SerializableMission(
            id = mission.id,
            name = mission.name,
            description = mission.description,
            difficulty = mission.difficulty.ordinal,
            minDisciples = 1,
            maxDisciples = 5,
            duration = mission.duration,
            rewards = emptyMap(),
            requirements = emptyMap(),
            type = mission.template.name,
            createdYear = mission.createdYear,
            createdMonth = mission.createdMonth,
            spiritStones = mission.rewards.spiritStones,
            spiritStonesMax = mission.rewards.spiritStonesMax,
            materialCountMin = mission.rewards.materialCountMin,
            materialCountMax = mission.rewards.materialCountMax,
            materialMinRarity = mission.rewards.materialMinRarity,
            materialMaxRarity = mission.rewards.materialMaxRarity,
            pillCountMin = mission.rewards.pillCountMin,
            pillCountMax = mission.rewards.pillCountMax,
            pillMinRarity = mission.rewards.pillMinRarity,
            pillMaxRarity = mission.rewards.pillMaxRarity,
            equipmentChance = mission.rewards.equipmentChance,
            equipmentMinRarity = mission.rewards.equipmentMinRarity,
            equipmentMaxRarity = mission.rewards.equipmentMaxRarity,
            manualChance = mission.rewards.manualChance,
            manualMinRarity = mission.rewards.manualMinRarity,
            manualMaxRarity = mission.rewards.manualMaxRarity,
            baseSpiritStones = mission.rewards.baseSpiritStones,
            baseMaterialCountMin = mission.rewards.baseMaterialCountMin,
            baseMaterialCountMax = mission.rewards.baseMaterialCountMax,
            baseMaterialMinRarity = mission.rewards.baseMaterialMinRarity,
            baseMaterialMaxRarity = mission.rewards.baseMaterialMaxRarity
        )
    }

    private fun convertBackMission(data: SerializableMission): com.xianxia.sect.core.model.Mission {
        val difficulty = com.xianxia.sect.core.model.MissionDifficulty.entries.getOrNull(data.difficulty)
            ?: com.xianxia.sect.core.model.MissionDifficulty.SIMPLE
        val template = migrateMissionTemplate(data.type)

        return com.xianxia.sect.core.model.Mission(
            id = data.id,
            template = template,
            name = data.name,
            description = data.description,
            difficulty = difficulty,
            duration = data.duration,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = data.spiritStones,
                spiritStonesMax = data.spiritStonesMax,
                materialCountMin = data.materialCountMin,
                materialCountMax = data.materialCountMax,
                materialMinRarity = data.materialMinRarity,
                materialMaxRarity = data.materialMaxRarity,
                pillCountMin = data.pillCountMin,
                pillCountMax = data.pillCountMax,
                pillMinRarity = data.pillMinRarity,
                pillMaxRarity = data.pillMaxRarity,
                equipmentChance = data.equipmentChance,
                equipmentMinRarity = data.equipmentMinRarity,
                equipmentMaxRarity = data.equipmentMaxRarity,
                manualChance = data.manualChance,
                manualMinRarity = data.manualMinRarity,
                manualMaxRarity = data.manualMaxRarity,
                baseSpiritStones = data.baseSpiritStones,
                baseMaterialCountMin = data.baseMaterialCountMin,
                baseMaterialCountMax = data.baseMaterialCountMax,
                baseMaterialMinRarity = data.baseMaterialMinRarity,
                baseMaterialMaxRarity = data.baseMaterialMaxRarity
            ),
            missionType = template.missionType,
            enemyType = template.enemyType,
            triggerChance = template.triggerChance,
            createdYear = data.createdYear,
            createdMonth = data.createdMonth
        )
    }

    private fun migrateMissionTemplate(name: String): com.xianxia.sect.core.model.MissionTemplate {
        return when (name) {
            "ESCORT" -> com.xianxia.sect.core.model.MissionTemplate.ESCORT_CARAVAN
            "SUPPRESS_BEASTS" -> com.xianxia.sect.core.model.MissionTemplate.SUPPRESS_LOW_BEASTS
            "SUPPRESS_BEASTS_NORMAL" -> com.xianxia.sect.core.model.MissionTemplate.SUPPRESS_JINDAN_BEASTS
            else -> try {
                com.xianxia.sect.core.model.MissionTemplate.valueOf(name)
            } catch (e: Exception) {
                com.xianxia.sect.core.model.MissionTemplate.ESCORT_CARAVAN
            }
        }
    }
}
