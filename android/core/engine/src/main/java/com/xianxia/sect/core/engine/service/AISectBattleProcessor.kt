package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton


/**
 * AI 宗门攻防结算处理器（D15 拆分后，2026-08-08）。
 *
 * 职责：AI 非焦点域热控分批修炼、宗门等级同步、AI-vs-AI 战斗编排；
 * 玩家防守战结算已拆至 [PlayerDefenseProcessor]，占领结算已拆至 [AISectOccupationResolver]。
 * 洞府探索域保留在 [CaveExplorationProcessor]。
 */
@Singleton
@GameService("AISectBattleProcessor")
class AISectBattleProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val thermalMonitor: ThermalMonitor,
    private val battleSystem: BattleSystem,
    private val playerDefenseProcessor: PlayerDefenseProcessor,
    private val occupationResolver: AISectOccupationResolver
) {
    // AI 非焦点域热控分批状态
    // 哨兵 -1 = 未初始化（0 不能作哨兵：首次对齐基准可为 0，见 computeAIBatch）
    private var aiNonFocusedLastSettleMonth: Int = -1
    private var aiNonFocusedBatchMonths: Int = 1

    companion object {
        private const val THERMAL_EMERGENCY_BATCH = 12
        private const val THERMAL_REDUCE_BATCH = 6
        // L2 AI 降频：1→3（季度批量）。settle 月 = 3/6/9/12，每个 1 月
        // monthsSince = 1 < 3 跳过 —— 年变叠加月不再触发 AI 修炼。
        // 年均修炼总量不变（processMonthlyCultivation 内部 repeat(batchMonths)）。
        private const val THERMAL_NORMAL_BATCH = 3

        /**
         * 从实际参战弟子构建防守战日志的敌人快照列表（纯函数，D3 迁移自 CaveExplorationProcessor）。
         * 供 BattleTickSystem 和测试使用。
         */
        internal fun buildDefenseBattleEnemies(
            survivingAttackers: List<Disciple>,
            deadAttackerIds: List<String>,
            sectDisciplePool: List<Disciple>,
            attackerSectName: String
        ): List<BattleLogEnemy> {
            val survivorIds = survivingAttackers.map { it.id }.toSet()
            val deadAttackerData = sectDisciplePool.filter {
                it.id in deadAttackerIds
            }
            val participants = survivingAttackers + deadAttackerData
            return participants.map { d ->
                val survived = d.id in survivorIds
                BattleLogEnemy(
                    id = d.id,
                    name = "${attackerSectName}弟子",
                    realm = d.realm,
                    realmName = d.realmName,
                    hp = if (survived) d.combat.currentHp else 0,
                    maxHp = d.maxHp,
                    isAlive = survived,
                    portraitRes = d.portraitRes
                )
            }
        }
    }
    /**
     * 当前批次月数（0 = 本月跳过 AI 修炼）。供测试断言热控相位。
     */
    internal fun currentAIBatchMonths(): Int = aiNonFocusedBatchMonths

    private fun computeAIBatch(currentAbsoluteMonth: Int) {
        if (aiNonFocusedLastSettleMonth < 0) {
            // L2 首次相位对齐：基准 = (当前月 - 1) 向下取 3 的倍数。
            // 基准 ≡ 0 (mod 3) ⇒ settle 月 = 基准 + 3k ≡ 0 (mod 3) = 3/6/9/12 ——
            // 1 月（mod 3 = 1）永不 settle，与首次调用月份无关。
            // 修复两个缺口：(a) 旧逻辑首次调用在 1 月时基准 = 1（mod 3 = 1），
            // settle 月 1/4/7/10 → 1 月成本 x3；(b) 2/5/8/11 月读档后基准 mod 3 ≠ 0，
            // 1 月成为 settle 月（对抗性审查 F4）。基准可为 0（abs ≤ 2 时），
            // 哨兵 -1 区分"未初始化"。
            aiNonFocusedLastSettleMonth =
                (currentAbsoluteMonth - 1) - ((currentAbsoluteMonth - 1) % 3)
            aiNonFocusedBatchMonths = 0
            return
        }
        val monthsSince = currentAbsoluteMonth - aiNonFocusedLastSettleMonth
        if (monthsSince <= 0) {
            // 时钟回退（读档到更早月份）/同月重复调用：跳过而非补修炼。
            // 旧逻辑 batchMonths=1 会在回退场景重复执行一个月修炼（对抗性审查 F1）。
            aiNonFocusedBatchMonths = 0
            return
        }
        val batchSize = when {
            thermalMonitor.shouldEmergencySave() -> THERMAL_EMERGENCY_BATCH
            thermalMonitor.shouldReduceWorkload() -> THERMAL_REDUCE_BATCH
            else -> THERMAL_NORMAL_BATCH
        }
        aiNonFocusedBatchMonths = if (monthsSince >= batchSize) {
            aiNonFocusedLastSettleMonth = currentAbsoluteMonth
            monthsSince
        } else {
            0
        }
    }

    fun processAISectOperations(year: Int, month: Int) {
        stateStore.update { processAISectOperations(year, month, this) }
        processAIVsAIBattles()
        playerDefenseProcessor.processPlayerDefenseBattles()
    }

    @Suppress("CyclomaticComplexMethod", "MaxLineLength") // 宗门等级同步 when 链（搬移自原文件）
    fun processAISectOperations(year: Int, month: Int, state: MutableGameState) {
        val data = state.gameData
        val aiDisciples = data.aiSectDisciples

        val currentAbsMonth = LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        computeAIBatch(currentAbsMonth)

        val cleanedSectDetails = data.sectDetails.mapValues { (sectId, detail) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect != null && !sect.isPlayerSect && detail.warehouse.items.isNotEmpty()) {
                detail.copy(warehouse = SectWarehouse())
            } else {
                detail
            }
        }

        // AI 弟子修炼（热控分批：跳过时保留原数据；传宗门等级供突破刷新装备/功法数量）
        val updatedAiDisciples = if (aiNonFocusedBatchMonths > 0) {
            aiDisciples.mapValues { (sectId, disciples) ->
                val sect = data.worldMapSects.find { it.id == sectId }
                if (sect == null || sect.isPlayerSect) return@mapValues disciples
                AISectDiscipleManager.processMonthlyCultivation(
                    disciples, aiNonFocusedBatchMonths, sect.level
                )
            }
        } else {
            aiDisciples
        }

        // 同步 AI 宗门等级 — 月度修炼弟子只会变强，仅用 any{} 短路检查升级（只升不降）
        // 玩家宗门等级由玩家手动升级（通过 SectLevelDetailDialog），此处跳过
        var finalAiDisciples = updatedAiDisciples
        val syncedWorldSects = data.worldMapSects.map { sect ->
            if (sect.isPlayerSect) {
                sect  // 玩家宗门手动升级，月度 tick 不再自动升级
            } else if (sect.level >= SectLevel.TOP) {
                sect  // 已是顶级 → 跳过
            } else {
                val disciples = updatedAiDisciples[sect.id] ?: return@map sect
                val newLevel = when (sect.level) {
                    SectLevel.SMALL -> if (disciples.any { it.isAlive && it.realm <= 5 }) SectLevel.MEDIUM else sect.level
                    SectLevel.MEDIUM -> if (disciples.any { it.isAlive && it.realm <= 4 }) SectLevel.LARGE else sect.level
                    SectLevel.LARGE -> if (disciples.any { it.isAlive && it.realm <= 2 }) SectLevel.TOP else sect.level
                    else -> sect.level
                }
                if (sect.level != newLevel) {
                    // 等级升级：全宗门补装备/功法数量至新等级标准（只补缺，不动已有；
                    // 同时修正"突破当月按旧等级计数"的数量滞后）
                    val upgraded = disciples.map { AISectDiscipleManager.ensureDiscipleGear(it, newLevel) }
                    finalAiDisciples = finalAiDisciples + (sect.id to upgraded)
                    sect.copy(level = newLevel, levelName = SectLevel.levelName(newLevel))
                } else {
                    sect
                }
            }
        }

        state.gameData = state.gameData.copy(
            sectDetails = cleanedSectDetails,
            aiSectDisciples = state.gameData.aiSectDisciples.mapValues { (sId, current) ->
                val calculated = finalAiDisciples[sId] ?: return@mapValues current
                val currentIds = current.map { it.id }.toSet()
                calculated.filter { it.id in currentIds }
            },
            worldMapSects = syncedWorldSects
        )
    }

    /**
     * AI-vs-AI 战斗月度结算（含玩家占领宗门防御）。
     * 同步执行，不通过 scope.launch 异步写入。
     */
    private fun processAIVsAIBattles() {
        val data = stateStore.gameData.value
        val playerSectId = data.worldMapSects
            .find { it.isPlayerSect }?.id

        // 驻军弟子由 BattleTickSystem 每 tick 实时结算，此处无需重复

        // 构建玩家占领宗门防御信息（P-2 拆分：防御构建提取）
        val allDisciples = stateStore.discipleTables.assembleAll()
        val equipmentMap = stateStore.equipmentInstancesSnapshot
            .associateBy { it.id }
        val manualMap = stateStore.manualInstancesSnapshot
            .associateBy { it.id }
        val profMap = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val playerDefenders = buildPlayerDefenseInfo(
            data, allDisciples, equipmentMap, manualMap, profMap, playerSectId
        )

        val results = AISectAttackManager.decideAttacks(data, playerDefenders)
        if (results.isEmpty()) return

        // P-2 拆分：单次攻击结果应用提取（含占领/关系变更）
        for (result in results) {
            occupationResolver.applyAIAttackResult(result, data.gameYear)
        }
    }

    /** P-2：构建玩家占领宗门的防御信息（驻军弟子 + 战斗参战者）。 */
    @Suppress("UnusedParameter") // playerSectId 保留签名兼容（搬移自原文件）
    private fun buildPlayerDefenseInfo(
        data: com.xianxia.sect.core.model.GameData,
        allDisciples: List<com.xianxia.sect.core.model.Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        profMap: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        playerSectId: String?
    ): Map<String, AISectAttackManager.PlayerOccupiedDefenseInfo> = if (playerSectId != null) {
        data.worldMapSects
            .filter { it.isPlayerOccupied && it.occupierSectId == playerSectId }
            .associate { sect ->
                val garrisoned = sect.garrisonSlots
                    .filter { it.discipleId.isNotEmpty() }
                    .mapNotNull { slot ->
                        allDisciples.find { d ->
                            d.id == slot.discipleId && d.isAlive
                        }
                    }
                val combatants = garrisoned.map { d ->
                    battleSystem.convertDiscipleToCombatant(
                        d, equipmentMap, manualMap, profMap,
                        CombatantSide.DEFENDER,
                        bloodRefinementPct = data.bloodRefinementPctTotals[d.id]
                    )
                }
                sect.id to AISectAttackManager.PlayerOccupiedDefenseInfo(
                    disciples = garrisoned,
                    combatants = combatants
                )
            }
    } else emptyMap()
}
