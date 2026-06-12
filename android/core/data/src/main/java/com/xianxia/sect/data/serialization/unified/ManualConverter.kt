package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot

class ManualConverter {
    private val teamAndBattleConverter = TeamAndBattleConverter()
    private val worldAndSectConverter = WorldAndSectConverter()
    private val slotConverter = SlotConverter()

    // ═══════════════════════════════════════════════════════════════════
    // Manual (ManualInstance ↔ SerializableManual)
    // ═══════════════════════════════════════════════════════════════════

    fun convertManual(instance: ManualInstance): SerializableManual {
        return SerializableManual(
            id = instance.id,
            name = instance.name,
            type = instance.type.name,
            rarity = instance.rarity,
            stats = instance.stats,
            description = instance.description,
            cultivationSpeedPercent = instance.stats["cultivationSpeedPercent"]?.toDouble() ?: 0.0,
            obtainedYear = 1,
            obtainedMonth = 1
        )
    }

    fun convertBackManual(data: SerializableManual): ManualInstance {
        return ManualInstance(
            id = data.id,
            name = data.name,
            description = data.description,
            type = safeEnumValueOf(data.type, ManualType.MIND, "type", "ManualInstance"),
            rarity = data.rarity,
            stats = data.stats
        )
    }

    fun convertManualProficiency(prof: ManualProficiencyData): SerializableManualProficiencyData {
        return SerializableManualProficiencyData(
            manualId = prof.manualId,
            manualName = prof.manualName,
            proficiency = prof.proficiency,
            maxProficiency = prof.maxProficiency,
            level = prof.level,
            masteryLevel = prof.masteryLevel
        )
    }

    fun convertBackManualProficiency(data: SerializableManualProficiencyData): ManualProficiencyData {
        return ManualProficiencyData(
            manualId = data.manualId,
            manualName = data.manualName,
            proficiency = data.proficiency,
            maxProficiency = data.maxProficiency,
            level = data.level,
            masteryLevel = data.masteryLevel
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // EquipmentNurtureData
    // ═══════════════════════════════════════════════════════════════════

    fun convertEquipmentNurtureData(data: EquipmentNurtureData): SerializableEquipmentNurtureData {
        return SerializableEquipmentNurtureData(
            equipmentId = data.equipmentId,
            rarity = data.rarity,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress
        )
    }

    fun convertBackEquipmentNurtureData(data: SerializableEquipmentNurtureData): EquipmentNurtureData {
        return EquipmentNurtureData(
            equipmentId = data.equipmentId,
            rarity = data.rarity,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // ActiveMission / Mission
    // ═══════════════════════════════════════════════════════════════════

    fun convertActiveMission(mission: ActiveMission): SerializableActiveMission {
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
            missionType = mission.missionType.name,
            difficulty = mission.difficulty.ordinal,
            discipleNames = mission.discipleNames,
            discipleRealms = mission.discipleRealms,
            spiritStones = mission.rewards.spiritStones,
            spiritStonesMax = mission.rewards.spiritStonesMax,
            extraItemChance = 0.0,
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

    fun convertBackActiveMission(data: SerializableActiveMission): ActiveMission {
        return ActiveMission(
            id = data.id,
            missionId = data.missionId,
            missionName = data.name,
            template = safeEnumValueOf(data.missionType, MissionTemplate.ESCORT_CARAVAN, "template", "ActiveMission"),
            difficulty = MissionDifficulty.entries.getOrNull(data.difficulty) ?: MissionDifficulty.SIMPLE,
            discipleIds = data.assignedDisciples,
            discipleNames = data.discipleNames,
            discipleRealms = data.discipleRealms,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.targetProgress,
            rewards = MissionRewardConfig(
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
            missionType = safeEnumValueOf(data.missionType, MissionType.NO_COMBAT, "missionType", "ActiveMission")
        )
    }

    fun convertMission(mission: Mission): SerializableMission {
        return SerializableMission(
            id = mission.id,
            name = mission.name,
            description = mission.description,
            difficulty = mission.difficulty.ordinal,
            minDisciples = mission.template.requiredMemberCount,
            maxDisciples = mission.template.requiredMemberCount,
            duration = mission.duration,
            type = mission.missionType.name,
            minRealm = mission.difficulty.minRealm,
            createdYear = mission.createdYear,
            createdMonth = mission.createdMonth,
            spiritStones = mission.rewards.spiritStones,
            spiritStonesMax = mission.rewards.spiritStonesMax,
            extraItemChance = 0.0,
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

    fun convertBackMission(data: SerializableMission): Mission {
        return Mission(
            id = data.id,
            name = data.name,
            description = data.description,
            template = safeEnumValueOf(data.type, MissionTemplate.ESCORT_CARAVAN, "template", "Mission"),
            difficulty = MissionDifficulty.entries.getOrNull(data.difficulty) ?: MissionDifficulty.SIMPLE,
            duration = data.duration,
            rewards = MissionRewardConfig(
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
            missionType = safeEnumValueOf(data.type, MissionType.NO_COMBAT, "missionType", "Mission"),
            createdYear = data.createdYear,
            createdMonth = data.createdMonth
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 委托方法 — Team/Battle
    // ═══════════════════════════════════════════════════════════════════

    fun convertTeam(team: ExplorationTeam) = teamAndBattleConverter.convertTeam(team)
    fun convertBackTeam(data: SerializableExplorationTeam) = teamAndBattleConverter.convertBackTeam(data)
    fun convertBattleLog(log: BattleLog) = teamAndBattleConverter.convertBattleLog(log)
    fun convertBackBattleLog(data: SerializableBattleLog) = teamAndBattleConverter.convertBackBattleLog(data)
    fun convertAlliance(alliance: Alliance) = teamAndBattleConverter.convertAlliance(alliance)
    fun convertBackAlliance(data: SerializableAlliance) = teamAndBattleConverter.convertBackAlliance(data)

    // ═══════════════════════════════════════════════════════════════════
    // 委托方法 — World/Sect
    // ═══════════════════════════════════════════════════════════════════

    fun convertWorldSect(sect: WorldSect, detail: SectDetail?) = worldAndSectConverter.convertWorldSect(sect, detail)
    fun convertBackWorldSect(data: SerializableWorldSect) = worldAndSectConverter.convertBackWorldSect(data)
    fun extractSectDetailFromWorldSect(data: SerializableWorldSect) = worldAndSectConverter.extractSectDetailFromWorldSect(data)
    fun convertSectDetail(detail: SectDetail) = worldAndSectConverter.convertSectDetail(detail)
    fun convertBackSectDetail(data: SerializableSectDetail) = worldAndSectConverter.convertBackSectDetail(data)
    fun convertSectScoutInfo(info: SectScoutInfo) = worldAndSectConverter.convertSectScoutInfo(info)
    fun convertBackSectScoutInfo(data: SerializableSectScoutInfo) = worldAndSectConverter.convertBackSectScoutInfo(data)
    fun convertExploredSectInfo(info: ExploredSectInfo) = worldAndSectConverter.convertExploredSectInfo(info)
    fun convertBackExploredSectInfo(data: SerializableExploredSectInfo) = worldAndSectConverter.convertBackExploredSectInfo(data)
    fun convertMerchantItem(item: MerchantItem) = worldAndSectConverter.convertMerchantItem(item)
    fun convertBackMerchantItem(data: SerializableMerchantItem) = worldAndSectConverter.convertBackMerchantItem(data)
    fun convertSectWarehouse(warehouse: SectWarehouse?) = worldAndSectConverter.convertSectWarehouse(warehouse)
    fun convertBackSectWarehouse(data: SerializableSectWarehouse) = worldAndSectConverter.convertBackSectWarehouse(data)
    fun convertWarehouseItem(item: WarehouseItem) = worldAndSectConverter.convertWarehouseItem(item)
    fun convertBackWarehouseItem(data: SerializableWarehouseItem) = worldAndSectConverter.convertBackWarehouseItem(data)
    fun convertSectRelation(relation: SectRelation) = worldAndSectConverter.convertSectRelation(relation)
    fun convertBackSectRelation(data: SerializableSectRelation) = worldAndSectConverter.convertBackSectRelation(data)
    fun convertSectPolicies(policies: SectPolicies?) = worldAndSectConverter.convertSectPolicies(policies)
    fun convertBackSectPolicies(data: SerializableSectPolicies) = worldAndSectConverter.convertBackSectPolicies(data)

    // ═══════════════════════════════════════════════════════════════════
    // 委托方法 — Slots
    // ═══════════════════════════════════════════════════════════════════

    fun convertMineSlot(slot: MineSlot) = slotConverter.convertMineSlot(slot)
    fun convertBackMineSlot(data: SerializableMineSlot) = slotConverter.convertBackMineSlot(data)
    fun convertPlantSlot(slot: PlantSlotData) = slotConverter.convertPlantSlot(slot)
    fun convertBackPlantSlot(data: SerializablePlantSlotData) = slotConverter.convertBackPlantSlot(data)
    fun convertCultivatorCave(cave: CultivatorCave) = slotConverter.convertCultivatorCave(cave)
    fun convertBackCultivatorCave(data: SerializableCultivatorCave) = slotConverter.convertBackCultivatorCave(data)
    fun convertCaveExplorationTeam(team: CaveExplorationTeam) = slotConverter.convertCaveExplorationTeam(team)
    fun convertBackCaveExplorationTeam(data: SerializableCaveExplorationTeam) = slotConverter.convertBackCaveExplorationTeam(data)
    fun convertElderSlots(slots: ElderSlots?) = slotConverter.convertElderSlots(slots)
    fun convertBackElderSlots(data: SerializableElderSlots) = slotConverter.convertBackElderSlots(data)
    fun convertDirectDiscipleSlot(slot: DirectDiscipleSlot) = slotConverter.convertDirectDiscipleSlot(slot)
    fun convertBackDirectDiscipleSlot(data: SerializableDirectDiscipleSlot) = slotConverter.convertBackDirectDiscipleSlot(data)
    fun convertSpiritMineSlot(slot: SpiritMineSlot) = slotConverter.convertSpiritMineSlot(slot)
    fun convertBackSpiritMineSlot(data: SerializableSpiritMineSlot) = slotConverter.convertBackSpiritMineSlot(data)
    fun convertLibrarySlot(slot: LibrarySlot) = slotConverter.convertLibrarySlot(slot)
    fun convertBackLibrarySlot(data: SerializableLibrarySlot) = slotConverter.convertBackLibrarySlot(data)
    fun convertResidenceSlot(slot: ResidenceSlot) = slotConverter.convertResidenceSlot(slot)
    fun convertBackResidenceSlot(data: SerializableResidenceSlot) = slotConverter.convertBackResidenceSlot(data)
    fun convertBuildingSlot(slot: BuildingSlot) = slotConverter.convertBuildingSlot(slot)
    fun convertBackBuildingSlot(data: SerializableBuildingSlot) = slotConverter.convertBackBuildingSlot(data)
    fun convertAlchemySlot(slot: AlchemySlot) = slotConverter.convertAlchemySlot(slot)
    fun convertBackAlchemySlot(data: SerializableAlchemySlot) = slotConverter.convertBackAlchemySlot(data)
    fun convertProductionSlot(slot: ProductionSlot) = slotConverter.convertProductionSlot(slot)
    fun convertBackProductionSlot(data: SerializableProductionSlot) = slotConverter.convertBackProductionSlot(data)
}
