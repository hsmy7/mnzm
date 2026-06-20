package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.config.HeavenlyTrialConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.HEAVENLY_TRIAL_CLEAR_REWARDS
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.TrialEnemyDef
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random

enum class ActionType { ATTACK, BUFF_ALLY, BUFF_SELF, NORMAL_ATTACK, NONE }

data class EnemyAction(
    val skill: CombatSkill?,
    val target: Combatant?,
    val actionType: ActionType
)

@Singleton
class HeavenlyTrialService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig
) {

    fun buildBeastEnemy(levelIndex: Int, def: TrialEnemyDef, index: Int): Combatant {
        val realmStats = GameConfig.Beast.getRealmStats(def.realm)
        val beastType = GameConfig.Beast.TYPES.find { it.name == def.beastType }
            ?: GameConfig.Beast.TYPES.first()

        val layerMult = 1.0 + (def.realmLayer - 1) * 0.1

        val hp = (realmStats.hp * layerMult * beastType.hpMod).toInt()
        val mp = (realmStats.mp * layerMult * beastType.hpMod).toInt()
        val physicalAttack = (realmStats.attack * layerMult * beastType.atkMod).toInt()
        val magicAttack = (realmStats.attack * layerMult * beastType.atkMod).toInt()
        val physicalDefense = (realmStats.defense * layerMult * beastType.defMod).toInt()
        val magicDefense = (realmStats.defense * layerMult * beastType.defMod).toInt()
        val speed = (realmStats.speed * layerMult * beastType.speedMod).toInt()

        val beastSkills = beastType.skills.map { skillConfig ->
            CombatSkill(
                name = skillConfig.name,
                skillType = skillConfig.skillType,
                damageType = skillConfig.damageType,
                damageMultiplier = skillConfig.damageMultiplier,
                mpCost = skillConfig.mpCost,
                cooldown = skillConfig.cooldown,
                hits = skillConfig.hits,
                healPercent = skillConfig.healPercent,
                healFixed = skillConfig.healFixed,
                healType = skillConfig.healType,
                buffType = skillConfig.buffType,
                buffValue = skillConfig.buffValue,
                buffDuration = skillConfig.buffDuration,
                buffs = skillConfig.buffs,
                isAoe = skillConfig.isAoe,
                targetScope = skillConfig.targetScope,
                shieldPercent = skillConfig.shieldPercent,
                turnAdvancePercent = skillConfig.turnAdvancePercent,
                damageSharePercent = skillConfig.damageSharePercent,
                damageLinkPercent = skillConfig.damageLinkPercent,
                skillDescription = skillConfig.skillDescription
            )
        }

        val typeIndex = GameConfig.Beast.TYPES.indexOf(beastType)

        return Combatant(
            id = "trial_beast_${levelIndex}_${index}",
            name = def.name,
            side = CombatantSide.ATTACKER,
            hp = hp,
            maxHp = hp,
            mp = mp,
            maxMp = mp,
            physicalAttack = physicalAttack,
            magicAttack = magicAttack,
            physicalDefense = physicalDefense,
            magicDefense = magicDefense,
            speed = speed,
            critRate = 0.05 + def.realm * 0.01,
            skills = beastSkills,
            realm = def.realm,
            realmName = GameConfig.Realm.getName(def.realm),
            realmLayer = def.realmLayer,
            element = beastType.element,
            portraitRes = "beast_$typeIndex",
            isBeast = true
        )
    }

    fun buildDiscipleEnemy(levelIndex: Int, def: TrialEnemyDef, index: Int): Combatant {
        // 功法：优先用固定 manualIds → 按角色精选 → 随机
        val selected = if (def.manualIds.isNotEmpty()) {
            val resolved = def.manualIds.mapNotNull { ManualDatabase.allManuals[it] }
            resolved.ifEmpty {
                val allManuals = ManualDatabase.allManuals.values.toList()
                val eligible = allManuals
                    .filter { it.minRealm <= def.realm }
                    .sortedByDescending { it.rarity }
                if (def.role.isNotEmpty()) selectManualsForRole(eligible, def.role, def.realm)
                else selectManuals(eligible, levelIndex, def.realm)
            }
        } else if (def.role.isNotEmpty()) {
            val allManuals = ManualDatabase.allManuals.values.toList()
            val eligible = allManuals
                .filter { it.minRealm <= def.realm }
                .sortedByDescending { it.rarity }
            selectManualsForRole(eligible, def.role, def.realm)
        } else {
            val allManuals = ManualDatabase.allManuals.values.toList()
            val eligible = allManuals
                .filter { it.minRealm <= def.realm }
                .sortedByDescending { it.rarity }
            selectManuals(eligible, levelIndex, def.realm)
        }

        // 装备：优先使用固定 equipmentIds，否则取境界最高品阶
        val weapon: ForgeRecipeDatabase.ForgeRecipe?
        val armor: ForgeRecipeDatabase.ForgeRecipe?
        val boots: ForgeRecipeDatabase.ForgeRecipe?
        val accessory: ForgeRecipeDatabase.ForgeRecipe?
        if (def.equipmentIds.isNotEmpty()) {
            val eqRecipes = def.equipmentIds.mapNotNull { ForgeRecipeDatabase.getRecipeById(it) }
            weapon = eqRecipes.find { it.type == EquipmentSlot.WEAPON }
            armor = eqRecipes.find { it.type == EquipmentSlot.ARMOR }
            boots = eqRecipes.find { it.type == EquipmentSlot.BOOTS }
            accessory = eqRecipes.find { it.type == EquipmentSlot.ACCESSORY }
        } else {
            val allEquipment = ForgeRecipeDatabase.getAllRecipes()
            val maxTier = getMaxTierForRealm(def.realm)
            val eligibleEquip = allEquipment.filter { it.tier <= maxTier }
                .sortedByDescending { it.rarity }
            weapon = eligibleEquip.find { it.type == EquipmentSlot.WEAPON }
            armor = eligibleEquip.find { it.type == EquipmentSlot.ARMOR }
            boots = eligibleEquip.find { it.type == EquipmentSlot.BOOTS }
            accessory = eligibleEquip.find { it.type == EquipmentSlot.ACCESSORY }
        }

        val stats = GameConfig.Enemy.getRealmStats(def.realm)
        val layerMult = 1.0 + (def.realmLayer - 1) * 0.1
        val skills = selected.map { it.toCombatSkill() }

        return Combatant(
            id = "trial_disciple_${levelIndex}_${index}",
            name = def.name,
            side = CombatantSide.ATTACKER,
            hp = (stats.hp * layerMult).toInt(),
            maxHp = (stats.hp * layerMult).toInt(),
            mp = (stats.mp * layerMult).toInt(),
            maxMp = (stats.mp * layerMult).toInt(),
            physicalAttack = (stats.physicalAttack * layerMult).toInt(),
            magicAttack = (stats.magicAttack * layerMult).toInt(),
            physicalDefense = (stats.physicalDefense * layerMult).toInt(),
            magicDefense = (stats.magicDefense * layerMult).toInt(),
            speed = (stats.speed * layerMult).toInt(),
            critRate = 0.05 + def.realm * 0.02,
            skills = skills,
            realm = def.realm,
            realmName = GameConfig.Realm.getName(def.realm),
            realmLayer = def.realmLayer,
            weaponName = weapon?.name,
            armorName = armor?.name,
            bootsName = boots?.name,
            accessoryName = accessory?.name,
            isBeast = false
        )
    }

    private fun getMaxTierForRealm(realm: Int): Int = when (realm) {
        1, 2, 3, 4 -> 4
        5 -> 3
        6 -> 2
        else -> 1
    }

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
     */
    fun executeEnemyAction(
        attacker: Combatant,
        playerTeam: List<Combatant>,
        allyTeam: List<Combatant> = emptyList()
    ): EnemyAction {
        val aiAction = BattleAI.decideAction(
            attacker, allyTeam, playerTeam
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
        stateStore.update {
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
    }

    // region Clear Reward

    suspend fun claimClearReward(levelIndex: Int): ClaimClearRewardResult {
        val snapshot = stateStore.gameDataSnapshot
            ?: return ClaimClearRewardResult.LevelNotCleared
        val current = snapshot.heavenlyTrialState
        if (!current.isLevelFullyCleared(levelIndex)) {
            return ClaimClearRewardResult.LevelNotCleared
        }
        if (levelIndex in current.claimedRewardLevels) {
            return ClaimClearRewardResult.AlreadyClaimed
        }
        val reward = HEAVENLY_TRIAL_CLEAR_REWARDS.find { it.levelIndex == levelIndex }
            ?: return ClaimClearRewardResult.LevelNotCleared

        var capacityError: String? = null
        val generatedCards = mutableListOf<RewardCardItem>()

        stateStore.update {
            for (item in reward.items) {
                when (item.itemType) {
                    "spiritStones" -> {
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones + item.quantity
                        )
                        generatedCards.add(RewardCardItem(
                            itemName = "灵石", itemType = "spiritStones",
                            rarity = 1, quantity = item.quantity
                        ))
                    }
                    "storageBag" -> {
                        val qty = item.quantity.coerceAtLeast(1)
                        val rarity = item.rarity.coerceIn(1, 6)
                        val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
                        val existing = storageBags.find { it.rarity == rarity }
                        if (existing != null) {
                            val maxStack = inventoryConfig.getMaxStackSize("storageBag")
                            if (existing.quantity >= maxStack) {
                                capacityError = "储物袋已达堆叠上限，请清理背包后重试"
                            } else {
                                val newQty = (existing.quantity + qty).coerceAtMost(maxStack)
                                storageBags = storageBags.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            }
                        } else {
                            storageBags = storageBags + StorageBag(
                                id = java.util.UUID.randomUUID().toString(),
                                name = bagName,
                                rarity = rarity,
                                description = "${bagName}，可开启获得随机物品",
                                quantity = qty
                            )
                        }
                        generatedCards.add(RewardCardItem(
                            itemName = bagName, itemType = "storageBag",
                            rarity = rarity, quantity = qty
                        ))
                    }
                    "randomPill" -> {
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
                            val existing = pills.find {
                                it.name == pill.name && it.rarity == pill.rarity &&
                                    it.category == pill.category
                            }
                            if (existing != null) {
                                val maxStack = inventoryConfig.getMaxStackSize("pill")
                                if (existing.quantity < maxStack) {
                                    val newQty = existing.quantity + 1
                                    pills = pills.map {
                                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                                    }
                                }
                            } else {
                                pills = pills + pill
                            }
                            generated.add(RewardCardItem(
                                itemName = pill.name, itemType = "pill",
                                rarity = pill.rarity, quantity = 1
                            ))
                        }
                        generatedCards.addAll(mergeCardsByName(generated))
                    }
                    "randomEquipment" -> {
                        val qty = item.quantity.coerceAtLeast(1)
                        val targetRarity = item.rarity
                        val eligible = equipmentStacks
                            .filter { it.rarity == targetRarity }
                        if (eligible.isEmpty()) {
                            capacityError = "没有可生成的${StorageBag.TIER_NAMES.getOrElse(targetRarity - 1) { "" }}装备"
                        } else {
                            val generated = mutableListOf<RewardCardItem>()
                            repeat(qty) {
                                val stack = eligible.random()
                                val instance = EquipmentInstance(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = stack.name,
                                    rarity = stack.rarity,
                                    description = stack.description,
                                    slot = stack.slot,
                                    physicalAttack = stack.physicalAttack,
                                    magicAttack = stack.magicAttack,
                                    physicalDefense = stack.physicalDefense,
                                    magicDefense = stack.magicDefense,
                                    speed = stack.speed,
                                    hp = stack.hp,
                                    mp = stack.mp,
                                    critChance = stack.critChance,
                                    minRealm = stack.minRealm
                                )
                                equipmentInstances = equipmentInstances + instance
                                generated.add(RewardCardItem(
                                    itemName = instance.name, itemType = "equipment",
                                    rarity = instance.rarity, quantity = 1
                                ))
                            }
                            generatedCards.addAll(mergeCardsByName(generated))
                        }
                    }
                    "randomManual" -> {
                        val qty = item.quantity.coerceAtLeast(1)
                        val targetRarity = item.rarity
                        val eligible = manualStacks
                            .filter { it.rarity == targetRarity }
                        if (eligible.isEmpty()) {
                            capacityError = "没有可生成的${StorageBag.TIER_NAMES.getOrElse(targetRarity - 1) { "" }}功法"
                        } else {
                            val generated = mutableListOf<RewardCardItem>()
                            repeat(qty) {
                                val stack = eligible.random()
                                val instance = stack.toInstance(
                                    id = java.util.UUID.randomUUID().toString(),
                                    isLearned = false
                                )
                                manualInstances = manualInstances + instance
                                generated.add(RewardCardItem(
                                    itemName = instance.name, itemType = "manual",
                                    rarity = instance.rarity, quantity = 1
                                ))
                            }
                            generatedCards.addAll(mergeCardsByName(generated))
                        }
                    }
                }
            }

            gameData = gameData.copy(
                heavenlyTrialState = gameData.heavenlyTrialState.copy(
                    claimedRewardLevels = gameData.heavenlyTrialState.claimedRewardLevels + levelIndex
                )
            )
        }

        return if (capacityError != null) {
            ClaimClearRewardResult.CapacityInsufficient(capacityError)
        } else {
            ClaimClearRewardResult.Success(generatedCards)
        }
    }

    private fun mergeCardsByName(cards: List<RewardCardItem>): List<RewardCardItem> {
        return cards.groupBy { Triple(it.itemName, it.itemType, it.rarity) }
            .map { (_, list) ->
                list.first().copy(quantity = list.sumOf { it.quantity })
            }
    }

    // endregion

    private fun selectManuals(
        eligible: List<ManualDatabase.ManualTemplate>,
        levelIndex: Int,
        realm: Int
    ): List<ManualDatabase.ManualTemplate> {
        val maxCount = when (realm) {
            1, 2 -> 10
            3, 4 -> 7
            5, 6 -> 4
            else -> 2
        }
        val types = listOf("attack", "defense", "support", "mind")
        val result = mutableListOf<ManualDatabase.ManualTemplate>()
        for (t in types) {
            eligible.filter { it.type.name.lowercase() == t }
                .maxByOrNull { it.rarity }
                ?.let { result.add(it) }
        }
        val remaining = eligible.filter { it !in result }
            .sortedByDescending { it.rarity }
        for (m in remaining) {
            if (result.size >= maxCount) break
            result.add(m)
        }
        return result
    }

    private fun selectManualsForRole(
        eligible: List<ManualDatabase.ManualTemplate>,
        role: String,
        realm: Int
    ): List<ManualDatabase.ManualTemplate> {
        val maxCount = when (realm) {
            1, 2 -> 10
            3, 4 -> 7
            5, 6 -> 4
            else -> 2
        }
        val result = mutableListOf<ManualDatabase.ManualTemplate>()

        // 按角色优先级选取各类型功法
        val typePriority = when (role.lowercase()) {
            "tank" -> listOf("defense" to 2, "attack" to 1, "mind" to 1, "support" to 0)
            "dps" -> listOf("attack" to 3, "mind" to 1, "defense" to 0, "support" to 0)
            "support" -> listOf("support" to 2, "mind" to 1, "defense" to 1, "attack" to 0)
            else -> listOf("attack" to 1, "defense" to 1, "support" to 1, "mind" to 1)
        }

        for ((type, desired) in typePriority) {
            val typeManuals = eligible
                .filter { it.type.name.lowercase() == type.lowercase() }
                .sortedByDescending { it.rarity }
                .take(desired)
            result.addAll(typeManuals)
        }

        // 补满到 maxCount
        val usedIds = result.map { it.id }.toSet()
        val remaining = eligible.filter { it.id !in usedIds }
            .sortedByDescending { it.rarity }
        for (m in remaining) {
            if (result.size >= maxCount) break
            result.add(m)
        }

        return result
    }

    private fun ManualDatabase.ManualTemplate.toCombatSkill(): CombatSkill {
        val skillTypeEnum = when (skillType.lowercase()) {
            "attack" -> SkillType.ATTACK
            "support" -> SkillType.SUPPORT
            else -> SkillType.ATTACK
        }
        val damageTypeEnum = when (skillDamageType.lowercase()) {
            "physical" -> DamageType.PHYSICAL
            "magic" -> DamageType.MAGIC
            else -> DamageType.PHYSICAL
        }
        val buffTypeEnum = skillBuffType?.let { buffName ->
            BuffType.entries.find { it.name.equals(buffName, ignoreCase = true) }
        }
        val buffsList = skillBuffs.map { buff ->
            val bt = BuffType.entries.find { it.name.equals(buff.type, ignoreCase = true) }
            if (bt != null) Triple(bt, buff.value, buff.duration) else null
        }.filterNotNull()

        val healTypeEnum = when (skillHealType.lowercase()) {
            "mp" -> HealType.MP
            else -> HealType.HP
        }

        return CombatSkill(
            name = skillName ?: name,
            skillType = skillTypeEnum,
            damageType = damageTypeEnum,
            damageMultiplier = skillDamageMultiplier,
            mpCost = skillMpCost,
            cooldown = skillCooldown,
            hits = skillHits,
            healPercent = skillHealPercent,
            healType = healTypeEnum,
            buffType = buffTypeEnum,
            buffValue = skillBuffValue,
            buffDuration = skillBuffDuration,
            buffs = buffsList,
            isAoe = skillIsAoe,
            targetScope = skillTargetScope,
            skillDescription = skillDescription ?: "",
            manualName = name
        )
    }
}

/**
 * 天道试炼通关奖励领取结果。
 */
sealed class ClaimClearRewardResult {
    data class Success(val cards: List<RewardCardItem>) : ClaimClearRewardResult()
    data object AlreadyClaimed : ClaimClearRewardResult()
    data object LevelNotCleared : ClaimClearRewardResult()
    data class CapacityInsufficient(val message: String?) : ClaimClearRewardResult()
}
