package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.config.HeavenlyTrialConfig
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.core.model.TrialEnemyDef
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
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
    private val stateStore: GameStateStore
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
                buffType = skillConfig.buffType,
                buffValue = skillConfig.buffValue,
                buffDuration = skillConfig.buffDuration,
                isAoe = skillConfig.isAoe,
                targetScope = skillConfig.targetScope
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

    // region Enhanced Enemy AI

    fun executeEnemyAction(
        attacker: Combatant,
        playerTeam: List<Combatant>,
        allyTeam: List<Combatant> = emptyList()
    ): EnemyAction {
        // 被控跳过
        if (attacker.hasControlEffect) {
            return EnemyAction(null, null, ActionType.NONE)
        }

        val alivePlayers = playerTeam.filter { !it.isDead }
        if (alivePlayers.isEmpty()) return EnemyAction(null, null, ActionType.NONE)

        val usable = filterUsableSkills(attacker)
        val attackSkills = usable.filter { it.skillType == SkillType.ATTACK || it.damageMultiplier > 0 }
        val supportSkills = usable.filter { it.skillType != SkillType.ATTACK && it.damageMultiplier <= 0 }

        // Priority 1: 斩杀残血玩家 (80%)
        if (Random.nextDouble() < 0.8 && attackSkills.isNotEmpty()) {
            val killAction = findKillTarget(attacker, alivePlayers, attackSkills)
            if (killAction != null) return killAction
        }

        // Priority 2: 治疗/保护残血队友 (85%)
        if (Random.nextDouble() < 0.85 && supportSkills.isNotEmpty()) {
            val allyAction = findAllySupportAction(attacker, allyTeam, supportSkills)
            if (allyAction != null) return allyAction
        }

        // Priority 3: AOE 多目标 (75%)
        if (alivePlayers.size >= 2 && Random.nextDouble() < 0.75 && attackSkills.isNotEmpty()) {
            val aoeSkills = attackSkills.filter { it.isAoe }
            if (aoeSkills.isNotEmpty()) {
                val bestAoe = aoeSkills.maxByOrNull { it.damageMultiplier }
                if (bestAoe != null) return EnemyAction(bestAoe, null, ActionType.ATTACK)
            }
        }

        // Priority 4: Buff 自身/队友 (60%)
        if (Random.nextDouble() < 0.6 && supportSkills.isNotEmpty()) {
            val buffAction = findBuffAction(attacker, allyTeam, supportSkills)
            if (buffAction != null) return buffAction
        }

        // Priority 5: 最优攻击技能打最弱防御
        if (attackSkills.isNotEmpty()) {
            val bestSkill = if (attacker.mpPercent < 0.3) {
                attackSkills.minByOrNull { it.mpCost }
            } else {
                attackSkills.maxByOrNull { it.damageMultiplier / it.mpCost.coerceAtLeast(1).toDouble() }
            }
            if (bestSkill != null) {
                val target = findWeakestTarget(attacker, alivePlayers, bestSkill)
                return EnemyAction(bestSkill, target, ActionType.ATTACK)
            }
        }

        // Priority 6: 普攻兜底
        val target = alivePlayers.minByOrNull { it.hp }
        if (target != null) return EnemyAction(null, target, ActionType.NORMAL_ATTACK)
        return EnemyAction(null, null, ActionType.NONE)
    }

    private fun filterUsableSkills(attacker: Combatant): List<CombatSkill> {
        val available = attacker.skills.filter {
            it.currentCooldown <= 0 && attacker.mp >= it.mpCost
        }
        if (available.isEmpty()) return emptyList()
        if (attacker.mpPercent < 0.3) {
            val cheap = available.filter { it.mpCost <= attacker.mp / 2 }
            return cheap.ifEmpty { available }
        }
        return available
    }

    private fun findKillTarget(
        attacker: Combatant,
        players: List<Combatant>,
        attackSkills: List<CombatSkill>
    ): EnemyAction? {
        val lowHp = players.filter { it.hpPercent < 0.3 }
        if (lowHp.isEmpty()) return null
        for (target in lowHp.sortedByDescending { it.effectivePhysicalAttack + it.effectiveMagicAttack }) {
            val bestSkill = attackSkills.filter { !it.isAoe }
                .maxByOrNull { it.damageMultiplier * (if (it.damageType == DamageType.PHYSICAL) attacker.effectivePhysicalAttack else attacker.effectiveMagicAttack) }
                ?: continue
            val estDmg = calculateEstimatedDamage(attacker, target, bestSkill)
            if (estDmg >= target.hp) {
                return EnemyAction(bestSkill, target, ActionType.ATTACK)
            }
        }
        return null
    }

    private fun findAllySupportAction(
        attacker: Combatant,
        allies: List<Combatant>,
        supportSkills: List<CombatSkill>
    ): EnemyAction? {
        val aliveAllies = allies.filter { !it.isDead }
        if (aliveAllies.isEmpty()) return null

        // 优先给最低 HP 队友加 HP Buff
        val lowestHpAlly = aliveAllies.minByOrNull { it.hpPercent }
        if (lowestHpAlly != null && lowestHpAlly.hpPercent < 0.5) {
            val hpBuff = supportSkills.firstOrNull { s ->
                s.buffs.any { it.first.name.contains("HP") } || s.buffType?.name?.contains("HP") == true
            }
            if (hpBuff != null) return EnemyAction(hpBuff, lowestHpAlly, ActionType.BUFF_ALLY)
        }

        // 其次给防御最低队友加防御 Buff
        val lowestDef = aliveAllies.minByOrNull { it.effectivePhysicalDefense + it.effectiveMagicDefense }
        if (lowestDef != null) {
            val defBuff = supportSkills.firstOrNull { s ->
                s.buffs.any { it.first.name.contains("DEFENSE") } || s.buffType?.name?.contains("DEFENSE") == true
            }
            if (defBuff != null) return EnemyAction(defBuff, lowestDef, ActionType.BUFF_ALLY)
        }

        return null
    }

    private fun findBuffAction(
        attacker: Combatant,
        allies: List<Combatant>,
        supportSkills: List<CombatSkill>
    ): EnemyAction? {
        // 团队 Buff 优先
        val teamBuffs = supportSkills.filter { it.targetScope == "team" && it.isAoe }
        if (teamBuffs.isNotEmpty()) {
            return EnemyAction(teamBuffs.first(), null, ActionType.BUFF_ALLY)
        }
        // 自身 Buff
        val selfBuffs = supportSkills.filter { it.targetScope == "self" }
        if (selfBuffs.isNotEmpty()) {
            return EnemyAction(selfBuffs.first(), attacker, ActionType.BUFF_SELF)
        }
        return null
    }

    private fun findWeakestTarget(
        attacker: Combatant,
        players: List<Combatant>,
        skill: CombatSkill
    ): Combatant {
        val isPhysical = skill.damageType == DamageType.PHYSICAL
        return players.minByOrNull { t ->
            if (isPhysical) t.effectivePhysicalDefense else t.effectiveMagicDefense
        } ?: players.first()
    }

    private fun calculateEstimatedDamage(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill
    ): Int {
        val atk = if (skill.damageType == DamageType.PHYSICAL)
            attacker.effectivePhysicalAttack else attacker.effectiveMagicAttack
        val def = if (skill.damageType == DamageType.PHYSICAL)
            defender.effectivePhysicalDefense else defender.effectiveMagicDefense
        val rawDmg = atk * skill.damageMultiplier * (1.0 - def / (def + 500.0))
        return (rawDmg * skill.hits).toInt().coerceAtLeast(1)
    }

    // endregion

    suspend fun recordLevelClear(levelIndex: Int) {
        stateStore.update {
            val current = gameData.heavenlyTrialState
            val newCounts = current.levelClearCounts.toMutableList()
            if (levelIndex in newCounts.indices) {
                newCounts[levelIndex] = newCounts[levelIndex] + 1
            }
            gameData = gameData.copy(
                heavenlyTrialState = current.copy(
                    highestClearedLevel = max(current.highestClearedLevel, levelIndex),
                    levelClearCounts = newCounts
                )
            )
        }
    }

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
