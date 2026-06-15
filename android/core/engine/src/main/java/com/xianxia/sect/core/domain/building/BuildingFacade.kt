package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.DomainResult

/** 建筑系统门面——UI 层统一入口。所有建筑操作通过此接口调用。 */
interface BuildingFacade {
    suspend fun placeBuilding(building: GridBuildingData)
    suspend fun moveBuildingDirect(instanceId: String, newGridX: Int, newGridY: Int)
    suspend fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String)
    suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int)
    fun getBuildingSlots(buildingId: String): List<BuildingSlot>
    /** 开始炼丹。成功返回 [DomainResult.Success] 含槽位，失败携带具体错误原因。 */
    suspend fun startAlchemy(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot>
    /** 开始锻造。成功返回 [DomainResult.Success] 含槽位，失败携带具体错误原因。 */
    suspend fun startForging(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot>
    suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult>
    fun clearPlantSlot(slotIndex: Int)
    fun getForgeSlots(): List<BuildingSlot>
    fun getAlchemyFurnaceCount(): Int
    fun getForgeWorkshopCount(): Int
    fun getAssignedDiscipleForSlot(buildingType: BuildingType, slotIndex: Int): Pair<String, String>?
    fun assignDiscipleToProductionSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        discipleId: String,
        discipleName: String
    )
    fun removeDiscipleFromProductionSlot(buildingType: BuildingType, slotIndex: Int)
    suspend fun toggleAutoRestart(buildingType: BuildingType, slotIndex: Int)
    suspend fun addProductionSlot(slot: ProductionSlot)
    suspend fun startManualPlanting(slotIndex: Int, seedId: String)
    suspend fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String)
    suspend fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String)
    suspend fun removePlantFromSpiritField(buildingInstanceId: String)
    fun clearAlchemySlot(slotIndex: Int)
    fun clearForgeSlot(slotIndex: Int)
    suspend fun removeBuilding(instanceId: String, refund: Long)
}
