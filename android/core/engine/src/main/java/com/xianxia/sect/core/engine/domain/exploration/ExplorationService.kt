package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.util.sortedByRealmForDefense

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.engine.domain.battle.BattleExecutionRouter
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.exploration.PatrolBattleSystem
import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.baseMagicDefense
import com.xianxia.sect.core.model.baseMp
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.basePhysicalDefense
import com.xianxia.sect.core.model.baseSpeed
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton



/**
 * 探索系统 Facade — 委派具体职责到各子领域系统。
 *
 * 保留的对外接口：
 * - [processMonthlyWorldLevels] — 由 ExplorationTickSystem 调用（含排期妖兽攻击自动执行）
 * - [executeScheduledBeastAttack] — 月度结算内执行排期妖兽攻击（自动防守）
 * - [resolveBeastAttackFight] — 世界地图手动进攻路径（B18-P4：遭遇战分支已删；
 *   原 `manualDefenders` 形参随之删除——唯一消费者 `selectBeastDefenders` 退役后
 *   它是死形参，UI 侧历来只传 beastLevelId）
 * - [consumePendingPatrolResults] — UI 层定时消费
 */
@Singleton
class ExplorationService @Inject constructor(
    internal val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val inventorySystem: InventorySystem,
    private val cultivationService: CultivationService,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val rngManager: GameRngManager,
    private val subSystems: ExplorationSubSystems
) {
    private val worldLevelManager get() = subSystems.worldLevelManager
    private val beastAttackDetector get() = subSystems.beastAttackDetector
    private val patrolBattleSystem get() = subSystems.patrolBattleSystem
    private val lootCalculator get() = subSystems.lootCalculator
    internal val encounterBattleService get() = subSystems.encounterBattleService
    private val deathHandler get() = subSystems.deathHandler

    /** 妖兽防守战斗结果弹窗缓存（resolveBeastFightInternal 写，UI 层读） */
    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "ExplorationService"
    }

    // ── 月度处理（由 ExplorationTickSystem 调用） ───────────────────────────

    /**
     * 月度世界事件处理 — 委派给子领域系统。
     *
     * 0. 执行上月排期的妖兽攻击（自动防守）——弹窗未关闭时此处自动关闭
     * 1. WorldLevelManager — 关卡刷新/过期清理/妖兽移动
     * 2. PatrolBattleSystem — 巡视楼自动攻击（先于检测，避免已击败妖兽产生预警）
     * 3. BeastAttackDetector — 检测妖兽攻击（仅检测巡视楼未击败的剩余妖兽）
     */
    // [已合并 ThrowsCount 理由: 多步骤事务/异常翻译边界：各 throw 对应不同失败路径的领域错误，刻意独立抛出保归因清晰，非疏忽计数超标] // 防御兜底: 异常源跨IO/SDK不可枚举,
    // 降级继续+日志留痕, 非静默吞噬
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun processMonthlyWorldLevels(state: MutableGameState) {
        // 计算玩家存活弟子的平均境界，传给 LevelGenerator 做安全兜底
        val disciples = state.discipleTables.assembleAll()
        val aliveDisciples = disciples.filter { it.isAlive }
        val playerAvgRealm = if (aliveDisciples.isNotEmpty()) {
            aliveDisciples.map { it.realm }.average().toInt()
        } else null

        // Step 0: 执行上月排期的妖兽攻击（自动防守）。
        // 排期妖兽若已被击败/消失（巡视塔、AI 宗门、玩家手动进攻、过期清理），
        // 自动跳过不战斗；全部处理完后清空排期——玩家未关闭预警弹窗时，
        // 排期清空即弹窗自动关闭（游戏时间不暂停，预警为纯通知）。
        executeScheduledBeastAttacks(state)

        // Step 1: 世界关卡惰性管理（纯函数）
        try {
            state.gameData = worldLevelManager.processMonthly(state.gameData, playerAvgRealm)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "processMonthlyWorldLevels: worldLevelManager failed", e)
        }

        // Step 2: 巡视楼自动攻击（先于攻击检测，避免已击败妖兽产生无效预警）
        try {
            patrolBattleSystem.executePatrolRound(state)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "processMonthlyWorldLevels: patrolBattle failed", e)
        }

        // Step 3: 妖兽攻击检测（仅检测巡视楼未击败的剩余妖兽）
        try {
            val attacks = beastAttackDetector.detectAttacks(state.gameData)
            if (attacks.isNotEmpty()) {
                stateStore.setPendingBeastAttacks(attacks)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "processMonthlyWorldLevels: detectAttacks failed", e)
        }
    }

    // ── 巡视塔战斗结果 ─────────────────────────────────────────────────────

    /**
     * 消费未展示的战斗结果弹窗（巡视塔 + 妖兽防守），由 GameEngineCore 每 tick 调用。
     *
     * w3-13 通道关闭配套（§2.80）：清空事务改 updateMirror 非捕获（pendingPatrol
     * BattleResults 已关闭回导——清空为纯 UI 队列消费，C++ 侧无该弹窗概念）；
     * 消费到防守弹窗时由调用方（GameEngineCoreLoopOps）触发基线重建收敛 C++ 侧
     * 残留（迎战线§2.79 导入的条目）。返回 (巡视塔弹窗, 妖兽防守弹窗) 二元组。
     */
    suspend fun consumePendingPatrolResults(): Pair<List<BattleResultUIData>, List<BattleResultUIData>> {
        val patrolResults = patrolBattleSystem.consumePendingPatrolResults()
        val defenseResults = stateStore.gameData.value
            .pendingPatrolBattleResults
        if (defenseResults.isNotEmpty()) {
            stateStore.updateMirror {
                gameData = gameData.copy(
                    pendingPatrolBattleResults = emptyList()
                )
            }
        }
        return Pair(patrolResults, defenseResults)
    }

    // ── 妖兽袭击处理 ──────────────────────────────────────────────────────

    internal fun MutableGameState.resolveBeastFightInternal(
        beastLevelId: String, level: WorldLevel
    ) {
        val gd = gameData
        val targetSect = gd.worldMapSects.find {
            it.isPlayerSect || it.isPlayerOccupied
        } ?: return

        // 自动选择防守弟子：优先巡视塔已分配弟子，其次宗门内其他弟子
        // 排除任务中/思过中/血炼中的弟子，其余均可参战
        var disciples = discipleTables.assembleAll()
        val excludeStatuses = setOf(
            DiscipleStatus.ON_MISSION,
            DiscipleStatus.IN_TEAM,
            DiscipleStatus.REFLECTING,
            DiscipleStatus.GARRISONING,
            DiscipleStatus.REFINING
        )
        val allAvailable = disciples.filter {
            it.isAlive && it.status !in excludeStatuses
        }
        val patrolDiscipleIds = gd.patrolSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()
        val patrolDefenders = allAvailable.filter { it.id in patrolDiscipleIds }
        // 防守选人按大境界优先（realm 升序）、同境界小层降序
        // （realmLayer 降序会把高境界弟子（突破后 layer=1）挤出 8 人防守队）
        val remainingAlive = allAvailable.filter {
            it.id !in patrolDiscipleIds
        }.sortedByRealmForDefense()
        val defenders = (patrolDefenders + remainingAlive).take(8)
        val defenderIds = defenders.map { it.id }.toSet()

        prepareBeastDefenders(defenderIds)

        markBeastDefeated(beastLevelId)

        if (defenders.isEmpty()) {
            handleNoBeastDefenders(level, targetSect, disciples)
            return
        }

        val result = createBeastBattle(defenders, level)
        val (processedDisciples, survivorIds) = processBeastCasualties(
            result, targetSect, disciples
        )
        disciples = processedDisciples

        val allRewards = mutableListOf<BattleRewardItem>()
        if (result.victory) {
            disciples = applyBeastVictoryBonuses(disciples)
            allRewards += collectBeastFightRewards(level, result)
        } else {
            applyBeastDefeatLoot()
        }

        buildBeastDefenseBattleLog(
            result, level, targetSect, survivorIds, allRewards
        )
        finalizeBeastDisciples(disciples)
    }

    // ── 战前准备 ───────────────────────────────────────────────────────────

    internal fun MutableGameState.prepareBeastDefenders(
        garrisonIds: Set<String>
    ) {
        if (garrisonIds.isNotEmpty()) {
            cultivationService.forceSettleDisciplesBeforeBattle(
                this, garrisonIds.toList()
            )
        }
    }

    internal fun MutableGameState.handleNoBeastDefenders(
        level: WorldLevel, targetSect: WorldSect,
        disciples: List<Disciple>
    ) {
        val loot = lootCalculator.computeLootPlan(gameData, this)
        lootCalculator.applyLoot(this, loot)
        battleLogs = (battleLogs + BattleLog(
            year = gameData.gameYear, month = gameData.gameMonth,
            type = BattleType.PVE,
            attackerName = level.beastName.ifEmpty { "妖兽" },
            defenderName = if (targetSect.isPlayerSect) "玩家宗门"
                else targetSect.name,
            result = BattleResult.LOSE,
            details = loot.toDetailString(level.beastName)
        )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        // 妖兽防守结果始终显示，不受 patrolBattleResultPopup 设置影响
        gameData = gameData.copy(
            pendingPatrolBattleResults =
                gameData.pendingPatrolBattleResults +
                BattleResultUIData(
                    battleLogId = "", victory = false,
                    teamMembers = emptyList(),
                    rewards = emptyList(),
                    lootedItems = loot.toRewardItems(),
                    isBeastDefense = true
                )
        )
        discipleTables.replaceAll(disciples)
    }

    // ── 战斗执行 ───────────────────────────────────────────────────────────

    internal fun MutableGameState.createBeastBattle(
        defenders: List<Disciple>, level: WorldLevel
    ): BattleSystemResult {
        val equipMap = equipmentInstances.associateBy { it.id }
        val manMap = manualInstances.associateBy { it.id }
        val profMap = gameData.manualProficiencies.mapValues {
            (_, list) -> list.associateBy { it.manualId }
        }
        val beastPreGenStats = if (level.beastMaxHp > 0) BattleSystem.BeastPreGenStats(
            maxHp = level.beastMaxHp,
            maxMp = level.beastMaxMp,
            physicalAttack = level.beastPhysicalAttack,
            magicAttack = level.beastMagicAttack,
            physicalDefense = level.beastPhysicalDefense,
            magicDefense = level.beastMagicDefense,
            speed = level.beastSpeed,
            realmLayer = level.realmLayer
        ) else null
        val battle = battleSystem.createBattle(
            defenders, equipMap, manMap,
            level.realm, level.count,
            GameConfig.Beast.getType(level.beastType ?: 0).name,
            profMap,
            beastPreGenStats = beastPreGenStats,
            bloodRefinementMap = gameData.bloodRefinementPctTotals
        )
        // 妖兽防守生产经 BattleExecutionRouter 路由（R4.3）：AUTHORITATIVE 生产走
        // C++ 战斗引擎（BATTLE 分区同区同序，与兽战/遭遇战/秘境同一路由契约）；
        // flag 关（OFF kill-switch 投影）/ native 未加载（平台兜底）/ 失败信封
        // → null 回退 Kotlin —— B18 判归：**非灰度回滚臂，不删**（正确性机制）
        return BattleExecutionRouter.tryExecuteNative(battle)
            ?: battleSystem.executeBattle(battle)
    }

    // ── 战后伤亡处理 ───────────────────────────────────────────────────────

    internal fun MutableGameState.applyBeastVictoryBonuses(
        disciples: List<Disciple>
    ): List<Disciple> {
        return disciples.map { d ->
            if (d.isAlive) {
                var m = d.copy(soulPower = d.soulPower + 1)
                if (m.talentIds.any { id ->
                    TalentDatabase.getById(id)?.effects
                        ?.containsKey("winBattleRandomAttrPlus") == true
                }) {
                    val r = rngManager.getRng(RngPartition.BATTLE)
                        .nextInt(17)
                    // 技能属性（0-9）clamp 到基础属性上限（忠诚 100 例外）；战斗属性（10-16）不 clamp
                    if (r <= 9) applyWinSkillAttrGrowth(m, r) else applyWinCombatAttrGrowth(m, r)
                }
                m
            } else d
        }
    }

    // ── 胜利奖励：妖兽材料+灵石 ──────────────────────────────────────────

    internal fun MutableGameState.collectBeastFightRewards(
        level: WorldLevel, result: BattleSystemResult
    ): List<BattleRewardItem> {
        val allRewards = mutableListOf<BattleRewardItem>()
        val beastConfig = GameConfig.Beast.getType(level.beastType ?: 0)
        val tier = GameConfig.Realm.getMaxRarity(level.realm)
        repeat(level.count) {
            repeat(rngManager.getRng(RngPartition.BATTLE).nextInt(3) + 1) {
                val mat = BeastMaterialDatabase
                    .getRandomMaterialByBeastType(beastConfig.name, tier)
                if (mat != null) {
                    val material = Material(
                        id = UUID.randomUUID().toString(),
                        name = mat.name, rarity = mat.rarity,
                        description = mat.description,
                        category = mat.materialCategory, quantity = 1
                    )
                    val addR = inventorySystem.withTrackingSource("beast_raid") { inventorySystem
                        .addMaterial(material) }
                    when (addR) {
                        is DomainResult.Success -> {
                            allRewards.add(BattleRewardItem(
                                itemId = material.id,
                                name = material.name, quantity = 1,
                                rarity = material.rarity,
                                type = "material"
                            ))
                        }
                        is DomainResult.Partial -> {
                            allRewards.add(BattleRewardItem(
                                itemId = material.id,
                                name = material.name, quantity = 1,
                                rarity = material.rarity,
                                type = "material"
                            ))
                            DomainLog.w(TAG, "材料 ${material.name} 溢出 ${addR.overflow} 个")
                        }
                        is DomainResult.Failure -> {
                            DomainLog.w(TAG, "添加材料失败: ${addR.error}")
                        }
                    }
                }
            }
        }

        val sr = result.rewards["spiritStones"] ?: 0
        if (sr > 0) {
            spiritStoneWallet.add(
                this, sr.toLong(),
                SpiritStoneGrade.LOW, SpiritStoneSource.Battle
            )
            allRewards.add(BattleRewardItem(
                name = ItemNames.SPIRIT_STONE, quantity = sr, rarity = 1,
                type = "spiritStones"
            ))
        }

        return allRewards
    }

    // ── 击败战利品 ─────────────────────────────────────────────────────────

    internal fun MutableGameState.applyBeastDefeatLoot() {
        val loot = lootCalculator.computeLootPlan(gameData, this)
        lootCalculator.applyLoot(this, loot)
    }

    // ── 战斗日志+UI结果 ────────────────────────────────────────────────────

    internal fun MutableGameState.buildBeastDefenseBattleLog(
        result: BattleSystemResult, level: WorldLevel,
        targetSect: WorldSect, survivorIds: Set<String>,
        allRewards: List<BattleRewardItem>
    ) {
        val teamMems = result.battle.team.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm,
                realmName = m.realmName, hp = m.hp,
                maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                isAlive = !m.isDead, portraitRes = m.portraitRes
            )
        }
        val enems = result.battle.beasts.map { b ->
            BattleLogEnemy(
                name = b.name, realm = b.realm,
                realmName = b.realmName,
                portraitRes = b.portraitRes
            )
        }
        val rds = result.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a -> BattleLogAction(
                    type = a.type, attacker = a.attacker,
                    attackerType = a.attackerType,
                    target = a.target, damage = a.damage,
                    damageType = a.damageType,
                    isCrit = a.isCrit, isKill = a.isKill,
                    message = a.message
                )}
            )
        }

        battleLogs = (battleLogs + BattleLog(
            year = gameData.gameYear,
            month = gameData.gameMonth, type = BattleType.PVE,
            attackerName = level.beastName.ifEmpty { "妖兽" },
            defenderName = if (targetSect.isPlayerSect) "玩家宗门"
                else targetSect.name,
            result = if (result.victory) BattleResult.WIN
                else BattleResult.LOSE,
            teamMembers = teamMems, enemies = enems,
            rounds = rds,
            turns = result.turnCount,
            teamCasualties = teamMems.count {
                !survivorIds.contains(it.id)
            },
            beastsDefeated = if (result.victory) level.count
                else result.battle.beasts.count { it.isDead },
            details = if (result.victory)
                "成功抵御${level.beastName}袭击"
                else "被${level.beastName}击败，宗门受损"
        )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

        appendPendingBeastDefenseResult(
            result.victory, teamMems, allRewards
        )
    }

    internal fun MutableGameState.appendPendingBeastDefenseResult(
        victory: Boolean, teamMems: List<BattleLogMember>,
        allRewards: List<BattleRewardItem>
    ) {
        // 妖兽防守结果始终显示，不受 patrolBattleResultPopup 设置影响
        val looted = if (victory) emptyList()
            else lootCalculator.computeLootPlan(gameData, this)
                .toRewardItems()
        gameData = gameData.copy(
            pendingPatrolBattleResults =
                gameData.pendingPatrolBattleResults +
                BattleResultUIData(
                    battleLogId = "", victory = victory,
                    teamMembers = teamMems,
                    rewards = allRewards,
                    lootedItems = looted,
                    isBeastDefense = true
                )
        )
    }

    // ── 最终写回 ───────────────────────────────────────────────────────────

    internal fun MutableGameState.finalizeBeastDisciples(
        disciples: List<Disciple>
    ) {
        discipleTables.replaceAll(disciples)
        // 死亡年份由 DiscipleDeathHandler 统一补写（replaceAll 已清空列写入）
        deathHandler.backfillDeathYears(discipleTables, disciples, gameData.gameYear)
        // 年报死亡计数：妖兽防守战死亡仅 map 标记（无统一入口），
        // 本函数每场战斗恰好调用一次，按 isAlive=false 人数直接计数
        val deadCount = disciples.count { !it.isAlive }
        if (deadCount > 0) {
            gameData = gameData.copy(
                annualDeceasedDisciples = gameData.annualDeceasedDisciples + deadCount
            )
        }
    }

    // 掠夺计算见 LootCalculator；死亡标记见 DiscipleDeathHandler；
    // 弟子状态更新见 DiscipleFacade。
}

/**
 * 探索域子领域系统聚合（ExplorationService 构造参数分组）：
 * 世界关卡管理 / 妖兽攻击检测 / 巡视楼战斗 / 掠夺计算 / 遭遇战 / 死亡处理
 * 六个子领域系统按域打包注入，避免 Facade 构造参数逐个透传。
 */
class ExplorationSubSystems @Inject constructor(
    val worldLevelManager: WorldLevelManager,
    val beastAttackDetector: BeastAttackDetector,
    val patrolBattleSystem: PatrolBattleSystem,
    val lootCalculator: LootCalculator,
    val encounterBattleService: EncounterBattleService,
    val deathHandler: DiscipleDeathHandler
)

/** 胜战技能属性 +1：clamp 到基础属性上限（忠诚 100 例外） */
private fun applyWinSkillAttrGrowth(disciple: Disciple, r: Int) {
    val sk = disciple.skills
    when (r) {
        0 -> sk.intelligence =
            minOf(sk.intelligence + 1, GameConfig.Disciple.SKILL_MAX)
        1 -> sk.comprehension =
            minOf(sk.comprehension + 1, GameConfig.Disciple.SKILL_MAX)
        2 -> sk.charm = minOf(sk.charm + 1, GameConfig.Disciple.SKILL_MAX)
        3 -> sk.loyalty =
            minOf(sk.loyalty + 1, GameConfig.Disciple.MAX_LOYALTY)
        4 -> sk.artifactRefining =
            minOf(sk.artifactRefining + 1, GameConfig.Disciple.SKILL_MAX)
        5 -> sk.pillRefining =
            minOf(sk.pillRefining + 1, GameConfig.Disciple.SKILL_MAX)
        6 -> sk.spiritPlanting =
            minOf(sk.spiritPlanting + 1, GameConfig.Disciple.SKILL_MAX)
        7 -> sk.mining = minOf(sk.mining + 1, GameConfig.Disciple.SKILL_MAX)
        8 -> sk.teaching =
            minOf(sk.teaching + 1, GameConfig.Disciple.SKILL_MAX)
        9 -> sk.morality =
            minOf(sk.morality + 1, GameConfig.Disciple.SKILL_MAX)
    }
}

/** 胜战战斗属性 +1：基础战斗属性不 clamp */
private fun applyWinCombatAttrGrowth(disciple: Disciple, r: Int) {
    val cb = disciple.combat
    when (r) {
        10 -> cb.baseHp++; 11 -> cb.baseMp++
        12 -> cb.basePhysicalAttack++
        13 -> cb.baseMagicAttack++
        14 -> cb.basePhysicalDefense++
        15 -> cb.baseMagicDefense++
        16 -> cb.baseSpeed++
    }
}
