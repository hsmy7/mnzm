package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*

internal class WorldAndSectConverter {

    fun convertWorldSect(sect: com.xianxia.sect.core.model.WorldSect, detail: com.xianxia.sect.core.model.SectDetail?): SerializableWorldSect {
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
            mineSlots = detail?.mineSlots?.map { slotConverter.convertMineSlot(it) } ?: emptyList(),
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

    fun convertBackWorldSect(data: SerializableWorldSect): com.xianxia.sect.core.model.WorldSect {
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

    fun extractSectDetailFromWorldSect(data: SerializableWorldSect): com.xianxia.sect.core.model.SectDetail {
        val scoutInfo = data.scoutInfo.takeIf { it.sectId.isNotEmpty() }?.let { convertBackSectScoutInfo(it) } ?: com.xianxia.sect.core.model.SectScoutInfo()

        return com.xianxia.sect.core.model.SectDetail(
            sectId = data.id,
            mineSlots = data.mineSlots.map { slotConverter.convertBackMineSlot(it) },
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

    fun convertSectDetail(detail: com.xianxia.sect.core.model.SectDetail): SerializableSectDetail {
        return SerializableSectDetail(
            sectId = detail.sectId,
            mineSlots = detail.mineSlots.map { slotConverter.convertMineSlot(it) },
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

    fun convertBackSectDetail(data: SerializableSectDetail): com.xianxia.sect.core.model.SectDetail {
        val scoutInfo = data.scoutInfo.takeIf { it.sectId.isNotEmpty() }?.let { convertBackSectScoutInfo(it) } ?: com.xianxia.sect.core.model.SectScoutInfo()

        return com.xianxia.sect.core.model.SectDetail(
            sectId = data.sectId,
            mineSlots = data.mineSlots.map { slotConverter.convertBackMineSlot(it) },
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

    fun convertSectScoutInfo(info: com.xianxia.sect.core.model.SectScoutInfo): SerializableSectScoutInfo {
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

    fun convertBackSectScoutInfo(data: SerializableSectScoutInfo): com.xianxia.sect.core.model.SectScoutInfo {
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

    fun convertExploredSectInfo(info: com.xianxia.sect.core.model.ExploredSectInfo): SerializableExploredSectInfo {
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

    fun convertBackExploredSectInfo(data: SerializableExploredSectInfo): com.xianxia.sect.core.model.ExploredSectInfo {
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

    fun convertMerchantItem(item: com.xianxia.sect.core.model.MerchantItem): SerializableMerchantItem {
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

    fun convertBackMerchantItem(data: SerializableMerchantItem): com.xianxia.sect.core.model.MerchantItem {
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

    fun convertSectWarehouse(warehouse: com.xianxia.sect.core.model.SectWarehouse?): SerializableSectWarehouse {
        if (warehouse == null) return SerializableSectWarehouse()
        return SerializableSectWarehouse(
            items = warehouse.items?.map { convertWarehouseItem(it) } ?: emptyList(),
            spiritStones = warehouse.spiritStones ?: 0L
        )
    }

    fun convertBackSectWarehouse(data: SerializableSectWarehouse): com.xianxia.sect.core.model.SectWarehouse {
        return com.xianxia.sect.core.model.SectWarehouse(
            items = data.items.map { convertBackWarehouseItem(it) },
            spiritStones = data.spiritStones
        )
    }

    fun convertWarehouseItem(item: com.xianxia.sect.core.model.WarehouseItem): SerializableWarehouseItem {
        return SerializableWarehouseItem(
            itemId = item.itemId ?: "",
            itemName = item.itemName ?: "",
            itemType = item.itemType ?: "",
            rarity = item.rarity ?: 0,
            quantity = item.quantity ?: 0
        )
    }

    fun convertBackWarehouseItem(data: SerializableWarehouseItem): com.xianxia.sect.core.model.WarehouseItem {
        return com.xianxia.sect.core.model.WarehouseItem(
            itemId = data.itemId,
            itemName = data.itemName,
            itemType = data.itemType,
            rarity = data.rarity,
            quantity = data.quantity
        )
    }

    fun convertSectRelation(relation: com.xianxia.sect.core.model.SectRelation): SerializableSectRelation {
        return SerializableSectRelation(
            sectId1 = relation.sectId1 ?: "",
            sectId2 = relation.sectId2 ?: "",
            favor = relation.favor ?: 0,
            lastInteractionYear = relation.lastInteractionYear ?: 0,
            noGiftYears = relation.noGiftYears ?: 0
        )
    }

    fun convertBackSectRelation(data: SerializableSectRelation): com.xianxia.sect.core.model.SectRelation {
        return com.xianxia.sect.core.model.SectRelation(
            sectId1 = data.sectId1,
            sectId2 = data.sectId2,
            favor = data.favor,
            lastInteractionYear = data.lastInteractionYear,
            noGiftYears = data.noGiftYears
        )
    }

    fun convertSectPolicies(policies: com.xianxia.sect.core.model.SectPolicies?): SerializableSectPolicies {
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
            autoForge = policies.autoForge ?: false,
            autoMineFocused = policies.autoMineFocused,
            autoMineRootCounts = policies.autoMineRootCounts,
            autoMineThreshold = policies.autoMineThreshold,
            autoPlantFocused = policies.autoPlantFocused,
            autoPlantRootCounts = policies.autoPlantRootCounts,
            autoPlantThreshold = policies.autoPlantThreshold,
            autoAlchemyFocused = policies.autoAlchemyFocused,
            autoAlchemyRootCounts = policies.autoAlchemyRootCounts,
            autoAlchemyThreshold = policies.autoAlchemyThreshold,
            autoForgeFocused = policies.autoForgeFocused,
            autoForgeRootCounts = policies.autoForgeRootCounts,
            autoForgeThreshold = policies.autoForgeThreshold
        )
    }

    fun convertBackSectPolicies(data: SerializableSectPolicies): com.xianxia.sect.core.model.SectPolicies {
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
            autoForge = data.autoForge,
            autoMineFocused = data.autoMineFocused,
            autoMineRootCounts = data.autoMineRootCounts,
            autoMineThreshold = data.autoMineThreshold,
            autoPlantFocused = data.autoPlantFocused,
            autoPlantRootCounts = data.autoPlantRootCounts,
            autoPlantThreshold = data.autoPlantThreshold,
            autoAlchemyFocused = data.autoAlchemyFocused,
            autoAlchemyRootCounts = data.autoAlchemyRootCounts,
            autoAlchemyThreshold = data.autoAlchemyThreshold,
            autoForgeFocused = data.autoForgeFocused,
            autoForgeRootCounts = data.autoForgeRootCounts,
            autoForgeThreshold = data.autoForgeThreshold
        )
    }

    companion object {
        internal val slotConverter = SlotConverter()
    }
}
