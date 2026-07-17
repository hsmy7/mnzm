package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AI 宗门妖兽攻击处理器。
 *
 * 每月判断活跃妖兽是否会遭受附近 AI 宗门的攻击。
 * 仅在 AI 宗门战力明显超过妖兽时才可能进攻，攻击胜利后不分配任何奖励。
 *
 * 此处理器在 [stateStore.update] 事务内调用，直接修改 [MutableGameState]。
 * 不涉及玩家宗门——玩家宗门的妖兽攻击处理由 [BeastAttackDetector] + [PendingBeastAttack] 系统负责。
 */
@Singleton
class AISectBeastAttackProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val rngManager: GameRngManager,
    private val encounterBattleService: EncounterBattleService
) {
    /**
     * 月度入口：遍历所有活跃妖兽，判断附近 AI 宗门是否会进攻。
     *
     * 处理流程：
     * 1. 过滤未击败、未过期的 BEAST 类型关卡
     * 2. 每个妖兽计算到所有世界宗门的欧氏距离，取最近 3 个作为候选池
     * 3. 对候选池宗门逐个判定是否进攻，跳过玩家宗门（由 PendingBeastAttack 系统处理），
     *    取前 2 个符合条件的 AI 宗门；若前 2 名含玩家且玩家不进攻，则递补第 3 名
     * 4. 汇总进攻者并执行战斗
     *
     * @param state [stateStore.update] 事务内的可变状态
     * @param year  当前游戏年
     * @param month 当前游戏月
     */
    fun processMonthly(state: MutableGameState, year: Int, month: Int) {
        if (!ManualDatabase.isInitialized) return  // 启动时序异常时静默跳过

        val gd = state.gameData
        val activeBeasts = gd.worldLevels.filter { level ->
            level.type == LevelType.BEAST && !level.defeated && !level.checkExpired(year, month)
        }
        if (activeBeasts.isEmpty()) return

        val rng = rngManager.getRng(RngPartition.EXPLORATION)
        val absoluteMonth = year * 12 + month

        for (beast in activeBeasts) {
            // Step a: 计算所有宗门到妖兽的欧氏距离
            val sectDistances = gd.worldMapSects.map { sect ->
                val dx = beast.x - sect.x
                val dy = beast.y - sect.y
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                sect to dist
            }

            // Step b: 按距离升序排序，取前 3 个作为候选池
            val candidates = sectDistances
                .sortedBy { it.second }
                .take(3)

            // Step c: 从候选池中筛选实际进攻的AI宗门
            //   - 若玩家宗门占前2名且不进攻，递补第3近的AI宗门
            //   - 若判定通过的AI不足2个，不再递补
            val aiAttackers = mutableListOf<WorldSect>()

            for ((sect, _) in candidates) {
                if (aiAttackers.size >= 2) break

                if (sect.isPlayerSect || sect.isPlayerOccupied) {
                    // 玩家宗门：走既有 PendingBeastAttack 系统，此处理器不处理
                    continue
                }

                // AI 宗门冷却检查
                val cooldown = state.gameData.aiSectBeastSkipCooldowns[sect.id] ?: 0
                if (cooldown >= absoluteMonth) continue

                // AI 弟子存活检查
                val aiDisciples = state.gameData.aiSectDisciples[sect.id] ?: continue
                val aliveDisciples = aiDisciples.filter { it.isAlive }
                if (aliveDisciples.size < GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK) continue

                // 计算战力和概率
                val aiPower = AISectAttackManager.calculatePowerScore(aliveDisciples)
                val beastPower = SectCombatPowerCalculator.calculateBeastCombatPower(
                    maxHp = beast.beastMaxHp,
                    physicalAttack = beast.beastPhysicalAttack,
                    magicAttack = beast.beastMagicAttack,
                    physicalDefense = beast.beastPhysicalDefense,
                    magicDefense = beast.beastMagicDefense,
                    speed = beast.beastSpeed
                )

                if (beastPower <= 0) {
                    // 妖兽战力为0，视为可轻松击败
                    aiAttackers.add(sect)
                    continue
                }

                if (aiPower <= beastPower) {
                    // 战力不足，记录冷却并跳过
                    recordSkipCooldown(state, sect.id, absoluteMonth)
                    continue
                }

                // 概率 = min((aiPower / beastPower - 1.0) * 0.3 + 0.3, 0.9)
                val prob = min((aiPower.toDouble() / beastPower.toDouble() - 1.0) * 0.3 + 0.3, 0.9)
                if (rng.nextDouble() < prob) {
                    aiAttackers.add(sect)
                } else {
                    recordSkipCooldown(state, sect.id, absoluteMonth)
                }
            }

            // Step d: 处理进攻者
            processAttackers(state, aiAttackers, beast, year)
        }

        // 清理超过12个月的冷却记录
        val cleanedCooldowns = state.gameData.aiSectBeastSkipCooldowns.filter { (_, value) ->
            value >= absoluteMonth - 12
        }
        if (cleanedCooldowns.size < state.gameData.aiSectBeastSkipCooldowns.size) {
            state.gameData = state.gameData.copy(aiSectBeastSkipCooldowns = cleanedCooldowns)
        }
    }

    // ── 内部方法 ───────────────────────────────────────────────

    /**
     * 处理进攻者列表。
     *
     * @param state   可变状态
     * @param attackers 参与进攻的 AI 宗门列表
     * @param beast   妖兽关卡
     * @param year    当前年份
     */
    private fun processAttackers(
        state: MutableGameState,
        attackers: List<WorldSect>,
        beast: WorldLevel,
        year: Int
    ) {
        when (attackers.size) {
            0 -> return
            1 -> {
                executeAIVersusBeast(state, attackers[0], beast, year)
            }
            else -> {
                // 2 个或更多 AI 宗门同时进攻：合并队伍后执行一次遭遇战
                executeAIEncounterBattle(state, attackers, beast, year)
            }
        }
    }

    /**
     * 单个 AI 宗门 vs 妖兽战斗。
     *
     * 创建战斗、执行、标记击败（无奖励）、处理死亡。
     */
    private fun executeAIVersusBeast(
        state: MutableGameState,
        aiSect: WorldSect,
        beast: WorldLevel,
        year: Int
    ) {
        val disciples = state.gameData.aiSectDisciples[aiSect.id]
            ?.filter { it.isAlive }
            ?.take(GameConfig.AI.TEAM_SIZE) ?: return
        if (disciples.isEmpty()) return

        val battle = createAIBattle(disciples, beast)
        val result = battleSystem.executeBattle(battle)

        if (result.victory) {
            markBeastDefeated(state, beast.id)
        }

        handleAIDeaths(state, aiSect.id, result, year)
    }

    /**
     * 多个 AI 宗门遭遇战：AI vs AI → 胜者 vs 妖兽。
     *
     * 取前 2 个 AI 宗门，各自组建队伍打 PVP，
     * 胜者的幸存弟子再与妖兽战斗。
     */
    private fun executeAIEncounterBattle(
        state: MutableGameState,
        aiSects: List<WorldSect>,
        beast: WorldLevel,
        year: Int
    ) {
        val sects = aiSects.take(2)
        if (sects.size < 2) {
            // 不足 2 个时退化到单 AI 打妖兽
            if (sects.size == 1) executeAIVersusBeast(state, sects[0], beast, year)
            return
        }

        val teamA = state.gameData.aiSectDisciples[sects[0].id]
            ?.filter { it.isAlive }
            ?.take(GameConfig.AI.TEAM_SIZE) ?: return
        val teamB = state.gameData.aiSectDisciples[sects[1].id]
            ?.filter { it.isAlive }
            ?.take(GameConfig.AI.TEAM_SIZE) ?: return

        // Phase 1: AI vs AI 遭遇战
        val encBattle = battleSystem.createBattle(
            disciples = teamA,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = beast.realm,
            beastCount = 0,
            beastPreGenStats = null
        )
        val encResult = battleSystem.executeBattle(encBattle)

        // 处理双方死亡
        val encDeadIds = encBattle.team.filter { it.isDead }.map { it.id }.toSet()
        val encEnemyDeadIds = encBattle.beasts.filter { it.isDead }.map { it.id }.toSet()
        handleAIDeaths(state, sects[0].id, encResult, year)
        for (sect in mutableListOf(sects[1])) {
            val roster = state.gameData.aiSectDisciples[sect.id]?.toMutableList() ?: continue
            val updated = roster.map { d ->
                if (d.id in encEnemyDeadIds) d.copy(isAlive = false, status = DiscipleStatus.DEAD)
                else d
            }
            state.gameData = state.gameData.copy(
                aiSectDisciples = state.gameData.aiSectDisciples + (sect.id to updated)
            )
        }

        // Phase 2: 胜者 vs 妖兽
        val winnerSect: WorldSect
        val winnerSurvivors: List<Disciple>
        if (encResult.victory) {
            // attacker (teamA = sects[0]) 获胜
            winnerSect = sects[0]
            winnerSurvivors = teamA.filter { it.id !in encDeadIds }
        } else {
            // beast (代表 teamB = sects[1]) 获胜
            winnerSect = sects[1]
            winnerSurvivors = teamB
        }
        if (winnerSurvivors.isEmpty()) return

        val beastBattle = createAIBattle(winnerSurvivors, beast)
        val beastResult = battleSystem.executeBattle(beastBattle)

        if (beastResult.victory) {
            markBeastDefeated(state, beast.id)
        }

        // 处理 Phase 2 的 AI 死亡
        handleAIDeaths(state, winnerSect.id, beastResult, year)
    }

    /**
     * 处理 AI 宗门弟子死亡。
     *
     * 注意：AI 弟子存储在 [GameData.aiSectDisciples] 中，不是 [DiscipleTables]。
     * 不要调用 [DiscipleDeathHandler.markDead] （那是对玩家弟子表的操作）。
     */
    private fun handleAIDeaths(
        state: MutableGameState,
        aiSectId: String,
        result: BattleSystemResult,
        year: Int
    ) {
        val deadIds = result.battle.team
            .filter { it.isDead }
            .map { it.id }
            .toSet()
        if (deadIds.isEmpty()) return

        val updatedDisciples = state.gameData.aiSectDisciples[aiSectId]?.map { d ->
            if (d.id in deadIds) d.copy(
                isAlive = false,
                status = DiscipleStatus.DEAD
            ) else d
        } ?: return

        state.gameData = state.gameData.copy(
            aiSectDisciples = state.gameData.aiSectDisciples + (aiSectId to updatedDisciples)
        )
    }

    /**
     * 记录 AI 宗门跳过冷却到当前绝对月份（年×12+月）。
     * 冷却粒度从年份改为月份，防止一次失败全年免疫。
     */
    private fun recordSkipCooldown(state: MutableGameState, sectId: String, absoluteMonth: Int) {
        state.gameData = state.gameData.copy(
            aiSectBeastSkipCooldowns = state.gameData.aiSectBeastSkipCooldowns + (sectId to absoluteMonth)
        )
    }

    /**
     * 标记妖兽关卡为已击败。
     */
    private fun markBeastDefeated(state: MutableGameState, beastId: String) {
        val updatedLevels = state.gameData.worldLevels.map {
            if (it.id == beastId) it.copy(defeated = true) else it
        }
        state.gameData = state.gameData.copy(worldLevels = updatedLevels)
    }

    /**
     * 为 AI 弟子创建战斗。
     *
     * AI 弟子不含真实装备/功法数据，使用 [AISectDiscipleManager.generateBattleItems]
     * 按境界生成模拟装备和功法，确保战斗有合理的数值表现。
     */
    private fun createAIBattle(disciples: List<Disciple>, beast: WorldLevel): Battle {
        if (!ManualDatabase.isInitialized) {
            // 启动时序异常时不应继续，但 processMonthly 入口已有守卫
            throw IllegalStateException(
                "ManualDatabase not initialized when creating AI vs beast battle"
            )
        }

        val equipmentMap = mutableMapOf<String, EquipmentInstance>()
        val manualMap = mutableMapOf<String, ManualInstance>()
        val proficiencies = mutableMapOf<String, Map<String, ManualProficiencyData>>()
        val modifiedDisciples = mutableListOf<Disciple>()

        for (disciple in disciples) {
            val items = AISectDiscipleManager.generateBattleItems(disciple)

            // 提取装备 ID
            val weaponId = items.equipments
                .firstOrNull { it.second == EquipmentSlot.WEAPON }?.first ?: ""
            val armorId = items.equipments
                .firstOrNull { it.second == EquipmentSlot.ARMOR }?.first ?: ""
            val bootsId = items.equipments
                .firstOrNull { it.second == EquipmentSlot.BOOTS }?.first ?: ""
            val accessoryId = items.equipments
                .firstOrNull { it.second == EquipmentSlot.ACCESSORY }?.first ?: ""

            // 构建装备映射
            buildEquipmentEntry(equipmentMap, weaponId, items.weaponNurture)
            buildEquipmentEntry(equipmentMap, armorId, items.armorNurture)
            buildEquipmentEntry(equipmentMap, bootsId, items.bootsNurture)
            buildEquipmentEntry(equipmentMap, accessoryId, items.accessoryNurture)

            // 构建功法映射和熟练度
            val manualIds = items.manuals.map { it.first }
            val manualMasteries = items.manuals.toMap()

            for (mId in manualIds) {
                if (mId !in manualMap) {
                    val template = ManualDatabase.getById(mId) ?: continue
                    manualMap[mId] = ManualDatabase.createFromTemplate(template)
                        .toInstance(id = mId)
                }
            }

            val discipleProfs = manualIds.associateWith { mId ->
                val mastery = manualMasteries[mId] ?: 0
                val manual = ManualDatabase.getById(mId)
                val masteryLevel = if (manual != null) {
                    ManualProficiencySystem.MasteryLevel.fromProficiency(mastery.toDouble()).level
                } else 0
                val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
                ManualProficiencyData(
                    manualId = mId,
                    proficiency = mastery.toDouble().coerceAtMost(maxProf.toDouble()),
                    maxProficiency = maxProf,
                    masteryLevel = masteryLevel
                )
            }
            proficiencies[disciple.id] = discipleProfs

            // 创建携带生成装备/功法的副本弟子
            modifiedDisciples.add(
                disciple.copy(
                    manualIds = manualIds,
                    manualMasteries = manualMasteries,
                    equipment = disciple.equipment.copy(
                        weaponId = weaponId,
                        armorId = armorId,
                        bootsId = bootsId,
                        accessoryId = accessoryId,
                        weaponNurture = items.weaponNurture,
                        armorNurture = items.armorNurture,
                        bootsNurture = items.bootsNurture,
                        accessoryNurture = items.accessoryNurture
                    )
                )
            )
        }

        return battleSystem.createBattle(
            disciples = modifiedDisciples,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = beast.realm,
            beastCount = beast.count,
            beastPreGenStats = BattleSystem.BeastPreGenStats(
                maxHp = beast.beastMaxHp,
                maxMp = beast.beastMaxMp,
                physicalAttack = beast.beastPhysicalAttack,
                magicAttack = beast.beastMagicAttack,
                physicalDefense = beast.beastPhysicalDefense,
                magicDefense = beast.beastMagicDefense,
                speed = beast.beastSpeed
            ),
            manualProficiencies = proficiencies
        )
    }

    /**
     * 向装备映射中添加单件装备条目（如已存在则跳过）。
     */
    private fun buildEquipmentEntry(
        equipmentMap: MutableMap<String, EquipmentInstance>,
        eqId: String,
        nurture: EquipmentNurtureData
    ) {
        if (eqId.isEmpty() || eqId in equipmentMap) return
        val template = EquipmentDatabase.getById(eqId) ?: return
        var instance = EquipmentDatabase.createFromTemplate(template).toInstance(id = eqId)
        if (nurture.equipmentId == eqId) {
            instance = instance.copy(
                nurtureLevel = nurture.nurtureLevel,
                nurtureProgress = nurture.nurtureProgress
            )
        }
        equipmentMap[eqId] = instance
    }
}
