package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState

/**
 * 生产系统速率指纹 — 检测影响生产系统产出速率的结构变化。
 *
 * 与 [CultivationRateFingerprint] 互补：
 * - 修炼指纹检测逐弟子修炼速率变化（cell-level）
 * - 生产指纹检测各生产系统槽位分配/配方/建筑/政策变化（slot-level）
 *
 * 任一指纹变化 → 触发微结算：旧速率结算已累积产出 → 重建缓存 → 新速率继续累积。
 *
 * 检测维度（纯 Int 比较 O(1)）：
 * - 采矿（灵矿）：槽位分配 + 建筑等级
 * - 种植（灵植阁）：灵田状态 + 长老/弟子分配
 * - 炼丹：长老/弟子分配 + 政策
 * - 炼器（锻造）：长老/弟子分配 + 政策
 * - 血炼：活跃血炼进程（discipleId × stat × 进度）
 * - 任务：活跃任务（discipleId × 类型 × 时长）
 * - 思过：弟子思过状态
 * - 通用：生产建筑等级 + 生产相关政策 + 已分配弟子关键属性
 *
 * 注意：槽位进度变化（如炼丹完成 50%→60%）不在此指纹检测范围内，
 *       进度完成型溢出由 80% 实时轨机制处理。
 */
data class ProductionRateFingerprint(
    /** 灵矿槽位：discipleId × buildingInstanceId */
    val spiritMineHash: Int,
    /** 灵植阁：灵田种植状态 + 长老/弟子分配 */
    val herbGardenHash: Int,
    /** 炼丹：长老/弟子分配 + 政策 */
    val alchemyHash: Int,
    /** 炼器：长老/弟子分配 + 政策 */
    val forgeHash: Int,
    /** 血炼：活跃进程（buildingId × discipleId × stat × 进度） */
    val bloodRefinementHash: Int,
    /** 任务：活跃任务（discipleIds × 类型 × 时长） */
    val missionHash: Int,
    /** 思过：弟子 REFLECTING 状态 */
    val reflectionHash: Int,
    /** 生产建筑等级（placedBuildings 中生产类） */
    val productionBuildingHash: Int,
    /** 生产相关政策 */
    val productionPolicyHash: Int,
    /** 分配到任何生产槽位的弟子关键属性 */
    val assignedDiscipleStatsHash: Int
) {
    companion object {
        /**
         * 从 [MutableGameState] 计算生产指纹。
         *
         * 仅哈希结构因素（分配/配方/政策/属性），不哈希进度值。
         * 计算复杂度 O(n) 其中 n 为生产槽位数，通常 < 50。
         */
        fun compute(state: MutableGameState): ProductionRateFingerprint {
            val data = state.gameData
            val tables = state.discipleTables

            // 1. 灵矿槽位
            val spiritMineHash = data.spiritMineSlots
                .filter { it.discipleId.isNotEmpty() }
                .map { "${it.discipleId}:${it.buildingInstanceId}" }
                .hashCode()

            // 2. 灵植阁：灵田状态 + 长老/弟子分配
            val herbGardenHash = buildHerbGardenHash(data, tables)

            // 3. 炼丹：长老/弟子/储备弟子分配 + 激励政策 + 已分配弟子属性
            val alchemyHash = buildAlchemyForgeHash(
                data, tables,
                elderId = data.elderSlots.alchemyElder,
                disciples = data.elderSlots.alchemyDisciples,
                reserveDisciples = data.elderSlots.alchemyReserveDisciples,
                policyEnabled = data.sectPolicies.alchemyIncentive
            )

            // 4. 炼器：同炼丹模式
            val forgeHash = buildAlchemyForgeHash(
                data, tables,
                elderId = data.elderSlots.forgeElder,
                disciples = data.elderSlots.forgeDisciples,
                reserveDisciples = data.elderSlots.forgeReserveDisciples,
                policyEnabled = data.sectPolicies.forgeIncentive
            )

            // 5. 血炼：活跃血炼进程
            val bloodRefinementHash = data.activeBloodRefinements.entries
                .map { (buildingId, progress) ->
                    val dId = progress.discipleId.toIntOrNull()
                    val realm = if (dId != null && tables.ids.contains(dId))
                        tables.realms.getOrDefault(dId, 9) else 9
                    "$buildingId:${progress.discipleId}:${progress.selectedStat}:$realm"
                }
                .hashCode()

            // 6. 任务：活跃任务
            val missionHash = data.activeMissions
                .map { m ->
                    val discipleRealms = m.discipleIds.map { dId ->
                        val idInt = dId.toIntOrNull()
                        if (idInt != null && tables.ids.contains(idInt))
                            tables.realms.getOrDefault(idInt, 9) else 9
                    }
                    "${m.missionId}:${discipleRealms}:${m.duration}:${m.missionType}"
                }
                .hashCode()

            // 7. 思过：弟子 REFLECTING 状态
            val reflectionHash = buildReflectionHash(tables)

            // 8. 生产建筑等级
            val productionBuildingHash = data.placedBuildings
                .filter { it.isProductionBuilding() }
                .map { "${it.displayName}:${it.instanceId}" }
                .hashCode()

            // 9. 生产相关政策
            val productionPolicyHash = buildProductionPolicyHash(data)

            // 10. 已分配到任何生产槽位的弟子关键属性
            val assignedDiscipleStatsHash = buildAssignedDiscipleStatsHash(data, tables)

            return ProductionRateFingerprint(
                spiritMineHash = spiritMineHash,
                herbGardenHash = herbGardenHash,
                alchemyHash = alchemyHash,
                forgeHash = forgeHash,
                bloodRefinementHash = bloodRefinementHash,
                missionHash = missionHash,
                reflectionHash = reflectionHash,
                productionBuildingHash = productionBuildingHash,
                productionPolicyHash = productionPolicyHash,
                assignedDiscipleStatsHash = assignedDiscipleStatsHash
            )
        }

        private fun buildHerbGardenHash(data: GameData, tables: DiscipleTables): Int {
            var h = 1
            // 灵田种植状态
            h = 31 * h + data.spiritFieldPlants.map {
                "${it.buildingInstanceId}:${it.seedId}:${it.growTime}:${it.expectedYield}"
            }.hashCode()
            // 灵植阁长老
            h = 31 * h + data.elderSlots.herbGardenElder.hashCode()
            // 灵植阁弟子
            h = 31 * h + data.elderSlots.herbGardenDisciples.map {
                "${it.discipleId}"
            }.hashCode()
            // 灵植阁储备弟子
            h = 31 * h + data.elderSlots.herbGardenReserveDisciples.map {
                "${it.discipleId}"
            }.hashCode()
            // 灵矿执事弟子（灵植阁可能共用）
            h = 31 * h + data.elderSlots.spiritMineDeaconDisciples.map {
                "${it.discipleId}"
            }.hashCode()
            return h
        }

        private fun buildAlchemyForgeHash(
            data: GameData,
            tables: DiscipleTables,
            elderId: String,
            disciples: List<com.xianxia.sect.core.model.DirectDiscipleSlot>,
            reserveDisciples: List<com.xianxia.sect.core.model.DirectDiscipleSlot>,
            policyEnabled: Boolean
        ): Int {
            var h = 1
            h = 31 * h + elderId.hashCode()
            h = 31 * h + disciples.map { "${it.discipleId}" }.hashCode()
            h = 31 * h + reserveDisciples.map { "${it.discipleId}" }.hashCode()
            h = 31 * h + policyEnabled.hashCode()
            // 已分配弟子的境界
            val allAssignedIds = (disciples + reserveDisciples)
                .mapNotNull { it.discipleId?.takeIf { id -> id.isNotEmpty() } }
            h = 31 * h + allAssignedIds.map { id ->
                val idInt = id.toIntOrNull()
                if (idInt != null && tables.ids.contains(idInt))
                    tables.realms.getOrDefault(idInt, 9) else 9
            }.hashCode()
            return h
        }

        private fun buildReflectionHash(tables: DiscipleTables): Int {
            return tables.ids
                .filter { tables.isAlive[it] == 1 && tables.statuses[it] == com.xianxia.sect.core.model.DiscipleStatus.REFLECTING }
                .map { id ->
                    val endYear = tables.statusData[id]?.get("reflectionEndYear") ?: "0"
                    "$id:$endYear"
                }
                .hashCode()
        }

        private fun buildProductionPolicyHash(data: GameData): Int {
            var h = 1
            h = 31 * h + data.sectPolicies.spiritMineBoost.hashCode()
            h = 31 * h + data.sectPolicies.alchemyIncentive.hashCode()
            h = 31 * h + data.sectPolicies.forgeIncentive.hashCode()
            return h
        }

        /**
         * 收集所有分配到生产槽位的弟子 ID，哈希其关键属性。
         * 任一弟子的境界/属性变化都会改变指纹。
         */
        private fun buildAssignedDiscipleStatsHash(data: GameData, tables: DiscipleTables): Int {
            val assignedIds = mutableSetOf<Int>()

            // 灵矿
            data.spiritMineSlots.forEach { slot ->
                slot.discipleId.toIntOrNull()?.let { assignedIds.add(it) }
            }
            // 灵植阁弟子
            data.elderSlots.herbGardenDisciples.forEach { slot ->
                slot.discipleId?.toIntOrNull()?.let { assignedIds.add(it) }
            }
            data.elderSlots.herbGardenReserveDisciples.forEach { slot ->
                slot.discipleId?.toIntOrNull()?.let { assignedIds.add(it) }
            }
            // 炼丹弟子
            data.elderSlots.alchemyDisciples.forEach { slot ->
                slot.discipleId?.toIntOrNull()?.let { assignedIds.add(it) }
            }
            data.elderSlots.alchemyReserveDisciples.forEach { slot ->
                slot.discipleId?.toIntOrNull()?.let { assignedIds.add(it) }
            }
            // 炼器弟子
            data.elderSlots.forgeDisciples.forEach { slot ->
                slot.discipleId?.toIntOrNull()?.let { assignedIds.add(it) }
            }
            data.elderSlots.forgeReserveDisciples.forEach { slot ->
                slot.discipleId?.toIntOrNull()?.let { assignedIds.add(it) }
            }
            // 血炼弟子
            data.activeBloodRefinements.values.forEach { progress ->
                progress.discipleId.toIntOrNull()?.let { assignedIds.add(it) }
            }
            // 任务弟子
            data.activeMissions.forEach { mission ->
                mission.discipleIds.forEach { dId ->
                    dId.toIntOrNull()?.let { assignedIds.add(it) }
                }
            }

            if (assignedIds.isEmpty()) return 0

            // 哈希每个已分配弟子的境界、灵根、关键属性
            return assignedIds.map { id ->
                var h = 1
                h = 31 * h + tables.realms.getOrDefault(id, 9)
                h = 31 * h + tables.realmLayers.getOrDefault(id, 0)
                h = 31 * h + (tables.ages.getOrDefault(id, 0))
                h = 31 * h + (tables.spiritRootTypes.getOrNull(id)?.hashCode() ?: 0)
                h
            }.hashCode()
        }

    }
}

/**
 * 判断建筑是否为生产类建筑。
 */
private fun com.xianxia.sect.core.model.GridBuildingData.isProductionBuilding(): Boolean {
    return displayName in PRODUCTION_BUILDING_NAMES
}

private val PRODUCTION_BUILDING_NAMES = setOf(
    "灵矿场", "灵植阁", "炼丹炉", "锻造坊", "血炼池",
    "任务大厅", "巡视楼", "思过崖", "藏经阁"
)
