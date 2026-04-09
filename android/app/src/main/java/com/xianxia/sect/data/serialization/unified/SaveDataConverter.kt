@file:Suppress("DEPRECATION")
package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import javax.inject.Inject
import javax.inject.Singleton

sealed class MigrationResult {
    data class Success(val data: SerializableSaveData) : MigrationResult()
    data class Failed(val error: Throwable, val fallbackData: SerializableSaveData? = null) : MigrationResult()
}

interface VersionMigrator {
    val fromVersion: String
    val toVersion: String
    suspend fun migrate(data: SerializableSaveData): SerializableSaveData
}

private inline fun <reified T : Enum<T>> safeEnumValueOf(
    value: String, 
    defaultValue: T,
    fieldName: String,
    context: String = ""
): T {
    return try {
        enumValueOf<T>(value)
    } catch (e: IllegalArgumentException) {
        val validValues = enumValues<T>().map { it.name }
        Log.w("SaveDataConverter", "Invalid enum value '$value' for field '$fieldName' in $context. " +
              "Valid values: ${validValues.joinToString()}. Using default: $defaultValue")
        defaultValue
    }
}


private inline fun <reified T : Enum<T>> safeEnumValueOfIgnoreCase(
    value: String, 
    defaultValue: T,
    fieldName: String,
    context: String = ""
): T {
    return try {
        enumValues<T>().find { it.name.equals(value, ignoreCase = true) } ?: run {
            val validValues = enumValues<T>().map { it.name }
            Log.w("SaveDataConverter", "Invalid enum value '$value' for field '$fieldName' in $context. " +
                  "Valid values: ${validValues.joinToString()}. Using default: $defaultValue")
            defaultValue
        }
    } catch (e: Exception) {
        Log.w("SaveDataConverter", "Error parsing enum value '$value' for field '$fieldName' in $context: ${e.message}")
        defaultValue
    }
}

@Singleton
class SaveDataMigrator @Inject constructor() {
    companion object {
        private const val TAG = "SaveDataMigrator"
        private const val CURRENT_VERSION = "2.0"
    }
    
    private val migrators = mutableListOf<VersionMigrator>()
    
    init {
        registerMigrator(V1ToV2Migrator())
    }
    
    fun registerMigrator(migrator: VersionMigrator) {
        migrators.add(migrator)
    }
    
    suspend fun migrate(data: SerializableSaveData): MigrationResult {
        var currentData = data
        
        try {
            while (currentData.version != CURRENT_VERSION) {
                val migrator = findMigrator(currentData.version)
                    ?: return MigrationResult.Failed(
                        IllegalStateException("No migrator found for version ${currentData.version}"),
                        currentData
                    )
                
                Log.i(TAG, "Migrating from ${migrator.fromVersion} to ${migrator.toVersion}")
                currentData = migrator.migrate(currentData)
            }
            
            return MigrationResult.Success(currentData)
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            return MigrationResult.Failed(e, currentData)
        }
    }
    
    fun needsMigration(version: String): Boolean {
        return version != CURRENT_VERSION
    }
    
    private fun findMigrator(fromVersion: String): VersionMigrator? {
        return migrators.find { it.fromVersion == fromVersion }
    }
}

class V1ToV2Migrator : VersionMigrator {
    override val fromVersion: String = "1.0"
    override val toVersion: String = "2.0"
    
    override suspend fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(
            version = toVersion,
            gameData = data.gameData.copy(
                playerProtectionEnabled = data.gameData.playerProtectionEnabled,
                playerProtectionStartYear = data.gameData.playerProtectionStartYear
            )
        )
    }
}

@Singleton
class SaveDataConverter @Inject constructor() {
    companion object {
        private const val TAG = "SaveDataConverter"
    }
    
    fun toSerializable(saveData: com.xianxia.sect.data.model.SaveData): SerializableSaveData {
        return SerializableSaveData(
            version = saveData.version ?: "1.0",
            timestamp = saveData.timestamp ?: System.currentTimeMillis(),
            gameData = convertGameData(saveData.gameData),
            disciples = saveData.disciples?.map { convertDisciple(it) } ?: emptyList(),
            equipment = saveData.equipment?.map { convertEquipment(it) } ?: emptyList(),
            manuals = saveData.manuals?.map { convertManual(it) } ?: emptyList(),
            pills = saveData.pills?.map { convertPill(it) } ?: emptyList(),
            materials = saveData.materials?.map { convertMaterial(it) } ?: emptyList(),
            herbs = saveData.herbs?.map { convertHerb(it) } ?: emptyList(),
            seeds = saveData.seeds?.map { convertSeed(it) } ?: emptyList(),
            teams = saveData.teams?.map { convertTeam(it) } ?: emptyList(),
            events = saveData.events?.map { convertEvent(it) } ?: emptyList(),
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
            equipment = data.equipment.map { convertBackEquipment(it) },
            manuals = data.manuals.map { convertBackManual(it) },
            pills = data.pills.map { convertBackPill(it) },
            materials = data.materials.map { convertBackMaterial(it) },
            herbs = data.herbs.map { convertBackHerb(it) },
            seeds = data.seeds.map { convertBackSeed(it) },
            teams = data.teams.map { convertBackTeam(it) },
            events = data.events.map { convertBackEvent(it) },
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
            worldMapSects = gameData.worldMapSects?.map { convertWorldSect(it) } ?: emptyList(),
            exploredSects = gameData.exploredSects?.mapValues { convertExploredSectInfo(it.value) } ?: emptyMap(),
            scoutInfo = gameData.scoutInfo?.mapValues { convertSectScoutInfo(it.value) } ?: emptyMap(),
            herbGardenPlantSlots = gameData.herbGardenPlantSlots?.map { convertPlantSlot(it) } ?: emptyList(),
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
            unlockedDungeons = gameData.unlockedDungeons ?: emptyList(),
            unlockedRecipes = gameData.unlockedRecipes ?: emptyList(),
            unlockedManuals = gameData.unlockedManuals ?: emptyList(),
            lastSaveTime = gameData.lastSaveTime ?: 0L,
            elderSlots = convertElderSlots(gameData.elderSlots),
            spiritMineSlots = gameData.spiritMineSlots?.map { convertSpiritMineSlot(it) } ?: emptyList(),
            librarySlots = gameData.librarySlots?.map { convertLibrarySlot(it) } ?: emptyList(),
            forgeSlots = gameData.forgeSlots?.map { convertBuildingSlot(it) } ?: emptyList(),
            alchemySlots = gameData.alchemySlots?.map { convertAlchemySlot(it) } ?: emptyList(),
            productionSlots = gameData.productionSlots?.map { convertProductionSlot(it) } ?: emptyList(),
            alliances = gameData.alliances?.map { convertAlliance(it) } ?: emptyList(),
            sectRelations = gameData.sectRelations?.map { convertSectRelation(it) } ?: emptyList(),
            playerAllianceSlots = gameData.playerAllianceSlots ?: 3,
            sectPolicies = convertSectPolicies(gameData.sectPolicies),
            // 战斗队伍：使用 NullSafeProtoBuf 的专用方法
            battleTeam = NullSafeProtoBuf.battleTeamToProto(gameData.battleTeam),
            aiBattleTeams = gameData.aiBattleTeams?.map { convertAIBattleTeam(it) } ?: emptyList(),
            usedRedeemCodes = gameData.usedRedeemCodes ?: emptyList(),
            playerProtectionEnabled = gameData.playerProtectionEnabled ?: true,
            playerProtectionStartYear = gameData.playerProtectionStartYear ?: 1,
            playerHasAttackedAI = gameData.playerHasAttackedAI ?: false,
            activeMissions = gameData.activeMissions?.map { convertActiveMission(it) } ?: emptyList(),
            availableMissions = gameData.availableMissions?.map { convertMission(it) } ?: emptyList()
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
            exploredSects = data.exploredSects.mapValues { convertBackExploredSectInfo(it.value) },
            scoutInfo = data.scoutInfo.mapValues { convertBackSectScoutInfo(it.value) },
            herbGardenPlantSlots = data.herbGardenPlantSlots.map { convertBackPlantSlot(it) },
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
            unlockedDungeons = data.unlockedDungeons,
            unlockedRecipes = data.unlockedRecipes,
            unlockedManuals = data.unlockedManuals,
            lastSaveTime = data.lastSaveTime,
            elderSlots = convertBackElderSlots(data.elderSlots),
            spiritMineSlots = data.spiritMineSlots.map { convertBackSpiritMineSlot(it) },
            librarySlots = data.librarySlots.map { convertBackLibrarySlot(it) },
            forgeSlots = data.forgeSlots.map { convertBackBuildingSlot(it) },
            alchemySlots = data.alchemySlots.map { convertBackAlchemySlot(it) },
            productionSlots = data.productionSlots.map { convertBackProductionSlot(it) },
            alliances = data.alliances.map { convertBackAlliance(it) },
            sectRelations = data.sectRelations.map { convertBackSectRelation(it) },
            playerAllianceSlots = data.playerAllianceSlots,
            sectPolicies = convertBackSectPolicies(data.sectPolicies),
            // 战斗队伍：使用 NullSafeProtoBuf 的反向转换方法
            battleTeam = NullSafeProtoBuf.battleTeamFromProto(data.battleTeam),
            aiBattleTeams = data.aiBattleTeams.map { convertBackAIBattleTeam(it) },
            usedRedeemCodes = data.usedRedeemCodes,
            playerProtectionEnabled = data.playerProtectionEnabled,
            playerProtectionStartYear = data.playerProtectionStartYear,
            playerHasAttackedAI = data.playerHasAttackedAI,
            activeMissions = data.activeMissions.map { convertBackActiveMission(it) },
            availableMissions = data.availableMissions.map { convertBackMission(it) }
        )
    }
    
    private fun convertDisciple(disciple: com.xianxia.sect.core.model.Disciple): SerializableDisciple {
        return SerializableDisciple(
            id = NullSafeProtoBuf.stringToProto(disciple.id),
            name = NullSafeProtoBuf.stringToProto(disciple.name),
            realm = disciple.realm ?: 0,
            realmLayer = disciple.realmLayer ?: 0,
            cultivation = disciple.cultivation ?: 0.0,
            spiritRootType = NullSafeProtoBuf.stringToProto(disciple.spiritRootType),
            age = disciple.age ?: 0,
            lifespan = disciple.lifespan ?: 0,
            isAlive = disciple.isAlive ?: true,
            gender = NullSafeProtoBuf.stringToProto(disciple.gender, "男"),
            // 关系字段：使用 relationIdToProto/relationIdFromProto
            partnerId = NullSafeProtoBuf.relationIdToProto(disciple.partnerId),
            partnerSectId = NullSafeProtoBuf.relationIdToProto(disciple.partnerSectId),
            parentId1 = NullSafeProtoBuf.relationIdToProto(disciple.parentId1),
            parentId2 = NullSafeProtoBuf.relationIdToProto(disciple.parentId2),
            lastChildYear = disciple.lastChildYear ?: 0,
            // 悲伤期结束年份：使用专用方法（哨兵值 -1）
            griefEndYear = NullSafeProtoBuf.griefEndYearToProto(disciple.griefEndYear),
            // 装备 ID 字段：使用 equipmentIdToProto/equipmentIdFromProto
            weaponId = NullSafeProtoBuf.equipmentIdToProto(disciple.weaponId),
            armorId = NullSafeProtoBuf.equipmentIdToProto(disciple.armorId),
            bootsId = NullSafeProtoBuf.equipmentIdToProto(disciple.bootsId),
            accessoryId = NullSafeProtoBuf.equipmentIdToProto(disciple.accessoryId),
            // 列表和 Map 类型
            manualIds = NullSafeProtoBuf.listToProto(disciple.manualIds),
            talentIds = NullSafeProtoBuf.listToProto(disciple.talentIds),
            manualMasteries = NullSafeProtoBuf.mapToProto(disciple.manualMasteries),
            // 装备培养数据：使用专用方法
            weaponNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.weaponNurture),
            armorNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.armorNurture),
            bootsNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.bootsNurture),
            accessoryNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.accessoryNurture),
            // 数值字段
            spiritStones = disciple.spiritStones ?: 0,
            soulPower = disciple.soulPower ?: 0,
            storageBagItems = NullSafeProtoBuf.listToProto(disciple.storageBagItems)?.map { convertStorageBagItem(it) } ?: emptyList(),
            storageBagSpiritStones = disciple.storageBagSpiritStones ?: 0L,
            status = disciple.status.name,
            statusData = NullSafeProtoBuf.mapToProto(disciple.statusData),
            cultivationSpeedBonus = disciple.cultivationSpeedBonus ?: 0.0,
            cultivationSpeedDuration = disciple.cultivationSpeedDuration ?: 0,
            pillPhysicalAttackBonus = disciple.pillPhysicalAttackBonus ?: 0.0,
            pillMagicAttackBonus = disciple.pillMagicAttackBonus ?: 0.0,
            pillPhysicalDefenseBonus = disciple.pillPhysicalDefenseBonus ?: 0.0,
            pillMagicDefenseBonus = disciple.pillMagicDefenseBonus ?: 0.0,
            pillHpBonus = disciple.pillHpBonus ?: 0.0,
            pillMpBonus = disciple.pillMpBonus ?: 0.0,
            pillSpeedBonus = disciple.pillSpeedBonus ?: 0.0,
            pillEffectDuration = disciple.pillEffectDuration ?: 0,
            totalCultivation = disciple.totalCultivation ?: 0L,
            breakthroughCount = disciple.breakthroughCount ?: 0,
            breakthroughFailCount = disciple.breakthroughFailCount ?: 0,
            intelligence = disciple.intelligence ?: 0,
            charm = disciple.charm ?: 0,
            loyalty = disciple.loyalty ?: 0,
            comprehension = disciple.comprehension ?: 0,
            artifactRefining = disciple.artifactRefining ?: 0,
            pillRefining = disciple.pillRefining ?: 0,
            spiritPlanting = disciple.spiritPlanting ?: 0,
            teaching = disciple.teaching ?: 0,
            morality = disciple.morality ?: 0,
            salaryPaidCount = disciple.salaryPaidCount ?: 0,
            salaryMissedCount = disciple.salaryMissedCount ?: 0,
            recruitedMonth = disciple.recruitedMonth ?: 0,
            hpVariance = disciple.hpVariance ?: 0,
            mpVariance = disciple.mpVariance ?: 0,
            physicalAttackVariance = disciple.physicalAttackVariance ?: 0,
            magicAttackVariance = disciple.magicAttackVariance ?: 0,
            physicalDefenseVariance = disciple.physicalDefenseVariance ?: 0,
            magicDefenseVariance = disciple.magicDefenseVariance ?: 0,
            speedVariance = disciple.speedVariance ?: 0,
            baseHp = disciple.baseHp ?: 0,
            baseMp = disciple.baseMp ?: 0,
            basePhysicalAttack = disciple.basePhysicalAttack ?: 0,
            baseMagicAttack = disciple.baseMagicAttack ?: 0,
            basePhysicalDefense = disciple.basePhysicalDefense ?: 0,
            baseMagicDefense = disciple.baseMagicDefense ?: 0,
            baseSpeed = disciple.baseSpeed ?: 0,
            discipleType = NullSafeProtoBuf.stringToProto(disciple.discipleType, "inner"),
            monthlyUsedPillIds = NullSafeProtoBuf.listToProto(disciple.monthlyUsedPillIds),
            usedExtendLifePillIds = NullSafeProtoBuf.listToProto(disciple.usedExtendLifePillIds),
            hasReviveEffect = disciple.hasReviveEffect ?: false,
            hasClearAllEffect = disciple.hasClearAllEffect ?: false,
            currentHp = disciple.currentHp,
            currentMp = disciple.currentMp
        )
    }
    
    private fun convertBackDisciple(data: SerializableDisciple): com.xianxia.sect.core.model.Disciple {
        // 使用 NullSafeProtoBuf 的反向转换方法
        val weaponId = NullSafeProtoBuf.equipmentIdFromProto(data.weaponId)
        val armorId = NullSafeProtoBuf.equipmentIdFromProto(data.armorId)
        val bootsId = NullSafeProtoBuf.equipmentIdFromProto(data.bootsId)
        val accessoryId = NullSafeProtoBuf.equipmentIdFromProto(data.accessoryId)

        val weaponNurture = NullSafeProtoBuf.nurtureDataFromProto(data.weaponNurture)
        val armorNurture = NullSafeProtoBuf.nurtureDataFromProto(data.armorNurture)
        val bootsNurture = NullSafeProtoBuf.nurtureDataFromProto(data.bootsNurture)
        val accessoryNurture = NullSafeProtoBuf.nurtureDataFromProto(data.accessoryNurture)

        // 关系字段：使用 relationIdFromProto
        val partnerId = NullSafeProtoBuf.relationIdFromProto(data.partnerId)
        val partnerSectId = NullSafeProtoBuf.relationIdFromProto(data.partnerSectId)
        val parentId1 = NullSafeProtoBuf.relationIdFromProto(data.parentId1)
        val parentId2 = NullSafeProtoBuf.relationIdFromProto(data.parentId2)

        // 悲伤期结束年份：使用专用方法（哨兵值 -1）
        val griefEndYear = NullSafeProtoBuf.griefEndYearFromProto(data.griefEndYear)

        return com.xianxia.sect.core.model.Disciple(
            id = data.id,
            name = data.name,
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
            discipleType = data.discipleType,
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
                pillEffectDuration = data.pillEffectDuration
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
                spiritStones = data.spiritStones,
                soulPower = data.soulPower
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
                teaching = data.teaching,
                morality = data.morality,
                salaryPaidCount = data.salaryPaidCount,
                salaryMissedCount = data.salaryMissedCount
            ),
            usage = com.xianxia.sect.core.model.UsageTracking(
                monthlyUsedPillIds = data.monthlyUsedPillIds,
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
            effect = item.effect?.let { convertItemEffect(it) } ?: SerializableItemEffect()
        )
    }
    
    private fun convertBackStorageBagItem(data: SerializableStorageBagItem): com.xianxia.sect.core.model.StorageBagItem {
        // SerializableItemEffect 现在是非空的，需要检查是否为默认实例来判断原始值是否为 null
        val effect = data.effect.takeIf { it.cultivationSpeed != 1.0 || it.cultivationPercent != 0.0 || it.skillExpPercent != 0.0 || it.breakthroughChance != 0.0 || it.targetRealm != 0 || it.heal != 0 || it.healPercent != 0.0 || it.healMaxHpPercent != 0.0 || it.hpPercent != 0.0 || it.mpPercent != 0.0 || it.mpRecoverPercent != 0.0 || it.extendLife != 0 || it.battleCount != 0 || it.physicalAttackPercent != 0.0 || it.magicAttackPercent != 0.0 || it.physicalDefensePercent != 0.0 || it.magicDefensePercent != 0.0 || it.speedPercent != 0.0 || it.revive || it.clearAll || it.duration != 0 }?.let { convertBackItemEffect(it) }

        return com.xianxia.sect.core.model.StorageBagItem(
            itemId = data.itemId,
            itemType = data.itemType,
            name = data.name,
            rarity = data.rarity,
            quantity = data.quantity,
            obtainedYear = data.obtainedYear,
            obtainedMonth = data.obtainedMonth,
            effect = effect
        )
    }
    
    private fun convertItemEffect(effect: com.xianxia.sect.core.model.ItemEffect): SerializableItemEffect {
        return SerializableItemEffect(
            cultivationSpeed = effect.cultivationSpeed ?: 1.0,
            cultivationPercent = effect.cultivationPercent ?: 0.0,
            skillExpPercent = effect.skillExpPercent ?: 0.0,
            breakthroughChance = effect.breakthroughChance ?: 0.0,
            targetRealm = effect.targetRealm ?: 0,
            heal = effect.heal ?: 0,
            healPercent = effect.healPercent ?: 0.0,
            healMaxHpPercent = effect.healMaxHpPercent ?: 0.0,
            hpPercent = effect.hpPercent ?: 0.0,
            mpPercent = effect.mpPercent ?: 0.0,
            mpRecoverPercent = effect.mpRecoverPercent ?: 0.0,
            extendLife = effect.extendLife ?: 0,
            battleCount = effect.battleCount ?: 0,
            physicalAttackPercent = effect.physicalAttackPercent ?: 0.0,
            magicAttackPercent = effect.magicAttackPercent ?: 0.0,
            physicalDefensePercent = effect.physicalDefensePercent ?: 0.0,
            magicDefensePercent = effect.magicDefensePercent ?: 0.0,
            speedPercent = effect.speedPercent ?: 0.0,
            revive = effect.revive ?: false,
            clearAll = effect.clearAll ?: false,
            duration = effect.duration ?: 0
        )
    }
    
    private fun convertBackItemEffect(data: SerializableItemEffect): com.xianxia.sect.core.model.ItemEffect {
        return com.xianxia.sect.core.model.ItemEffect(
            cultivationSpeed = data.cultivationSpeed,
            cultivationPercent = data.cultivationPercent,
            skillExpPercent = data.skillExpPercent,
            breakthroughChance = data.breakthroughChance,
            targetRealm = data.targetRealm,
            heal = data.heal,
            healPercent = data.healPercent,
            healMaxHpPercent = data.healMaxHpPercent,
            hpPercent = data.hpPercent,
            mpPercent = data.mpPercent,
            mpRecoverPercent = data.mpRecoverPercent,
            extendLife = data.extendLife,
            battleCount = data.battleCount,
            physicalAttackPercent = data.physicalAttackPercent,
            magicAttackPercent = data.magicAttackPercent,
            physicalDefensePercent = data.physicalDefensePercent,
            magicDefensePercent = data.magicDefensePercent,
            speedPercent = data.speedPercent,
            revive = data.revive,
            clearAll = data.clearAll,
            duration = data.duration
        )
    }
    
    private fun convertEquipment(equipment: com.xianxia.sect.core.model.Equipment): SerializableEquipment {
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
            quantity = equipment.quantity
        )
    }
    
    private fun convertBackEquipment(data: SerializableEquipment): com.xianxia.sect.core.model.Equipment {
        val ownerId = data.ownerId.ifEmpty { null }

        return com.xianxia.sect.core.model.Equipment(
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
            isEquipped = data.isEquipped,
            quantity = data.quantity ?: 1
        )
    }
    
    private fun convertManual(manual: com.xianxia.sect.core.model.Manual): SerializableManual {
        return SerializableManual(
            id = manual.id,
            name = manual.name,
            type = manual.type.name,
            rarity = manual.rarity,
            stats = manual.stats,
            description = manual.description
        )
    }
    
    private fun convertBackManual(data: SerializableManual): com.xianxia.sect.core.model.Manual {
        return com.xianxia.sect.core.model.Manual(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.ManualType.MIND, "type", "Manual"),
            stats = data.stats,
            description = data.description
        )
    }
    
    private fun convertPill(pill: com.xianxia.sect.core.model.Pill): SerializablePill {
        return SerializablePill(
            id = pill.id,
            name = pill.name,
            type = pill.category.name,
            rarity = pill.rarity,
            effects = mapOf(
                "breakthroughChance" to pill.breakthroughChance,
                "targetRealm" to pill.targetRealm.toDouble(),
                "isAscension" to if (pill.isAscension) 1.0 else 0.0,
                "cultivationSpeed" to pill.cultivationSpeed,
                "duration" to pill.duration.toDouble(),
                "cannotStack" to if (pill.cannotStack) 1.0 else 0.0,
                "cultivationPercent" to pill.cultivationPercent,
                "skillExpPercent" to pill.skillExpPercent,
                "extendLife" to pill.extendLife.toDouble(),
                "physicalAttackPercent" to pill.physicalAttackPercent,
                "magicAttackPercent" to pill.magicAttackPercent,
                "physicalDefensePercent" to pill.physicalDefensePercent,
                "magicDefensePercent" to pill.magicDefensePercent,
                "hpPercent" to pill.hpPercent,
                "mpPercent" to pill.mpPercent,
                "speedPercent" to pill.speedPercent,
                "healMaxHpPercent" to pill.healMaxHpPercent,
                "healPercent" to pill.healPercent,
                "heal" to pill.heal.toDouble(),
                "battleCount" to pill.battleCount.toDouble(),
                "revive" to if (pill.revive) 1.0 else 0.0,
                "clearAll" to if (pill.clearAll) 1.0 else 0.0,
                "mpRecoverMaxMpPercent" to pill.mpRecoverMaxMpPercent
            ),
            description = pill.description,
            quantity = pill.quantity
        )
    }
    
    private fun convertBackPill(data: SerializablePill): com.xianxia.sect.core.model.Pill {
        return com.xianxia.sect.core.model.Pill(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            category = safeEnumValueOf(data.type, com.xianxia.sect.core.model.PillCategory.CULTIVATION, "type", "Pill"),
            breakthroughChance = data.effects["breakthroughChance"] ?: 0.0,
            targetRealm = (data.effects["targetRealm"] ?: 0.0).toInt(),
            isAscension = (data.effects["isAscension"] ?: 0.0) > 0.5,
            cultivationSpeed = data.effects["cultivationSpeed"] ?: 1.0,
            duration = (data.effects["duration"] ?: 0.0).toInt(),
            cannotStack = (data.effects["cannotStack"] ?: 0.0) > 0.5,
            cultivationPercent = data.effects["cultivationPercent"] ?: 0.0,
            skillExpPercent = data.effects["skillExpPercent"] ?: 0.0,
            extendLife = (data.effects["extendLife"] ?: 0.0).toInt(),
            physicalAttackPercent = data.effects["physicalAttackPercent"] ?: 0.0,
            magicAttackPercent = data.effects["magicAttackPercent"] ?: 0.0,
            physicalDefensePercent = data.effects["physicalDefensePercent"] ?: 0.0,
            magicDefensePercent = data.effects["magicDefensePercent"] ?: 0.0,
            hpPercent = data.effects["hpPercent"] ?: 0.0,
            mpPercent = data.effects["mpPercent"] ?: 0.0,
            speedPercent = data.effects["speedPercent"] ?: 0.0,
            healMaxHpPercent = data.effects["healMaxHpPercent"] ?: 0.0,
            healPercent = data.effects["healPercent"] ?: 0.0,
            heal = (data.effects["heal"] ?: 0.0).toInt(),
            battleCount = (data.effects["battleCount"] ?: 0.0).toInt(),
            revive = (data.effects["revive"] ?: 0.0) > 0.5,
            clearAll = (data.effects["clearAll"] ?: 0.0) > 0.5,
            mpRecoverMaxMpPercent = data.effects["mpRecoverMaxMpPercent"] ?: 0.0,
            description = data.description,
            quantity = data.quantity
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
    
    private fun convertEvent(event: com.xianxia.sect.core.model.GameEvent): SerializableGameEvent {
        return SerializableGameEvent(
            id = event.id,
            type = event.type.name,
            title = "",
            description = event.message,
            timestamp = event.timestamp,
            gameYear = event.year,
            gameMonth = event.month,
            data = emptyMap()
        )
    }
    
    private fun convertBackEvent(data: SerializableGameEvent): com.xianxia.sect.core.model.GameEvent {
        return com.xianxia.sect.core.model.GameEvent(
            id = data.id,
            message = data.description,
            type = safeEnumValueOfIgnoreCase(data.type, com.xianxia.sect.core.model.EventType.INFO, "type", "GameEvent"),
            timestamp = data.timestamp,
            year = data.gameYear,
            month = data.gameMonth
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
            targetType = action.attackerType,
            targetId = "",
            targetName = action.target,
            skillName = action.skillName ?: "",
            damage = action.damage,
            isCritical = action.isCrit,
            effect = action.message
        )
    }
    
    private fun convertBackBattleLogAction(data: SerializableBattleLogAction): com.xianxia.sect.core.model.BattleLogAction {
        return com.xianxia.sect.core.model.BattleLogAction(
            attacker = data.actorName,
            attackerType = data.targetType,
            target = data.targetName,
            damage = data.damage,
            isCrit = data.isCritical,
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
            maxMp = member.maxMp
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
            isAlive = data.isAlive
        )
    }
    
    private fun convertBattleLogEnemy(enemy: com.xianxia.sect.core.model.BattleLogEnemy): SerializableBattleLogMember {
        return SerializableBattleLogMember(
            discipleId = enemy.id,
            name = enemy.name,
            realm = enemy.realm,
            isAlive = enemy.isAlive,
            remainingHp = enemy.hp,
            maxHp = enemy.maxHp
        )
    }
    
    private fun convertBackBattleLogEnemy(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogEnemy {
        return com.xianxia.sect.core.model.BattleLogEnemy(
            id = data.discipleId,
            name = data.name,
            realm = data.realm,
            hp = data.remainingHp,
            maxHp = data.maxHp,
            isAlive = data.isAlive
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
    
    private fun convertWorldSect(sect: com.xianxia.sect.core.model.WorldSect): SerializableWorldSect {
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
            mineSlots = sect.mineSlots?.map { convertMineSlot(it) } ?: emptyList(),
            occupationTime = sect.occupationTime ?: 0L,
            isOwned = sect.isOwned ?: false,
            expiryYear = sect.expiryYear ?: 0,
            expiryMonth = sect.expiryMonth ?: 0,
            scoutInfo = sect.scoutInfo?.let { convertSectScoutInfo(it) } ?: SerializableSectScoutInfo(sectId="", sectName="", scoutYear=0, scoutMonth=0, discipleCount=0, maxRealm=0, isKnown=false, expiryYear=0, expiryMonth=0),
            tradeItems = sect.tradeItems?.map { convertMerchantItem(it) } ?: emptyList(),
            tradeLastRefreshYear = sect.tradeLastRefreshYear ?: 0,
            lastGiftYear = sect.lastGiftYear ?: 0,
            allianceId = sect.allianceId ?: "",
            allianceStartYear = sect.allianceStartYear ?: 0,
            isRighteous = sect.isRighteous ?: true,
            aiDisciples = sect.aiDisciples?.map { convertDisciple(it) } ?: emptyList(),
            isPlayerOccupied = sect.isPlayerOccupied ?: false,
            occupierBattleTeamId = sect.occupierBattleTeamId ?: "",
            isUnderAttack = sect.isUnderAttack ?: false,
            attackerSectId = sect.attackerSectId ?: "",
            occupierSectId = sect.occupierSectId ?: "",
            warehouse = convertSectWarehouse(sect.warehouse),
            giftPreference = sect.giftPreference?.name ?: "NONE"
        )
    }
    
    private fun convertBackWorldSect(data: SerializableWorldSect): com.xianxia.sect.core.model.WorldSect {
        val occupierTeamId = data.occupierTeamId.ifEmpty { "" }
        val allianceId = data.allianceId.ifEmpty { "" }
        val occupierBattleTeamId = data.occupierBattleTeamId.ifEmpty { "" }
        val attackerSectId = data.attackerSectId.ifEmpty { "" }
        val occupierSectId = data.occupierSectId.ifEmpty { "" }
        val scoutInfo = data.scoutInfo.takeIf { it.sectId.isNotEmpty() }?.let { convertBackSectScoutInfo(it) } ?: com.xianxia.sect.core.model.SectScoutInfo()

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
            mineSlots = data.mineSlots.map { convertBackMineSlot(it) },
            occupationTime = data.occupationTime,
            isOwned = data.isOwned,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth,
            scoutInfo = scoutInfo,
            tradeItems = data.tradeItems.map { convertBackMerchantItem(it) },
            tradeLastRefreshYear = data.tradeLastRefreshYear,
            lastGiftYear = data.lastGiftYear,
            allianceId = allianceId,
            allianceStartYear = data.allianceStartYear,
            isRighteous = data.isRighteous,
            aiDisciples = data.aiDisciples.map { convertBackDisciple(it) },
            isPlayerOccupied = data.isPlayerOccupied,
            occupierBattleTeamId = occupierBattleTeamId,
            isUnderAttack = data.isUnderAttack,
            attackerSectId = attackerSectId,
            occupierSectId = occupierSectId,
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
            price = item.price ?: 0,
            quantity = item.quantity ?: 0,
            description = item.description ?: "",
            obtainedYear = item.obtainedYear ?: 1,
            obtainedMonth = item.obtainedMonth ?: 1
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
            obtainedMonth = data.obtainedMonth
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
            harvestAmount = slot.harvestAmount ?: 0,
            harvestHerbId = slot.harvestHerbId ?: ""
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
            harvestAmount = data.harvestAmount,
            harvestHerbId = data.harvestHerbId
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
            disciples = emptyList(),
            resources = emptyMap(),
            discovered = cave.isExplored
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
            isExplored = data.discovered
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
            disciples = emptyList(),
            status = team.status.name,
            startYear = 0,
            startMonth = 0
        )
    }
    
    private fun convertBackAICaveTeam(data: SerializableAICaveTeam): com.xianxia.sect.core.model.AICaveTeam {
        return com.xianxia.sect.core.model.AICaveTeam(
            id = data.id,
            caveId = data.targetCaveId,
            sectId = data.sectId,
            sectName = data.sectName,
            disciples = emptyList(),
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.AITeamStatus.EXPLORING, "status", "AICaveTeam")
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
            discipleSpiritRootColor = slot.discipleSpiritRootColor ?: ""
        )
    }
    
    private fun convertBackDirectDiscipleSlot(data: SerializableDirectDiscipleSlot): com.xianxia.sect.core.model.DirectDiscipleSlot {
        return com.xianxia.sect.core.model.DirectDiscipleSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            discipleRealm = data.discipleRealm,
            discipleSpiritRootColor = data.discipleSpiritRootColor
        )
    }
    
    private fun convertSpiritMineSlot(slot: com.xianxia.sect.core.model.SpiritMineSlot): SerializableSpiritMineSlot {
        return SerializableSpiritMineSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            output = slot.output ?: 0
        )
    }
    
    private fun convertBackSpiritMineSlot(data: SerializableSpiritMineSlot): com.xianxia.sect.core.model.SpiritMineSlot {
        return com.xianxia.sect.core.model.SpiritMineSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            output = data.output
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
            manualResearch = policies.manualResearch ?: false
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
            manualResearch = data.manualResearch
        )
    }
    
    private fun convertBattleTeam(team: com.xianxia.sect.core.model.BattleTeam): SerializableBattleTeam {
        return SerializableBattleTeam(
            id = team.id ?: "",
            name = team.name ?: "",
            slots = team.slots?.map { convertBattleTeamSlot(it) } ?: emptyList(),
            isAtSect = team.isAtSect ?: true,
            currentX = team.currentX ?: 0f,
            currentY = team.currentY ?: 0f,
            targetX = team.targetX ?: 0f,
            targetY = team.targetY ?: 0f,
            status = team.status ?: "",
            targetSectId = team.targetSectId ?: "",
            originSectId = team.originSectId ?: "",
            route = team.route ?: emptyList(),
            currentRouteIndex = team.currentRouteIndex ?: 0,
            moveProgress = team.moveProgress ?: 0f,
            isOccupying = team.isOccupying ?: false,
            occupiedSectId = team.occupiedSectId ?: "",
            isReturning = team.isReturning ?: false
        )
    }
    
    private fun convertBackBattleTeam(data: SerializableBattleTeam): com.xianxia.sect.core.model.BattleTeam {
        val targetSectId = data.targetSectId.ifEmpty { "" }
        val originSectId = data.originSectId.ifEmpty { "" }
        val occupiedSectId = data.occupiedSectId.ifEmpty { "" }

        return com.xianxia.sect.core.model.BattleTeam(
            id = data.id,
            name = data.name,
            slots = data.slots.map { convertBackBattleTeamSlot(it) },
            isAtSect = data.isAtSect,
            currentX = data.currentX,
            currentY = data.currentY,
            targetX = data.targetX,
            targetY = data.targetY,
            status = data.status,
            targetSectId = targetSectId,
            originSectId = originSectId,
            route = data.route,
            currentRouteIndex = data.currentRouteIndex,
            moveProgress = data.moveProgress,
            isOccupying = data.isOccupying,
            occupiedSectId = occupiedSectId,
            isReturning = data.isReturning
        )
    }
    
    private fun convertBattleTeamSlot(slot: com.xianxia.sect.core.model.BattleTeamSlot): SerializableBattleTeamSlot {
        return SerializableBattleTeamSlot(
            index = slot.index,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName,
            discipleRealm = slot.discipleRealm,
            slotType = slot.slotType.name,
            isAlive = slot.isAlive
        )
    }
    
    private fun convertBackBattleTeamSlot(data: SerializableBattleTeamSlot): com.xianxia.sect.core.model.BattleTeamSlot {
        return com.xianxia.sect.core.model.BattleTeamSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            discipleRealm = data.discipleRealm,
            slotType = safeEnumValueOf(data.slotType, com.xianxia.sect.core.model.BattleSlotType.DISCIPLE, "slotType", "BattleTeamSlot"),
            isAlive = data.isAlive
        )
    }
    
    private fun convertAIBattleTeam(team: com.xianxia.sect.core.model.AIBattleTeam): SerializableAIBattleTeam {
        return SerializableAIBattleTeam(
            id = team.id ?: "",
            attackerSectId = team.attackerSectId ?: "",
            attackerSectName = team.attackerSectName ?: "",
            defenderSectId = team.defenderSectId ?: "",
            defenderSectName = team.defenderSectName ?: "",
            disciples = team.disciples?.map { convertDisciple(it) } ?: emptyList(),
            currentX = team.currentX ?: 0f,
            currentY = team.currentY ?: 0f,
            targetX = team.targetX ?: 0f,
            targetY = team.targetY ?: 0f,
            attackerStartX = team.attackerStartX ?: 0f,
            attackerStartY = team.attackerStartY ?: 0f,
            moveProgress = team.moveProgress ?: 0f,
            status = team.status ?: "",
            route = team.route ?: emptyList(),
            currentRouteIndex = team.currentRouteIndex ?: 0,
            startYear = team.startYear ?: 0,
            startMonth = team.startMonth ?: 0,
            isPlayerDefender = team.isPlayerDefender ?: false
        )
    }
    
    private fun convertBackAIBattleTeam(data: SerializableAIBattleTeam): com.xianxia.sect.core.model.AIBattleTeam {
        return com.xianxia.sect.core.model.AIBattleTeam(
            id = data.id,
            attackerSectId = data.attackerSectId,
            attackerSectName = data.attackerSectName,
            defenderSectId = data.defenderSectId,
            defenderSectName = data.defenderSectName,
            disciples = data.disciples.map { convertBackDisciple(it) },
            currentX = data.currentX,
            currentY = data.currentY,
            targetX = data.targetX,
            targetY = data.targetY,
            attackerStartX = data.attackerStartX,
            attackerStartY = data.attackerStartY,
            moveProgress = data.moveProgress,
            status = data.status,
            route = data.route,
            currentRouteIndex = data.currentRouteIndex,
            startYear = data.startYear,
            startMonth = data.startMonth,
            isPlayerDefender = data.isPlayerDefender
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
            materialMaxRarity = mission.rewards.materialMaxRarity
        )
    }

    private fun convertBackActiveMission(data: SerializableActiveMission): com.xianxia.sect.core.model.ActiveMission {
        val difficulty = com.xianxia.sect.core.model.MissionDifficulty.entries.getOrNull(data.difficulty)
            ?: com.xianxia.sect.core.model.MissionDifficulty.SIMPLE
        val template = try {
            com.xianxia.sect.core.model.MissionTemplate.valueOf(data.missionType)
        } catch (e: Exception) {
            com.xianxia.sect.core.model.MissionTemplate.ESCORT
        }

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
                materialMaxRarity = data.materialMaxRarity
            )
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
            materialMaxRarity = mission.rewards.materialMaxRarity
        )
    }

    private fun convertBackMission(data: SerializableMission): com.xianxia.sect.core.model.Mission {
        val difficulty = com.xianxia.sect.core.model.MissionDifficulty.entries.getOrNull(data.difficulty)
            ?: com.xianxia.sect.core.model.MissionDifficulty.SIMPLE
        val template = try {
            com.xianxia.sect.core.model.MissionTemplate.valueOf(data.type)
        } catch (e: Exception) {
            com.xianxia.sect.core.model.MissionTemplate.ESCORT
        }

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
                materialMaxRarity = data.materialMaxRarity
            ),
            createdYear = data.createdYear,
            createdMonth = data.createdMonth
        )
    }
}

