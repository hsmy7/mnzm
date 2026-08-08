package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.AlchemyResult
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.DomainResult



/** 建筑系统门面——UI 层统一入口。所有建筑操作通过此接口调用。 */
interface BuildingFacade {
    val buildingService: BuildingService
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
    suspend fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String)
    suspend fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String)
    suspend fun removePlantFromSpiritField(buildingInstanceId: String)
    suspend fun removePlantsFromSpiritFields(instanceIds: List<String>)
    fun clearAlchemySlot(slotIndex: Int): DomainResult<Unit>
    fun clearForgeSlot(slotIndex: Int): DomainResult<Unit>
    suspend fun removeBuilding(instanceId: String, refund: Long)

    /**
     * 批量拆除多座建筑（一键拆除）。
     * 单次事务内逐栋清理关联槽位并返还灵石，事务后统一同步弟子状态。
     *
     * @param refunds 建筑 instanceId → 返还灵石数映射；未知实例自动跳过
     */
    suspend fun removeBuildings(refunds: Map<String, Long>)

    /**
     * 没收某宗门的全部建筑（无灵石返还）。
     *
     * 2026-08-06 新增：玩家占领的宗门被 AI 夺回时调用——该宗门内玩家建造的
     * 建筑整体拆除（槽位/弟子完整清理），灵石不返还（没收语义）。
     * 引擎月度结算链为非挂起路径，故本方法不标 suspend。
     *
     * @param sectId 目标宗门 id；本宗（""）与空宗门安全跳过
     */
    fun seizeBuildingsOfSect(sectId: String)
}
