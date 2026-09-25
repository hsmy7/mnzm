package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.engine.domain.disciple.getMasterDiscipleCultivationBonus
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhaseColumn

/**
 * 修炼速率计算器。
 *
 * 职责：
 * - 计算弟子每旬修炼速度（乘区法）
 * - 修炼相关加成计算（住所、讲道、师徒、政策）
 *
 * 缓存说明：
 * `manualInstanceMap` 和 `disciplesMap` 在同一个 stateStore.update {} 事务内
 * 引用不变，因此使用引用相等检测实现自动缓存，避免每弟子每旬重建映射。
 */
@Singleton
@GameService("CultivationRateCalculator")
class CultivationRateCalculator @Inject constructor(
    private val stateStore: GameStateStore
) {

    // ── 映射缓存（热路径优化：associateBy 在每个 tick 中只构建一次） ──────

    /** manualInstances 列表引用缓存。引用变化时自动重建映射。 */
    private var lastManualInstances: List<ManualInstance>? = null
    private var cachedManualInstanceMap: Map<String, ManualInstance> = emptyMap()

    private fun getManualInstanceMap(): Map<String, ManualInstance> {
        /** 当前设备的电源管理配置 */
        val current = stateStore.manualInstances.value
        if (lastManualInstances !== current) {
            lastManualInstances = current
            cachedManualInstanceMap = current.associateBy { it.id }
        }
        return cachedManualInstanceMap
    }

    /**
     * 计算弟子每旬修炼速度（1x 速度基准）。
     * calculateCultivationSpeed 已直接返回每旬值，无需再换算。
     */
    fun calculateDiscipleCultivationPerPhase(
        disciple: Disciple, data: GameData, tables: DiscipleTables
    ): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)
        val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(disciple, data, tables, "outer")
        val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(disciple, data, tables, "inner")

        val manualInstanceMap = getManualInstanceMap()
        // 仅取本弟子内层映射（O(P)），不再每弟子重建全量 outer map（O(D×P)）
        val discipleProficiencies = data.manualProficiencies[disciple.id]
            ?.associateBy { it.manualId } ?: emptyMap()

        // 师徒加成：徒弟有师父且师父存活时，按大境界差提供修炼速度加成
        val masterDiscipleBonus = disciple.social.masterId?.let { mid ->
            val midInt = mid.toIntOrNull() ?: return@let 0.0
            if (tables.names.contains(midInt) && tables.isAlive[midInt] == 1) {
                val masterRealm = tables.realms[midInt]
                DiscipleStatCalculator.getMasterDiscipleCultivationBonus(disciple.realm, masterRealm)
            } else 0.0
        } ?: 0.0

        val perPhase = DiscipleStatCalculator.calculateCultivationPerPhase(
            disciple = disciple,
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = calculatePolicyCultivationBonus(disciple.realm, data),
            masterDiscipleBonus = masterDiscipleBonus
        ).coerceAtLeast(1.0)
        return perPhase
    }

    /**
     * 列直读版每旬修炼速度（无 Disciple 组装）。
     *
     * 与 [calculateDiscipleCultivationPerPhase] 语义等价——从 DiscipleTables
     * 列直读所有速率相关字段，复用同一套乘区计算。
     * 供每旬热点循环（GameEngineCore.checkBreakthroughsAndPills）使用，
     * 消除每弟子每旬的 assemble（60~100 次列读取 + 10 个嵌套对象分配）。
     */
    fun calculateCultivationPerPhaseById(
        id: Int, data: GameData, tables: DiscipleTables,
        residenceByDiscipleId: Map<Int, ResidenceSlot> = emptyMap(),
        buildingByInstanceId: Map<String, GridBuildingData> = emptyMap()
    ): Double {
        val realm = tables.realms.getOrDefault(id, 9)
        val discipleType = tables.discipleTypes.getOrNull(id) ?: "outer"
        val buildingBonus = calculateBuildingCultivationBonus(
            id, data, residenceByDiscipleId, buildingByInstanceId
        )
        val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonusesColumn(
            realm, discipleType, data, tables, "outer"
        )
        val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonusesColumn(
            realm, discipleType, data, tables, "inner"
        )

        val manualInstanceMap = getManualInstanceMap()
        // 仅取本弟子内层映射（O(P)）
        val discipleProficiencies = data.manualProficiencies[id.toString()]
            ?.associateBy { it.manualId } ?: emptyMap()

        val masterDiscipleBonus = calculateMasterDiscipleBonusColumn(
            realm = realm, id = id, tables = tables
        )

        return DiscipleStatCalculator.calculateCultivationPerPhaseColumn(
            input = buildColumnRateInput(id = id, tables = tables, realm = realm),
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = calculatePolicyCultivationBonus(realm, data),
            masterDiscipleBonus = masterDiscipleBonus
        ).coerceAtLeast(1.0)
    }

    /** 列直读版师徒加成：师父存活时按大境界差提供修炼速度加成 */
    private fun calculateMasterDiscipleBonusColumn(
        realm: Int,
        id: Int,
        tables: DiscipleTables
    ): Double {
        // 师徒加成：徒弟有师父且师父存活时，按大境界差提供修炼速度加成
        return tables.masterIds.getOrNull(id)?.let { mid ->
            val midInt = mid.toIntOrNull() ?: return@let 0.0
            if (tables.names.contains(midInt) && tables.isAlive[midInt] == 1) {
                val masterRealm = tables.realms[midInt]
                DiscipleStatCalculator.getMasterDiscipleCultivationBonus(realm, masterRealm)
            } else 0.0
        } ?: 0.0
    }

    /** 列直读版乘区输入构建：默认值与 assemble 路径一致，防半幽灵数据两入口分歧 */
    private fun buildColumnRateInput(
        id: Int,
        tables: DiscipleTables,
        realm: Int
    ): DiscipleStatCalculator.CultivationRateColumnInput {
        return DiscipleStatCalculator.CultivationRateColumnInput(
            realm = realm,
            spiritRootCount = tables.spiritRootTypes.getOrNull(id)?.split(",")?.size ?: 1,
            manualIds = tables.manualIds.getOrDefault(id, emptyList()),
            // 丹药修炼速度加成统一收敛于 pillEffects 体系（旧
            // 旧 cultivationSpeedBonus 组件列不再读取，防双写双倍生效）
            pillEffectDuration = tables.pillEffectDurations.getOrDefault(id, 0),
            pillCultivationSpeedBonus = tables.pillCultivationSpeedBonuses.getOrDefault(id, 0.0)
        )
    }

    /** 有效教学值（列直读）：长老/师兄弟子的教学基础值。 */
    private fun getEffectiveTeaching(id: Int, tables: DiscipleTables): Int = tables.teachings[id]

    /**
     * 政策修炼加成汇总（修行津贴/苦修令/松弛管理，送入同一个乘区）。
     * 对象式与列式两个入口共用。
     *
     * @param realm 弟子境界（津贴仅 realm>5 生效）
     * @param data 游戏数据（政策开关）
     * @return 政策加成总和（负值为减益）
     */
    private fun calculatePolicyCultivationBonus(realm: Int, data: GameData): Double {
        var total = 0.0
        // 修行津贴：化神下(realm>5)弟子+15%
        if (data.sectPolicies.cultivationSubsidy && realm > 5) {
            total += GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_EFFECT
        }
        // 苦修令：全体+25%
        if (data.sectPolicies.asceticTraining) {
            total += GameConfig.PolicyConfig.ASCETIC_TRAINING_EFFECT
        }
        // 松弛管理：修炼速度-10%
        if (data.sectPolicies.relaxedMgmt) {
            total -= GameConfig.PolicyConfig.RELAXED_MGMT_CULTIVATION_PENALTY
        }
        return total
    }

    // ── 私有辅助方法 ──────────────────────────────────

    /** 列直读版讲道加成 + 导师加成，无 assemble。对标原 calculatePreachingBonuses。 */
    private fun calculatePreachingBonuses(
        disciple: Disciple,
        data: GameData,
        tables: DiscipleTables,
        targetDiscipleType: String
    ): Pair<Double, Double> = calculatePreachingBonusesColumn(
        disciple.realm, disciple.discipleType, data, tables, targetDiscipleType
    )

    /** 列直读版讲道加成 + 导师加成，无 assemble。对标原 calculatePreachingBonuses。 */
    private fun calculatePreachingBonusesColumn(
        discipleRealm: Int,
        discipleType: String,
        data: GameData,
        tables: DiscipleTables,
        targetDiscipleType: String
    ): Pair<Double, Double> {
        if (discipleType != targetDiscipleType) return 0.0 to 0.0
        val elderSlots = data.elderSlots

        return when (targetDiscipleType) {
            "outer" -> preachingElderBonusColumn(
                discipleRealm, elderSlots.preachingElder, tables,
                ::getEffectiveTeaching
            ) to preachingMastersBonusColumn(
                discipleRealm, elderSlots.preachingMasters.map { it.discipleId }, tables,
                ::getEffectiveTeaching
            )
            "inner" -> preachingElderBonusColumn(
                discipleRealm, elderSlots.qingyunPreachingElder, tables,
                ::getEffectiveTeaching
            ) to preachingMastersBonusColumn(
                discipleRealm, elderSlots.qingyunPreachingMasters.map { it.discipleId }, tables,
                ::getEffectiveTeaching
            )
            else -> 0.0 to 0.0
        }
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
     * @param disciple 待计算的弟子
     * @param data 当前游戏数据，用于查询住所槽位与已放置建筑
     * @return 修炼速度加成系数，无建筑时返回 1.0
     */
    private fun calculateBuildingCultivationBonus(disciple: Disciple, data: GameData): Double {
        val id = disciple.id.toIntOrNull() ?: return 1.0
        return calculateBuildingCultivationBonus(id, data, emptyMap(), emptyMap())
    }

    /** 列直读版住所加成，无 Disciple 组装。对标原 calculateBuildingCultivationBonus。 */
    private fun calculateBuildingCultivationBonus(
        id: Int, data: GameData,
        residenceByDiscipleId: Map<Int, ResidenceSlot>,
        buildingByInstanceId: Map<String, GridBuildingData>
    ): Double {
        // 预构建索引为空时懒构建（直接调用方不受影响）；
        // 每旬热点循环预构建后 O(1) 查找，消除 O(R)+O(B) 线性扫描 + 每弟子 id.toString()
        val residenceMap = if (residenceByDiscipleId.isNotEmpty()) {
            residenceByDiscipleId
        } else {
            buildResidenceIndex(data)
        }
        val buildingMap = if (buildingByInstanceId.isNotEmpty()) {
            buildingByInstanceId
        } else {
            data.placedBuildings.associateBy { it.instanceId }
        }
        val slot = residenceMap[id] ?: return 1.0
        val building = buildingMap[slot.buildingInstanceId] ?: return 1.0
        return GameConfig.Cultivation.BUILDING_BONUSES[building.displayName] ?: 1.0
    }

    /**
     * 构建住所索引：discipleId → 槽位。
     *
     * 与原 firstOrNull 语义一致——重复 discipleId 保留第一个匹配槽位；
     * 非数值 discipleId（如空串）永不匹配弟子 id.toString()，直接跳过。
     */
    private fun buildResidenceIndex(data: GameData): Map<Int, ResidenceSlot> {
        val map = HashMap<Int, ResidenceSlot>()
        for (r in data.residenceSlots) {
            val rid = r.discipleId.toIntOrNull() ?: continue
            if (rid !in map) map[rid] = r
        }
        return map
    }
}

/** 列直读版长老讲道加成：有效教学 ≥80 且境界达标时按教学差提供加成 */
private fun preachingElderBonusColumn(
    discipleRealm: Int,
    elderId: String?,
    tables: DiscipleTables,
    effectiveTeaching: (Int, DiscipleTables) -> Int
): Double {
    val id = elderId?.toIntOrNull() ?: return 0.0
    if (!tables.names.contains(id) || tables.isAlive[id] != 1) return 0.0
    val teaching = effectiveTeaching(id, tables)
    val realm = tables.realms[id]
    if (discipleRealm >= realm && teaching >= 80) {
        return ((teaching - 80) * 0.0025).coerceAtMost(0.10)
    }
    return 0.0
}

/** 列直读版师兄弟讲道加成：有效教学 ≥60 且境界达标时累计教学差加成 */
private fun preachingMastersBonusColumn(
    discipleRealm: Int,
    masterIds: List<String?>,
    tables: DiscipleTables,
    effectiveTeaching: (Int, DiscipleTables) -> Int
): Double {
    var total = 0.0
    for (mId in masterIds) {
        val id = mId?.toIntOrNull()
        // id 非法/不存在/已死亡的师尊跳过
        if (id == null || !tables.names.contains(id) || tables.isAlive[id] != 1) continue
        val teaching = effectiveTeaching(id, tables)
        val realm = tables.realms[id]
        if (discipleRealm >= realm && teaching >= 60) total += ((teaching - 60) * 0.001).coerceAtMost(0.05)
    }
    return total
}
