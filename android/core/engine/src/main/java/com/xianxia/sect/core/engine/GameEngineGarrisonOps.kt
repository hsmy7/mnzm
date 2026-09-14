package com.xianxia.sect.core.engine


import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.util.DomainLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException


// ── Garrison ────────────────────────────────────────────────────────

suspend fun GameEngine.assignGarrisonDisciple(sectId: String, slotIndex: Int, discipleId: String) {
    return engineContextDispatcher.withEngineContext {
        // ── Native 臂（AUTHORITATIVE）：存在/存活校验 + 全槽清理 + 槽位写经 C++
        // （零 RNG 纯确定性变换）；gate 释放/Room 生产仓/状态同步留 Kotlin。
        // 校验失败信封 / flag 关 / 镜像不可用 → null 回退 Kotlin 原路径
        // （require 校验链在 Kotlin 重执行——用户可见文案由 Kotlin 产出）
        val native = assignGarrisonNative(sectId, slotIndex, discipleId)
        val (written, oldOccupantId) = native ?: assignGarrisonDiscipleInside(sectId, slotIndex, discipleId)
        if (!written) return@withEngineContext
        // 事务成功后才操作 gate（失败回滚时不触碰注册表）
        assignmentGate.release(discipleId)
        if (oldOccupantId != discipleId) {
            assignmentGate.release(oldOccupantId)
        }
        val slotRef = SlotRef(
            category = SlotCategory.GARRISON_SLOT,
            slotType = "${sectId}:${slotIndex}",
            slotId = "garrison_${sectId}_${slotIndex}"
        )
        assignmentGate.confirmAssign(discipleId, slotRef)
        // 双存储同步：清 Room 生产槽 Repository
        clearDiscipleFromProductionRepository(discipleId)
        // 同步新弟子与旧 occupant 状态（不同步会状态残留，影响选择弹窗）
        @Suppress("TooGenericExceptionCaught")
        try {
            discipleFacade.syncSingleDiscipleStatus(discipleId)
            if (oldOccupantId != discipleId) {
                discipleFacade.syncSingleDiscipleStatus(oldOccupantId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.w("GameEngine", "assignGarrison: syncSingleDiscipleStatus 失败", e)
        }
    }
}

/** Native 臂（assignGarrisonDisciple）：null = 降级回退；Pair(是否写入, 目标槽旧 occupant)。 */
private suspend fun GameEngine.assignGarrisonNative(
    sectId: String,
    slotIndex: Int,
    discipleId: String
): Pair<Boolean, String>? {
    val native = ExplorationNativeForward.tryForward(this, ActionIds.EXPLORE_TX_ASSIGN_GARRISON) {
        put("sectId", sectId)
        put("slotIndex", slotIndex)
        put("discipleId", discipleId)
    } as? JsonObject ?: return null
    val written = native["written"]?.jsonPrimitive?.booleanOrNull ?: false
    val oldOccupantId = native["oldOccupantId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return written to oldOccupantId
}

/**
 * 驻守分配事务体：校验 → 清理新弟子全部槽位 → 写入目标槽。
 *
 * @return (是否成功写入, 目标槽旧 occupant 弟子 ID)
 */
private suspend fun GameEngine.assignGarrisonDiscipleInside(
    sectId: String,
    slotIndex: Int,
    discipleId: String
): Pair<Boolean, String> {
    var oldOccupantId = ""
    var written = false
    stateStore.update {
        val id = discipleId.toIntOrNull()
        require(id != null && id in discipleTables.ids) { "弟子不存在: $discipleId" }
        require(discipleTables.isAlive[id] != 0) { "弟子已死亡: $discipleId" }
        val targetSect = gameData.worldMapSects.find { it.id == sectId } ?: return@update
        if (targetSect.garrisonSlots.any { it.discipleId == discipleId }) return@update
        // 覆写前捕获目标槽旧 occupant（事务内读取，避免快照竞态）
        oldOccupantId = targetSect.garrisonSlots
            .find { it.index == slotIndex }?.discipleId.orEmpty()
        // 清理新弟子全部槽位（含跨 sect 旧驻守），防同一弟子多槽位
        // （只查同 sect 重复时，巡逻/长老等岗位可直接拉入驻守）
        gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlotsDataOnly(gameData, discipleId)
        val aggregate = discipleTables.assemble(id)
        val name = aggregate?.name ?: ""
        val realm = aggregate?.realmName ?: ""
        val portrait = aggregate?.portraitRes ?: ""
        val rootColor = aggregate?.spiritRoot?.countColor ?: ""
        gameData = gameData.copy(worldMapSects = gameData.worldMapSects.map { sect ->
            if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot ->
                if (slot.index == slotIndex) GarrisonSlot(
                    index = slotIndex, discipleId = discipleId, discipleName = name,
                    discipleRealm = realm, discipleSpiritRootColor = rootColor,
                    portraitRes = portrait
                ) else slot
            }) else sect
        })
        written = true
    }
    return written to oldOccupantId
}

suspend fun GameEngine.removeGarrisonDisciple(sectId: String, slotIndex: Int) {
    return engineContextDispatcher.withEngineContext {
        // ── Native 臂：occupant 捕获 + 槽位清空经 C++（零 RNG）；gate 释放留 Kotlin。
        // 降级 null → Kotlin 回退臂原样（快照读 occupant + 单事务清槽）
        val native = removeGarrisonNative(sectId, slotIndex)
        val currentDiscipleId = if (native != null) {
            native
        } else {
            val current = stateStore.gameDataSnapshot.worldMapSects
                .find { it.id == sectId }
                ?.garrisonSlots?.find { it.index == slotIndex }?.discipleId.orEmpty()
            stateStore.update {
                gameData = gameData.copy(worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot -> if (slot
                        .index == slotIndex) GarrisonSlot(index = slotIndex) else slot }) else sect
                })
            }
            current
        }
        if (currentDiscipleId.isNotEmpty()) {
            assignmentGate.release(currentDiscipleId)
        }
    }
}

/** Native 臂（removeGarrisonDisciple）：null = 降级回退；非空 = C++ 已清槽，返回清空前 occupant。 */
private suspend fun GameEngine.removeGarrisonNative(sectId: String, slotIndex: Int): String? {
    val native = ExplorationNativeForward.tryForward(this, ActionIds.EXPLORE_TX_REMOVE_GARRISON) {
        put("sectId", sectId)
        put("slotIndex", slotIndex)
    } as? JsonObject ?: return null
    return native["currentDiscipleId"]?.jsonPrimitive?.contentOrNull.orEmpty()
}
