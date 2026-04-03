package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.*

object DisciplePositionHelper {
    
    fun getDisciplePosition(discipleId: String, gameData: GameData): String? {
        val elderSlots = gameData.elderSlots

        if (elderSlots.viceSectMaster == discipleId) return "副掌门"
        if (elderSlots.herbGardenElder == discipleId) return "灵药宛长老"
        if (elderSlots.alchemyElder == discipleId) return "丹鼎殿长老"
        if (elderSlots.forgeElder == discipleId) return "天工峰长老"
        if (elderSlots.outerElder == discipleId) return "外门执事"
        if (elderSlots.preachingElder == discipleId) return "问道峰传道长老"
        if (elderSlots.lawEnforcementElder == discipleId) return "执法长老"
        if (elderSlots.innerElder == discipleId) return "内门执事"
        if (elderSlots.qingyunPreachingElder == discipleId) return "青云峰传道长老"

        if (elderSlots.preachingMasters.any { it.discipleId == discipleId }) return "问道峰传道师"
        if (elderSlots.qingyunPreachingMasters.any { it.discipleId == discipleId }) return "青云峰传道师"

        if (elderSlots.herbGardenDisciples.any { it.discipleId == discipleId }) return "灵药宛亲传弟子"
        if (elderSlots.alchemyDisciples.any { it.discipleId == discipleId }) return "丹鼎殿亲传弟子"
        if (elderSlots.forgeDisciples.any { it.discipleId == discipleId }) return "天工峰亲传弟子"

        if (elderSlots.lawEnforcementDisciples.any { it.discipleId == discipleId }) return "执法弟子"

        if (gameData.spiritMineSlots.any { it.discipleId == discipleId }) return "采矿弟子"
        if (elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }) return "灵矿执事"

        return null
    }
    
    fun hasDisciplePosition(discipleId: String, gameData: GameData): Boolean {
        return getDisciplePosition(discipleId, gameData) != null
    }
    
    fun isPositionWorkStatus(discipleId: String, gameData: GameData): Boolean {
        return getWorkStatusPositionIds(gameData).contains(discipleId)
    }
    
    fun getWorkStatusPositionIds(gameData: GameData): List<String> {
        val elderSlots = gameData.elderSlots
        return listOfNotNull(elderSlots.viceSectMaster) +
               elderSlots.preachingMasters.mapNotNull { it.discipleId } +
               elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId } +
               elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId } +
               elderSlots.spiritMineDeaconDisciples.mapNotNull { it.discipleId }
    }
    
    fun isReserveDisciple(discipleId: String, gameData: GameData): Boolean {
        val elderSlots = gameData.elderSlots
        return elderSlots.lawEnforcementReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.herbGardenReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.alchemyReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.forgeReserveDisciples.any { it.discipleId == discipleId }
    }
    
    fun getEligibleBattleDisciples(
        disciples: List<Disciple>,
        currentSlotDiscipleIds: List<String>,
        gameData: GameData
    ): List<Disciple> {
        return disciples.filter { disciple ->
            disciple.isAlive &&
            disciple.realmLayer > 0 &&
            disciple.status == DiscipleStatus.IDLE &&
            !currentSlotDiscipleIds.contains(disciple.id) &&
            !isPositionWorkStatus(disciple.id, gameData)
        }.sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }
}
