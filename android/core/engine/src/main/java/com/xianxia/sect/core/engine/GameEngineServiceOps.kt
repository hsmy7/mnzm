package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.map
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.service.checkpointAllProduction
import com.xianxia.sect.core.state.MutableGameState


/** 内存压力释放时世界地图宗门裁剪上限（无玩家宗门时） */
private const val MEMORY_SECT_LIMIT_PLAIN = 30


/** 内存压力释放时世界地图宗门裁剪上限（玩家宗门存在时少留 1 个） */
private const val MEMORY_SECT_LIMIT_WITH_PLAYER = 29


/** 内存压力释放时秘境探索队伍（玩家/AI）裁剪上限 */
private const val MEMORY_RELEASE_CAVE_TEAM_LIMIT = 15


/** 内存压力释放时战斗日志保留数（CRITICAL 级） */
private const val MEMORY_RELEASE_LOG_KEEP_CRITICAL = 10


/** 内存压力释放时战斗日志保留数（MODERATE 级）——与探索展示截断 [BATTLE_LOG_DISPLAY_LIMIT] 独立 */
private const val MEMORY_RELEASE_LOG_KEEP_MODERATE = 20


// ── Service delegates ───────────────────────────────────────────────

suspend fun GameEngine.redeemCode(code: String, usedCodes: List<String>, currentYear: Int,
    currentMonth: Int): RedeemResult = redeemCodeService.redeemCode(code, usedCodes, currentYear, currentMonth)
fun GameEngine.resetCultivationTimer() { cultivationService.resetHighFrequencyData() }
suspend fun GameEngine.checkpointAllProduction() { cultivationService.checkpointAllProduction() }
suspend fun GameEngine.checkpointAllDisciples() {
    engineContextDispatcher.withEngineContext {
        stateStore.update { cultivationService.checkpointAllDisciples(this) }
    }
}

// ── Private: Production slot fix ────────────────────────────────────



// ── Memory ──────────────────────────────────────────────────────────

fun GameEngine.getMemoryUsageInfo(): String {
    val sb = StringBuilder()
    sb.appendLine("=== 内存使用情况 ===")
    sb.appendLine("弟子数量: ${stateStore.discipleTables.count}"); sb
        .appendLine("装备栈数量: ${stateStore.equipmentStacks.value.size}")
    sb.appendLine("装备实例数量: ${stateStore.equipmentInstances.value.size}"); sb
        .appendLine("功法栈数量: ${stateStore.manualStacks.value.size}")
    sb.appendLine("功法实例数量: ${stateStore.manualInstances.value.size}"); sb
        .appendLine("丹药数量: ${stateStore.pills.value.size}")
    sb.appendLine("材料数量: ${stateStore.materials.value.size}"); sb.appendLine("灵草数量: ${stateStore.herbs.value.size}")
    sb.appendLine("种子数量: ${stateStore.seeds.value.size}")
    sb.appendLine("战斗日志: ${stateStore.battleLogs.value.size}/50")
    return sb.toString()
}

@Suppress("DEPRECATION")
fun GameEngine.releaseMemory(level: Int) {
    val normalizedLevel = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 1 -> 1
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE, 2 -> 2
        else -> { DomainLog.w("GameEngine", "未知的内存压力级别: $level，忽略"); return }
    }
    val logsToKeep = if (normalizedLevel == 1) MEMORY_RELEASE_LOG_KEEP_MODERATE else MEMORY_RELEASE_LOG_KEEP_CRITICAL
    val levelName = if (normalizedLevel == 1) "MODERATE" else "CRITICAL"
    gameEngineCore.launchInScope {
        var trimmedAny = false
        stateStore.update {
            if (battleLogs.size > logsToKeep) { battleLogs = battleLogs.take(logsToKeep); DomainLog.d("GameEngine",
                "内存释放($levelName): 战斗日志已清理，保留最近$logsToKeep 条") }
            if (normalizedLevel == 2) {
                trimmedAny = trimHeavyListsForMemoryRelease(levelName)
            }
        }
        // w3-13 通道关闭配套：裁剪写面（worldMapSects/caveExplorationTeams/aiCaveTeams
        // 均已关闭 §2.78/§2.79）发生后全量重建 native 基线——裁剪语义 = 双侧释放内存
        //（C++ 侧同步收敛到裁剪后状态）；未裁剪时零成本
        if (trimmedAny) rebaselineNativeMirror("内存裁剪")
    }

}

/** CRITICAL 级裁剪体（releaseMemory 提取）：worldMapSects/洞府探索队/AI 洞府队三列表。 */
private fun MutableGameState.trimHeavyListsForMemoryRelease(levelName: String): Boolean {
    var trimmed = false
    val worldSects = gameData.worldMapSects
    if (worldSects.size > 30) {
        val playerSect = worldSects.find { it.isPlayerSect }
        val sectLimit =
            if (playerSect != null) MEMORY_SECT_LIMIT_WITH_PLAYER else MEMORY_SECT_LIMIT_PLAIN
        val otherSects = worldSects.filter { !it.isPlayerSect }
            .sortedByDescending { s -> s.relation }.take(sectLimit)
        val trimmedSects = if (playerSect != null) listOf(playerSect) + otherSects else otherSects
        gameData = gameData.copy(worldMapSects = trimmedSects); trimmed = true; DomainLog.d("GameEngine",
            "内存释放($levelName): worldMapSects 裁剪至 ${trimmedSects.size} 个")
    }
    val caveTeams = gameData.caveExplorationTeams
    if (caveTeams.size > MEMORY_RELEASE_CAVE_TEAM_LIMIT) {
        val trimmedCave = caveTeams.take(MEMORY_RELEASE_CAVE_TEAM_LIMIT)
        gameData = gameData.copy(caveExplorationTeams = trimmedCave)
        trimmed = true
        DomainLog.d("GameEngine", "内存释放($levelName): caveExplorationTeams 裁剪至 ${trimmedCave.size} 个")
    }
    val aiCaveTeams = gameData.aiCaveTeams
    if (aiCaveTeams.size > MEMORY_RELEASE_CAVE_TEAM_LIMIT) {
        val trimmedAiCave = aiCaveTeams.take(MEMORY_RELEASE_CAVE_TEAM_LIMIT)
        gameData = gameData.copy(aiCaveTeams = trimmedAiCave)
        trimmed = true
        DomainLog.d("GameEngine", "内存释放($levelName): aiCaveTeams 裁剪至 ${trimmedAiCave.size} 个")
    }
    if (!trimmed) DomainLog.d("GameEngine", "内存释放($levelName): 无需裁剪其他列表")
    return trimmed
}

// ── 血炼原子操作 ────────────────────────────────────────────────────

/** 血炼启动结果 */
sealed interface BloodRefinementStartResult {
    data object Success : BloodRefinementStartResult
    data class InsufficientStones(
        val required: Long, val current: Long
    ) : BloodRefinementStartResult
    data class InsufficientMaterials(
        val materialName: String, val missing: Int
    ) : BloodRefinementStartResult
    data class Error(val message: String) : BloodRefinementStartResult
}

// ════════════════════════════════════════════════════════════
// 生命周期状态引擎线程入口（架构合规）
//
// SaveLoadViewModel 与 SaveLoad delegate 不得直接操作
// GameStateStore（resetBootPhase/setIdle/setPausedDirect/update），
// 那会违反 CLAUDE.md 4.4 与双线程模型（主线程直写绕过引擎串行化）。
// 本组入口统一走引擎线程，UI 层只允许读 StateFlow。
// ════════════════════════════════════════════════════════════

/** 重置生命周期状态（读档/重启路径：bootPhase → UNINITIALIZED, runState → IDLE）。 */
suspend fun GameEngine.resetLifecycleState() {
    engineContextDispatcher.withEngineContext {
        stateStore.resetBootPhase()
        stateStore.setIdle()
    }
}

/** 引擎线程设置暂停标志（SaveLoad 流程用，替代 UI 直调 setPausedDirect）。 */
suspend fun GameEngine.setPausedDirectOnEngine(paused: Boolean) {
    engineContextDispatcher.withEngineContext {
        stateStore.setPausedDirect(paused)
    }
}

/** 引擎线程批量设置保存/加载标志（替代 UI 层多次 stateStore.update）。 */
/**
 * 引擎线程批量设置保存/加载标志（suspend + withEngineContext）。
 *
 * 调用方 await 后标志立即生效——fire-and-forget 派发会使 finally
 * 异常兜底路径的标志复位无时序保证（下一次 load 的 setLoading(true)
 * 可能在复位之后执行，加载期间引擎误推进）。
 */
suspend fun GameEngine.setSaveLoadFlags(isSaving: Boolean, isLoading: Boolean) {
    engineContextDispatcher.withEngineContext {
        stateStore.update {
            this.isSaving = isSaving
            this.isLoading = isLoading
        }
    }
}

/**
 * 建筑占地迁移状态应用（引擎线程入口）。
 *
 * 计算侧（computeBuildingOverflowMigration，纯函数）在调用方完成，
 * 状态应用（灵石退款 + 建筑列表 + 槽位清理 + 弟子状态）必须经本入口
 * 在引擎线程执行（stateStore.update 不得在主线程直调）。
 */
suspend fun GameEngine.applyBuildingMigrationOnEngine(
    kept: List<com.xianxia.sect.core.model.GridBuildingData>,
    totalRefund: Long,
    freedDiscipleIds: Set<String>
) {
    engineContextDispatcher.withEngineContext {
        stateStore.update {
            if (totalRefund > 0) {
                spiritStoneWallet.add(
                    this, totalRefund,
                    com.xianxia.sect.core.model.SpiritStoneGrade.LOW,
                    com.xianxia.sect.core.wallet.SpiritStoneSource.Refund
                )
            }
            var gd = gameData.copy(placedBuildings = kept)
            // 清除已拆除建筑的关联槽位数据
            val keptIds = kept.map { it.instanceId }.toSet()
            val removedIds = gameData.placedBuildings.map { it.instanceId }.toSet() - keptIds
            if (removedIds.isNotEmpty()) {
                gd = gd.copy(
                    productionSlots = gd.productionSlots.filter { it.buildingInstanceId !in removedIds },
                    residenceSlots = gd.residenceSlots.filter { it.buildingInstanceId !in removedIds },
                    spiritMineSlots = gd.spiritMineSlots.filter { it.buildingInstanceId !in removedIds },
                    patrolSlots = gd.patrolSlots.filter { it.buildingInstanceId !in removedIds },
                    warehouseGarrisons = gd.warehouseGarrisons.filter { it.buildingInstanceId !in removedIds },
                    activeBloodRefinements = gd.activeBloodRefinements.filterKeys { it !in removedIds }
                )
            }
            gameData = gd

            for (didStr in freedDiscipleIds) {
                val id = didStr.toIntOrNull() ?: continue
                if (discipleTables.ids.contains(id) && discipleTables.isAlive[id] == 1) {
                    discipleTables.statuses[id] = com.xianxia.sect.core.model.DiscipleStatus.IDLE
                }
            }
        }
    }
}
