package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
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

    /** disciples 列表引用缓存。引用变化时自动重建映射。 */
    private var lastDisciples: List<Disciple>? = null
    private var cachedDisciplesMap: Map<String, Disciple> = emptyMap()

    private fun getManualInstanceMap(): Map<String, ManualInstance> {
        val current = stateStore.manualInstances.value
        if (lastManualInstances !== current) {
            lastManualInstances = current
            cachedManualInstanceMap = current.associateBy { it.id }
        }
        return cachedManualInstanceMap
    }

    private fun getDisciplesMap(): Map<String, Disciple> {
        val current = stateStore.disciples.value
        if (lastDisciples !== current) {
            lastDisciples = current
            cachedDisciplesMap = current.associateBy { it.id }
        }
        return cachedDisciplesMap
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

        // 修行津贴：化神下(realm>5)弟子+15%
        var cultivationSubsidyBonus = 0.0
        if (data.sectPolicies.cultivationSubsidy && disciple.realm > 5) {
            cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_EFFECT
        }

        // 苦修令：全体+25%
        var asceticTrainingBonus = 0.0
        if (data.sectPolicies.asceticTraining) {
            asceticTrainingBonus = GameConfig.PolicyConfig.ASCETIC_TRAINING_EFFECT
        }

        // 松弛管理：修炼速度-10%
        var relaxedMgmtPenalty = 0.0
        if (data.sectPolicies.relaxedMgmt) {
            relaxedMgmtPenalty = -GameConfig.PolicyConfig.RELAXED_MGMT_CULTIVATION_PENALTY
        }

        val manualInstanceMap = getManualInstanceMap()
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()

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

        // 合并所有政策修炼加成/减益（送入同一个乘区）
        val totalPolicyBonus = cultivationSubsidyBonus + asceticTrainingBonus + relaxedMgmtPenalty

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
            cultivationSubsidyBonus = totalPolicyBonus,
            parentCultivationBonus = parentCultivationBonus,
            griefCultivationSpeedPenalty = griefPenalty,
            masterDiscipleBonus = masterDiscipleBonus
        ).coerceAtLeast(1.0)
        return perPhase
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
    ): Pair<Double, Double> {
        if (disciple.discipleType != targetDiscipleType) return 0.0 to 0.0
        val elderSlots = data.elderSlots
        // 用于提取长老职务加成（PositionBonus）
        val allDisciples = getDisciplesMap()

        fun elderBonus(elderId: String?, slotType: ElderSlotType): Double {
            val id = elderId?.toIntOrNull() ?: return 0.0
            if (!tables.names.contains(id) || tables.isAlive[id] != 1) return 0.0
            val teaching = tables.teachings[id]
            val realm = tables.realms[id]
            if (disciple.realm >= realm && teaching >= 80) {
                val base = ((teaching - 80) * 0.0025).coerceAtMost(0.10)
                // 长老职务加成（PositionBonus）：作为乘算因子作用于长老职能效果
                val posBonus = allDisciples[elderId]?.let {
                    DiscipleStatCalculator.getPositionEffectBonus(it, slotType)
                } ?: 0.0
                return base * (1.0 + posBonus)
            }
            return 0.0
        }

        fun mastersBonus(masterIds: List<String?>): Double {
            var total = 0.0
            for (mId in masterIds) {
                val id = mId?.toIntOrNull() ?: continue
                if (!tables.names.contains(id) || tables.isAlive[id] != 1) continue
                val teaching = tables.teachings[id]
                val realm = tables.realms[id]
                if (disciple.realm >= realm && teaching >= 60) total += ((teaching - 60) * 0.001).coerceAtMost(0.05)
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
        val slot = data.residenceSlots.firstOrNull { it.discipleId == disciple.id } ?: return 1.0
        val building = data.placedBuildings.firstOrNull { it.instanceId == slot.buildingInstanceId } ?: return 1.0
        return GameConfig.Cultivation.BUILDING_BONUSES[building.displayName] ?: 1.0
    }

    companion object {
        private const val TAG = "CultivationRateCalculator"
    }
}
