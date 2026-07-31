package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 修炼速率计算器。
 *
 * 职责：
 * - 计算弟子每旬修炼速度（乘区法）
 * - 境界寿命增益查询
 * - 修炼相关加成计算（住所、讲道、师徒、父母灵根、丧期惩罚）
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

        val parentCultivationBonus = calculateParentBonusColumn(
            disciple.social.parentId1, disciple.social.parentId2, tables
        )

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

        val perPhase = DiscipleStatCalculator.calculateCultivationPerPhase(
            disciple = disciple,
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = calculatePolicyCultivationBonus(disciple.realm, data),
            parentCultivationBonus = parentCultivationBonus,
            griefCultivationSpeedPenalty = griefPenalty,
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
        id: Int, data: GameData, tables: DiscipleTables
    ): Double {
        val realm = tables.realms.getOrDefault(id, 9)
        val discipleType = tables.discipleTypes.getOrNull(id) ?: "outer"
        val buildingBonus = calculateBuildingCultivationBonus(id, data)
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

        val parentCultivationBonus = calculateParentBonusColumn(
            tables.parentId1s.getOrNull(id), tables.parentId2s.getOrNull(id), tables
        )

        // 师徒加成：徒弟有师父且师父存活时，按大境界差提供修炼速度加成
        val masterDiscipleBonus = tables.masterIds.getOrNull(id)?.let { mid ->
            val midInt = mid.toIntOrNull() ?: return@let 0.0
            if (tables.names.contains(midInt) && tables.isAlive[midInt] == 1) {
                val masterRealm = tables.realms[midInt]
                DiscipleStatCalculator.getMasterDiscipleCultivationBonus(realm, masterRealm)
            } else 0.0
        } ?: 0.0

        // 显式过滤哨兵值 -1（GRIEF_YEAR_NULL_SENTINEL）→ null，
        // 与 assemble 路径的 takeIf 过滤严格一致（防篡改负年份时两入口分歧）
        val griefEndYear = tables.griefEndYears.getOrNull(id)
            ?.takeIf { it != DiscipleTables.GRIEF_YEAR_NULL_SENTINEL }
        val griefPenalty = if (
            DiscipleStatCalculator.isGrieving(griefEndYear, data.gameYear)
        ) {
            DiscipleStatCalculator.GRIEF_CULTIVATION_SPEED_PENALTY
        } else {
            0.0
        }

        return DiscipleStatCalculator.calculateCultivationPerPhaseColumn(
            input = DiscipleStatCalculator.CultivationRateColumnInput(
                realm = realm,
                spiritRootCount = tables.spiritRootTypes.getOrNull(id)?.split(",")?.size ?: 1,
                talentIds = tables.talentIds.getOrDefault(id, emptyList()),
                physiqueIds = tables.physiqueIds.getOrDefault(id, emptyList()),
                affixIds = tables.affixIds.getOrDefault(id, emptyList()),
                manualIds = tables.manualIds.getOrDefault(id, emptyList()),
                // 默认值与 assemble 路径一致（:723-724），防半幽灵数据两入口分歧
                age = tables.ages.getOrDefault(id, 16),
                lifespan = tables.lifespans.getOrDefault(id, 80),
                cultivationSpeedDuration = tables.cultivationSpeedDurations.getOrDefault(id, 0),
                cultivationSpeedBonus = tables.cultivationSpeedBonuses.getOrDefault(id, 0.0),
                pillEffectDuration = tables.pillEffectDurations.getOrDefault(id, 0),
                pillCultivationSpeedBonus = tables.pillCultivationSpeedBonuses.getOrDefault(id, 0.0)
            ),
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = calculatePolicyCultivationBonus(realm, data),
            parentCultivationBonus = parentCultivationBonus,
            griefCultivationSpeedPenalty = griefPenalty,
            masterDiscipleBonus = masterDiscipleBonus
        ).coerceAtLeast(1.0)
    }

    /**
     * 有效教学值 = 基础教学 + teachingFlat 天赋加成。
     *
     * 对齐 [DiscipleStatCalculator.getBaseStats].teaching 的语义
     * （基础值 + effects["teachingFlat"].toInt() 截断），
     * 修复"UI 用 getBaseStats 显示讲道加成、结算用列基础值"的不一致——
     * teachingFlat 天赋（如教学+10）跨过 80/60 阈值线时 UI 显示满加成而实际 0 加成。
     *
     * @param id 长老/师兄弟子 ID
     * @param tables 弟子数据表
     * @return 含 teachingFlat 天赋加成的有效教学值
     */
    private fun getEffectiveTeaching(id: Int, tables: DiscipleTables): Int {
        val flat = tables.talentIds.getOrDefault(id, emptyList())
            .mapNotNull { TalentDatabase.getById(it) }
            .sumOf { it.effects["teachingFlat"] ?: 0.0 }
            .toInt()
        return tables.teachings[id] + flat
    }

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

    // ── 私有辅助方法 ──────────────────────────────────

    /** 列直读版父母灵根加成，无 assemble。对标 calculateParentCultivationBonus。 */
    private fun calculateParentBonusColumn(
        parentId1: String?, parentId2: String?, tables: DiscipleTables
    ): Double {
        fun bonusFor(pid: String?): Double {
            val id = pid?.toIntOrNull() ?: return 0.0
            if (tables.isAlive[id] != 1) return 0.0
            val rootCount = tables.spiritRootTypes.getOrNull(id)?.split(",")?.size ?: 0
            return DiscipleStatCalculator.getParentSpiritRootBonus(rootCount)
        }
        return bonusFor(parentId1) + bonusFor(parentId2)
    }

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

        fun elderBonus(elderId: String?, slotType: ElderSlotType): Double {
            val id = elderId?.toIntOrNull() ?: return 0.0
            if (!tables.names.contains(id) || tables.isAlive[id] != 1) return 0.0
            // 有效教学 = 基础教学 + teachingFlat 天赋加成（对齐 getBaseStats().teaching 语义，
            // 修复"UI 显示讲道加成但实际不生效"的不一致）
            val teaching = getEffectiveTeaching(id, tables)
            val realm = tables.realms[id]
            if (discipleRealm >= realm && teaching >= 80) {
                val base = ((teaching - 80) * 0.0025).coerceAtMost(0.10)
                // 长老职务加成（PositionBonus）：作为乘算因子作用于长老职能效果
                // 列直读版：从列提取 talentIds/affixIds 计算，无 Disciple 组装
                val posBonus = DiscipleStatCalculator.getPositionEffectBonus(
                    tables.talentIds.getOrDefault(id, emptyList()),
                    tables.affixIds.getOrDefault(id, emptyList()),
                    slotType
                )
                return base * (1.0 + posBonus)
            }
            return 0.0
        }

        fun mastersBonus(masterIds: List<String?>): Double {
            var total = 0.0
            for (mId in masterIds) {
                val id = mId?.toIntOrNull() ?: continue
                if (!tables.names.contains(id) || tables.isAlive[id] != 1) continue
                val teaching = getEffectiveTeaching(id, tables)
                val realm = tables.realms[id]
                if (discipleRealm >= realm && teaching >= 60) total += ((teaching - 60) * 0.001).coerceAtMost(0.05)
            }
            return total
        }

        return when (targetDiscipleType) {
            "outer" -> elderBonus(elderSlots.preachingElder, ElderSlotType.PREACHING) to
                mastersBonus(elderSlots.preachingMasters.map { it.discipleId })
            "inner" -> elderBonus(elderSlots.qingyunPreachingElder, ElderSlotType.CLOUD_PREACHING) to
                mastersBonus(elderSlots.qingyunPreachingMasters.map { it.discipleId })
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
        return calculateBuildingCultivationBonus(id, data)
    }

    /** 列直读版住所加成，无 Disciple 组装。对标原 calculateBuildingCultivationBonus。 */
    private fun calculateBuildingCultivationBonus(id: Int, data: GameData): Double {
        val slot = data.residenceSlots.firstOrNull { it.discipleId == id.toString() } ?: return 1.0
        val building = data.placedBuildings.firstOrNull { it.instanceId == slot.buildingInstanceId } ?: return 1.0
        return GameConfig.Cultivation.BUILDING_BONUSES[building.displayName] ?: 1.0
    }

    companion object {
        private const val TAG = "CultivationRateCalculator"
    }
}
