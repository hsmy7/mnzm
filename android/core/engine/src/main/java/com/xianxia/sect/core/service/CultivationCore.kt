package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单旬 tick 时间与倍率参数。
 *
 * @property year 当前游戏年
 * @property month 当前游戏月
 * @property phase 当前游戏旬
 * @property multiplier HP/MP 恢复倍率
 * @property decay 持续效果衰减旬数
 */
data class TickTimeContext(
    val year: Int,
    val month: Int,
    val phase: Int,
    val multiplier: Double,
    val decay: Int
)

/**
 * 单旬 tick 装备领域参数。
 *
 * @property instanceMap 装备实例映射（id → EquipmentInstance）
 * @property stacks 装备堆叠列表
 * @property maxStack 装备堆叠数量上限
 */
data class TickEquipContext(
    val instanceMap: Map<String, EquipmentInstance>,
    val stacks: List<EquipmentStack>,
    val maxStack: Int
)

/**
 * 单旬 tick 功法领域参数。
 *
 * @property instanceMap 功法实例映射（id → ManualInstance）
 * @property proficienciesMap 弟子功法熟练度映射（discipleId → proficiencyList）
 * @property stacks 功法堆叠列表
 * @property maxStack 功法堆叠数量上限
 */
data class TickManualContext(
    val instanceMap: Map<String, ManualInstance>,
    val proficienciesMap: Map<String, List<ManualProficiencyData>>,
    val stacks: List<ManualStack>,
    val maxStack: Int
)

/**
 * 单旬 tick 跨弟子共享状态。
 *
 * 在批量 tick 循环中仅读取一次，所有弟子共享同一份引用，
 * 避免每个弟子独立获取 [HighFrequencyData] 等高开销对象。
 *
 * @property focusedDiscipleId 当前焦点弟子 ID（可空）
 * @property cachedCultivationRates 缓存的修炼速率映射
 * @property highFrequencyData 高频更新数据
 * @property autoEquipDirty 自动装备脏标记集合
 * @property autoLearnDirty 自动学习脏标记集合
 */
data class TickSharedContext(
    val focusedDiscipleId: String?,
    val cachedCultivationRates: Map<String, Double>,
    val highFrequencyData: HighFrequencyData,
    val autoEquipDirty: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>,
    val autoLearnDirty: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>
)

/**
 * [CultivationCore.processDiscipleTick] 的参数集合。
 *
 * 将原 19 个独立参数按领域分组为嵌套上下文对象，
 * 满足编码规范 §3.4（类构造参数 ≤7）。
 *
 * @property disciple 待处理的弟子
 * @property time 游戏时间与倍率参数
 * @property equip 装备领域参数
 * @property manual 功法领域参数
 * @property shared 跨弟子共享状态
 * @property acc 单旬累积器，用于收集装备/功法变更
 */
data class DiscipleTickParams(
    val disciple: Disciple,
    val time: TickTimeContext,
    val equip: TickEquipContext,
    val manual: TickManualContext,
    val shared: TickSharedContext,
    val acc: PhaseTickAccumulator
)

@Singleton
@GameService("CultivationCore")
class CultivationCore @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val thermalMonitor: ThermalMonitor,
    private val gameClock: GameTimeClock,
    private val scopeProvider: CoroutineScopeProvider,
    private val pillManager: DisciplePillManager,
    private val equipmentManager: DiscipleEquipmentManager,
    private val manualManager: DiscipleManualManager
) {

    val phaseMultiplier: Int get() = 10

    /**
     * 计算弟子每旬修炼速度（1x 速度基准）。
     * calculateCultivationSpeed 已直接返回每旬值，无需再换算。
     */
    fun calculateDiscipleCultivationPerPhase(disciple: Disciple, data: GameData, tables: DiscipleTables): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)
        val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(disciple, data, tables, "outer")
        val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(disciple, data, tables, "inner")

        var cultivationSubsidyBonus = 0.0
        if (data.sectPolicies.cultivationSubsidy && disciple.realm > 5) {
            cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
        }

        val manualInstanceMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()

        val parent1 = disciple.social.parentId1?.let { pid ->
            val pidInt = pid.toIntOrNull() ?: return@let null
            if (tables.names.contains(pidInt)) tables.assemble(pidInt) else null
        }
        val parent2 = disciple.social.parentId2?.let { pid ->
            val pidInt = pid.toIntOrNull() ?: return@let null
            if (tables.names.contains(pidInt)) tables.assemble(pidInt) else null
        }
        val parentCultivationBonus = DiscipleStatCalculator.calculateParentCultivationBonus(parent1, parent2)

        // 师徒加成：徒弟有师父且师父存活时，按大境界差提供修炼速度加成
        val masterDiscipleBonus = disciple.social.masterId?.let { mid ->
            val midInt = mid.toIntOrNull() ?: return@let 0.0
            if (tables.names.contains(midInt) && tables.isAlive[midInt] == 1) {
                val masterRealm = tables.realms[midInt]
                DiscipleStatCalculator.getMasterDiscipleCultivationBonus(disciple.realm, masterRealm)
            } else 0.0
        } ?: 0.0

        val griefPenalty = if (DiscipleStatCalculator.isGrieving(disciple.social.griefEndYear, data.gameYear)) {
            DiscipleStatCalculator.GRIEF_CULTIVATION_SPEED_PENALTY
        } else {
            0.0
        }

        val perSecond = DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            parentCultivationBonus = parentCultivationBonus,
            griefCultivationSpeedPenalty = griefPenalty,
            masterDiscipleBonus = masterDiscipleBonus
        ).coerceAtLeast(1.0)
        // calculateCultivationSpeed 已直接返回每旬值，无需再换算
        return perSecond
    }

    private fun calculatePreachingBonuses(
        disciple: Disciple,
        data: GameData,
        tables: DiscipleTables,
        targetDiscipleType: String
    ): Pair<Double, Double> {
        val elderSlots = data.elderSlots
        val preachingElder = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingElder.let { id -> if (id.isNotEmpty()) id.toIntOrNull()?.let { tables.assemble(it) } else null }
            "inner" -> elderSlots.qingyunPreachingElder.let { id -> if (id.isNotEmpty()) id.toIntOrNull()?.let { tables.assemble(it) } else null }
            else -> null
        }
        val preachingMasters = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingMasters.mapNotNull { slot -> slot.discipleId?.toIntOrNull()?.let { tables.assemble(it) } }
            "inner" -> elderSlots.qingyunPreachingMasters.mapNotNull { slot -> slot.discipleId?.toIntOrNull()?.let { tables.assemble(it) } }
            else -> emptyList()
        }
        return DiscipleStatCalculator.calculatePreachingBonus(
            disciple = disciple,
            targetDiscipleType = targetDiscipleType,
            preachingElder = preachingElder,
            preachingMasters = preachingMasters
        )
    }

    /**
     * 计算弟子住所建筑对修炼速度的加成系数。
     *
     * 根据弟子所居住建筑的 displayName 返回对应加成系数：
     * - 1.40：中级单人住所（中级品质，单人专属，加成最高）
     * - 1.20：单人住所（普通品质，单人专属）
     * - 1.10：多人住所（普通品质，多人共享，加成最低）
     * - 1.0：无建筑或未识别建筑（无加成）
     *
     * 上述数值为建筑品质/类型对应的修炼速度乘数，品质越高、专属度越强则系数越大。
     *
     * @param disciple 待计算的弟子
     * @param data 当前游戏数据，用于查询住所槽位与已放置建筑
     * @return 修炼速度加成系数，无建筑时返回 1.0
     */
    private fun calculateBuildingCultivationBonus(disciple: Disciple, data: GameData): Double {
        val slot = data.residenceSlots.firstOrNull { it.discipleId == disciple.id } ?: return 1.0
        val building = data.placedBuildings.firstOrNull { it.instanceId == slot.buildingInstanceId } ?: return 1.0
        return when (building.displayName) {
            "中级单人住所" -> 1.40
            "单人住所" -> 1.20
            "多人住所" -> 1.10
            else -> 1.0
        }
    }

    /**
     * 判断弟子当前 HP 与 MP 是否均已达到上限。
     *
     * 当 currentHp/currentMp 为负数时视为满值（用于标记特殊状态）。
     *
     * @param disciple 待判断的弟子
     * @return true 表示 HP 与 MP 均已满；false 表示未满
     */
    fun isDiscipleFullHpMp(disciple: Disciple): Boolean {
        val hp = if (disciple.combat.currentHp < 0) disciple.maxHp else disciple.combat.currentHp
        val mp = if (disciple.combat.currentMp < 0) disciple.maxMp else disciple.combat.currentMp
        return hp >= disciple.maxHp && mp >= disciple.maxMp
    }

    /**
     * 判断指定 ID 弟子当前 HP 与 MP 是否均已达到上限（基于 Tables 查询）。
     *
     * 当 currentHps/currentMps 为负数时视为满值（用于标记特殊状态）。
     *
     * @param id 弟子 ID
     * @param tables 弟子数据表，提供当前与基础 HP/MP
     * @return true 表示 HP 与 MP 均已满；false 表示未满
     */
    fun isDiscipleFullHpMp(id: Int, tables: DiscipleTables): Boolean {
        val curHp = tables.currentHps[id]
        val curMp = tables.currentMps[id]
        val hp = if (curHp < 0) tables.baseHps[id] else curHp
        val mp = if (curMp < 0) tables.baseMps[id] else curMp
        return hp >= tables.baseHps[id] && mp >= tables.baseMps[id]
    }

    /**
     * 根据境界等级返回对应的寿命增益。
     *
     * 境界越低（凡人/练气）寿命增益越大，境界越高（渡劫/飞升）增益越小：
     * realm 0 -> 10000，realm 8 -> 50；未知境界返回 0。
     *
     * @param realm 境界等级（0-8）
     * @return 该境界对应的寿命增益值；未知境界返回 0
     */
    fun getLifespanGainForRealm(realm: Int): Int {
        return when (realm) {
            8 -> 50
            7 -> 100
            6 -> 200
            5 -> 400
            4 -> 800
            3 -> 1500
            2 -> 3000
            1 -> 5000
            0 -> 10000
            else -> 0
        }
    }

    /**
     * 为所有存活弟子恢复 HP 与 MP。
     *
     * 恢复量 = maxHp/maxMp × DAILY_HP_MP_RECOVERY_RATE × phaseMultiplier（每旬 ×10），
     * 至少恢复 1 点，且不超过上限。currentHp/currentMp 均为负数的弟子被视为特殊状态跳过恢复。
     *
     * @param state 可变游戏状态，包含弟子数据表与装备/功法实例
     */
    fun recoverHpMpForAllDisciples(state: MutableGameState) {
        val tables = state.discipleTables
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()

        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            val curHp = tables.currentHps[id]
            val curMp = tables.currentMps[id]
            if (curHp < 0 && curMp < 0) continue

            val disciple = tables.assemble(id)
            val proficiencyMap = allProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(disciple, equipmentMap, manualMap, proficiencyMap)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp

            val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
            val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

            if (newHp != curHp) tables.currentHps[id] = newHp
            if (newMp != curMp) tables.currentMps[id] = newMp
        }
    }

    /**
     * 月度 HP/MP 恢复（月结制专用）。
     * 每旬 multiplier=10，每月 3 旬 → 月度 multiplier=30。
     * @param focusedPhaseCount 本月焦点域已处理的旬数，用于扣除已应用的恢复
     */
    fun recoverMonthlyHpMp(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0) {
        val curHp = tables.currentHps[id]
        val curMp = tables.currentMps[id]
        if (curHp < 0 && curMp < 0) return

        // 扣除已在焦点域旬结算中应用的部分，避免双计
        val monthlyMultiplier = (30.0 - focusedPhaseCount * 10).coerceAtLeast(0.0)
        if (monthlyMultiplier <= 0.0) return

        val maxHp = tables.baseHps[id]
        val maxMp = tables.baseMps[id]

        val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * monthlyMultiplier)
            .toInt().coerceAtLeast(1)
        val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * monthlyMultiplier)
            .toInt().coerceAtLeast(1)
        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp) tables.currentHps[id] = newHp
        if (newMp != curMp) tables.currentMps[id] = newMp
    }

    /**
     * 月度持续效果衰减（月结制专用）。
     * 修炼速度加成和丹药效果每旬衰减 10，每月衰减 30。
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

    /**
     * 为参与战斗的指定弟子恢复 HP 与 MP。
     *
     * 仅处理 discipleIds 列表中存活的弟子，已满 HP/MP 的弟子跳过。
     * 恢复量 = maxHp/maxMp × DAILY_HP_MP_RECOVERY_RATE × phaseMultiplier（每旬 ×10），
     * 至少恢复 1 点，且不超过上限。
     *
     * @param state 可变游戏状态
     * @param discipleIds 参与战斗的弟子 ID 字符串列表
     */
    fun recoverHpMpForBattleParticipants(state: MutableGameState, discipleIds: List<String>) {
        val tables = state.discipleTables
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()
        val idSet = discipleIds.toSet()

        for (id in tables.ids) {
            val strId = id.toString()
            if (strId !in idSet || tables.isAlive[id] != 1) continue

            val disciple = tables.assemble(id)
            var curHp = tables.currentHps[id]
            var curMp = tables.currentMps[id]
            if (curHp >= disciple.maxHp && curMp >= disciple.maxMp) continue

            val discipleProficiencies = allProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(disciple, equipmentMap, manualMap, discipleProficiencies)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp
            curHp = if (curHp < 0) curHp else (curHp + (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxHp)
            curMp = if (curMp < 0) curMp else (curMp + (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxMp)
            if (curHp != tables.currentHps[id]) tables.currentHps[id] = curHp
            if (curMp != tables.currentMps[id]) tables.currentMps[id] = curMp
        }
    }

    /**
     * 处理单个弟子的单旬 tick：持续效果衰减、HP/MP 恢复、自动用丹、自动装备、自动学功法。
     *
     * 步骤：
     * 1. 衰减修炼速度加成与丹药效果持续时间（按 [params.time.decay] 旬数）
     * 2. 恢复 HP/MP（按 [params.time.multiplier] 倍率）
     * 3. 自动使用丹药（pillManager）
     * 4. 自动装备背包中的装备（equipmentManager）
     * 5. 自动学习背包中的功法（manualManager）
     *
     * 装备/功法实例与堆叠的增删改通过 [params.acc] 累积，由 applyAccumulator 统一应用。
     *
     * @param params 单旬 tick 所需的全部参数，详见 [DiscipleTickParams]
     * @return 处理完成后的弟子对象
     */
    fun processDiscipleTick(params: DiscipleTickParams): Disciple {
        var d = params.disciple

        if (d.cultivationSpeedDuration > 0) {
            val newDuration = d.cultivationSpeedDuration - params.time.decay
            if (newDuration <= 0) {
                d = d.copy(cultivationSpeedBonus = 0.0, cultivationSpeedDuration = 0)
            } else {
                d = d.copy(cultivationSpeedDuration = newDuration)
            }
        }

        if (d.pillEffects.pillEffectDuration > 0) {
            val newDuration = d.pillEffects.pillEffectDuration - params.time.decay
            if (newDuration <= 0) {
                d = d.copy(pillEffects = PillEffects())
            } else {
                d = d.copy(pillEffects = d.pillEffects.copy(
                    pillEffectDuration = newDuration
                ))
            }
        }

        val discipleProficiencies = params.manual.proficienciesMap
            .getOrDefault(d.id, emptyList())
            .associateBy { it.manualId }
        val finalStats = DiscipleStatCalculator.getFinalStats(
            d, params.equip.instanceMap, params.manual.instanceMap, discipleProficiencies
        )
        val maxHp = finalStats.maxHp
        val maxMp = finalStats.maxMp
        val curHp = d.combat.currentHp
        val curMp = d.combat.currentMp

        val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * params.time.multiplier)
            .toInt().coerceAtLeast(1)
        val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * params.time.multiplier)
            .toInt().coerceAtLeast(1)

        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp || newMp != curMp) {
            d = d.copy(combat = d.combat.copy(currentHp = newHp, currentMp = newMp))
        }

        val pillResult = pillManager.processAutoUsePills(
            disciple = d,
            gameYear = params.time.year,
            gameMonth = params.time.month,
            gamePhase = params.time.phase
        )
        if (pillResult.disciple != d) {
            d = pillResult.disciple
        }

        val hasEquipmentInBag = d.equipment.storageBagItems.any { it.itemType == "equipment" }
        val needEquipCheck = hasEquipmentInBag || d.id in params.shared.autoEquipDirty
        val equipResult = if (needEquipCheck) {
            equipmentManager.processAutoEquip(
                disciple = d,
                equipmentStacks = params.equip.stacks,
                equipmentInstances = params.equip.instanceMap,
                gameYear = params.time.year,
                gameMonth = params.time.month,
                gamePhase = params.time.phase,
                maxStack = params.equip.maxStack
            )
        } else null
        if (equipResult != null && equipResult.newInstances.isNotEmpty()) {
            d = equipResult.disciple
            params.acc.equipInstancesToAdd.addAll(equipResult.newInstances)
            equipResult.replacedInstances.forEach { params.acc.equipInstanceIdsToRemove.add(it.id) }
            equipResult.stackUpdates.forEach { update ->
                if (update.isDeletion) {
                    params.acc.equipStackDeletions.add(update.stackId)
                } else {
                    params.acc.equipStackQuantityDeltas.merge(
                        update.stackId, update.newQuantity
                    ) { _, new -> new }
                }
            }
            equipResult.replacedEquipmentStacks.forEach { replacedStack ->
                params.acc.equipStackAdditions.add(replacedStack)
            }
        }

        val hasManualInBag = d.equipment.storageBagItems.any { it.itemType == "manual" }
        val needLearnCheck = hasManualInBag || d.id in params.shared.autoLearnDirty
        val manualResult = if (needLearnCheck) {
            manualManager.processAutoLearn(
                disciple = d,
                manualStacks = params.manual.stacks,
                manualInstances = params.manual.instanceMap,
                gameYear = params.time.year,
                gameMonth = params.time.month,
                gamePhase = params.time.phase,
                maxStack = params.manual.maxStack
            )
        } else null
        if (manualResult != null && manualResult.newInstance != null) {
            d = manualResult.disciple
            manualResult.newInstance?.let { params.acc.manualInstancesToAdd.add(it) }
            manualResult.replacedInstance?.let { replaced ->
                params.acc.manualInstanceIdsToRemove.add(replaced.id)
                params.acc.profRemovals.getOrPut(d.id) { mutableSetOf() }.add(replaced.id)
            }
            manualResult.stackUpdate?.let { update ->
                if (update.isDeletion) {
                    params.acc.manualStackDeletions.add(update.stackId)
                } else {
                    params.acc.manualStackQuantityDeltas.merge(
                        update.stackId, update.newQuantity
                    ) { _, new -> new }
                }
            }
            manualResult.replacedManualStack?.let { replacedStack ->
                params.acc.manualStackAdditions.add(replacedStack)
            }
        }

        params.shared.autoEquipDirty.remove(d.id)
        params.shared.autoLearnDirty.remove(d.id)
        return d
    }

    /**
     * 将单旬累积器中的装备/功法变更应用到游戏状态。
     *
     * 处理内容：
     * - 装备实例的增删（equipInstancesToAdd/equipInstanceIdsToRemove）
     * - 装备堆叠的删除、数量更新、新增（受 maxEquipStack 上限约束）
     * - 功法实例的增删（manualInstancesToAdd/manualInstanceIdsToRemove）
     * - 被替换功法对应的熟练度清理（profRemovals）
     * - 功法堆叠的删除、数量更新、新增（受 maxManualStack 上限约束）
     *
     * @param acc 单旬累积器
     * @param state 可变游戏状态
     * @param maxEquipStack 装备堆叠数量上限
     * @param maxManualStack 功法堆叠数量上限
     */
    fun applyAccumulator(acc: PhaseTickAccumulator, state: MutableGameState, maxEquipStack: Int, maxManualStack: Int) {
        if (acc.equipInstancesToAdd.isNotEmpty() || acc.equipInstanceIdsToRemove.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances
                .filter { it.id !in acc.equipInstanceIdsToRemove } + acc.equipInstancesToAdd
        }
        if (acc.equipStackDeletions.isNotEmpty()) {
            state.equipmentStacks = state.equipmentStacks.filter { it.id !in acc.equipStackDeletions }
        }
        acc.equipStackQuantityDeltas.forEach { (stackId, newQty) ->
            state.equipmentStacks = state.equipmentStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxEquipStack)) else it
            }
        }
        acc.equipStackAdditions.forEach { stack ->
            val existing = state.equipmentStacks.find { it.id == stack.id }
            state.equipmentStacks = if (existing != null) {
                state.equipmentStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxEquipStack)) else it
                }
            } else {
                state.equipmentStacks + stack
            }
        }

        if (acc.manualInstancesToAdd.isNotEmpty() || acc.manualInstanceIdsToRemove.isNotEmpty()) {
            state.manualInstances = state.manualInstances
                .filter { it.id !in acc.manualInstanceIdsToRemove } + acc.manualInstancesToAdd
        }
        if (acc.profRemovals.isNotEmpty()) {
            val updatedProficiencies = state.gameData.manualProficiencies.toMutableMap()
            acc.profRemovals.forEach { (discipleId, manualIds) ->
                updatedProficiencies[discipleId]?.let { profList ->
                    val filtered = profList.filter { it.manualId !in manualIds }
                    if (filtered.isEmpty()) updatedProficiencies.remove(discipleId)
                    else updatedProficiencies[discipleId] = filtered
                }
            }
            state.gameData = state.gameData.copy(manualProficiencies = updatedProficiencies)
        }
        if (acc.manualStackDeletions.isNotEmpty()) {
            state.manualStacks = state.manualStacks.filter { it.id !in acc.manualStackDeletions }
        }
        acc.manualStackQuantityDeltas.forEach { (stackId, newQty) ->
            state.manualStacks = state.manualStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxManualStack)) else it
            }
        }
        acc.manualStackAdditions.forEach { stack ->
            val existing = state.manualStacks.find { it.id == stack.id }
            state.manualStacks = if (existing != null) {
                state.manualStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxManualStack)) else it
                }
            } else {
                state.manualStacks + stack
            }
        }
    }

    /**
     * 月度修炼结算：为所有存活弟子结算本月修炼、功法熟练度与装备温养进度。
     *
     * 月度增量 = 每旬增量 × 3（3 旬/月），并扣除焦点域已通过高频更新应用的部分，避免双计。
     * 结算完成后清空 highFrequencyData 中的累积增量。
     *
     * @param state 可变游戏状态
     * @param highFrequencyData 高频更新数据，包含已应用的修炼/熟练度/温养增量
     * @return 重置累积增量后的 highFrequencyData
     */
    fun updateMonthlyCultivation(state: MutableGameState, highFrequencyData: HighFrequencyData): HighFrequencyData {
        val data = state.gameData
        val tables = state.discipleTables
        val livingIds = tables.ids.filter { tables.isAlive[it] == 1 }
        if (livingIds.isEmpty()) return highFrequencyData

        val equipmentInstanceMap = state.equipmentInstances.associateBy { it.id }
        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

        val focusedGains = highFrequencyData.cultivationUpdates
        val focusedProfGains = highFrequencyData.proficiencyUpdates
        val focusedNurtureGains = highFrequencyData.nurtureUpdates

        for (id in livingIds) {
            val disciple = tables.assemble(id)
            val cultivationPerPhase = calculateDiscipleCultivationPerPhase(disciple, data, tables)
            val monthlyGain = cultivationPerPhase * 3  // 3旬/月
            val alreadyGained = focusedGains[disciple.id] ?: 0.0
            val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
            tables.cultivations[id] = (disciple.cultivation + netGain).coerceIn(0.0, disciple.maxCultivation)

            val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
            val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
            val proficiencyGainPerPhase = ManualProficiencySystem.calculateProficiencyGainPerPhase(disciple.comprehension, libraryBonus)
            val proficiencyGain = proficiencyGainPerPhase * 3  // 3旬/月
            val profAlreadyGained = focusedProfGains[disciple.id] ?: emptyMap()
            disciple.manualIds.forEach { manualId ->
                manualInstanceMap[manualId]?.let { manual ->
                    val profList = updatedManualProficiencies.getOrDefault(disciple.id, emptyList()).toMutableList()
                    val profIndex = profList.indexOfFirst { it.manualId == manualId }
                    val alreadyGainedProf = profAlreadyGained[manualId] ?: 0.0
                    val netProfGain = (proficiencyGain - alreadyGainedProf).coerceAtLeast(0.0)
                    val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
                    if (profIndex >= 0) {
                        val cp = profList[profIndex]
                        val fixedMaxProf = if (cp.maxProficiency != maxProf) maxProf else cp.maxProficiency
                        val newProf = (cp.proficiency + netProfGain).coerceAtMost(fixedMaxProf.toDouble())
                        profList[profIndex] = cp.copy(
                            proficiency = newProf,
                            maxProficiency = fixedMaxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProf).level
                        )
                    } else {
                        val initProf = netProfGain.coerceAtMost(maxProf.toDouble())
                        profList.add(ManualProficiencyData(
                            manualId = manualId,
                            manualName = manual.name,
                            proficiency = initProf,
                            maxProficiency = maxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(initProf).level
                        ))
                    }
                    updatedManualProficiencies[disciple.id] = profList
                }
            }

            // 温养每旬基础值：5.0/s × MS_PER_PHASE_1X/1000 → × 3旬/月
            val monthlyNurtureGain = 5.0 * com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X / 1000.0 * 3
            val nurtureAlreadyGained = focusedNurtureGains[disciple.id] ?: emptyMap()

            fun processNurture(eqId: String?) {
                eqId?.let { eid ->
                    equipmentInstanceMap[eid]?.let { eq ->
                        val already = nurtureAlreadyGained[eid] ?: 0.0
                        val netGain = (monthlyNurtureGain - already).coerceAtLeast(0.0)
                        equipmentInstanceUpdates[eid] = EquipmentNurtureSystem.updateNurtureExp(eq, netGain).equipment
                    }
                }
            }
            processNurture(disciple.equipment.weaponId)
            processNurture(disciple.equipment.armorId)
            processNurture(disciple.equipment.bootsId)
            processNurture(disciple.equipment.accessoryId)
        }

        if (equipmentInstanceUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
        }
        if (updatedManualProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        return highFrequencyData.copy(
            lastUpdateTime = System.currentTimeMillis(),
            totalDisciples = livingIds.size,
            cultivationUpdates = emptyMap(),
            proficiencyUpdates = emptyMap(),
            nurtureUpdates = emptyMap()
        )
    }

    /**
     * 焦点弟子高频实时更新：修炼推进、突破检测、功法熟练度与装备温养。
     *
     * 当弟子修炼值满且 HP/MP 均满时触发实时突破处理。
     * 每次调用推进一个 phase 的修炼与熟练度，并将增量累积到 highFrequencyData，
     * 供月度结算时扣除，避免双计。
     *
     * @param discipleId 焦点弟子 ID 字符串
     * @param state 可变游戏状态
     * @param highFrequencyData 高频更新数据
     * @param breakthroughHandler 突破处理器
     * @return 更新后的 highFrequencyData
     */
    fun updateFocusedDisciple(discipleId: String, state: MutableGameState, highFrequencyData: HighFrequencyData, breakthroughHandler: DiscipleBreakthroughHandler): HighFrequencyData {
        val data = state.gameData
        val tables = state.discipleTables
        val idInt = discipleId.toIntOrNull() ?: return highFrequencyData
        if (!tables.names.contains(idInt) || tables.isAlive[idInt] != 1) return highFrequencyData

        val disciple = tables.assemble(idInt)

        if (disciple.cultivation >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)) {
            breakthroughHandler.processRealtimeBreakthroughs(listOf(disciple), data, state)
        }

        val currentDisciple = tables.assemble(idInt)

        val cultivationPerPhase = calculateDiscipleCultivationPerPhase(disciple, data, tables)

        tables.cultivations.update(idInt) { (it + cultivationPerPhase).coerceIn(0.0, currentDisciple.maxCultivation) }

        val accumGains = highFrequencyData.cultivationUpdates.toMutableMap()
        accumGains[discipleId] = (accumGains[discipleId] ?: 0.0) + cultivationPerPhase

        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        val inLibrary = data.librarySlots.any { it.discipleId == discipleId }
        val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val proficiencyGainPerTick = ManualProficiencySystem.calculateProficiencyGainPerPhase(currentDisciple.comprehension, libraryBonus)

        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        val profUpdatesMap = highFrequencyData.proficiencyUpdates.toMutableMap()
        val discipleProfUpdates = profUpdatesMap.getOrDefault(discipleId, emptyMap()).toMutableMap()
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()

        currentDisciple.manualIds.forEach { manualId ->
            manualInstanceMap[manualId]?.let { manual ->
                val profList = updatedManualProficiencies.getOrDefault(discipleId, emptyList()).toMutableList()
                val profIndex = profList.indexOfFirst { it.manualId == manualId }
                if (profIndex >= 0) {
                    val cp = profList[profIndex]
                    val fixedMaxProf = if (cp.maxProficiency != maxProf) maxProf else cp.maxProficiency
                    val newProf = (cp.proficiency + proficiencyGainPerTick).coerceAtMost(fixedMaxProf.toDouble())
                    profList[profIndex] = cp.copy(
                        proficiency = newProf,
                        maxProficiency = fixedMaxProf,
                        masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProf).level
                    )
                } else {
                    val initProf = proficiencyGainPerTick.coerceAtMost(maxProf.toDouble())
                    profList.add(ManualProficiencyData(
                        manualId = manualId,
                        manualName = manual.name,
                        proficiency = initProf,
                        maxProficiency = maxProf,
                        masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(initProf).level
                    ))
                }
                updatedManualProficiencies[discipleId] = profList
                discipleProfUpdates[manualId] = (discipleProfUpdates[manualId] ?: 0.0) + proficiencyGainPerTick
            }
        }
        profUpdatesMap[discipleId] = discipleProfUpdates
        if (updatedManualProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        val equipmentInstanceMap = state.equipmentInstances.associateBy { it.id }
        // 温养每旬基础值 = 5.0/s × 每旬秒数
        val nurtureGainPerTick = 5.0 * com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X / 1000.0
        val nurtureUpdatesMap = highFrequencyData.nurtureUpdates.toMutableMap()
        val discipleNurtureUpdates = nurtureUpdatesMap.getOrDefault(discipleId, emptyMap()).toMutableMap()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

        listOfNotNull(
            currentDisciple.equipment.weaponId,
            currentDisciple.equipment.armorId,
            currentDisciple.equipment.bootsId,
            currentDisciple.equipment.accessoryId
        ).forEach { eqId ->
            equipmentInstanceMap[eqId]?.let { eq ->
                val result = EquipmentNurtureSystem.updateNurtureExp(eq, nurtureGainPerTick)
                equipmentInstanceUpdates[eqId] = result.equipment
                discipleNurtureUpdates[eqId] = (discipleNurtureUpdates[eqId] ?: 0.0) + nurtureGainPerTick
            }
        }
        nurtureUpdatesMap[discipleId] = discipleNurtureUpdates
        if (equipmentInstanceUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
        }

        return highFrequencyData.copy(
            lastCultivationTime = System.currentTimeMillis(),
            cultivationPerPhase = cultivationPerPhase,
            totalDisciples = tables.ids.count { tables.isAlive[it] == 1 },
            cultivationUpdates = accumGains,
            proficiencyUpdates = profUpdatesMap,
            nurtureUpdates = nurtureUpdatesMap
        )
    }

    companion object {
        private const val TAG = "CultivationCore"
    }
}
