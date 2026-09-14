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
     * 任务系统分区 RNG 解析（**无自持状态**）。
     *
     * 历史实现为 `DeterministicRng.fromSeed(System.nanoTime())` 惰性单例——
     * 非托管、非确定性（违背确定性 RNG 规范）；其后收敛于
     * [GameRngManager] 的 [RngPartition.MISSION] 分区（存档 rngStates 8 号键，
     * 读档恢复后任务随机序列可重放），但管理器经 object 级字段注入。
     *
     * **本 object 是进程级单例**：双引擎同进程场景（跨语言对拍夹具、多存档
     * 预览）下后构造者会覆写前者，一侧的月变于是消费另一侧的分区状态——
     * 实测 `DiffAuthoritativeTickTest` 第 9 旬 `availableMissions` 1 vs 4
     *（C++ 与 Kotlin 各多消费一次 MISSION 分区）。
     *
     * 现改为**形参必传**：调用方各自透传自己持有的 [GameRngManager]
     *（生产 = [com.xianxia.sect.core.engine.service.CultivationEventProcessor]
     * 构造注入的那个；测试 = 各自夹具实例）。消除 object 级可变状态后，
     * 隔离性由**构造期依赖**保证，不再依赖"初始化顺序恰好正确"。
     */
    private fun rngOf(rngManager: GameRngManager): DeterministicRng =
        rngManager.getRng(RngPartition.MISSION)

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
        currentMonth: Int,
        rngManager: GameRngManager
    ): MonthlyRefreshResult {
        val newMissions = mutableListOf<Mission>()

        if (currentMonth % REFRESH_INTERVAL_MONTHS == 0) {
            val rng = rngOf(rngManager)
            val refreshCount = rng.nextInt(MAX_REFRESH_COUNT + 1)
            val weightedPool = buildWeightedPool()
            repeat(refreshCount) {
                val template = weightedRandom(weightedPool, rng)
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
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap(),
        // W4-C 随机源收敛：MISSION 分区由本函数经 rngOf 自取、ENEMY_GEN 分区
        // （人形敌人生成）经 rngManager 透传至 EnemyGenerator——顶层可变
        // enemyGenRngManager 已摘除，调用方只透传自己持有的 GameRngManager
        rngManager: GameRngManager
    ): MissionResult {
        val rng = rngOf(rngManager)
        return when (activeMission.missionType) {
            MissionType.NO_COMBAT -> processNoCombatMission(activeMission, rng)
            MissionType.COMBAT_REQUIRED -> processCombatRequiredMission(
                activeMission, disciples, equipmentMap, manualMap, manualProficiencies,
                battleSystem, bloodRefinementMap, rngManager
            )
            MissionType.COMBAT_RANDOM -> processCombatRandomMission(
                activeMission, disciples, equipmentMap, manualMap, manualProficiencies,
                battleSystem, bloodRefinementMap, rngManager
            )
        }
    }

    private fun processNoCombatMission(activeMission: ActiveMission, rng: DeterministicRng): MissionResult {
        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards, rng)
        val materials = generateMaterials(rewards, rng)
        val pills = generatePills(rewards, rng)

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
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap(),
        rngManager: GameRngManager
    ): MissionResult {
        val rng = rngOf(rngManager)
        val battleResult = executeMissionBattle(
            activeMission, disciples, equipmentMap, manualMap, manualProficiencies,
            battleSystem, bloodRefinementMap, rngManager
        ) ?: return MissionResult(victory = false)

        if (!battleResult.victory) {
            return MissionResult(
                battleResult = battleResult,
                combatTriggered = true,
                victory = false
            )
        }

        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards, rng)
        val materials = generateMaterials(rewards, rng)
        val pills = generatePills(rewards, rng)
        val equipmentStacks = generateEquipment(rewards, rng)
        val manualStacks = generateManuals(rewards, rng)

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
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap(),
        rngManager: GameRngManager
    ): MissionResult {
        val rng = rngOf(rngManager)
        val triggered = rng.nextDouble() < activeMission.triggerChance

        if (!triggered) {
            val rewards = activeMission.rewards
            val baseSpiritStones = rewards.baseSpiritStones
            val baseMaterials = generateBaseMaterials(rewards, rng)

            return MissionResult(
                spiritStones = baseSpiritStones,
                materials = baseMaterials,
                combatTriggered = false,
                victory = true
            )
        }

        val battleResult = executeMissionBattle(
            activeMission, disciples, equipmentMap, manualMap, manualProficiencies,
            battleSystem, bloodRefinementMap, rngManager
        ) ?: return MissionResult(combatTriggered = true, victory = false)

        if (!battleResult.victory) {
            return MissionResult(
                battleResult = battleResult,
                combatTriggered = true,
                victory = false
            )
        }

        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards, rng)
        val materials = generateMaterials(rewards, rng)
        val pills = generatePills(rewards, rng)
        val equipmentStacks = generateEquipment(rewards, rng)
        val manualStacks = generateManuals(rewards, rng)

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
        bloodRefinementMap: Map<String, com.xianxia.sect.core.model.BloodRefinementPctTotal> = emptyMap(),
        rngManager: GameRngManager
    ): BattleSystemResult? {
        if (battleSystem == null) return null
        val rng = rngOf(rngManager)

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
                val enemies = EnemyGenerator.generateHumanEnemies(realmMin, realmMax, humanCount, rngManager)
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
