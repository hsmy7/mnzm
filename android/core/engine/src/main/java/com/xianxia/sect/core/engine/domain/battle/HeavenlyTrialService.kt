package com.xianxia.sect.core.engine.domain.battle
import com.xianxia.sect.core.util.ItemNames

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.HeavenlyTrialConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.ClearRewardItem
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.HEAVENLY_TRIAL_CLEAR_REWARDS
import com.xianxia.sect.core.model.HeavenlyTrialClearReward
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.TrialEnemyDef
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.rebaselineNativeMirror
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.CancellationException

enum class ActionType { ATTACK, BUFF_ALLY, BUFF_SELF, NORMAL_ATTACK, NONE }

data class EnemyAction(
    val skill: CombatSkill?,
    val target: Combatant?,
    val actionType: ActionType
)

@Singleton
@GameService("HeavenlyTrialService")
class HeavenlyTrialService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val inventorySystem: InventorySystem,
    /**
     * C++ 引擎核心（w3-13 通道关闭配套 §2.80：试炼写面——heavenlyTrialState/
     * 钱包/年度账/集合奖励——领取与通关后经 rebaselineNativeMirror 全量重建基线
     * 回导 C++）。默认 null 仅供测试直构。
     *
     * 经 [javax.inject.Provider] 注入（DiplomacyService 同款惰性破环边）。
     */
    private val gameEngineCoreProvider: javax.inject.Provider<com.xianxia.sect.core.engine.GameEngineCore>? = null,
) {
    /** 基线重建用引擎核心；无 Provider（测试直构）时为 null → 跳过（JVM 语义等价） */
    private val gameEngineCore: com.xianxia.sect.core.engine.GameEngineCore?
        get() = gameEngineCoreProvider?.get()

    fun buildBeastEnemy(levelIndex: Int, def: TrialEnemyDef, index: Int): Combatant {
        // 篡改防御：realm/realmLayer 钳制合法范围——非法层数会产生
        // 0/负属性（Combatant.isDead 开战即亡）或饱和 Int.MAX（打不死）
        val safeRealm = def.realm.coerceIn(0, 9)
        val safeLayer = def.realmLayer.coerceIn(
            1, GameConfig.Realm.get(safeRealm).maxLayers
        )
        val realmStats = GameConfig.Beast.getRealmStats(safeRealm)
        val beastType = GameConfig.Beast.TYPES.find { it.name == def.beastType }
            ?: GameConfig.Beast.TYPES.first()

        val layerMult = 1.0 + (safeLayer - 1) * 0.1

        // 与 LevelGenerator/世界妖兽一致补 ±0.2 方差（加在类型 mod 上）。
        // 确定性派生种子（同 [buildDiscipleEnemy]），预览 = 战斗属性一致。
        val enemyRng = DeterministicRng(enemySeed(levelIndex, def, index))
        val stats = computeBeastStats(
            realmStats = realmStats,
            beastType = beastType,
            layerMult = layerMult,
            enemyRng = enemyRng
        )

        val beastSkills = buildBeastSkills(beastType = beastType)

        val typeIndex = GameConfig.Beast.TYPES.indexOf(beastType)

        return Combatant(
            id = "trial_beast_${levelIndex}_${index}",
            name = def.name,
            side = CombatantSide.ATTACKER,
            hp = stats.hp,
            maxHp = stats.hp,
            mp = stats.mp,
            maxMp = stats.mp,
            physicalAttack = stats.physicalAttack,
            magicAttack = stats.magicAttack,
            physicalDefense = stats.physicalDefense,
            magicDefense = stats.magicDefense,
            speed = stats.speed,
            critRate = (0.05 + safeRealm * 0.01).coerceIn(0.0, 1.0),
            skills = beastSkills,
            realm = safeRealm,
            realmName = GameConfig.Realm.getName(safeRealm),
            realmLayer = safeLayer,
            element = beastType.element,
            portraitRes = "beast_$typeIndex",
            isBeast = true
        )
    }

    /** 妖兽基础属性：realmStats × layerMult ×（类型 mod + 方差），钳制 ≥1 */
    internal data class BeastStats(
        val hp: Int,
        val mp: Int,
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int
    )

    fun buildDiscipleEnemy(levelIndex: Int, def: TrialEnemyDef, index: Int): Combatant {
        val selected = selectTrialManuals(def, levelIndex)
        val equipment = selectTrialEquipment(def)

        // 使用与玩家弟子相同的属性公式：境界基础值 × (1 + 方差) × 层数倍率
        // + 装备加成 + 功法属性加成（stats × 熟练度 bonus，与 computeFinalStats 一致）
        // 方差 ±30%，与 DiscipleStatCalculator.computeBaseStats 的 variance 一致
        val layerMult = 1.0 + (def.realmLayer - 1) * 0.1
        // 试炼敌人生成用确定性派生种子（关卡定义 + 敌人名），
        // 不消费全局 ENEMY_GEN 分区——UI 线程调用（CombatScreen/BattleDialog）
        // 会推进引擎侧探索敌人生成序列，破坏读档重放；固定种子同时保证
        // 预览（BattleDialog）与战斗（startCombat）敌人属性一致
        val enemyRng = DeterministicRng(enemySeed(levelIndex, def, index))
        val stats = buildTrialBaseStats(
            def, layerMult, enemyRng, selected, equipment
        )

        return Combatant(
            id = "trial_disciple_${levelIndex}_${index}",
            name = def.name,
            side = CombatantSide.ATTACKER,
            hp = stats.hp,
            maxHp = stats.hp,
            mp = stats.mp,
            maxMp = stats.mp,
            physicalAttack = stats.physAtk,
            magicAttack = stats.magAtk,
            physicalDefense = stats.physDef,
            magicDefense = stats.magDef,
            speed = stats.speed,
            critRate = 0.05 + def.realm * 0.01 + stats.critChance,
            skills = buildTrialSkills(selected),
            realm = def.realm,
            realmName = GameConfig.Realm.getName(def.realm),
            realmLayer = def.realmLayer,
            weaponName = equipment.weapon?.name,
            armorName = equipment.armor?.name,
            bootsName = equipment.boots?.name,
            accessoryName = equipment.accessory?.name,
            isBeast = false
        )
    }

    internal data class TrialEquipmentSelection(
        val weapon: ForgeRecipeDatabase.ForgeRecipe?,
        val armor: ForgeRecipeDatabase.ForgeRecipe?,
        val boots: ForgeRecipeDatabase.ForgeRecipe?,
        val accessory: ForgeRecipeDatabase.ForgeRecipe?
    )

    internal data class TrialBaseStats(
        val hp: Int,
        val mp: Int,
        val physAtk: Int,
        val magAtk: Int,
        val physDef: Int,
        val magDef: Int,
        val speed: Int,
        val critChance: Double
    )

    /** 试炼功法选取（buildDiscipleEnemy 提取）：固定 manualIds → 角色精选 → 随机 */
    internal data class StatBonus(
        val hp: Int = 0, val mp: Int = 0,
        val physAtk: Int = 0, val magAtk: Int = 0,
        val physDef: Int = 0, val magDef: Int = 0,
        val speed: Int = 0, val critChance: Double = 0.0
    )

    fun getEnemiesForPhase(levelIndex: Int, phaseIndex: Int): List<Combatant> {
        val config = HeavenlyTrialConfig.getLevel(levelIndex) ?: return emptyList()
        val defs = if (phaseIndex == 0) config.phase1Enemies else config.phase2Enemies
        return defs.mapIndexed { idx, def ->
            if (def.isBeast) buildBeastEnemy(levelIndex, def, idx)
            else buildDiscipleEnemy(levelIndex, def, idx)
        }
    }

    // region Enemy AI（委托到统一 BattleAI）

    /**
     * 敌方 AI 决策 —— 委托到统一 [BattleAI.decideAction]。
     * 所有敌人（天道试炼、妖兽、AI 弟子等）共用同一套 8 层级联 AI。
     *
     * @param rng 调用方（UI 战斗模拟）必须传本地 PRNG
     *   （currentCombatRng）——UI 线程不得消费全局 BATTLE 分区，
     *   敌方行动数百次消费会使引擎侧战斗序列不可重放；引擎侧调用方
     *   （BattleSystem 等）传引擎线程的 BATTLE 分区实例
     */
    fun executeEnemyAction(
        attacker: Combatant,
        playerTeam: List<Combatant>,
        allyTeam: List<Combatant> = emptyList(),
        rng: DeterministicRng
    ): EnemyAction {
        val aiAction = BattleAI.decideAction(
            attacker, allyTeam, playerTeam, rng
        )
        return convertToEnemyAction(aiAction)
    }

    /**
     * 将统一 [BattleAI.AIAction] 映射回 HeavenlyTrialCombatScreen
     * 使用的 [EnemyAction] 类型，保持 UI 层兼容。
     */
    private fun convertToEnemyAction(
        ai: BattleAI.AIAction
    ): EnemyAction {
        val legacyType = when (ai.actionType) {
            BattleAI.AIActionType.SKILL_ATTACK_SINGLE,
            BattleAI.AIActionType.SKILL_ATTACK_AOE -> ActionType.ATTACK
            BattleAI.AIActionType.SKILL_HEAL_ALLY,
            BattleAI.AIActionType.SKILL_BUFF_ALLY,
            BattleAI.AIActionType.SKILL_HEAL_TEAM,
            BattleAI.AIActionType.SKILL_BUFF_TEAM -> ActionType.BUFF_ALLY
            BattleAI.AIActionType.SKILL_HEAL_SELF,
            BattleAI.AIActionType.SKILL_BUFF_SELF -> ActionType.BUFF_SELF
            BattleAI.AIActionType.NORMAL_ATTACK -> ActionType.NORMAL_ATTACK
            BattleAI.AIActionType.NONE -> ActionType.NONE
        }
        return EnemyAction(ai.skill, ai.target, legacyType)
    }

    // endregion

    suspend fun recordPhaseClear(levelIndex: Int, phaseIndex: Int) {
        // 捕获豁免（updateMirror，§2.80）：heavenlyTrialState 已关闭回导——通关记录
        // 经尾部基线重建回导 C++（试炼通关为低频用户动作，O(状态) 一次性成本可接受）
        stateStore.updateMirror {
            /** 当前设备的电源管理配置 */
            val current = gameData.heavenlyTrialState
            val newP1 = if (phaseIndex == 0) (current.phase1ClearedLevels + levelIndex).distinct()
                        else current.phase1ClearedLevels
            val newP2 = if (phaseIndex == 1) (current.phase2ClearedLevels + levelIndex).distinct()
                        else current.phase2ClearedLevels

            val fullyCleared = levelIndex in newP1 && levelIndex in newP2
            val newHighest = if (fullyCleared) maxOf(current.highestClearedLevel, levelIndex)
                             else current.highestClearedLevel
            val newCounts = if (fullyCleared) {
                current.levelClearCounts.toMutableList().also {
                    if (levelIndex in it.indices) it[levelIndex] = it[levelIndex] + 1
                }
            } else current.levelClearCounts

            gameData = gameData.copy(
                heavenlyTrialState = current.copy(
                    phase1ClearedLevels = newP1,
                    phase2ClearedLevels = newP2,
                    highestClearedLevel = newHighest,
                    levelClearCounts = newCounts
                )
            )
        }
        gameEngineCore?.rebaselineNativeMirror("天劫通关记录")
    }

    // region Clear Reward

    suspend fun claimClearReward(levelIndex: Int): ClaimClearRewardResult {
        val snapshot = stateStore.gameDataSnapshot
        val reward = when (val check = checkClearRewardClaimable(levelIndex, snapshot)) {
            is ClearRewardClaimCheck.Eligible -> check.reward
            is ClearRewardClaimCheck.NotCleared -> return ClaimClearRewardResult.LevelNotCleared
            is ClearRewardClaimCheck.AlreadyClaimed -> return ClaimClearRewardResult.AlreadyClaimed
            is ClearRewardClaimCheck.CapacityInsufficient ->
                return ClaimClearRewardResult.CapacityInsufficient(check.message)
        }

        val generatedCards = mutableListOf<RewardCardItem>()

        try {
            // 捕获豁免（updateMirror，§2.80）：领取写面（钱包/年度账/集合奖励/
            // heavenlyTrialState 均已关闭回导）——成功路径尾部基线重建回导 C++；
            // 容量不足异常整体回滚（零写入）不触发重建
            stateStore.updateMirror {
                // 原子内二次检查：防止并发重复领取（外层检查在 mutex 外，快速双击可能绕过）
                if (levelIndex in gameData.heavenlyTrialState.claimedRewardLevels) {
                    return@updateMirror
                }
                // 凭据类路径（与宗门等级奖励一致）：溢出抑制转邮件，
                // addXxx 返回 Partial/Failure 时抛异常 → 异常传播出 update → 事务整体回滚，
                // claimedRewardLevels 不写入，玩家清理仓库后可重试补齐，不会部分入仓。
                inventorySystem.withOverflowMailSuppressed {
                    inventorySystem.withTrackingSource("trial") {
                        distributeRewardItems(reward, generatedCards)
                    }
                }
                gameData = gameData.copy(
                    heavenlyTrialState = gameData.heavenlyTrialState.copy(
                        claimedRewardLevels = gameData.heavenlyTrialState.claimedRewardLevels + levelIndex
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // 容量不足/发放失败 → 事务整体回滚（物品/凭据均未写），凭据保留可重试
            DomainLog.w(TAG, "claimClearReward: level=$levelIndex 仓库容量不足（事务已回滚）: ${e.message}")
            return ClaimClearRewardResult.CapacityInsufficient(e.message)
        }

        gameEngineCore?.rebaselineNativeMirror("天劫奖励领取")
        return ClaimClearRewardResult.Success(generatedCards)
    }

    /** 领取资格校验结果：通过时携带奖励定义 */
    private sealed interface ClearRewardClaimCheck {
        data class Eligible(val reward: HeavenlyTrialClearReward) : ClearRewardClaimCheck
        data object NotCleared : ClearRewardClaimCheck
        data object AlreadyClaimed : ClearRewardClaimCheck
        /** 仓库容量不足：奖励未发放、领取记录未写入，清理后可重新领取 */
        data class CapacityInsufficient(val message: String?) : ClearRewardClaimCheck
    }

    /**
     * 领取前置校验：关卡全清 / 未重复领取 / 奖励定义存在 /
     * 仓库容量预校验四道检查（均在 update 事务外，只读）。
     */
    private fun checkClearRewardClaimable(levelIndex: Int, snapshot: GameData?): ClearRewardClaimCheck {
        /** 当前设备的电源管理配置 */
        val current = snapshot?.heavenlyTrialState
        if (current == null || !current.isLevelFullyCleared(levelIndex)) {
            return ClearRewardClaimCheck.NotCleared
        }
        if (levelIndex in current.claimedRewardLevels) {
            return ClearRewardClaimCheck.AlreadyClaimed
        }
        val reward = HEAVENLY_TRIAL_CLEAR_REWARDS.find { it.levelIndex == levelIndex }
            ?: return ClearRewardClaimCheck.NotCleared

        // 预校验容量（storageBag 快速失败）：在 update 事务外做只读检查。
        // randomEquipment/randomManual/randomPill 随机物品名未知，无法精确预校验，
        // 由事务内 addXxx 溢出抛异常整体回滚兜底（凭据保留，清理后可重试）。
        val capacityCheck = checkRewardCapacity(
            reward = reward,
            storageBags = stateStore.storageBagsSnapshot,
            inventoryConfig = inventoryConfig
        )
        if (capacityCheck is RewardCapacityCheck.Failed) {
            return ClearRewardClaimCheck.CapacityInsufficient(capacityCheck.message)
        }
        return ClearRewardClaimCheck.Eligible(reward)
    }

    /**
     * 在 [MutableGameState] 上下文中发放通关奖励物品。
     * 从 [claimClearReward] 提取，控制函数在 60 行以内。
     * 可堆叠物品统一委托 InventorySystem.addXxx（StackableItemStore 合并 +
     * withTrackingSource 来源追踪 + 溢出邮件兜底），Partial/Failure 抛异常整体回滚。
     */
    internal fun MutableGameState.distributeRewardItems(
        reward: HeavenlyTrialClearReward,
        generatedCards: MutableList<RewardCardItem>
    ) {
        for (item in reward.items) {
            when (item.itemType) {
                "spiritStones" -> grantSpiritStoneRewardItem(item, generatedCards)
                "storageBag" -> grantStorageBagRewardItem(item, generatedCards)
                "randomPill" -> grantRandomPillRewards(item, generatedCards)
                "randomEquipment" -> grantRandomEquipmentRewards(item, generatedCards)
                "randomManual" -> grantRandomManualRewards(item, generatedCards)
            }
        }
    }

    /** 灵石奖励发放（distributeRewardItems 提取） */
    internal fun MutableGameState.grantSpiritStoneRewardItem(
        item: ClearRewardItem,
        generatedCards: MutableList<RewardCardItem>
    ) {
        spiritStoneWallet.add(this, item.quantity.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.HeavenlyTrial)
        generatedCards.add(RewardCardItem(
            itemName = ItemNames.SPIRIT_STONE, itemType = "spiritStones",
            rarity = 1, quantity = item.quantity
        ))
    }

    /** 储物袋奖励发放（distributeRewardItems 提取） */
    internal fun MutableGameState.grantStorageBagRewardItem(
        item: ClearRewardItem,
        generatedCards: MutableList<RewardCardItem>
    ) {
        val qty = item.quantity.coerceAtLeast(1)
        val rarity = item.rarity.coerceIn(1, 6)
        val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
        val newBag = StorageBag(
            id = java.util.UUID.randomUUID().toString(),
            name = bagName,
            rarity = rarity,
            description = "${bagName}，可开启获得随机物品",
            quantity = qty
        )
        // 统一委托 addStorageBag（StackableItemStore 合并 + 来源追踪 + 溢出邮件），
        // 替代手写 mergeStackable（无来源追踪、无仓库容量约束）
        when (val result = inventorySystem.addStorageBag(newBag)) {
            is DomainResult.Success -> {}
            is DomainResult.Partial ->
                error("储物袋 $bagName 仓库空间不足，溢出 ${result.overflow} 个")
            is DomainResult.Failure ->
                error("储物袋 $bagName 发放失败: ${result.error}")
        }
        generatedCards.add(RewardCardItem(
            itemName = bagName, itemType = "storageBag",
            rarity = rarity, quantity = qty
        ))
    }

    /** 随机丹药奖励发放（distributeRewardItems 提取） */
    internal fun MutableGameState.grantRandomPillRewards(
        item: ClearRewardItem,
        generatedCards: MutableList<RewardCardItem>
    ) {
        val qty = item.quantity.coerceAtLeast(1)
        val generated = mutableListOf<RewardCardItem>()
        val minRarity = item.rarity
        val maxRarity = item.rarity
        repeat(qty) {
            val pill = ItemDatabase.generateRandomPill(
                minRarity = minRarity, maxRarity = maxRarity
            ).copy(
                id = java.util.UUID.randomUUID().toString(), quantity = 1
            )
            // 统一委托 addPill（StackableItemStore 合并 + 来源追踪 + 溢出邮件）。
            // 修复历史 bug：原 mergeStackable 实现仅当 existing.quantity < maxStack 才合并，
            // 否则静默丢弃且未设置 capacityError，导致奖励物品消失。
            when (val result = inventorySystem.addPill(pill)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial ->
                    error("丹药 ${pill.name} 仓库空间不足，溢出 ${result.overflow} 个")
                is DomainResult.Failure ->
                    error("丹药 ${pill.name} 发放失败: ${result.error}")
            }
            generated.add(RewardCardItem(
                itemName = pill.name, itemType = "pill",
                rarity = pill.rarity, quantity = 1
            ))
        }
        generatedCards.addAll(mergeCardsByName(generated))
    }

    /** 随机装备奖励发放（distributeRewardItems 提取） */
    internal fun MutableGameState.grantRandomEquipmentRewards(
        item: ClearRewardItem,
        generatedCards: MutableList<RewardCardItem>
    ) {
        val qty = item.quantity.coerceAtLeast(1)
        val targetRarity = item.rarity
        val generated = mutableListOf<RewardCardItem>()
        repeat(qty) {
            val stack = EquipmentDatabase.generateRandom(
                minRarity = targetRarity,
                maxRarity = targetRarity
            )
            // 统一委托 addEquipmentStack：进仓库堆叠轨道（equipmentStacks，仓库 UI 可见）。
            // 仓库 UI 只渲染堆叠不渲染实例——直接写实例轨道会导致领取后装备不可见，
            // 且无来源追踪/溢出兜底。
            when (val result = inventorySystem.addEquipmentStack(stack)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial ->
                    error("装备 ${stack.name} 仓库空间不足，溢出 ${result.overflow} 个")
                is DomainResult.Failure ->
                    error("装备 ${stack.name} 发放失败: ${result.error}")
            }
            generated.add(RewardCardItem(
                itemName = stack.name, itemType = "equipment",
                rarity = stack.rarity, quantity = 1
            ))
        }
        generatedCards.addAll(mergeCardsByName(generated))
    }

    /** 随机功法奖励发放（distributeRewardItems 提取） */
    internal fun MutableGameState.grantRandomManualRewards(
        item: ClearRewardItem,
        generatedCards: MutableList<RewardCardItem>
    ) {
        val qty = item.quantity.coerceAtLeast(1)
        val targetRarity = item.rarity
        val generated = mutableListOf<RewardCardItem>()
        repeat(qty) {
            val stack = ManualDatabase.generateRandom(
                minRarity = targetRarity,
                maxRarity = targetRarity
            )
            // 统一委托 addManualStack：进仓库功法堆叠轨道（manualStacks，仓库 UI 可见）。
            // 仓库 UI 只渲染堆叠不渲染实例——直接写实例轨道会导致领取后功法不可见。
            when (val result = inventorySystem.addManualStack(stack)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial ->
                    error("功法 ${stack.name} 仓库空间不足，溢出 ${result.overflow} 个")
                is DomainResult.Failure ->
                    error("功法 ${stack.name} 发放失败: ${result.error}")
            }
            generated.add(RewardCardItem(
                itemName = stack.name, itemType = "manual",
                rarity = stack.rarity, quantity = 1
            ))
        }
        generatedCards.addAll(mergeCardsByName(generated))
    }

    /**
     * 奖励容量预校验结果。
     *
     * - [Ok]：所有可堆叠物品有足够容量，可进入事务写入
     * - [Failed]：存在容量不足，应返回 [ClaimClearRewardResult.CapacityInsufficient]
     */
    internal sealed class RewardCapacityCheck {
        data object Ok : RewardCapacityCheck()
        data class Failed(val message: String) : RewardCapacityCheck()
    }

    /**
     * 纯函数：在事务外预校验奖励发放的容量前置条件。
     *
     * 提取为 companion object 静态方法以便单元测试。
     *
     * 校验项：
     * - storageBag：现有同类堆叠未达上限（达上限则用户应先清理背包）——快速失败路径
     *
     * 不校验 randomEquipment/randomManual/randomPill：随机生成物品名未知，无法精确预估
     * 合并结果；由事务内 addXxx 溢出抛异常整体回滚兜底（凭据保留，清理后可重试）。
     *
     * 不校验 spiritStones：Long 类型无上限。
     */
    internal companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        private const val TAG = "HeavenlyTrialService"

        fun checkRewardCapacity(
            reward: HeavenlyTrialClearReward,
            storageBags: List<StorageBag>,
            inventoryConfig: InventoryConfig
        ): RewardCapacityCheck {
            for (item in reward.items) {
                when (item.itemType) {
                    "storageBag" -> {
                        val rarity = item.rarity.coerceIn(1, 6)
                        val maxStack = inventoryConfig.getMaxStackSize("storageBag")
                        val existing = storageBags.find { it.rarity == rarity }
                        if (existing != null && existing.quantity >= maxStack) {
                            return RewardCapacityCheck.Failed(
                                "储物袋已达堆叠上限，请清理背包后重试"
                            )
                        }
                    }
                    // randomEquipment/randomManual/randomPill：由事务内 addXxx 溢出回滚兜底
                }
            }
            return RewardCapacityCheck.Ok
        }
    }

    // endregion

}

/**
 * 天道试炼通关奖励领取结果。
 */
sealed class ClaimClearRewardResult {
    data class Success(val cards: List<RewardCardItem>) : ClaimClearRewardResult()
    data object AlreadyClaimed : ClaimClearRewardResult()
    data object LevelNotCleared : ClaimClearRewardResult()
    /** 仓库容量不足：奖励未发放、领取记录未写入，清理后可重新领取 */
    data class CapacityInsufficient(val message: String?) : ClaimClearRewardResult()
}
