package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.production.BuildingType

// ── ProductionProcessor 拆分域 3/5（行为零变更） ──

private val TAG = ProductionProcessor.TAG
internal fun ProductionProcessor.clearSlotAssignment(buildingName: String, slotIndex: Int) {
    scopeProvider.scope.launch(ioDispatcher.dispatcher) {
        productionSlotRepository.updateSlotByBuildingId(buildingName, slotIndex) { s ->
            s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
        }
    }
}

fun ProductionProcessor.processAutoAssign(state: MutableGameState) {
    val data = state.gameData
    val policies = data.sectPolicies
    // 双槽分叉防线：全槽位占用弟子排除在自动排班候选之外。
    // - status==IDLE 为第一层（存储权威）
    // - occupiedIds 为第二层：扫描全部工作槽位（长老/生产/灵矿/藏经阁/仓库驻守/
    //   巡视/宗门驻守/战斗队伍/活跃任务/秘境/洞穴/血炼）——防御"分配后
    //   尚未 syncAllDiscipleStatuses"的陈旧状态窗口与推导缺口（如纳徒长老被推导
    //   为 IDLE 后从"可用弟子"可见），杜绝占用弟子被捕获制造双槽位
    val occupiedIds = buildOccupiedSlotDiscipleIds(data)
    val idleDisciples = state.discipleTables.assembleAll()
        .filter { d -> d.status == DiscipleStatus.IDLE && d.isAlive && d.id !in occupiedIds }
        .toMutableList()


    val occupiedResidentIds = data.residenceSlots
        .filter { it.discipleId.isNotEmpty() }
        .map { it.discipleId }
        .toSet()
    val allAssignments = computeResidenceAssignments(state, data, policies, occupiedResidentIds)
    val assignedResidentIds = allAssignments.values.map { it.first }.toSet()
    // 住所弟子不参与生产自动分配：本次新分配住所（assignedResidentIds）与
    // 已住住所（occupiedResidentIds）均排除——住所虽与工作"被动共存"（清理/自愈
    // 不清住所），但自动管理不应把已住住所弟子当作空闲捕获到生产槽位，
    // 否则形成"住所+生产"双槽位（用户反馈：弟子处于住所槽位仍被安排其他工作槽位）。
    // 状态推导无住所 flag（deriveDiscipleStatus），已住住所弟子 status==IDLE，
    // 若不排除会被第一层 IDLE 过滤放过，制造双槽位。
    idleDisciples.removeAll { it.id in assignedResidentIds }
    idleDisciples.removeAll { it.id in occupiedResidentIds }

    // ── 生产槽位候选预计算（按优先级逐级筛选，候选从 idleDisciples 移除） ──
    // 每类型仅取"空槽数"上限的候选：超出上限的合格弟子保留在池中，回流给
    // 低优先级类型继续分配——否则高优先级类型槽满时会吞掉全部合格候选，
    // 导致低优先级空槽永远无人（无法"一次性安排所有符合条件的弟子"）。
    val emptyHerbSlots = data.productionSlots.count {
        it.buildingType == BuildingType.HERB_GARDEN &&
            it.assignedDiscipleId.isNullOrEmpty() && it.status == ProductionSlotStatus.IDLE
    }
    val emptyMineSlots = data.spiritMineSlots.count { it.discipleId.isEmpty() }
    val emptyAlchemySlots = data.productionSlots.count {
        it.buildingType == BuildingType.ALCHEMY &&
            it.assignedDiscipleId.isNullOrEmpty() && it.status == ProductionSlotStatus.IDLE
    }
    val emptyForgeSlots = data.productionSlots.count {
        it.buildingType == BuildingType.FORGE &&
            it.assignedDiscipleId.isNullOrEmpty() && it.status == ProductionSlotStatus.IDLE
    }

    val herbCandidates = takeCandidates(
        idleDisciples, emptyHerbSlots, policies.autoPlantFocused, policies.autoPlantRootCounts,
        policies.autoPlantThreshold
    ) { it.spiritPlanting }
    val mineCandidates = takeCandidates(
        idleDisciples, emptyMineSlots, policies.autoMineFocused, policies.autoMineRootCounts,
        policies.autoMineThreshold
    ) { it.mining }
    val alchemyCandidates = takeCandidates(
        idleDisciples, emptyAlchemySlots, policies.autoAlchemyFocused, policies.autoAlchemyRootCounts,
        policies.autoAlchemyThreshold
    ) { it.pillRefining }
    val forgeCandidates = takeCandidates(
        idleDisciples, emptyForgeSlots, policies.autoForgeFocused, policies.autoForgeRootCounts,
        policies.autoForgeThreshold
    ) { it.artifactRefining }

    // ══════════════════════════════════════════════════════════════════
    // 单次原子写入（所有 5 步骤在同一事务内完成，由调用方 stateStore.update 包裹）
    // ══════════════════════════════════════════════════════════════════
    val noAutoWorkToDo = allAssignments.isEmpty() && herbCandidates.isEmpty() &&
        mineCandidates.isEmpty() && alchemyCandidates.isEmpty() && forgeCandidates.isEmpty()
    if (noAutoWorkToDo) return

    applyAutoAssignments(
        state, allAssignments, herbCandidates, mineCandidates, alchemyCandidates, forgeCandidates
    )
}

/**
 * 自动分配原子写入：住所 + 灵植/灵矿/炼丹/锻造 5 步（预计算候选迭代器分配）。
 * 在调用方 stateStore.update 事务内执行。
 */

internal fun ProductionProcessor.applyAutoAssignments(
    state: MutableGameState,
    allAssignments: Map<String, Pair<String, String>>,
    herbCandidates: List<Disciple>,
    mineCandidates: List<Disciple>,
    alchemyCandidates: List<Disciple>,
    forgeCandidates: List<Disciple>
) {
    val herbIter = herbCandidates.iterator()
    val alchemyIter = alchemyCandidates.iterator()
    val forgeIter = forgeCandidates.iterator()

    // 1. 住所写入 + 状态同步
    if (allAssignments.isNotEmpty()) {
        val writtenIds = mutableSetOf<String>()
        state.gameData = state.gameData.copy(
            residenceSlots = state.gameData.residenceSlots.map { slot ->
                val key = "${slot.buildingInstanceId}:${slot.slotIndex}"
                val assignment = allAssignments[key]
                if (assignment != null && slot.discipleId.isEmpty()
                    && assignment.first !in writtenIds
                ) {
                    writtenIds.add(assignment.first)
                    slot.copy(discipleId = assignment.first, discipleName = assignment.second)
                } else slot
            }
        )
    }

    // 2. 灵植（使用预计算候选迭代器）
    if (herbCandidates.isNotEmpty()) {
        batchAssignToProductionSlots(
            BuildingType.HERB_GARDEN, BuildingNames.HERB_GARDEN,
            { if (herbIter.hasNext()) herbIter.next() else null }, state
        )
    }

    // 3. 灵矿（inline 写入 + 状态同步）
    if (mineCandidates.isNotEmpty()) {
        val mineAssignments = mineCandidates.map { it.id to it.name }
        val mineAssignIter = mineAssignments.iterator()
        state.gameData = state.gameData.copy(
            spiritMineSlots = state.gameData.spiritMineSlots.map { slot ->
                if (slot.discipleId.isEmpty() && mineAssignIter.hasNext()) {
                    val (id, name) = mineAssignIter.next()
                    slot.copy(discipleId = id, discipleName = name)
                } else slot
            }
        )
    }

    // 4. 炼丹（使用预计算候选迭代器）
    if (alchemyCandidates.isNotEmpty()) {
        batchAssignToProductionSlots(
            BuildingType.ALCHEMY, BuildingNames.ALCHEMY,
            { if (alchemyIter.hasNext()) alchemyIter.next() else null }, state
        )
    }

    // 5. 锻造（使用预计算候选迭代器）
    if (forgeCandidates.isNotEmpty()) {
        batchAssignToProductionSlots(
            BuildingType.FORGE, BuildingNames.FORGE,
            { if (forgeIter.hasNext()) forgeIter.next() else null }, state
        )
    }
}

/**
 * 批量安排弟子到指定生产建筑的所有空闲槽位。
 *
 * 从 [MutableGameState.productionSlots] 读取/写入，确保与 stateStore 在同一事务内。
 */

internal fun ProductionProcessor.batchAssignToProductionSlots(
    type: BuildingType,
    buildingId: String,
    takeNext: () -> Disciple?,
    state: MutableGameState
) {
    val slots = state.gameData.productionSlots.filter { it.buildingType == type }
    val emptySlots = slots.filter { slot ->
        slot.assignedDiscipleId.isNullOrEmpty()
            && slot.status == ProductionSlotStatus.IDLE
    }
    if (emptySlots.isEmpty()) return


    val updates = mutableMapOf<Int, Pair<String, String>>() // slotIndex → (discipleId, discipleName)
    for (emptySlot in emptySlots) {
        val candidate = takeNext() ?: break
        updates[emptySlot.slotIndex] = candidate.id to candidate.name
        val cid = candidate.id.toIntOrNull()
        if (cid == null) {
            DomainLog.w(TAG, "batchAssignToProductionSlots: invalid disciple id ${candidate.id}")
        }
    }
    if (updates.isEmpty()) return

    // 镜像先行（与 stateStore 同一事务，保证本帧状态推导一致）
    state.gameData = state.gameData.copy(
        productionSlots = state.gameData.productionSlots.map { slot ->
            val update = updates[slot.slotIndex]
            if (update != null) slot.copy(
                assignedDiscipleId = update.first,
                assignedDiscipleName = update.second
            ) else slot
        }
    )

    // Repository 回写（双存储对齐）：UI 读 repo 真源，仅写镜像会导致
    // UI 显示空闲但弟子已被占用（4.00.91 玩家反馈症状）
    scopeProvider.scope.launch(ioDispatcher.dispatcher) {
        for ((slotIndex, assignment) in updates) {
            writeBatchAssignmentToRepo(buildingId, slotIndex, assignment)
        }
    }
}

/**
 * S3 repo 回写单个自动排班槽位。
 * transform 内条件覆盖（锁内原子）防止与玩家手动任命竞态；
 * 回写失败或竞态被跳过时回滚镜像，保持双端一致。
 */

internal suspend fun ProductionProcessor.writeBatchAssignmentToRepo(
    buildingId: String,
    slotIndex: Int,
    assignment: Pair<String, String>
) {
    val result = productionSlotRepository.updateSlotByBuildingId(buildingId, slotIndex) { s ->
        if (!s.assignedDiscipleId.isNullOrEmpty()) s
        else s.copy(assignedDiscipleId = assignment.first, assignedDiscipleName = assignment.second)
    }
    val written = result.getOrNull()
    if (result.isFailure || written == null || written.assignedDiscipleId != assignment.first) {
        DomainLog.w(
            TAG,
            "batchAssignToProductionSlots: repo 回写被跳过/失败 $buildingId[$slotIndex] " +
                "disciple=${assignment.first} current=${written?.assignedDiscipleId}",
            result.exceptionOrNull()
        )
        rollbackMirrorBatchAssignment(buildingId, slotIndex, assignment.first)
    }
}

/** S3 回滚镜像中自动排班写入的槽位（仅当仍是该弟子，防覆盖玩家后续任命） */

internal fun ProductionProcessor.rollbackMirrorBatchAssignment(buildingId: String, slotIndex: Int, discipleId: String) {
    stateStore.update {
        gameData = gameData.copy(
            productionSlots = gameData.productionSlots.map { s ->
                if (s.buildingId == buildingId && s.slotIndex == slotIndex &&
                    s.assignedDiscipleId == discipleId
                ) s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                else s
            }
        )
    }
}

fun ProductionProcessor.isDiscipleFollowed(d: Disciple): Boolean {
    return d.statusData["followed"] == "true"
}

/**
 * 月结窗口前置对齐：以 Repository 为真源整表写入镜像 productionSlots
 * （nativeSettleMonth 之前调用）。
 *
 * C++ 月结生产结算（production.h）只看镜像——若不对齐，手动启动生产
 * （历史只写 Room 的 B5 口径）/惰性创建槽（ensureSlotForBuilding）/
 * 取消收获等 Room 先行写入都会让 C++ 结算在过期视图上执行（漏结算/
 * autoRestart 双扣料）。读档后镜像本已对齐，此处为幂等兜底。
 */

internal fun ProductionProcessor.alignMirrorFromRepository() {
    val repoSlots = productionSlotRepository.getSlots()
    if (repoSlots.isEmpty()) return
    stateStore.update {
        gameData = gameData.copy(productionSlots = repoSlots)
    }
}

/**
 * S4 月结窗口后置写回：以镜像为权威整表重放 Repository（restoreSlots，
 * 不走 SlotStateMachine——C++ 结算的 WORKING→IDLE→WORKING 复合变更
 * 无法用单步转换表达）。
 *
 * 前置对齐保证镜像 ⊇ 对齐时点 Room（C++ 不删槽位），整表重放无丢失；
 * UI 并发写窗口（月结瞬间放建筑）与读档 restoreSlots 同口径接受。
 * IO 失败仅记录：镜像已是权威，Room 落后由下月对齐自愈。
 */

internal fun ProductionProcessor.restoreRepositoryFromMirror(slotId: Int) {
    val mirrorSlots = stateStore.gameData.value.productionSlots
    scopeProvider.scope.launch(ioDispatcher.dispatcher) {
        try {
            productionSlotRepository.restoreSlots(mirrorSlots, slotId)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            DomainLog.e(TAG, "restoreRepositoryFromMirror 槽位写回失败: $slotId", e)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 影子状态批量生产方法
//
// 操作 [MutableGameState]（shadow）和 [MutableList]（productionSlots），
// 不走 Repository/stateStore，用于并行 computePhaseTick。
// 与现有同名方法的区别：所有 I/O 方向改为本地列表 + state 字段。
// ═══════════════════════════════════════════════════════════════

/**
 * 在影子状态上模拟 N 个月的生产循环。
 * 可由 [ProductionSubsystem.computePhaseTick] 在 ParallelDispatcher 上调用。
 */

fun ProductionProcessor.processMonthlyProductionOnSlots(
    slots: MutableList<ProductionSlot>,
    state: MutableGameState,
    months: Int
) {
    repeat(months) {
        batchAutoAlchemy(slots, state)
        batchAutoForge(slots, state)
        batchBuildingCompletion(slots, state)
        batchSpiritFieldHarvest(slots, state)
    }
}

/** 影子版自动炼丹：从 state 读取政策/草药，直接修改 slots */

internal fun ProductionProcessor.batchAutoAlchemy(
    slots: MutableList<ProductionSlot>,
    state: MutableGameState
) {
    val gd = state.gameData
    val policyBonus = if (gd.sectPolicies.alchemyIncentive)
        GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_EFFECT else 0.0

    val idleSlotIndices = slots
        .filter { it.buildingType == BuildingType.ALCHEMY }
        .filter { it.autoRestartEnabled && it.status == ProductionSlotStatus.IDLE
            && !it.assignedDiscipleId.isNullOrEmpty() }
        .map { it.slotIndex }

    for (slotIndex in idleSlotIndices) {
        // 无可炼配方（材料/门禁不满足）中断后续槽位（续炼启动停止扫槽）
        if (!autoAlchemyRestartSlot(slots, slotIndex, state, gd, policyBonus)) break
    }
}

/** 影子版自动炼丹单槽：true=继续下一槽，false=中断循环 */

internal fun ProductionProcessor.autoAlchemyRestartSlot(
    slots: MutableList<ProductionSlot>,
    slotIndex: Int,
    state: MutableGameState,
    gd: GameData,
    policyBonus: Double
): Boolean {
    val currentHerbs = state.herbs.all()
    val slotIdx = slots.indexOfFirst {
        it.buildingType == BuildingType.ALCHEMY && it.slotIndex == slotIndex
    }
    if (slotIdx < 0) return true

    // 职业门禁：按槽位弟子炼丹师职业等级限制可炼品阶（无职业只能炼凡品）
    val worker = slots[slotIdx].assignedDiscipleId
        ?.let { id -> state.discipleTables.assembleAll().find { it.id == id } }
    val maxTier = worker?.let { ProfessionRules.maxCraftableTier(it.skills.alchemyLevel) }
        ?: 1
    val recipeToStart = findRecipe(currentHerbs, maxTier) ?: return false

    // 消耗材料
    consumeHerbsForRecipeLocal(recipeToStart.materials, currentHerbs, state)
    val absoluteMonth = gd.gameYear * 12 + gd.gameMonth

    // 公式化成功率（属性+职业合成基础率 × 乘区），不再用配方 successRate
    val effectiveSuccessRate = formulaService.buildSuccessRateZones(
        disciple = worker,
        buildingId = BuildingNames.ALCHEMY,
        recipeTier = recipeToStart.tier,
        policyBonus = policyBonus
    ).calculate()
    slots[slotIdx] = slots[slotIdx].copy(
        status = ProductionSlotStatus.WORKING,
        recipeId = recipeToStart.id,
        recipeName = recipeToStart.name,
        startYear = gd.gameYear,
        startMonth = gd.gameMonth,
        duration = recipeToStart.duration,
        baseDuration = recipeToStart.duration,
        successRate = effectiveSuccessRate,
        completionMonth = absoluteMonth + recipeToStart.duration.coerceAtLeast(1),
        completionPhase = 3,
        outputItemId = recipeToStart.id,
        outputItemName = recipeToStart.name,
        outputItemRarity = recipeToStart.rarity
    )
    return true
}

/** 影子版自动锻造 */
