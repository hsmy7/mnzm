package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.util.BuildingNames

/**
 * 公式计算服务 - 负责游戏内各种数值计算
 *
 * 职责域：
 * - 成功率加成计算 (calculateSuccessRateBonus)
 * - 工作持续时间计算 (calculateWorkDurationWithAllDisciples)
 * - 长老和弟子加成计算 (calculateElderAndDisciplesBonus)
 * - 境界/天赋/功法/建筑相关加成计算
 */
class FormulaService(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>
) {
    companion object {
        private const val TAG = "FormulaService"
    }

    // ==================== 数据类定义 ====================

    /**
     * 长老加成数据类
     */
    data class ElderBonusData(
        val speedBonus: Double,
        val successBonus: Double,
        val yieldBonus: Double
    )

    // ==================== 成功率计算 ====================

    /**
     * 计算生产成功率加成
     *
     * @param弟子 执行生产的弟子（可为空）
     * @param buildingId 建筑ID
     * @return 总成功率加成（0.0-1.0）
     */
    fun calculateSuccessRateBonus(disciple: Disciple?, buildingId: String): Double {
        if (disciple == null) return 0.0

        if (buildingId == "forge") return 0.0

        var bonus = 0.0

        bonus += getRealmSuccessRateBonus(disciple.realm)

        bonus += getSuccessRateTalentBonus(disciple, buildingId)

        bonus += getSuccessRateManualBonus(disciple, buildingId)

        return bonus
    }

    /**
     * 根据境界获取成功率加成
     *
     * @param realm 境界等级
     * @return 成功率加成
     */
    private fun getRealmSuccessRateBonus(realm: Int): Double {
        return when (realm) {
            0 -> 0.30  // 仙人 +30%
            1 -> 0.25  // 渡劫 +25%
            2 -> 0.22  // 大乘 +22%
            3 -> 0.19  // 合体 +19%
            4 -> 0.16  // 炼虚 +16%
            5 -> 0.13  // 化神 +13%
            6 -> 0.10  // 元婴 +10%
            7 -> 0.07  // 金丹 +7%
            8 -> 0.04  // 筑基 +4%
            else -> 0.0 // 炼气 0%
        }
    }

    /**
     * 获取成功率相关天赋加成
     *
     * @param disciple 弟子对象
     * @param buildingId 建筑ID
     * @return 天赋加成
     */
    private fun getSuccessRateTalentBonus(disciple: Disciple, buildingId: String): Double {
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val breakthroughBonus = (talentEffects["breakthroughChance"] ?: 0.0) * 0.80
        val craftFlatBonus = getBuildingCraftFlatBonus(talentEffects, buildingId) * 0.006
        return breakthroughBonus + craftFlatBonus
    }

    /**
     * 获取建筑工艺固定加成
     *
     * @param talentEffects 天赋效果映射
     * @param buildingId 建筑ID
     * @return 固定加成值
     */
    private fun getBuildingCraftFlatBonus(talentEffects: Map<String, Double>, buildingId: String): Double {
        return when (buildingId) {
            "alchemy" -> talentEffects["pillRefiningFlat"] ?: 0.0
            "forge" -> talentEffects["artifactRefiningFlat"] ?: 0.0
            "herbGarden" -> talentEffects["spiritPlantingFlat"] ?: 0.0
            else -> 0.0
        }
    }

    /**
     * 获取成功率相关功法加成
     *
     * @param disciple 弟子对象
     * @param buildingId 建筑ID
     * @return 功法加成
     */
    private fun getSuccessRateManualBonus(disciple: Disciple, buildingId: String): Double {
        return 0.0
    }

    // ==================== 工作时间计算 ====================

    /**
     * 计算所有弟子的工作持续时间加成
     *
     * @param baseDuration 基础持续时间（月）
     * @param buildingId 建筑ID
     * @return 实际持续时间（月）
     */
    fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var totalSpeedBonus = 0.0
        val data = _gameData.value

        when (buildingId) {
            "alchemy" -> {
                val elderBonus = calculateElderAndDisciplesBonus("alchemy")
                totalSpeedBonus += elderBonus.speedBonus
            }
            "forge" -> {
                val elderBonus = calculateElderAndDisciplesBonus("forge")
                totalSpeedBonus += elderBonus.speedBonus
            }
            else -> {
                val allBuildingSlots = data.forgeSlots.filter { it.buildingId == buildingId }
                val assignedDiscipleIds = allBuildingSlots.mapNotNull { it.discipleId }
                if (assignedDiscipleIds.isNotEmpty()) {
                    totalSpeedBonus += getElderPositionBonus(buildingId)
                }
            }
        }

        return calculateReducedDuration(baseDuration, totalSpeedBonus)
    }

    /**
     * 根据速度加成计算减少后的持续时间
     *
     * @param baseDuration 基础持续时间
     * @param speedBonus 速度加成
     * @return 减少后的持续时间
     */
    private fun calculateReducedDuration(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration

        val reductionPercent = speedBonus / 4.0

        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    // ==================== 长老职位加成 ====================

    /**
     * 获取长老职位对建筑的速度加成
     *
     * @param buildingId 建筑ID
     * @return 速度加成
     */
    private fun getElderPositionBonus(buildingId: String): Double {
        val elderBuildingId = when (buildingId) {
            "forge" -> "forge"
            "alchemy" -> "alchemy"
            "herbGarden" -> "herbGarden"
            else -> return 0.0
        }

        // 检查该建筑是否有长老任职
        val data = _gameData.value
        val elderSlot = data.forgeSlots.find {
            it.buildingId == elderBuildingId && it.discipleId != null
        }

        // 如果没有长老任职，返回 0 加成
        val elderDiscipleId = elderSlot?.discipleId ?: return 0.0

        // 获取长老弟子对象
        val elderDisciple = _disciples.value.find { it.id == elderDiscipleId } ?: return 0.0

        return when (buildingId) {
            "forge" -> {
                val baseline = 80
                val maxBonus = 0.20
                val diff = (elderDisciple.artifactRefining - baseline).coerceAtLeast(0)
                (diff * 0.01).coerceAtMost(maxBonus)
            }
            "alchemy" -> {
                val diff = elderDisciple.pillRefining - 50
                diff * 0.02
            }
            "herbGarden" -> {
                val diff = elderDisciple.spiritPlanting - 50
                diff * 0.02
            }
            else -> 0.0
        }
    }

    // ==================== 长老和弟子综合加成 ====================

    /**
     * 计算长老和亲传弟子的综合加成
     *
     * @param buildingType 建筑类型
     * @return 长老加成数据（包含速度、成功率和产量加成）
     */
    fun calculateElderAndDisciplesBonus(buildingType: String): ElderBonusData {
        val data = _gameData.value
        val (elderId, discipleSlots) = when (buildingType) {
            "spiritMine" -> return ElderBonusData(0.0, 0.0, 0.0)
            "herbGarden" -> data.elderSlots.herbGardenElder to data.elderSlots.herbGardenDisciples
            "alchemy" -> data.elderSlots.alchemyElder to data.elderSlots.alchemyDisciples
            "forge" -> data.elderSlots.forgeElder to data.elderSlots.forgeDisciples
            else -> return ElderBonusData(0.0, 0.0, 0.0)
        }

        val elder = elderId?.let { _disciples.value.find { d -> d.id == it } }
        val disciples = discipleSlots.mapNotNull { slot ->
            slot.discipleId?.let { id -> _disciples.value.find { d -> d.id == id } }
        }

        var yieldBonus = 0.0
        var speedBonus = 0.0
        var successBonus = 0.0

        when (buildingType) {
            "herbGarden" -> {
                // 灵药园：受灵植属性影响
                // 长老：以50为基准，每高5点增加1%成熟速度，每低5点减少1%成熟速度
                elder?.let { e ->
                    val spiritPlantingDiff = e.spiritPlanting - 50
                    speedBonus += (spiritPlantingDiff / 5.0) * 0.01
                }
                // 亲传弟子：以50为基准，每高10点增加1%成熟速度，每低10点减少1%成熟速度
                disciples.forEach { d ->
                    val spiritPlantingDiff = d.spiritPlanting - 50
                    speedBonus += (spiritPlantingDiff / 10.0) * 0.01
                }
            }
            "alchemy" -> {
                // 炼丹房：受炼丹属性影响
                // 长老：以50为基准，每高5点增加1%炼制速度，每低5点减少1%炼制速度；每多5点增加1%成功率
                elder?.let { e ->
                    val pillRefiningDiff = e.pillRefining - 50
                    speedBonus += (pillRefiningDiff / 5.0) * 0.01
                    successBonus += (pillRefiningDiff / 5.0) * 0.01
                }
                // 亲传弟子：以50为基准，每高10点增加1%炼制速度，每低10点减少1%炼制速度；每多10点增加1%成功率
                disciples.forEach { d ->
                    val pillRefiningDiff = d.pillRefining - 50
                    speedBonus += (pillRefiningDiff / 10.0) * 0.01
                    successBonus += (pillRefiningDiff / 10.0) * 0.01
                }
            }
            "forge" -> {
                val elderBaseline = 80
                val discipleBaseline = 80
                val maxElderSuccessBonus = 0.20

                elder?.let { e ->
                    val diff = (e.artifactRefining - elderBaseline).coerceAtLeast(0)
                    successBonus += (diff * 0.01).coerceAtMost(maxElderSuccessBonus)
                }
                disciples.forEach { d ->
                    val diff = (d.artifactRefining - discipleBaseline).coerceAtLeast(0)
                    speedBonus += diff * 0.01
                }
            }
        }

        return ElderBonusData(speedBonus, successBonus, yieldBonus)
    }
}
