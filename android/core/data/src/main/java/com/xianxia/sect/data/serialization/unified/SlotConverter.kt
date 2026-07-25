package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot

internal class SlotConverter {

    fun convertMineSlot(slot: com.xianxia.sect.core.model.MineSlot): SerializableMineSlot {
        return SerializableMineSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            output = slot.output ?: 0,
            efficiency = slot.efficiency ?: 1.0,
            isActive = slot.isActive ?: false
        )
    }

    fun convertBackMineSlot(data: SerializableMineSlot): com.xianxia.sect.core.model.MineSlot {
        return com.xianxia.sect.core.model.MineSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            output = data.output,
            efficiency = data.efficiency,
            isActive = data.isActive
        )
    }

    fun convertPlantSlot(slot: com.xianxia.sect.core.model.PlantSlotData): SerializablePlantSlotData {
        return SerializablePlantSlotData(
            index = slot.index ?: 0,
            status = slot.status ?: "empty",
            seedId = slot.seedId ?: "",
            seedName = slot.seedName ?: "",
            startYear = slot.startYear ?: 0,
            startMonth = slot.startMonth ?: 0,
            growTime = slot.growTime ?: 0,
            expectedYield = slot.expectedYield ?: 0
        )
    }

    fun convertBackPlantSlot(data: SerializablePlantSlotData): com.xianxia.sect.core.model.PlantSlotData {
        return com.xianxia.sect.core.model.PlantSlotData(
            index = data.index,
            status = data.status,
            seedId = data.seedId,
            seedName = data.seedName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            growTime = data.growTime,
            expectedYield = data.expectedYield
        )
    }

    fun convertCultivatorCave(cave: com.xianxia.sect.core.model.CultivatorCave): SerializableCultivatorCave {
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

    fun convertBackCultivatorCave(data: SerializableCultivatorCave): com.xianxia.sect.core.model.CultivatorCave {
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

    fun convertCaveExplorationTeam(team: com.xianxia.sect.core.model.CaveExplorationTeam): SerializableCaveExplorationTeam {
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

    fun convertBackCaveExplorationTeam(data: SerializableCaveExplorationTeam): com.xianxia.sect.core.model.CaveExplorationTeam {
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

    fun convertElderSlots(slots: com.xianxia.sect.core.model.ElderSlots?): SerializableElderSlots {
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
            innerElder = slots.innerElder ?: "",
            recruitingElder = slots.recruitingElder ?: "",
            qingyunPreachingElder = slots.qingyunPreachingElder ?: "",
            qingyunPreachingMasters = slots.qingyunPreachingMasters?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            herbGardenDisciples = slots.herbGardenDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            alchemyDisciples = slots.alchemyDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            forgeDisciples = slots.forgeDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            spiritMineDeaconDisciples = slots.spiritMineDeaconDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList()
        )
    }

    fun convertBackElderSlots(data: SerializableElderSlots): com.xianxia.sect.core.model.ElderSlots {
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
            innerElder = data.innerElder,
            recruitingElder = data.recruitingElder,
            qingyunPreachingElder = data.qingyunPreachingElder,
            qingyunPreachingMasters = data.qingyunPreachingMasters.map { convertBackDirectDiscipleSlot(it) },
            herbGardenDisciples = data.herbGardenDisciples.map { convertBackDirectDiscipleSlot(it) },
            alchemyDisciples = data.alchemyDisciples.map { convertBackDirectDiscipleSlot(it) },
            forgeDisciples = data.forgeDisciples.map { convertBackDirectDiscipleSlot(it) },
            spiritMineDeaconDisciples = data.spiritMineDeaconDisciples.map { convertBackDirectDiscipleSlot(it) }
        )
    }

    fun convertDirectDiscipleSlot(slot: com.xianxia.sect.core.model.DirectDiscipleSlot): SerializableDirectDiscipleSlot {
        return SerializableDirectDiscipleSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            discipleRealm = slot.discipleRealm ?: "",
            discipleSpiritRootColor = slot.discipleSpiritRootColor ?: "",
            sectId = slot.sectId ?: ""
        )
    }

    fun convertBackDirectDiscipleSlot(data: SerializableDirectDiscipleSlot): com.xianxia.sect.core.model.DirectDiscipleSlot {
        return com.xianxia.sect.core.model.DirectDiscipleSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            discipleRealm = data.discipleRealm,
            discipleSpiritRootColor = data.discipleSpiritRootColor,
            sectId = data.sectId
        )
    }

    fun convertSpiritMineSlot(slot: com.xianxia.sect.core.model.SpiritMineSlot): SerializableSpiritMineSlot {
        return SerializableSpiritMineSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            output = slot.output ?: 0,
            sectId = slot.sectId ?: "",
            buildingInstanceId = slot.buildingInstanceId,
            consecutiveMiningMonths = slot.consecutiveMiningMonths
        )
    }

    fun convertBackSpiritMineSlot(data: SerializableSpiritMineSlot): com.xianxia.sect.core.model.SpiritMineSlot {
        return com.xianxia.sect.core.model.SpiritMineSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            output = data.output,
            sectId = data.sectId,
            buildingInstanceId = data.buildingInstanceId,
            consecutiveMiningMonths = data.consecutiveMiningMonths
        )
    }

    fun convertLibrarySlot(slot: com.xianxia.sect.core.model.LibrarySlot): SerializableLibrarySlot {
        return SerializableLibrarySlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            buildingInstanceId = slot.buildingInstanceId
        )
    }

    fun convertBackLibrarySlot(data: SerializableLibrarySlot): com.xianxia.sect.core.model.LibrarySlot {
        return com.xianxia.sect.core.model.LibrarySlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            buildingInstanceId = data.buildingInstanceId
        )
    }

    fun convertResidenceSlot(slot: com.xianxia.sect.core.model.ResidenceSlot): SerializableResidenceSlot {
        return SerializableResidenceSlot(
            buildingInstanceId = slot.buildingInstanceId,
            slotIndex = slot.slotIndex,
            discipleId = slot.discipleId,
            discipleName = slot.discipleName
        )
    }

    fun convertBackResidenceSlot(data: SerializableResidenceSlot): com.xianxia.sect.core.model.ResidenceSlot {
        return com.xianxia.sect.core.model.ResidenceSlot(
            buildingInstanceId = data.buildingInstanceId,
            slotIndex = data.slotIndex,
            discipleId = data.discipleId,
            discipleName = data.discipleName
        )
    }

    fun convertBuildingSlot(slot: com.xianxia.sect.core.model.BuildingSlot): SerializableBuildingSlot {
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

    fun convertBackBuildingSlot(data: SerializableBuildingSlot): com.xianxia.sect.core.model.BuildingSlot {
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

fun convertProductionSlot(slot: com.xianxia.sect.core.model.production.ProductionSlot): SerializableProductionSlot {
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
            outputItemRarity = slot.outputItemRarity,
            buildingInstanceId = slot.buildingInstanceId,
            baseDuration = slot.baseDuration,
            requiredMaterials = slot.requiredMaterials.map { (matId, qty) ->
                SerializableMaterial(id = matId, name = "", type = "", rarity = 1, quantity = qty)
            },
            outputItemSlot = slot.outputItemSlot,
            expectedYield = slot.expectedYield,
            autoRestartEnabled = slot.autoRestartEnabled,
            completionMonth = slot.completionMonth,
            completionPhase = slot.completionPhase
        )
    }

    fun convertBackProductionSlot(data: SerializableProductionSlot): com.xianxia.sect.core.model.production.ProductionSlot {
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
            outputItemRarity = data.outputItemRarity,
            buildingInstanceId = data.buildingInstanceId,
            baseDuration = data.baseDuration,
            requiredMaterials = data.requiredMaterials.associate { it.id to it.quantity },
            outputItemSlot = data.outputItemSlot,
            expectedYield = data.expectedYield,
            autoRestartEnabled = data.autoRestartEnabled,
            completionMonth = data.completionMonth,
            completionPhase = data.completionPhase
        )
    }
}
