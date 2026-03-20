package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
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
        playerEquipmentMap: Map<String, Equipment>,
        playerManualMap: Map<String, Manual>,
        playerManualProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        aiTeam: AICaveTeam
    ): Battle {
        val playerCombatants = playerDisciples.map { disciple ->
            val discipleEquipment = buildMap {
                disciple.weaponId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.armorId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.bootsId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.accessoryId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
            }
            val discipleManuals = disciple.manualIds.mapNotNull { id -> playerManualMap[id]?.let { id to it } }.toMap()
            val discipleProficiencies = playerManualProficiencies[disciple.id] ?: emptyMap()
            val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
            Combatant(
                id = disciple.id,
                name = disciple.name,
                type = CombatantType.DISCIPLE,
                hp = stats.maxHp,
                maxHp = stats.maxHp,
                mp = stats.maxMp,
                maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = stats.critRate,
                skills = emptyList(),
                realm = disciple.realm,
                realmName = disciple.realmName
            )
        }
        
        val aiCombatants = aiTeam.disciples.map { aiDisciple ->
            Combatant(
                id = aiDisciple.id,
                name = aiDisciple.name,
                type = CombatantType.DISCIPLE,
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
                realmName = aiDisciple.realmName
            )
        }
        
        return Battle(
            team = playerCombatants,
            beasts = aiCombatants,
            turn = 0,
            isFinished = false,
            winner = null
        )
    }
    
    fun createGuardianBattle(
        playerDisciples: List<Disciple>,
        playerEquipmentMap: Map<String, Equipment>,
        playerManualMap: Map<String, Manual>,
        playerManualProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        cave: CultivatorCave
    ): Battle {
        val playerCombatants = playerDisciples.map { disciple ->
            val discipleEquipment = buildMap {
                disciple.weaponId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.armorId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.bootsId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
                disciple.accessoryId?.let { id -> playerEquipmentMap[id]?.let { put(id, it) } }
            }
            val discipleManuals = disciple.manualIds.mapNotNull { id -> playerManualMap[id]?.let { id to it } }.toMap()
            val discipleProficiencies = playerManualProficiencies[disciple.id] ?: emptyMap()
            val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
            Combatant(
                id = disciple.id,
                name = disciple.name,
                type = CombatantType.DISCIPLE,
                hp = stats.maxHp,
                maxHp = stats.maxHp,
                mp = stats.maxMp,
                maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = stats.critRate,
                skills = emptyList(),
                realm = disciple.realm,
                realmName = disciple.realmName
            )
        }
        
        val guardianRealm = (cave.ownerRealm - 1).coerceIn(0, 9)
        val guardians = (1..3).map { index ->
            createGuardian(guardianRealm, index)
        }
        
        return Battle(
            team = playerCombatants,
            beasts = guardians,
            turn = 0,
            isFinished = false,
            winner = null
        )
    }
    
    private fun createGuardian(realm: Int, index: Int): Combatant {
        val realmConfig = GameConfig.Realm.get(realm)
        val beastType = GameConfig.Beast.TYPES.random()
        
        val variance = 0.8 + Random.nextDouble() * 0.4
        val isPhysicalAttacker = Random.nextDouble() < 0.5
        val atkMultiplier = 0.8 + Random.nextDouble() * 0.4
        
        val realmMultiplier = if (realm <= 4) 2.0 else 1.0
        
        val physicalAttack: Int
        val magicAttack: Int
        if (isPhysicalAttacker) {
            physicalAttack = (realmConfig.cultivationBase * 5 * beastType.atkMod * atkMultiplier * realmMultiplier).toInt()
            magicAttack = (realmConfig.cultivationBase * 5 * beastType.atkMod * 0.3 * atkMultiplier * realmMultiplier).toInt()
        } else {
            physicalAttack = (realmConfig.cultivationBase * 5 * beastType.atkMod * 0.3 * atkMultiplier * realmMultiplier).toInt()
            magicAttack = (realmConfig.cultivationBase * 5 * beastType.atkMod * atkMultiplier * realmMultiplier).toInt()
        }
        
        val hp = (realmConfig.cultivationBase * 20 * beastType.hpMod * variance * realmMultiplier).toInt()
        val defense = (realmConfig.cultivationBase * 2 * beastType.defMod * variance * realmMultiplier).toInt()
        val speed = (50 + realm * 10 * beastType.speedMod * variance).toInt()
        
        return Combatant(
            id = "guardian_$index",
            name = "${beastType.prefix}${beastType.name}",
            type = CombatantType.BEAST,
            hp = hp,
            maxHp = hp,
            mp = (hp * 0.2).toInt(),
            maxMp = (hp * 0.2).toInt(),
            physicalAttack = physicalAttack,
            magicAttack = magicAttack,
            physicalDefense = defense,
            magicDefense = (defense * 0.8).toInt(),
            speed = speed,
            critRate = 0.05 + realm * 0.01,
            skills = emptyList(),
            realm = realm,
            realmName = GameConfig.Realm.getName(realm)
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
            val allManuals = ManualDatabase.attackManuals.values.filter { it.rarity == currentRarity } +
                             ManualDatabase.defenseManuals.values.filter { it.rarity == currentRarity } +
                             ManualDatabase.mindManuals.values.filter { it.rarity == currentRarity }
            
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
