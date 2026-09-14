package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.bool
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.long
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * 生产 UI 面 + 灵田种植族 native 事务臂（batch-17 写者下沉，S7 同族）。
 *
 * AUTHORITATIVE 门控下把生产槽 UI 四操作（任命/卸任/自动续炼翻转/惰性建槽）
 * 与灵田种植四操作（单/批播种、单/批移除）的 C++ 事务经 nativeExecute 转发；
 * tryExecuteNative 成功内含 applyDirtyFromNative 脏段回读（gameData.
 * productionSlots / gameData.spiritFieldPlants 整段镜像）。
 *
 * 真相约定（不得引入第二套——README §7.3）：
 * - 镜像（C++ gameData.productionSlots / gameData.spiritFieldPlants）为真源；
 *   Room 生产槽持久化与弟子状态推导为**后置残差**，由本文件与门面承担。
 * - 失败信封/降级（flag 关、桥未加载、校验失败）返回 null/false —— 调用方回退
 *   Kotlin 原路径重执行校验链（双实现并行契约，用户可见文案由 Kotlin 臂产出）。
 *
 * 失败零写入：C++ 事务校验链先行（槽位存在性 / 种子存在·未锁定·余量>0），
 * 任一校验失败不触碰任何状态且不发脏段——本文件返回降级信号，镜像零变更。
 *
 * 零 RNG：本批八事务全族不触达 RngManager（C++ 侧签名级论证 + GTest 全分区
 * 快照差分；dispatch 全链路另以 exportStates 对照断言）。
 */
private suspend fun BuildingFacadeImpl.execNativeTx(
    actionId: Int,
    build: JsonObjectBuilder.() -> Unit
): JsonElement? {
    if (!NativeEngineFlag.authoritative) return null
    // 测试 mock 下 stateSyncServiceRef 返回 null —— tryExecuteNative 内部守卫
    // 已放宽为可空入参；此处先赋可空局部再传（handover findings 13）
    return GameEngineNativeOps.tryExecuteNative(
        stateSyncService = gameEngineCore.stateSyncServiceRef,
        actionId = actionId,
        paramsJson = params(build)
    )
}

// ── 生产槽：任命 ────────────────────────────────────────────────

/**
 * 任命残差（native 臂与 Kotlin 回退臂共用；此时镜像已由其中一臂写就）：
 * repo 写入目标槽并清该弟子他处占用；目标槽写失败 → 回滚镜像为分配前快照
 * 且不登记 gate（4.00.91 玩家"任命不生效"主症状路径），玩家可重试任命。
 */
internal suspend fun BuildingFacadeImpl.finishProductionAssignment(
    buildingType: BuildingType,
    slotIndex: Int,
    discipleId: String,
    discipleName: String,
    existingSlot: ProductionSlot?,
    targetSlot: com.xianxia.sect.core.model.SlotRef
) {
    if (writeRepoAssignment(buildingType, slotIndex, discipleId, discipleName)) {
        // 镜像回滚为分配前快照，双端一致；gate 不登记
        rollbackMirrorSlot(buildingType, slotIndex, existingSlot)
        return
    }
    // 清旧注册再登记新分配（未注册时 release 为空操作，安全）
    assignmentGate.release(discipleId)
    assignmentGate.confirmAssign(discipleId, targetSlot)
    // 旧 occupant 状态在事务完成后同步（推导式，此时 GameData 已清旧槽位）
    syncOldOccupantStatus(existingSlot, discipleId)
}

/**
 * 生产槽任命 native 臂（镜像事务：全槽位清理 + 目标槽写 + 他槽清空）。
 * @return true = C++ 已写镜像（调用方只承担 Room/gate/状态残差）；
 *         false = 降级或校验失败（零写入），调用方回退原路径
 */
internal suspend fun BuildingFacadeImpl.productionNativeAssign(
    buildingType: BuildingType,
    slotIndex: Int,
    discipleId: String,
    discipleName: String
): Boolean {
    val data = execNativeTx(ActionIds.PROD_UI_ASSIGN_SLOT) {
        put("buildingType", buildingType.name)
        put("slotIndex", slotIndex)
        put("discipleId", discipleId)
        put("discipleName", discipleName)
    } ?: return false
    return data.str("assigned") == "true"
}

// ── 生产槽：卸任 ────────────────────────────────────────────────

/**
 * 生产槽卸任 native 臂（镜像事务：占用捕获 + WORKING 剩余时长归一 + 槽位清空）。
 *
 * C++ 事务已把归一后的槽位写入镜像，调用方把该镜像槽单槽回放 Room
 * （S7 productionNativeReset 同族"镜像→Room 回放"），保证 repo 获得与
 * 回退臂同式的 startYear/startMonth/duration 归一值。
 *
 * @return true = C++ 已写镜像；false = 降级/校验失败，调用方回退原路径
 */
internal suspend fun BuildingFacadeImpl.productionNativeRemove(
    buildingType: BuildingType,
    slotIndex: Int
): Boolean {
    val data = execNativeTx(ActionIds.PROD_UI_REMOVE_SLOT) {
        put("buildingType", buildingType.name)
        put("slotIndex", slotIndex)
    } ?: return false
    return data.str("removed") == "true"
}

/** 卸任残差：镜像槽位回放 Room（保留 Room 侧 buildingInstanceId——C++ 模型无该字段）。 */
internal suspend fun BuildingFacadeImpl.replayMirrorSlotToRepository(
    buildingType: BuildingType,
    slotIndex: Int
) {
    val mirrored = stateStore.gameDataSnapshot.productionSlots
        .find { it.buildingType == buildingType && it.slotIndex == slotIndex } ?: return
    withContext(ioDispatcher.dispatcher) {
        productionCoordinator.repository.updateSlot(buildingType, slotIndex) { repoSlot ->
            mirrored.copy(buildingInstanceId = repoSlot.buildingInstanceId)
        }
    }
}

// ── 生产槽：自动续炼开关 ────────────────────────────────────────

/**
 * 自动续炼翻转 native 臂（镜像字段翻转，回传翻转后值）。
 * @return 翻转后值；null = 降级/校验失败（零写入），调用方回退原路径
 */
internal suspend fun BuildingFacadeImpl.productionNativeToggleAutoRestart(
    buildingType: BuildingType,
    slotIndex: Int
): Boolean? {
    val data = execNativeTx(ActionIds.PROD_UI_TOGGLE_AUTO_RESTART) {
        put("buildingType", buildingType.name)
        put("slotIndex", slotIndex)
    } ?: return null
    if (data.str("toggled") != "true") return null
    return data.bool("newValue")
}

/** 自动续炼残差：把镜像翻转值单字段回放 Room（行为以 repo 为准）。 */
internal suspend fun BuildingFacadeImpl.replayToggleToRepository(
    buildingType: BuildingType,
    slotIndex: Int,
    newValue: Boolean
) {
    withContext(ioDispatcher.dispatcher) {
        productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
            slot.copy(autoRestartEnabled = newValue)
        }
    }
}

// ── 生产槽：惰性建槽 / 镜像槽维护 ───────────────────────────────

/**
 * 惰性建槽 native 臂（按 buildingId+slotIndex upsert 镜像，幂等）。
 *
 * BuildingDelegate 语义：放置建筑时 Kotlin 已把新槽追加进镜像，本臂只是让
 * "C++ 真相先行"约定自洽（缺失则追加、命中则覆写为传入快照），随后调用方
 * 落 Room。降级/失败对 Room 写入无影响（幂等重复 append 由 repo 侧去重）。
 */
internal suspend fun BuildingFacadeImpl.productionNativeAddSlot(slot: ProductionSlot) {
    execNativeTx(ActionIds.PROD_UI_ADD_SLOT) {
        put("id", slot.id)
        put("slotIndex", slot.slotIndex)
        put("buildingType", slot.buildingType.name)
        put("buildingId", slot.buildingId)
        put("status", slot.status.name)
        put("recipeId", slot.recipeId ?: "")
        put("recipeName", slot.recipeName)
        put("startYear", slot.startYear)
        put("startMonth", slot.startMonth)
        put("duration", slot.duration)
        put("baseDuration", slot.baseDuration)
        put("assignedDiscipleId", slot.assignedDiscipleId ?: "")
        put("assignedDiscipleName", slot.assignedDiscipleName)
        put("successRate", slot.successRate)
        put("outputItemId", slot.outputItemId ?: "")
        put("outputItemName", slot.outputItemName)
        put("outputItemRarity", slot.outputItemRarity)
        put("outputItemSlot", slot.outputItemSlot)
        put("expectedYield", slot.expectedYield)
        put("autoRestartEnabled", slot.autoRestartEnabled)
        put("completionMonth", slot.completionMonth)
        put("completionPhase", slot.completionPhase)
    }
}

// ── 灵田：播种 / 移除 ──────────────────────────────────────────

/**
 * 灵田单块播种 native 臂（种子校验 + 空地匹配 + 同事务扣种）。
 * @return true = C++ 已写镜像；false = 降级/校验失败，调用方回退原路径
 */
internal suspend fun BuildingFacadeImpl.spiritFieldNativePlantOne(
    buildingInstanceId: String,
    seedId: String,
    sectId: String
): Boolean {
    val data = execNativeTx(ActionIds.SPIRIT_FIELD_PLANT_ONE) {
        put("buildingInstanceId", buildingInstanceId)
        put("seedId", seedId)
        put("sectId", sectId)
    } ?: return false
    return (data.long("planted") ?: 0L) > 0L
}

/**
 * 灵田批量播种 native 臂（余量约束上限 + 地块镜像序播种 + 按实际播种数扣种）。
 * @return true = C++ 已写镜像；false = 降级/校验失败，调用方回退原路径
 */
internal suspend fun BuildingFacadeImpl.spiritFieldNativePlantBatch(
    instanceIds: List<String>,
    seedId: String,
    sectId: String
): Boolean {
    val data = execNativeTx(ActionIds.SPIRIT_FIELD_PLANT_BATCH) {
        put("instanceIds", JsonArray(instanceIds.map { JsonPrimitive(it) }))
        put("seedId", seedId)
        put("sectId", sectId)
    } ?: return false
    return (data.long("planted") ?: 0L) > 0L
}

/**
 * 灵田单块移除 native 臂（首命中实例清空种植字段；未知实例为静默成功空操作）。
 * @return true = C++ 已写镜像；false = 降级，调用方回退原路径
 */
internal suspend fun BuildingFacadeImpl.spiritFieldNativeRemoveOne(
    buildingInstanceId: String
): Boolean {
    val data = execNativeTx(ActionIds.SPIRIT_FIELD_REMOVE_ONE) {
        put("buildingInstanceId", buildingInstanceId)
    } ?: return false
    return data.long("removed") != null
}

/**
 * 灵田批量移除 native 臂（实例集合清空种植字段）。
 * @return true = C++ 已写镜像；false = 降级，调用方回退原路径
 */
internal suspend fun BuildingFacadeImpl.spiritFieldNativeRemoveBatch(
    instanceIds: List<String>
): Boolean {
    val data = execNativeTx(ActionIds.SPIRIT_FIELD_REMOVE_BATCH) {
        put("instanceIds", JsonArray(instanceIds.map { JsonPrimitive(it) }))
    } ?: return false
    return data.long("removed") != null
}
