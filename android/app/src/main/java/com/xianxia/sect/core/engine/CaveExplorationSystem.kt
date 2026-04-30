package com.xianxia.sect.core.engine

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.*
import kotlin.random.Random

object CaveExplorationSystem {
    
    const val TRAVEL_DURATION = 1
    
    fun createCaveExplorationTeam(
        cave: CultivatorCave,
        disciples: List<Disciple>,
        currentYear: Int,
        currentMonth: Int,
        sectX: Float = 2000f,
        sectY: Float = 1750f,
        travelDuration: Int = TRAVEL_DURATION
    ): CaveExplorationTeam {
        return CaveExplorationTeam(
            id = "cave_exp_${cave.id}_${System.currentTimeMillis()}",
            caveId = cave.id,
            caveName = cave.name,
            memberIds = disciples.map { it.id },
            memberNames = disciples.map { it.name },
            startYear = currentYear,
            startMonth = currentMonth,
            duration = travelDuration,
            status = CaveExplorationStatus.TRAVELING,
            startX = sectX,
            startY = sectY,
            targetX = cave.x.toFloat(),
            targetY = cave.y.toFloat(),
            currentX = sectX,
            currentY = sectY,
            moveProgress = 0f
        )
    }
    
    fun createAIBattle(
        playerDisciples: List<Disciple>,
        playerEquipmentMap: Map<String, EquipmentInstance>,
        playerManualMap: Map<String, ManualInstance>,
        playerManualProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        aiTeam: AICaveTeam
    ): Battle {
        val playerCombatants = playerDisciples.map { disciple ->
            val discipleEquipment = buildMap {
                disciple.equipment.weaponId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.armorId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.bootsId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.accessoryId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
            }
            val discipleManuals = disciple.manualIds.mapNotNull { id -> playerManualMap[id]?.let { id to it } }.toMap()
            val discipleProficiencies = playerManualProficiencies[disciple.id] ?: emptyMap()
            val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
            val effectiveHp = if (disciple.combat.currentHp < 0) stats.maxHp else disciple.combat.currentHp.coerceAtMost(stats.maxHp)
            val effectiveMp = if (disciple.combat.currentMp < 0) stats.maxMp else disciple.combat.currentMp.coerceAtMost(stats.maxMp)
            val skills = disciple.manualIds.mapNotNull { manualId ->
                val manual = discipleManuals[manualId] ?: return@mapNotNull null
                val proficiencyData = discipleProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val baseSkill = manual.skill ?: return@mapNotNull null
                val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
                    baseSkill.damageMultiplier,
                    masteryLevel
                )
                baseSkill.copy(
                    damageMultiplier = adjustedMultiplier
                ).toCombatSkill(manualName = manual.name)
            }
            Combatant(
                id = disciple.id,
                name = disciple.name,
                side = CombatantSide.DEFENDER,
                hp = effectiveHp,
                maxHp = stats.maxHp,
                mp = effectiveMp,
                maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = stats.critRate,
                skills = skills,
                realm = disciple.realm,
                realmName = disciple.realmName,
                element = disciple.spiritRoot.types.firstOrNull()?.trim() ?: "metal"
            )
        }

        val aiCombatants = aiTeam.disciples.map { aiDisciple ->
            Combatant(
                id = aiDisciple.id,
                name = aiDisciple.name,
                side = CombatantSide.ATTACKER,
                hp = aiDisciple.maxHp,
                maxHp = aiDisciple.maxHp,
                mp = aiDisciple.maxHp / 5,
                maxMp = aiDisciple.maxHp / 5,
                physicalAttack = aiDisciple.physicalAttack,
                magicAttack = aiDisciple.magicAttack,
                physicalDefense = aiDisciple.physicalDefense,
                magicDefense = aiDisciple.magicDefense,
                speed = aiDisciple.speed,
                critRate = 0.05 + aiDisciple.realm * 0.01,
                skills = emptyList(),
                realm = aiDisciple.realm,
                realmName = aiDisciple.realmName,
                element = "metal"
            )
        }
        
        return Battle(
            team = playerCombatants,
            beasts = aiCombatants,
            turn = 0,
            isFinished = false,
            winner = null,
            maxTurns = Int.MAX_VALUE
        )
    }

    fun createGuardianBattle(
        playerDisciples: List<Disciple>,
        playerEquipmentMap: Map<String, EquipmentInstance>,
        playerManualMap: Map<String, ManualInstance>,
        playerManualProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        cave: CultivatorCave
    ): Battle {
        val playerCombatants = playerDisciples.map { disciple ->
            val discipleEquipment = buildMap {
                disciple.equipment.weaponId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.armorId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.bootsId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.accessoryId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
            }
            val discipleManuals = disciple.manualIds.mapNotNull { id -> playerManualMap[id]?.let { id to it } }.toMap()
            val discipleProficiencies = playerManualProficiencies[disciple.id] ?: emptyMap()
            val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
            val effectiveHp = if (disciple.combat.currentHp < 0) stats.maxHp else disciple.combat.currentHp.coerceAtMost(stats.maxHp)
            val effectiveMp = if (disciple.combat.currentMp < 0) stats.maxMp else disciple.combat.currentMp.coerceAtMost(stats.maxMp)
            val skills = disciple.manualIds.mapNotNull { manualId ->
                val manual = discipleManuals[manualId] ?: return@mapNotNull null
                val proficiencyData = discipleProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val baseSkill = manual.skill ?: return@mapNotNull null
                val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
                    baseSkill.damageMultiplier,
                    masteryLevel
                )
                baseSkill.copy(
                    damageMultiplier = adjustedMultiplier
                ).toCombatSkill(manualName = manual.name)
            }
            Combatant(
                id = disciple.id,
                name = disciple.name,
                side = CombatantSide.DEFENDER,
                hp = effectiveHp,
                maxHp = stats.maxHp,
                mp = effectiveMp,
                maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = stats.critRate,
                skills = skills,
                realm = disciple.realm,
                realmName = disciple.realmName,
                element = disciple.spiritRoot.types.firstOrNull()?.trim() ?: "metal"
            )
        }
        
        val guardianRealm = (cave.ownerRealm - 1).coerceIn(0, 9)
        val guardianCount = when {
            cave.ownerRealm <= 2 -> Random.nextInt(4, 7)
            cave.ownerRealm <= 4 -> Random.nextInt(3, 6)
            else -> Random.nextInt(2, 5)
        }
        val hasBoss = cave.ownerRealm <= 3 && Random.nextDouble() < 0.3
        val guardians = (1..guardianCount).mapIndexed { index, _ ->
            val isBoss = hasBoss && index == 0
            createGuardian(guardianRealm, index, isBoss)
        }
        
        return Battle(
            team = playerCombatants,
            beasts = guardians,
            turn = 0,
            isFinished = false,
            winner = null,
            maxTurns = Int.MAX_VALUE
        )
    }

    private fun createGuardian(realm: Int, index: Int, isBoss: Boolean = false): Combatant {
        val realmIndex = realm.coerceIn(0, 9)
        val stats = GameConfig.Beast.getRealmStats(realmIndex)
        val beastType = GameConfig.Beast.TYPES.random()
        val realmLayer = Random.nextInt(1, 10)
        val layerMult = 1.0 + (realmLayer - 1) * 0.1

        val hpVariance = -0.2 + Random.nextDouble() * 0.4
        val atkVariance = -0.2 + Random.nextDouble() * 0.4
        val defVariance = -0.2 + Random.nextDouble() * 0.4
        val speedVariance = -0.2 + Random.nextDouble() * 0.4

        val bossMultiplier = if (isBoss) 2.5 else 1.0

        val hp = (stats.hp * layerMult * (beastType.hpMod + hpVariance) * bossMultiplier).toInt()
        val mp = (stats.mp * layerMult * (beastType.hpMod + hpVariance) * bossMultiplier).toInt()
        val physicalAttack = (stats.attack * layerMult * (beastType.atkMod + atkVariance) * bossMultiplier).toInt()
        val magicAttack = (stats.attack * layerMult * (beastType.atkMod + atkVariance) * bossMultiplier).toInt()
        val physicalDefense = (stats.defense * layerMult * (beastType.defMod + defVariance) * bossMultiplier).toInt()
        val magicDefense = (stats.defense * layerMult * (beastType.defMod + defVariance) * bossMultiplier).toInt()
        val speed = (stats.speed * layerMult * (beastType.speedMod + speedVariance) * bossMultiplier).toInt()

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

        val guardianName = if (isBoss) "【首领】${beastType.prefix}${beastType.name}" else "守护兽·${beastType.prefix}${beastType.name}"

        return Combatant(
            id = if (isBoss) "guardian_boss_$index" else "guardian_$index",
            name = guardianName,
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
            critRate = 0.05 + realmIndex * 0.01 + if (isBoss) 0.1 else 0.0,
            skills = beastSkills,
            realm = realmIndex,
            realmName = GameConfig.Realm.getName(realmIndex),
            realmLayer = realmLayer,
            element = beastType.element
        )
    }
    
    fun generateVictoryRewards(cave: CultivatorCave): CaveRewards {
        val rewards = mutableListOf<CaveRewardItem>()
        
        val spiritStones = Random.nextInt(800, 5001)
        rewards.add(CaveRewardItem(
            type = "spiritStones",
            name = "灵石",
            quantity = spiritStones
        ))
        
        val rarityRange = CaveGenerator.getRarityRangeForCave(cave.ownerRealm)
        
        val itemTypes = listOf("pill", "equipment", "manual").shuffled().take(2)
        
        itemTypes.forEach { type ->
            val rarity = rarityRange.random()
            val quantity = Random.nextInt(1, 4)
            
            val item = when (type) {
                "pill" -> generateRandomPill(rarity)
                "equipment" -> generateRandomEquipment(rarity)
                "manual" -> generateRandomManual(rarity)
                else -> null
            }
            
            if (item != null) {
                rewards.add(CaveRewardItem(
                    type = type,
                    name = item.name,
                    quantity = quantity,
                    rarity = rarity,
                    itemId = item.itemId
                ))
            }
        }
        
        return CaveRewards(
            caveId = cave.id,
            caveName = cave.name,
            items = rewards
        )
    }
    
    private fun generateRandomPill(rarity: Int): CaveRewardItem? {
        var currentRarity = rarity
        while (currentRarity >= 1) {
            val recipes = PillRecipeDatabase.getRecipesByTier(currentRarity)
            if (recipes.isNotEmpty()) {
                val recipe = recipes.random()
                return CaveRewardItem(
                    type = "pill",
                    name = recipe.name,
                    quantity = 1,
                    rarity = currentRarity,
                    itemId = recipe.id
                )
            }
            currentRarity--
        }
        return null
    }
    
    private fun generateRandomEquipment(rarity: Int): CaveRewardItem? {
        var currentRarity = rarity
        while (currentRarity >= 1) {
            val allEquipment = EquipmentDatabase.weapons.values.filter { it.rarity == currentRarity } +
                               EquipmentDatabase.armors.values.filter { it.rarity == currentRarity } +
                               EquipmentDatabase.boots.values.filter { it.rarity == currentRarity } +
                               EquipmentDatabase.accessories.values.filter { it.rarity == currentRarity }
            
            if (allEquipment.isNotEmpty()) {
                val template = allEquipment.random()
                return CaveRewardItem(
                    type = "equipment",
                    name = template.name,
                    quantity = 1,
                    rarity = currentRarity,
                    itemId = template.id
                )
            }
            currentRarity--
        }
        return null
    }
    
    private fun generateRandomManual(rarity: Int): CaveRewardItem? {
        var currentRarity = rarity
        while (currentRarity >= 1) {
            val allManuals = ManualDatabase.getByRarity(currentRarity)
            
            if (allManuals.isNotEmpty()) {
                val template = allManuals.random()
                return CaveRewardItem(
                    type = "manual",
                    name = template.name,
                    quantity = 1,
                    rarity = currentRarity,
                    itemId = template.id
                )
            }
            currentRarity--
        }
        return null
    }
}

data class CaveRewards(
    val caveId: String,
    val caveName: String,
    val items: List<CaveRewardItem>
)

data class CaveRewardItem(
    val type: String,
    val name: String,
    val quantity: Int,
    val rarity: Int = 1,
    val itemId: String = ""
)
