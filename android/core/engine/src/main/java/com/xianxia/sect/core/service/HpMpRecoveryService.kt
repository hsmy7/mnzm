package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HP/MP 恢复乘区（Recovery Zone）。
 *
 * 公式：恢复量 = maxValue × baseRate × (1 + buildingZone + pillZone + realmZone) × multiplier
 * 各乘区内加算，乘区间乘算（当前只有 baseRate × multiplier 活跃，其余乘区预留）。
 * baseRate 为每旬恢复率（[GameConfig.Cultivation.PHASE_HP_MP_RECOVERY_RATE]）。
 */
data class RecoveryZones(
    val baseRate: Double = GameConfig.Cultivation.PHASE_HP_MP_RECOVERY_RATE,
    val buildingZone: Double = 0.0,   // 建筑恢复乘区（如丹药房，预留）
    val pillZone: Double = 0.0,       // 丹药恢复乘区（预留）
    val realmZone: Double = 0.0,      // 境界恢复乘区（预留）
) {
    /**
     * 计算恢复量。
     * @param maxValue 最大值（maxHp 或 maxMp）
     * @param multiplier 结算旬数（恢复率为每旬基准，跨旬结算按旬数倍增）
     * @return 恢复量，至少 1
     */
    fun calculateRecovery(maxValue: Int, multiplier: Double): Int {
        val totalZone = 1.0 + buildingZone + pillZone + realmZone
        return (maxValue.toDouble() * baseRate * totalZone * multiplier)
            .toInt().coerceAtLeast(1)
    }
}

/**
 * HP/MP 恢复服务。
 *
 * 职责：
 * - 每旬/月度 HP/MP 恢复
 * - 战斗前恢复
 * - 满状态判定
 * - 月度丹药效果衰减
 */
@Singleton
@GameService("HpMpRecoveryService")
class HpMpRecoveryService @Inject constructor() {

    /**
     * 判断弟子当前 HP 与 MP 是否均已达到上限（含血炼口径，2026-08-06 途中发现修复）。
     *
     * 血炼进战斗后弟子常态上限为含血炼 finalStats maxHp/maxMp；若按基础上限
     * （disciple.maxHp）判满，血炼弟子会少恢复差额血即被判定"满血"（突破门槛/
     * 战前恢复提前跳过）。
     *
     * 当 currentHp/currentMp 为负数时视为满值（用于标记特殊状态）。
     *
     * @param disciple 待判断的弟子
     * @param state 可变游戏状态（读装备/功法/熟练度/血炼记录）
     * @return true 表示 HP 与 MP 均已满；false 表示未满
     */
    fun isDiscipleFullHpMp(disciple: Disciple, state: MutableGameState): Boolean {
        val (maxHp, maxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(state, disciple)
        val hp = if (disciple.combat.currentHp < 0) maxHp else disciple.combat.currentHp
        val mp = if (disciple.combat.currentMp < 0) maxMp else disciple.combat.currentMp
        return hp >= maxHp && mp >= maxMp
    }

    /**
     * 判断指定 ID 弟子当前 HP 与 MP 是否均已达到上限（基于 Tables 查询，含血炼口径）。
     *
     * 当 currentHps/currentMps 为负数时视为满值（用于标记特殊状态）。
     *
     * @param id 弟子 ID
     * @param tables 弟子数据表，提供当前与基础 HP/MP
     * @param state 可变游戏状态（读装备/功法/熟练度/血炼记录）
     * @return true 表示 HP 与 MP 均已满；false 表示未满
     */
    fun isDiscipleFullHpMp(id: Int, tables: DiscipleTables, state: MutableGameState): Boolean {
        val (maxHp, maxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(state, tables.assemble(id))
        val curHp = tables.currentHps[id]
        val curMp = tables.currentMps[id]
        val hp = if (curHp < 0) maxHp else curHp
        val mp = if (curMp < 0) maxMp else curMp
        return hp >= maxHp && mp >= maxMp
    }

    /**
     * 单弟子旬级 HP/MP 恢复。
     *
     * 恢复量 = maxHp/maxMp × PHASE_HP_MP_RECOVERY_RATE × phasesToSettle，
     * 至少恢复 1 点，且不超过上限。currentHp/currentMp 为负数的弟子视为特殊状态跳过恢复
     * （存活过滤由调用方负责，本方法不检查 isAlive）。
     *
     * @param state 可变游戏状态
     * @param id 弟子 ID
     * @param phasesToSettle 需结算的旬数（默认 1）
     * @param zones 恢复乘区（可选，默认为无额外加成）
     * @param equipmentMap 装备实例映射（每旬热点循环共享构建，null 时内部构建）
     * @param manualMap 功法实例映射（每旬热点循环共享构建，null 时内部构建）
     */
    fun recoverHpMpSingle(
        state: MutableGameState,
        id: Int,
        phasesToSettle: Int = 1,
        zones: RecoveryZones = RecoveryZones(),
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null
    ) {
        if (phasesToSettle <= 0) return
        val tables = state.discipleTables
        val curHp = tables.currentHps[id]
        val curMp = tables.currentMps[id]
        if (curHp < 0 && curMp < 0) return

        val disciple = tables.assemble(id)
        val eqMap = equipmentMap ?: state.equipmentInstances.associateBy { it.id }
        val mMap = manualMap ?: state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val proficiencyMap = allProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
        val finalStats = DiscipleStatCalculator.getFinalStats(
            disciple, eqMap, mMap, proficiencyMap,
            state.gameData.bloodRefinementPctTotals[disciple.id]
        )
        val maxHp = finalStats.maxHp
        val maxMp = finalStats.maxMp

        val multiplier = phasesToSettle.toDouble()
        val hpRecovery = zones.calculateRecovery(maxHp, multiplier)
        val mpRecovery = zones.calculateRecovery(maxMp, multiplier)
        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp) tables.currentHps[id] = newHp
        if (newMp != curMp) tables.currentMps[id] = newMp
    }

    /**
     * 单弟子旬级 HP/MP 恢复（列直读版，2026-08-01 每旬热点专用）。
     *
     * 与 [recoverHpMpSingle] 数学等价（共用 [DiscipleStatCalculator.computeBaseHpMp]
     * 公式），但不 assemble 弟子对象——只直读 17 列（境界/层/方差/天赋/词条/四槽装备/
     * 功法/丹药），省去 ~90 列读取 + 嵌套对象分配。
     * 满血弟子提前退出（无需计算 maxHp 也能判断：curHp 达上限与否——注意
     * maxHp 由列版廉价算出后比较）。
     *
     * @param state 可变游戏状态
     * @param id 弟子 ID
     * @param phasesToSettle 需结算的旬数（默认 1）
     * @param zones 恢复乘区（可选，默认为无额外加成）
     * @param equipmentMap 装备实例映射（每旬热点循环共享构建，null 时内部构建）
     * @param manualMap 功法实例映射（每旬热点循环共享构建，null 时内部构建）
     * @param manualProficiencies 功法熟练度映射（每旬热点循环共享构建，null 时内部构建）
     * @return 是否发生写入
     */
    fun recoverHpMpSingleColumn(
        state: MutableGameState,
        id: Int,
        phasesToSettle: Int = 1,
        zones: RecoveryZones = RecoveryZones(),
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null,
        manualProficiencies: Map<String, List<ManualProficiencyData>>? = null
    ): Boolean {
        if (phasesToSettle <= 0) return false
        val tables = state.discipleTables
        val curHp = tables.currentHps[id]
        val curMp = tables.currentMps[id]
        if (curHp < 0 && curMp < 0) return false

        val input = DiscipleStatCalculator.HpMpColumnInput(
            realm = tables.realms[id],
            realmLayer = tables.realmLayers[id],
            hpVariance = tables.hpVariances[id],
            mpVariance = tables.mpVariances[id],
            talentIds = tables.talentIds.getOrNull(id) ?: emptyList(),
            affixIds = tables.affixIds.getOrNull(id) ?: emptyList(),
            weaponId = tables.weaponIds.getOrNull(id),
            armorId = tables.armorIds.getOrNull(id),
            bootsId = tables.bootsIds.getOrNull(id),
            accessoryId = tables.accessoryIds.getOrNull(id),
            manualIds = tables.manualIds.getOrNull(id) ?: emptyList(),
            pillEffectDuration = tables.pillEffectDurations[id],
            pillHpBonus = tables.pillHpBonuses[id],
            pillMpBonus = tables.pillMpBonuses[id],
            bloodRefinementPct = state.gameData.bloodRefinementPctTotals[id.toString()]
        )
        val eqMap = equipmentMap ?: state.equipmentInstances.associateBy { it.id }
        val mMap = manualMap ?: state.manualInstances.associateBy { it.id }
        val profList = manualProficiencies ?: state.gameData.manualProficiencies
        val profMap = profList[id.toString()]?.associateBy { it.manualId } ?: emptyMap()
        val (maxHp, maxMp) = DiscipleStatCalculator.getMaxHpMpColumn(input, eqMap, mMap, profMap)

        // 满血提前退出（负值视为满，语义与对象版一致）
        val effHp = if (curHp < 0) maxHp else curHp
        val effMp = if (curMp < 0) maxMp else curMp
        if (effHp >= maxHp && effMp >= maxMp) return false

        val multiplier = phasesToSettle.toDouble()
        val hpRecovery = zones.calculateRecovery(maxHp, multiplier)
        val mpRecovery = zones.calculateRecovery(maxMp, multiplier)
        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        var wrote = false
        if (newHp != curHp) { tables.currentHps[id] = newHp; wrote = true }
        if (newMp != curMp) { tables.currentMps[id] = newMp; wrote = true }
        return wrote
    }

    /**
     * 为参与战斗的指定弟子恢复 HP 与 MP（战前恢复，确保血量最新状态）。
     *
     * 仅处理 discipleIds 列表中存活的弟子，已满 HP/MP 的弟子跳过。
     * 恢复量 = maxHp/maxMp × PHASE_HP_MP_RECOVERY_RATE（1 旬量），至少恢复 1 点，且不超过上限。
     *
     * @param state 可变游戏状态
     * @param discipleIds 参与战斗的弟子 ID 字符串列表
     * @param zones 恢复乘区（可选，默认为无额外加成）
     */
    fun recoverHpMpForBattleParticipants(
        state: MutableGameState,
        discipleIds: List<String>,
        zones: RecoveryZones = RecoveryZones()
    ) {
        val tables = state.discipleTables
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val multiplier = 1.0
        val idSet = discipleIds.toSet()

        for (id in tables.ids) {
            val strId = id.toString()
            if (strId !in idSet || tables.isAlive[id] != 1) continue

            val disciple = tables.assemble(id)
            var curHp = tables.currentHps[id]
            var curMp = tables.currentMps[id]

            val discipleProficiencies = allProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(
                disciple, equipmentMap, manualMap, discipleProficiencies,
                state.gameData.bloodRefinementPctTotals[disciple.id]
            )
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp
            // 满血判定用含血炼口径（P2 对抗性审查修复）——原 disciple.maxHp（基础）会把
            // 血炼差额血量误判为已满，战前恢复跳过导致少血入场
            if (curHp >= maxHp && curMp >= maxMp) continue
            val hpRecovery = zones.calculateRecovery(maxHp, multiplier)
            val mpRecovery = zones.calculateRecovery(maxMp, multiplier)
            curHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
            curMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)
            if (curHp != tables.currentHps[id]) tables.currentHps[id] = curHp
            if (curMp != tables.currentMps[id]) tables.currentMps[id] = curMp
        }
    }

    /**
     * 月度持续效果衰减（月结制专用）。
     * 修炼速度加成和丹药效果每旬衰减 10，每月衰减 30。
     * @param tables 弟子数据表
     * @param id 弟子 ID
     * @param focusedPhaseCount 本月焦点域已处理的旬数，用于扣除已应用的衰减
     */
    fun applyMonthlyDurationDecay(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0) {
        // 扣除已在焦点域旬结算中应用的部分，避免双计
        // 每月 3 旬，duration 以旬为单位
        val monthlyDecay = (3 - focusedPhaseCount).coerceAtLeast(0)
        if (monthlyDecay <= 0) return

        // 修炼速度加成衰减
        val speedDuration = tables.cultivationSpeedDurations[id]
        if (speedDuration > 0) {
            val newDuration = speedDuration - monthlyDecay
            if (newDuration <= 0) {
                tables.cultivationSpeedBonuses[id] = 0.0
                tables.cultivationSpeedDurations[id] = 0
            } else {
                tables.cultivationSpeedDurations[id] = newDuration
            }
        }

        // 丹药效果衰减
        val pillDuration = tables.pillEffectDurations[id]
        if (pillDuration > 0) {
            val newDuration = pillDuration - monthlyDecay
            if (newDuration <= 0) {
                tables.pillHpBonuses[id] = 0
                tables.pillMpBonuses[id] = 0
                tables.pillPhysicalAttackBonuses[id] = 0
                tables.pillMagicAttackBonuses[id] = 0
                tables.pillPhysicalDefenseBonuses[id] = 0
                tables.pillMagicDefenseBonuses[id] = 0
                tables.pillSpeedBonuses[id] = 0
                tables.pillCritRateBonuses[id] = 0.0
                tables.pillCritEffectBonuses[id] = 0.0
                tables.pillCultivationSpeedBonuses[id] = 0.0
                tables.pillSkillExpSpeedBonuses[id] = 0.0
                tables.pillNurtureSpeedBonuses[id] = 0.0
                tables.activePillCategories[id] = ""
                tables.activePillTypes[id] = emptySet()
                tables.pillEffectDurations[id] = 0
            } else {
                tables.pillEffectDurations[id] = newDuration
            }
        }
    }

    companion object {
        private const val TAG = "HpMpRecoveryService"
    }
}
