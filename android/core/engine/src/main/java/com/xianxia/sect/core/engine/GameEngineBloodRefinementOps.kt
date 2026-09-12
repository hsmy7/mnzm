package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.TimeProgressUtil
import kotlinx.coroutines.CancellationException
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.domain.disciple.addPctToTotal


/** 血炼属性 key → 显示名映射 */
private val STAT_DISPLAY_NAMES = mapOf(
    "hp" to "生命",
    "physicalAttack" to "物攻",
    "magicAttack" to "法攻",
    "physicalDefense" to "物防",
    "magicDefense" to "法防",
    "speed" to "速度"
)


/** 单弟子血炼材料记录上限（审计 P2-7；与 C++ blood_refinement.h 同值锚定） */
private const val BLOOD_REFINEMENT_RECORDS_CAP = 100


/**
 * 原子化启动血炼：灵石扣除、材料消耗、进度写入、弟子状态更新
 * 在同一 [stateStore.update] 事务中完成，失败时整体回滚。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
suspend fun GameEngine.startBloodRefinementAtomic(
    materialName: String,
    materialRarity: Int,
    materialCount: Int,
    buildingInstanceId: String,
    requiredSpiritStones: Long,
    progress: BloodRefinementProgress
): BloodRefinementStartResult {
    return engineContextDispatcher.withEngineContext {
        if (requiredSpiritStones <= 0) {
            return@withEngineContext BloodRefinementStartResult.Error("灵石消耗必须为正数")
        }
        if (materialCount <= 0) {
            return@withEngineContext BloodRefinementStartResult.Error("材料消耗必须为正数")
        }
        if (progress.durationMonths <= 0 || progress.bonusPercent <= 0.0) {
            return@withEngineContext BloodRefinementStartResult.Error("血炼配置异常（duration/bonus）")
        }
        progress.discipleId.toIntOrNull() ?: return@withEngineContext BloodRefinementStartResult.Error("非法弟子ID")
        try {
            stateStore.update {
                checkStones(requiredSpiritStones)
                consumeMaterial(materialName, materialRarity, materialCount)
                // 清理该弟子在全部槽位的旧引用（防同一弟子多槽位；血炼池内部排他
                // 保留在 commitBloodRefinement 内）。checkStones/consumeMaterial 抛错时
                // 事务整体回滚，清理副作用不残留
                gameData = DiscipleSlotCleanup(assignmentGate)
                    .clearAllSlotsDataOnly(gameData, progress.discipleId)
                commitBloodRefinement(buildingInstanceId, requiredSpiritStones, progress)
            }
            // 防御：清理旧 gate 注册（confirmAssign 由调用方在成功后登记，此处消除窗口期）
            assignmentGate.release(progress.discipleId)
            // 双存储同步：清 Room 生产槽 Repository
            clearDiscipleFromProductionRepository(progress.discipleId)
            return@withEngineContext BloodRefinementStartResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e("GameEngine", "血炼原子启动失败: ${e.message}", e)
            return@withEngineContext BloodRefinementStartResult.Error(e.message ?: "未知错误")
        }
    }
}

/** 原子化取消血炼：移除进度 + 恢复弟子状态为空闲 */
suspend fun GameEngine.cancelBloodRefinement(
    buildingInstanceId: String,
    discipleId: String
) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            cancelBloodRefinement(buildingInstanceId, discipleId)
        }
    }
}

/** 月度结算 — 处理所有到期血炼 */
suspend fun GameEngine.processBloodRefinementCompletions() {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { processBloodRefinementCompletions() }
    }
}

/** 校验灵石是否足够 */
private fun MutableGameState.checkStones(required: Long) {
    if (gameData.spiritStones < required) {
        error("灵石不足: 需要 $required, 当前 ${gameData.spiritStones}")
    }
}

/** 跨堆叠扣除材料 */
private fun MutableGameState.consumeMaterial(
    name: String, rarity: Int, count: Int
) {
    var remaining = count
    val matching = materials.all().filter {
        it.name == name && it.rarity == rarity && !it.isLocked
    }
    for (mat in matching) {
        if (remaining <= 0) break
        val take = minOf(remaining, mat.quantity)
        val newQty = mat.quantity - take
        if (newQty <= 0) materials.remove(mat.id)
        else materials.update(mat.id) { it.copy(quantity = newQty) }
        remaining -= take
    }
    if (remaining > 0) error("兽血材料不足: 缺少 $remaining 份 $name")
}

/** 扣除灵石并写入血炼进度 */
private fun MutableGameState.commitBloodRefinement(
    buildingInstanceId: String,
    requiredSpiritStones: Long,
    progress: BloodRefinementProgress
) {
    // 排他性检查：该建筑已有血炼
    if (buildingInstanceId in gameData.activeBloodRefinements) {
        error("该血炼池已有进行中的血炼")
    }
    // 排他性检查：该弟子已在其他血炼池中
    if (gameData.activeBloodRefinements.values.any {
        it.discipleId == progress.discipleId
    }) {
        error("该弟子已在其他血炼池中")
    }
    val filledProgress = progress.copy(
        startYear = gameData.gameYear, startMonth = gameData.gameMonth
    )
    gameData = gameData.copy(
        spiritStones = gameData.spiritStones - requiredSpiritStones,
        activeBloodRefinements = gameData.activeBloodRefinements +
            (buildingInstanceId to filledProgress)
    )
    val dId = progress.discipleId.toIntOrNull()
    if (dId != null && dId in discipleTables.ids) {
        discipleTables.statuses[dId] = DiscipleStatus.REFINING
        discipleTables.statusData[dId] = mapOf(
            "buildingId" to buildingInstanceId
        )
    }
}

/**
 * 月度结算 — 检查并处理所有到期血炼。
 * 遍历 [activeBloodRefinements]，对已到期的条目逐条结算。
 */
@Suppress("UnusedParameter") // buildingId: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
fun MutableGameState.processBloodRefinementCompletions() {
    if (gameData.activeBloodRefinements.isEmpty()) return
    val remaining = mutableMapOf<String, BloodRefinementProgress>()
    val originals = gameData.activeBloodRefinements
    for ((buildingId, progress) in originals) {
        val elapsed = TimeProgressUtil.calculateElapsedMonths(
            progress.startYear, progress.startMonth,
            gameData.gameYear, gameData.gameMonth
        )
        if (elapsed < progress.durationMonths) {
            remaining[buildingId] = progress
        } else {
            settleSingleRefinement(buildingId, progress)
        }
    }
    if (remaining.size != originals.size) {
        gameData = gameData.copy(activeBloodRefinements = remaining)
    }
}

/**
 * 结算单条到期血炼：累加百分比乘区、记录完成、
 * 重置弟子状态、发送通知。
 *
 * 新系统使用乘区百分比，直接累加材料百分比到累计记录，
 * 不再写入 DiscipleTables.base* 列。
 */
@Suppress("UnusedParameter") // buildingId: 语义形参：签名表达 API 决策域，当前策略不消费
private fun MutableGameState.settleSingleRefinement(
    buildingId: String,
    progress: BloodRefinementProgress
) {
    val dId = progress.discipleId.toIntOrNull()
    if (dId == null || dId !in discipleTables.ids) return
    val statKey = progress.selectedStat
    if (statKey.isEmpty()) {
        // 数据异常防御：进度被 processBloodRefinementCompletions 移除，但
        // REFINING 受保护状态须显式打破，否则弟子永久卡"血炼池中"
        discipleTables.clearBloodRefinementStatusData(dId)
        discipleTables.statuses[dId] = DiscipleStatus.IDLE
        return
    }

    // 防御：血炼期间弟子可能因其他系统死亡
    if (discipleTables.isAlive[dId] == 0) return

    // 百分比累加：只传增量（addPctToTotal 内部做 total + pct）
    // 防御篡改/损坏：NaN 无法被 coerceAtLeast 拦下（NaN 比较恒 false），先 isFinite 归零
    val existingTotal = gameData.bloodRefinementPctTotals[progress.discipleId]
    val safeBonusPct = progress.bonusPercent.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

    val updatedTotal = if (existingTotal != null) {
        DiscipleStatCalculator.addPctToTotal(existingTotal, statKey, safeBonusPct)
    } else {
        DiscipleStatCalculator.addPctToTotal(
            BloodRefinementPctTotal(discipleId = progress.discipleId),
            statKey, safeBonusPct
        )
    }

    // 不写 DiscipleTables.base* 列（血炼为乘法乘区，计算时动态应用）

    val existingRefinements =
        gameData.bloodRefinements[progress.discipleId] ?: emptyList()
    // 审计 P2-7：纯审计轨迹零消费者，追加处 takeLast 封顶（与 C++
    // BLOOD_REFINEMENT_RECORDS_CAP = 100 同值锚定）
    val updatedRefinements = (existingRefinements + progress.materialId)
        .takeLast(BLOOD_REFINEMENT_RECORDS_CAP)
    gameData = gameData.copy(
        bloodRefinements = gameData.bloodRefinements +
            (progress.discipleId to updatedRefinements),
        bloodRefinementPctTotals = gameData.bloodRefinementPctTotals +
            (progress.discipleId to updatedTotal)
    )

    discipleTables.clearBloodRefinementStatusData(dId)

    // 血炼完成后恢复弟子为空闲：REFINING 是受保护状态（deriveDiscipleStatus
    // 永不回退），须在事务内显式重置为 IDLE，与 CHANGELOG"重置弟子为空闲"
    // 契约一致——否则月结后弟子永久卡"血炼池中"（根因：与思过解除模式不一致）
    discipleTables.statuses[dId] = DiscipleStatus.IDLE

    val statName = STAT_DISPLAY_NAMES[statKey] ?: statKey
    recordGameEvent(
        GameEventCategory.SECT, GameEventType.BLOOD_REFINEMENT,
        "${progress.discipleName}的血练已完成！属性「$statName」获得提升。",
        relatedEntityName = progress.discipleName
    )
}
private fun MutableGameState.cancelBloodRefinement(
    buildingInstanceId: String,
    discipleId: String
) {
    gameData = gameData.copy(
        activeBloodRefinements = gameData.activeBloodRefinements - buildingInstanceId
    )
    val dId = discipleId.toIntOrNull()
    if (dId != null && dId in discipleTables.ids) {
        discipleTables.clearBloodRefinementStatusData(dId)
        // 血炼 REFINING 是受保护状态（deriveDiscipleStatus 永不回退），须在
        // 事务内显式重置为 IDLE——否则进度已删但弟子永久卡"血炼池中"，
        // 卸任/取消血炼全部"无反应"（根因：与思过 REFLECTING 解除模式不一致）
        discipleTables.statuses[dId] = DiscipleStatus.IDLE
    }
}

/** 仅清除血炼相关的 statusData key，不擦除其他系统写入的数据 */
private fun DiscipleTables.clearBloodRefinementStatusData(dId: Int) {
    val current = statusData.getOrDefault(dId, emptyMap())
    statusData[dId] = current - "buildingId"
}
