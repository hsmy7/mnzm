package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup

/**
 * GameEngine 扩展 — 住所/巡视楼原子分配操作。
 *
 * 6 个原子方法，每个都在单次 [GameStateStore.update] 事务内完成所有槽位操作
 * （释放原 occupant、清理新弟子旧槽位、写入新槽位、登记门卫、更新状态），
 * 保证分配/释放/交换的一致性。
 *
 * 所有方法返回 [DomainResult]，调用方可通过 `when` 穷举处理成功/失败。
 */

// ── 住所原子操作 ──────────────────────────────────────────────────────

/**
 * 原子化分配弟子到指定住所槽位。
 *
 * 若目标槽位已被其他弟子占用，先自动释放原 occupant 再写入新人。
 * 若同弟子重复分配，视为无操作成功返回。
 */
suspend fun GameEngine.assignToResidenceAtomic(
    buildingInstanceId: String,
    slotIndex: Int,
    discipleId: String
): DomainResult<Unit> = DomainResult.catching(
    AppError.Domain.GameLoop.Unknown("分配住所失败 id=$discipleId")
) {
    stateStore.update {
        val id = discipleId.toIntOrNull()
        require(id != null && id in discipleTables.ids) { "弟子不存在: $discipleId" }
        require(discipleTables.isAlive[id] != 0) { "弟子已死亡: $discipleId" }
        val canonicalId = id.toString() // 标准化 ID："0123" → "123"，防止字符串比较不一致

        // 校验建筑物存在且是住所
        val building = gameData.placedBuildings.find { it.instanceId == buildingInstanceId }
        require(building != null) { "建筑物不存在: $buildingInstanceId" }
        val slotList = gameData.residenceSlots
        require(slotIndex >= 0) { "slotIndex 不能为负数: $slotIndex" }
        val slotIdx = slotList.indexOfFirst {
            it.buildingInstanceId == buildingInstanceId && it.slotIndex == slotIndex
        }
        require(slotIdx >= 0) { "住所槽位不存在: building=$buildingInstanceId slot=$slotIndex" }

        val current = slotList[slotIdx]
        val isSameDisciple = current.discipleId == canonicalId

        if (isSameDisciple) {
            DomainLog.d("GameEngine", "assignToResidence: 重复分配，跳过 canonicalId=$canonicalId slot=$buildingInstanceId/$slotIndex")
        }

        // 释放目标槽位原 occupant（仅清空住所槽位，不改变原 occupant 状态或工作分配）
        if (!isSameDisciple && current.discipleId.isNotEmpty()) {
            val occupantId = current.discipleId
            gameData = gameData.copy(
                residenceSlots = gameData.residenceSlots.map { slot ->
                    if (slot.buildingInstanceId == buildingInstanceId && slot.slotIndex == slotIndex) {
                        slot.copy(discipleId = "", discipleName = "")
                    } else slot
                }
            )
            DomainLog.d("GameEngine", "assignToResidence: 释放原 occupant=$occupantId（被 $canonicalId 覆盖），槽位 $buildingInstanceId/$slotIndex")
        }

        // 跨住所搬迁：如果新弟子已入住其他住所槽位，只清理那个旧槽位
        if (!isSameDisciple) {
            val existingSlot = gameData.residenceSlots.find {
                it.discipleId == canonicalId && !(it.buildingInstanceId == buildingInstanceId && it.slotIndex == slotIndex)
            }
            if (existingSlot != null) {
                gameData = gameData.copy(
                    residenceSlots = gameData.residenceSlots.map { slot ->
                        if (slot.discipleId == canonicalId) {
                            slot.copy(discipleId = "", discipleName = "")
                        } else slot
                    }
                )
                DomainLog.d("GameEngine", "assignToResidence: 清理旧住所槽位 building=${existingSlot.buildingInstanceId} slot=${existingSlot.slotIndex}")
            }
        }

        // 写入新槽位（住所不改变弟子状态、不注册门卫、不影响其他槽位）
        if (!isSameDisciple) {
            val aggregate = requireNotNull(discipleTables.assemble(id)) {
                "弟子 $canonicalId 数据损坏: assemble 返回 null"
            }
            val name = aggregate.name

            gameData = gameData.copy(
                residenceSlots = gameData.residenceSlots.map { slot ->
                    if (slot.buildingInstanceId == buildingInstanceId && slot.slotIndex == slotIndex) {
                        slot.copy(discipleId = canonicalId, discipleName = name)
                    } else slot
                }
            )
        }
    }
}

/**
 * 原子化从住所槽位移除弟子。
 *
 * 若目标槽位已为空，视为无操作成功返回。
 */
suspend fun GameEngine.removeFromResidenceAtomic(
    buildingInstanceId: String,
    slotIndex: Int
): DomainResult<Unit> = DomainResult.catching(
    AppError.Domain.GameLoop.Unknown("移除住所失败")
) {
    stateStore.update {
        val slotList = gameData.residenceSlots
        require(slotIndex >= 0) { "slotIndex 不能为负数: $slotIndex" }
        val slotIdx = slotList.indexOfFirst {
            it.buildingInstanceId == buildingInstanceId && it.slotIndex == slotIndex
        }
        require(slotIdx >= 0) { "住所槽位不存在: building=$buildingInstanceId slot=$slotIndex" }

        val slot = slotList[slotIdx]
        if (slot.discipleId.isEmpty()) return@update // 已为空槽位

        // 清空住所槽位（搬离不改变弟子状态、不影响其他系统槽位）
        gameData = gameData.copy(
            residenceSlots = gameData.residenceSlots.map { s ->
                if (s.buildingInstanceId == buildingInstanceId && s.slotIndex == slotIndex) {
                    s.copy(discipleId = "", discipleName = "")
                } else s
            }
        )

        DomainLog.d("GameEngine", "removeFromResidence: 移除 ${slot.discipleId}")
    }
}

// ── 巡视楼原子操作 ────────────────────────────────────────────────────

/**
 * 原子化分配弟子到指定巡视槽位（按全局索引）。
 *
 * @param globalIndex 在 [GameData.patrolSlots] 列表中的索引
 */
suspend fun GameEngine.assignPatrolAtomic(
    discipleId: String,
    globalIndex: Int
): DomainResult<Unit> = DomainResult.catching(
    AppError.Domain.GameLoop.Unknown("分配巡逻失败 id=$discipleId")
) {
    stateStore.update {
        val id = discipleId.toIntOrNull()
        require(id != null && id in discipleTables.ids) { "弟子不存在: $discipleId" }
        require(discipleTables.isAlive[id] != 0) { "弟子已死亡: $discipleId" }
        require(globalIndex in gameData.patrolSlots.indices) {
            "巡视槽位越界: index=$globalIndex size=${gameData.patrolSlots.size}"
        }

        val current = gameData.patrolSlots[globalIndex]
        val isSameDisciple = current.discipleId == discipleId

        // 释放目标槽位原 occupant（仅清空巡逻槽位 + 释放 gate，不清除其他系统槽位）
        if (!isSameDisciple && current.discipleId.isNotEmpty()) {
            val occupantId = current.discipleId
            val buildingInstanceId = current.buildingInstanceId
            val mutableSlots = gameData.patrolSlots.toMutableList()
            mutableSlots[globalIndex] = PatrolSlot(
                index = globalIndex,
                buildingInstanceId = buildingInstanceId
            )
            gameData = gameData.copy(patrolSlots = mutableSlots)
            assignmentGate.release(occupantId)
            DomainLog.d("GameEngine", "assignPatrol: 释放原 occupant=$occupantId（仅巡逻槽位）")
        }

        // 清理新弟子的旧槽位
        if (!isSameDisciple) {
            gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, discipleId)
        }

        // 写入新槽位
        if (!isSameDisciple) {
            val aggregate = discipleTables.assemble(id)
            val name = aggregate?.name ?: ""
            val realm = aggregate?.realmName ?: ""
            val portrait = aggregate?.portraitRes ?: ""
            val buildingInstanceId = current.buildingInstanceId

            val mutableSlots = gameData.patrolSlots.toMutableList()
            mutableSlots[globalIndex] = PatrolSlot(
                index = globalIndex,
                discipleId = discipleId,
                discipleName = name,
                discipleRealm = realm,
                portraitRes = portrait,
                buildingInstanceId = buildingInstanceId
            )
            gameData = gameData.copy(patrolSlots = mutableSlots)

            assignmentGate.confirmAssign(
                discipleId,
                SlotRef(
                    category = SlotCategory.PATROL_SLOT,
                    slotType = "patrol",
                    slotId = "patrol_$globalIndex"
                )
            )
        }

    }
    discipleFacade.syncSingleDiscipleStatus(discipleId)
}

/**
 * 原子化分配弟子到指定巡视槽位（按塔索引 + 槽偏移）。
 *
 * 自动计算全局索引：`globalIndex = towerIndex * slotsPerTower + slotOffset`。
 */
suspend fun GameEngine.assignPatrolAtomic(
    discipleId: String,
    towerIndex: Int,
    slotOffset: Int,
    slotsPerTower: Int
): DomainResult<Unit> = assignPatrolAtomic(
    discipleId = discipleId,
    globalIndex = towerIndex * slotsPerTower + slotOffset
)

/**
 * 原子化从巡视槽位移除弟子（按全局索引）。
 *
 * 若目标槽位已为空，视为无操作成功返回。
 */
suspend fun GameEngine.removePatrolAtomic(
    globalIndex: Int
): DomainResult<Unit> = DomainResult.catching(
    AppError.Domain.GameLoop.Unknown("移除巡逻失败")
) {
    var removedDiscipleId = ""
    stateStore.update {
        require(globalIndex in gameData.patrolSlots.indices) {
            "巡视槽位越界: index=$globalIndex size=${gameData.patrolSlots.size}"
        }

        val slot = gameData.patrolSlots[globalIndex]
        if (slot.discipleId.isEmpty()) return@update // 已为空槽位

        removedDiscipleId = slot.discipleId
        val buildingInstanceId = slot.buildingInstanceId

        // 仅清空该巡视槽位，不清除其他系统槽位
        val mutableSlots = gameData.patrolSlots.toMutableList()
        mutableSlots[globalIndex] = PatrolSlot(
            index = globalIndex,
            buildingInstanceId = buildingInstanceId
        )
        gameData = gameData.copy(patrolSlots = mutableSlots)

        // 释放 gate 注册（仅影响门卫）
        assignmentGate.release(removedDiscipleId)

        DomainLog.d("GameEngine", "removePatrol: 移除 $removedDiscipleId 从槽位 $globalIndex")
    }
    discipleFacade.syncSingleDiscipleStatus(removedDiscipleId)
}

/**
 * 原子化交换两个巡视槽位的弟子。
 *
 * 两者均为空时无操作；一方为空时相当于移动。
 */
suspend fun GameEngine.swapPatrolAtomic(
    fromGlobalIndex: Int,
    toGlobalIndex: Int
): DomainResult<Unit> = DomainResult.catching(
    AppError.Domain.GameLoop.Unknown("交换巡逻失败")
) {
    stateStore.update {
        require(fromGlobalIndex in gameData.patrolSlots.indices) {
            "来源槽位越界: from=$fromGlobalIndex"
        }
        require(toGlobalIndex in gameData.patrolSlots.indices) {
            "目标槽位越界: to=$toGlobalIndex"
        }
        if (fromGlobalIndex == toGlobalIndex) return@update

        val fromSlot = gameData.patrolSlots[fromGlobalIndex]
        val toSlot = gameData.patrolSlots[toGlobalIndex]
        val fromDid = fromSlot.discipleId
        val toDid = toSlot.discipleId

        // 同时释放两者槽位引用
        if (fromDid.isNotEmpty()) {
            gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, fromDid)
        }
        if (toDid.isNotEmpty()) {
            gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, toDid)
        }

        // 获取交换后各 slot 所需 disciples 数据
        val fromAgg = fromDid.let { did ->
            if (did.isNotEmpty()) discipleTables.assemble(did.toIntOrNull() ?: -1) else null
        }
        val toAgg = toDid.let { did ->
            if (did.isNotEmpty()) discipleTables.assemble(did.toIntOrNull() ?: -1) else null
        }

        val mutableSlots = gameData.patrolSlots.toMutableList()

        // 将 to 的弟子写入 from 槽位
        mutableSlots[fromGlobalIndex] = if (toDid.isNotEmpty()) {
            val agg = toAgg
            PatrolSlot(
                index = fromGlobalIndex,
                discipleId = toDid,
                discipleName = agg?.name ?: "",
                discipleRealm = agg?.realmName ?: "",
                portraitRes = agg?.portraitRes ?: "",
                buildingInstanceId = fromSlot.buildingInstanceId
            ).also {
                assignmentGate.confirmAssign(
                    toDid,
                    SlotRef(SlotCategory.PATROL_SLOT, "patrol", "patrol_$fromGlobalIndex")
                )
            }
        } else {
            PatrolSlot(index = fromGlobalIndex, buildingInstanceId = fromSlot.buildingInstanceId)
        }

        // 将 from 的弟子写入 to 槽位
        mutableSlots[toGlobalIndex] = if (fromDid.isNotEmpty()) {
            val agg = fromAgg
            PatrolSlot(
                index = toGlobalIndex,
                discipleId = fromDid,
                discipleName = agg?.name ?: "",
                discipleRealm = agg?.realmName ?: "",
                portraitRes = agg?.portraitRes ?: "",
                buildingInstanceId = toSlot.buildingInstanceId
            ).also {
                assignmentGate.confirmAssign(
                    fromDid,
                    SlotRef(SlotCategory.PATROL_SLOT, "patrol", "patrol_$toGlobalIndex")
                )
            }
        } else {
            PatrolSlot(index = toGlobalIndex, buildingInstanceId = toSlot.buildingInstanceId)
        }

        gameData = gameData.copy(patrolSlots = mutableSlots)

        DomainLog.d("GameEngine", "swapPatrol: $fromDid ↔ $toDid 槽位 $fromGlobalIndex ↔ $toGlobalIndex")
    }
    discipleFacade.syncAllDiscipleStatuses()
}

/**
 * 原子化交换两个巡视槽位的弟子（按塔索引 + 槽偏移）。
 *
 * 自动计算全局索引：`globalIndex = towerIndex * slotsPerTower + slotOffset`。
 */
suspend fun GameEngine.swapPatrolAtomic(
    fromTowerIndex: Int,
    fromSlotOffset: Int,
    toTowerIndex: Int,
    toSlotOffset: Int,
    slotsPerTower: Int
): DomainResult<Unit> = swapPatrolAtomic(
    fromGlobalIndex = fromTowerIndex * slotsPerTower + fromSlotOffset,
    toGlobalIndex = toTowerIndex * slotsPerTower + toSlotOffset
)

/**
 * 原子化从巡视槽位移除弟子（按塔索引 + 槽偏移）。
 *
 * 自动计算全局索引：`globalIndex = towerIndex * slotsPerTower + slotOffset`。
 */
suspend fun GameEngine.removePatrolAtomic(
    towerIndex: Int,
    slotOffset: Int,
    slotsPerTower: Int
): DomainResult<Unit> = removePatrolAtomic(
    globalIndex = towerIndex * slotsPerTower + slotOffset
)

/**
 * 原子化批量自动分配巡视槽位。
 *
 * 所有分配在单次 [GameStateStore.update] 事务内完成。
 * 任一分配失败导致整体回滚。
 *
 * @param assignments 槽位分配列表，每项为 (globalIndex, discipleId) 对。
 *                    discipleId 为空字符串时表示清空该槽位。
 */
suspend fun GameEngine.autoAssignPatrolAtomic(
    assignments: List<Pair<Int, String>>
): DomainResult<Unit> = DomainResult.catching(
    AppError.Domain.GameLoop.Unknown("批量分配巡逻失败")
) {
    // 校验：空列表直接返回（不视为失败，兼容上层调用方）
    if (assignments.isEmpty()) {
        DomainLog.d("GameEngine", "autoAssignPatrolAtomic: 空分配列表，跳过")
        return@catching
    }

    // 校验：检查重复槽位索引
    val slotIndexes = assignments.map { it.first }
    val uniqueSlotIndexes = slotIndexes.toSet()
    require(slotIndexes.size == uniqueSlotIndexes.size) {
        "autoAssignPatrolAtomic: 重复的槽位索引 " +
        (slotIndexes.groupBy { it }.filter { it.value.size > 1 }.keys)
    }

    // 校验：检查同一弟子分配到多个槽位
    val discipleIds = assignments.map { it.second }.filter { it.isNotEmpty() }
    val uniqueDiscipleIds = discipleIds.toSet()
    require(discipleIds.size == uniqueDiscipleIds.size) {
        "autoAssignPatrolAtomic: 同一弟子分配到多个槽位 " +
        (discipleIds.groupBy { it }.filter { it.value.size > 1 }.keys)
    }

    stateStore.update {
        // 锁内前置校验：所有槽位索引边界 + 弟子存在性（使用 update 内的最新 gameData/discipleTables，
        // 避免锁外 gameDataSnapshot 的 TOCTOU 竞态。校验在 processing 循环前执行，
        // 若 require 抛出不导致 gate 操作残留）
        for ((globalIndex, did) in assignments) {
            require(globalIndex in gameData.patrolSlots.indices) {
                "巡视槽位越界: index=$globalIndex size=${gameData.patrolSlots.size}"
            }
            if (did.isNotEmpty()) {
                val id = did.toIntOrNull()
                require(id != null && id in discipleTables.ids) { "弟子不存在: $did" }
                require(discipleTables.isAlive[id] != 0) { "弟子已死亡: $did" }
            }
        }

        for ((globalIndex, discipleId) in assignments) {
            if (discipleId.isEmpty()) {
                // 清空槽位
                val slot = gameData.patrolSlots[globalIndex]
                if (slot.discipleId.isNotEmpty()) {
                    val oldDid = slot.discipleId
                    val buildingInstanceId = slot.buildingInstanceId
                    gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, oldDid)

                    val mutableSlots = gameData.patrolSlots.toMutableList()
                    mutableSlots[globalIndex] = PatrolSlot(
                        index = globalIndex,
                        buildingInstanceId = buildingInstanceId
                    )
                    gameData = gameData.copy(patrolSlots = mutableSlots)

                }
            } else {
                // 分配弟子到槽位
                val id = discipleId.toIntOrNull()!!
                val current = gameData.patrolSlots[globalIndex]
                val isSame = current.discipleId == discipleId

                // 释放原 occupant（精确释放：仅清空巡逻槽位 + 释放 gate，不清除其他系统槽位）
                if (!isSame && current.discipleId.isNotEmpty()) {
                    val occupantId = current.discipleId
                    val bi = current.buildingInstanceId
                    val ms = gameData.patrolSlots.toMutableList()
                    ms[globalIndex] = PatrolSlot(index = globalIndex, buildingInstanceId = bi)
                    gameData = gameData.copy(patrolSlots = ms)
                    assignmentGate.release(occupantId)
                }

                // 清理新弟子旧槽位（全量清除准备分配）
                if (!isSame) {
                    gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, discipleId)
                }

                if (!isSame) {
                    val aggregate = discipleTables.assemble(id)
                    val buildingInstanceId = current.buildingInstanceId

                    val mutableSlots = gameData.patrolSlots.toMutableList()
                    mutableSlots[globalIndex] = PatrolSlot(
                        index = globalIndex,
                        discipleId = discipleId,
                        discipleName = aggregate?.name ?: "",
                        discipleRealm = aggregate?.realmName ?: "",
                        portraitRes = aggregate?.portraitRes ?: "",
                        buildingInstanceId = buildingInstanceId
                    )
                    gameData = gameData.copy(patrolSlots = mutableSlots)

                    assignmentGate.confirmAssign(
                        discipleId,
                        SlotRef(SlotCategory.PATROL_SLOT, "patrol", "patrol_$globalIndex")
                    )
                }
            }
        }
    }
    discipleFacade.syncAllDiscipleStatuses()
}
