package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EnemyType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.MissionType
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleExecutionRouter
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.EnemyGenerator
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition

object MissionSystem {
    /**
     * 任务系统分区 RNG。
     *
     * 历史实现为 `DeterministicRng.fromSeed(System.nanoTime())` 惰性单例——
     * 非托管、非确定性（违背确定性 RNG 规范）；现收敛于
     * [GameRngManager] 的 [RngPartition.MISSION] 分区（存档 rngStates 8 号键，
     * 读档恢复后任务随机序列可重放）。
     *
     * 注入时机：生产经 [CultivationEventProcessor]（@Singleton，唯一月变/任务
     * 编排入口）构造时 [initialize]；测试须显式注入固定种子实例，否则访问
     * [rng] 抛 IllegalStateException（拒绝静默非确定性降级）。
     */
    @Volatile
    private var rngManager: GameRngManager? = null

    /** 注入 RNG 管理器（幂等；生产经 CultivationEventProcessor 构造，测试注入固定种子实例） */
    fun initialize(manager: GameRngManager) {
        rngManager = manager
    }

    /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
    internal val rng: DeterministicRng
        get() = (rngManager ?: error(
            "MissionSystem 未注入 GameRngManager——生产经 CultivationEventProcessor 构造注入，" +
                "测试须调用 MissionSystem.initialize() 注入固定种子实例"
        )).getRng(RngPartition.MISSION)

    const val REFRESH_INTERVAL_MONTHS = 3
    const val MAX_REFRESH_COUNT = 6

    data class MissionResult(
        val spiritStones: Int = 0,
        val materials: List<Material> = emptyList(),
        val pills: List<Pill> = emptyList(),
        val equipmentStacks: List<com.xianxia.sect.core.model.EquipmentStack> = emptyList(),
        val manualStacks: List<com.xianxia.sect.core.model.ManualStack> = emptyList(),
        val battleResult: BattleSystemResult? = null,
        val combatTriggered: Boolean = false,
        val victory: Boolean = true
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
            val refreshCount = rng.nextInt(MAX_REFRESH_COUNT + 1)
            val weightedPool = buildWeightedPool()
            repeat(refreshCount) {
                val template = weightedRandom(weightedPool)
                newMissions.add(createMission(template, currentYear, currentMonth))
            }
        }

        val afterClean = if (currentMonth % REFRESH_INTERVAL_MONTHS == 0) {
            emptyList()
        } else {
            existingMissions
        }

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
            missionType = template.missionType,
            enemyType = template.enemyType,
            triggerChance = template.triggerChance,
            createdYear = year,
            createdMonth = month
        )
    }

    fun validateDisciplesForMission(
        mission: Mission,
        disciples: List<Disciple>
    ): ValidationResult {
        if (disciples.size != mission.memberCount) {
            return ValidationResult(false, "队伍需要${mission.memberCount}名弟子")
        }

        for (disciple in disciples) {
            if (disciple.status != DiscipleStatus.IDLE) {
                return ValidationResult(false, "弟子${disciple.name}状态不允许")
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
        // 弟子 id 不变量：重复或空 id 会导致 MissionHallDialog 网格 LazyGrid 重复 key 崩溃（Bugly #5079/#3091）
        require(disciples.all { it.id.isNotBlank() }) {
            "任务弟子存在空 id: ${disciples.filter { it.id.isBlank() }.map { it.name }}"
        }
        require(disciples.map { it.id }.distinct().size == disciples.size) {
            "任务弟子存在重复 id: ${disciples.groupBy { it.id }.filter { it.value.size > 1 }.keys}"
        }

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
            rewards = mission.rewards,
            missionType = mission.missionType,
            enemyType = mission.enemyType,
            triggerChance = mission.triggerChance
        )
    }

    fun processMissionCompletion(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance> = emptyMap(),
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>> = emptyMap(),
        battleSystem: BattleSystem? = null,
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap()
    ): MissionResult {
        return when (activeMission.missionType) {
            MissionType.NO_COMBAT -> processNoCombatMission(activeMission)
            MissionType.COMBAT_REQUIRED -> processCombatRequiredMission(
                activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem, bloodRefinementMap
            )
            MissionType.COMBAT_RANDOM -> processCombatRandomMission(
                activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem, bloodRefinementMap
            )
        }
    }

    private fun processNoCombatMission(activeMission: ActiveMission): MissionResult {
        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards)
        val materials = generateMaterials(rewards)
        val pills = generatePills(rewards)

        return MissionResult(
            spiritStones = spiritStones,
            materials = materials,
            pills = pills,
            victory = true
        )
    }

    private fun processCombatRequiredMission(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        battleSystem: BattleSystem?,
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap()
    ): MissionResult {
        val battleResult = executeMissionBattle(
            activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem, bloodRefinementMap
        ) ?: return MissionResult(victory = false)

        if (!battleResult.victory) {
            return MissionResult(
                battleResult = battleResult,
                combatTriggered = true,
                victory = false
            )
        }

        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards)
        val materials = generateMaterials(rewards)
        val pills = generatePills(rewards)
        val equipmentStacks = generateEquipment(rewards)
        val manualStacks = generateManuals(rewards)

        return MissionResult(
            spiritStones = spiritStones,
            materials = materials,
            pills = pills,
            equipmentStacks = equipmentStacks,
            manualStacks = manualStacks,
            battleResult = battleResult,
            combatTriggered = true,
            victory = true
        )
    }

    private fun processCombatRandomMission(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        battleSystem: BattleSystem?,
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap()
    ): MissionResult {
        val triggered = rng.nextDouble() < activeMission.triggerChance

        if (!triggered) {
            val rewards = activeMission.rewards
            val baseSpiritStones = rewards.baseSpiritStones
            val baseMaterials = generateBaseMaterials(rewards)

            return MissionResult(
                spiritStones = baseSpiritStones,
                materials = baseMaterials,
                combatTriggered = false,
                victory = true
            )
        }

        val battleResult = executeMissionBattle(
            activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem, bloodRefinementMap
        ) ?: return MissionResult(combatTriggered = true, victory = false)

        if (!battleResult.victory) {
            return MissionResult(
                battleResult = battleResult,
                combatTriggered = true,
                victory = false
            )
        }

        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards)
        val materials = generateMaterials(rewards)
        val pills = generatePills(rewards)
        val equipmentStacks = generateEquipment(rewards)
        val manualStacks = generateManuals(rewards)

        return MissionResult(
            spiritStones = spiritStones,
            materials = materials,
            pills = pills,
            equipmentStacks = equipmentStacks,
            manualStacks = manualStacks,
            battleResult = battleResult,
            combatTriggered = true,
            victory = true
        )
    }

    private fun executeMissionBattle(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        battleSystem: BattleSystem?,
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap()
    ): BattleSystemResult? {
        if (battleSystem == null) return null

        val difficulty = activeMission.difficulty
        val realmMin = difficulty.enemyRealmMin
        val realmMax = difficulty.enemyRealmMax

        return when (activeMission.enemyType) {
            EnemyType.BEAST -> {
                val beastCount = (activeMission.template.beastCountRange.first +
                    activeMission.template.beastCountRange.last) / 2
                val beastRealm = (realmMin + realmMax) / 2
                val battle = battleSystem.createBattle(
                    disciples = disciples,
                    equipmentMap = equipmentMap,
                    manualMap = manualMap,
                    beastLevel = beastRealm,
                    beastCount = beastCount,
                    manualProficiencies = manualProficiencies,
                    bloodRefinementMap = bloodRefinementMap
                )
                // AUTHORITATIVE 下经 C++ 战斗引擎执行（降级回退 Kotlin）
                BattleExecutionRouter.tryExecuteNative(battle)
                    ?: battleSystem.executeBattle(battle)
            }
            EnemyType.HUMAN -> {
                val humanCount = activeMission.template.humanCountRange.first + rng.nextInt(
                    activeMission.template.humanCountRange.last - activeMission.template.humanCountRange.first + 1
                )
                val enemies = EnemyGenerator.generateHumanEnemies(realmMin, realmMax, humanCount)
                val team = disciples.map { disciple ->
                    battleSystem.convertDiscipleToCombatant(
                        disciple, equipmentMap, manualMap, manualProficiencies,
                        com.xianxia.sect.core.CombatantSide.DEFENDER,
                        bloodRefinementPct = bloodRefinementMap[disciple.id]
                    )
                }
                val battle = Battle(
                    team = team,
                    beasts = enemies.map { it.combatant },
                    turn = 0,
                    isFinished = false,
                    winner = null
                )
                // AUTHORITATIVE 下经 C++ 战斗引擎执行（降级回退 Kotlin）
                BattleExecutionRouter.tryExecuteNative(battle)
                    ?: battleSystem.executeBattle(battle)
            }
        }
    }

    internal data class WeightedEntry(
        val template: MissionTemplate,
        val cumulativeWeight: Double
    )

}
