package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.AlchemyResult
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.DomainResult



/**
 * 建筑升级结果。
 */
sealed interface UpgradeResult {
    /**
     * 升级成功。
     *
     * @param upgradedCount 实际升级数量
     * @param spaceBlockedCount 因「升级后占地扩大空间不足」被跳过的数量（0 = 无）
     */
    data class Success(
        val upgradedCount: Int,
        val spaceBlockedCount: Int = 0
    ) : UpgradeResult

    /**
     * 升级失败（未升级任何建筑），[reasons] 为逐条中文原因（直接用于提示框文案）。
     */
    data class Failure(val reasons: List<String>) : UpgradeResult
}

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
     * 单座建筑升级（原地变换 + 扣差价）。
     *
     * 任一条件不满足（宗门等级 / 灵石差价 / 升级后占地空间）时返回
     * [UpgradeResult.Failure] 且状态零变更（原子性）。
     *
     * @param instanceId 目标建筑实例 id
     * @return [UpgradeResult.Success]（升级 1 座）或 [UpgradeResult.Failure]（含逐条原因）
     */
    suspend fun upgradeBuilding(instanceId: String): UpgradeResult

    /**
     * 批量升级某源建筑类型（一键升级）。
     *
     * 事务内按 gridX/gridY 稳定序逐座升级，每座以「升级中间态」增量校验空间
     * （防止相邻建筑同时扩占地互相重叠）；灵石不足时按可负担数量升级（不报错），
     * 灵石不足以升级任意一座时返回 [UpgradeResult.Failure]。
     *
     * @param sectId 目标宗门作用域
     * @param sourceKey 源建筑 key
     * @param maxCount 最多升级数量上限（通常为 1 或该类型实例总数）
     * @return [UpgradeResult.Success]（含升级数与空间不足跳过数）或 [UpgradeResult.Failure]
     */
    suspend fun upgradeBuildings(sectId: String, sourceKey: String, maxCount: Int): UpgradeResult

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
