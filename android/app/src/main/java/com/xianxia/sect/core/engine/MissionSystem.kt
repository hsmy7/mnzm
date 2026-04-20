@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import kotlin.random.Random

object MissionSystem {
    const val EXPIRY_MONTHS = 3
    const val REFRESH_INTERVAL_MONTHS = 3
    const val MAX_REFRESH_COUNT = 5

    data class MissionResult(
        val spiritStones: Int = 0,
        val materials: List<Material> = emptyList()
    )

    data class ValidationResult(
        val valid: Boolean,
        val errorMessage: String? = null
    )

    data class MonthlyRefreshResult(
        val newMissions: List<Mission>,
        val cleanedMissions: List<Mission>
    )

    fun processMonthlyRefresh(
        existingMissions: List<Mission>,
        currentYear: Int,
        currentMonth: Int
    ): MonthlyRefreshResult {
        val newMissions = mutableListOf<Mission>()

        if (currentMonth % REFRESH_INTERVAL_MONTHS == 0) {
            val refreshCount = Random.nextInt(0, MAX_REFRESH_COUNT + 1)
            val pool = MissionTemplate.entries
            repeat(refreshCount) {
                val template = pool.random()
                newMissions.add(createMission(template, currentYear, currentMonth))
            }
        }

        val afterClean = cleanExpiredMissions(existingMissions, currentYear, currentMonth)

        return MonthlyRefreshResult(
            newMissions = newMissions,
            cleanedMissions = afterClean + newMissions
        )
    }

    private fun createMission(
        template: MissionTemplate,
        year: Int = 1,
        month: Int = 1
    ): Mission {
        val difficulty = template.difficulty
        val rewards = createRewardConfig(template)

        return Mission(
            template = template,
            name = "${difficulty.displayName}${template.displayName}",
            description = template.description,
            difficulty = difficulty,
            duration = template.duration,
            rewards = rewards,
            createdYear = year,
            createdMonth = month
        )
    }

    private fun createRewardConfig(template: MissionTemplate): MissionRewardConfig {
        return when (template) {
            MissionTemplate.ESCORT -> MissionRewardConfig(
                spiritStones = 600,
                spiritStonesMax = 0
            )
            MissionTemplate.SUPPRESS_BEASTS -> MissionRewardConfig(
                spiritStones = 0,
                spiritStonesMax = 0,
                materialCountMin = 10,
                materialCountMax = 20,
                materialMinRarity = 1,
                materialMaxRarity = 2
            )
            MissionTemplate.SUPPRESS_BEASTS_NORMAL -> MissionRewardConfig(
                spiritStones = 0,
                spiritStonesMax = 0,
                materialCountMin = 10,
                materialCountMax = 20,
                materialMinRarity = 2,
                materialMaxRarity = 3
            )
        }
    }

    fun validateDisciplesForMission(
        mission: Mission,
        disciples: List<Disciple>
    ): ValidationResult {
        if (disciples.size != mission.memberCount) {
            return ValidationResult(false, "队伍需要${mission.memberCount}名弟子")
        }

        val allowedPositions = mission.difficulty.allowedPositions

        for (disciple in disciples) {
            if (disciple.status != DiscipleStatus.IDLE) {
                return ValidationResult(false, "弟子${disciple.name}状态不允许")
            }

            val disciplePosition = if (disciple.discipleType == "outer") "外门弟子" else "内门弟子"
            if (disciplePosition !in allowedPositions) {
                return ValidationResult(false, "弟子${disciple.name}职务不符合，需要${allowedPositions.joinToString("/")}")
            }
        }

        return ValidationResult(true)
    }

    fun createActiveMission(
        mission: Mission,
        disciples: List<Disciple>,
        currentYear: Int,
        currentMonth: Int
    ): ActiveMission {
        require(disciples.size == mission.memberCount) { "任务需要 ${mission.memberCount} 名弟子，实际传入 ${disciples.size}" }

        return ActiveMission(
            missionId = mission.id,
            missionName = mission.name,
            template = mission.template,
            difficulty = mission.difficulty,
            discipleIds = disciples.map { it.id },
            discipleNames = disciples.map { it.name },
            discipleRealms = disciples.map { it.realmNameOnly },
            startYear = currentYear,
            startMonth = currentMonth,
            duration = mission.duration,
            rewards = mission.rewards
        )
    }

    fun processMissionCompletion(
        activeMission: ActiveMission,
        disciples: List<Disciple>
    ): MissionResult {
        val rewards = activeMission.rewards
        val spiritStones = if (rewards.spiritStonesMax > 0) {
            Random.nextInt(rewards.spiritStones, rewards.spiritStonesMax + 1)
        } else {
            rewards.spiritStones
        }
        val materials = generateMaterials(rewards)

        return MissionResult(
            spiritStones = spiritStones,
            materials = materials
        )
    }

    private fun generateMaterials(rewards: MissionRewardConfig): List<Material> {
        if (rewards.materialCountMin <= 0) return emptyList()

        val count = Random.nextInt(rewards.materialCountMin, rewards.materialCountMax + 1)
        val materials = mutableListOf<Material>()

        repeat(count) {
            val eligibleMaterials = BeastMaterialDatabase.getAllMaterials()
                .filter { it.rarity in rewards.materialMinRarity..rewards.materialMaxRarity }
            if (eligibleMaterials.isNotEmpty()) {
                val template = eligibleMaterials.random()
                materials.add(ItemDatabase.createMaterialFromTemplate(
                    ItemDatabase.MaterialTemplate(
                        id = template.id,
                        name = template.name,
                        category = template.materialCategory,
                        rarity = template.rarity,
                        description = template.description,
                        price = template.price
                    )
                ))
            }
        }

        return materials
    }

    fun cleanExpiredMissions(
        missions: List<Mission>,
        currentYear: Int,
        currentMonth: Int
    ): List<Mission> {
        return missions.filter { mission ->
            !isExpired(mission, currentYear, currentMonth)
        }
    }

    fun isExpired(mission: Mission, currentYear: Int, currentMonth: Int): Boolean {
        val yearDiff = currentYear - mission.createdYear
        val monthDiff = currentMonth - mission.createdMonth
        val totalMonths = yearDiff * 12 + monthDiff
        return totalMonths >= EXPIRY_MONTHS
    }
}
