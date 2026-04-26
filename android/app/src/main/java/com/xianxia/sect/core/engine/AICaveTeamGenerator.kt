package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import kotlin.math.sqrt
import kotlin.random.Random

object AICaveTeamGenerator {

    fun generateAITeam(
        cave: CultivatorCave,
        nearbySects: List<WorldSect>,
        existingTeams: List<AICaveTeam>,
        aiDisciplesMap: Map<String, List<Disciple>>
    ): AICaveTeam? {
        val usedSectIds = existingTeams.map { it.sectId }.toSet()

        val eligibleSects = nearbySects.filter { sect ->
            sect.id !in usedSectIds &&
            (aiDisciplesMap[sect.id] ?: emptyList()).any { disciple ->
                disciple.realm <= cave.ownerRealm && disciple.isAlive
            }
        }

        if (eligibleSects.isEmpty()) return null

        val targetSect = eligibleSects.minByOrNull { sect ->
            sqrt(
                (cave.x - sect.x).toDouble() * (cave.x - sect.x).toDouble() +
                (cave.y - sect.y).toDouble() * (cave.y - sect.y).toDouble()
            )
        } ?: return null

        val sectDisciples = aiDisciplesMap[targetSect.id] ?: emptyList()
        val eligibleDisciples = sectDisciples
            .filter { it.realm <= cave.ownerRealm && it.isAlive }
            .sortedBy { it.realm }

        if (eligibleDisciples.isEmpty()) return null

        val selectedDisciples = eligibleDisciples.take(10)

        val aiDisciples = selectedDisciples.mapIndexed { index, disciple ->
            convertToAICaveDisciple(disciple, index)
        }

        if (aiDisciples.isEmpty()) return null

        val avgRealm = GameUtils.calculateBeastRealm(
            aiDisciples,
            realmExtractor = { it.realm },
            layerExtractor = { null }
        )

        return AICaveTeam(
            id = "ai_team_${cave.id}_${System.currentTimeMillis()}_${Random.nextInt(1000)}",
            caveId = cave.id,
            sectId = targetSect.id,
            sectName = targetSect.name,
            memberCount = aiDisciples.size,
            avgRealm = avgRealm,
            avgRealmName = GameConfig.Realm.getName(avgRealm),
            disciples = aiDisciples,
            status = AITeamStatus.EXPLORING
        )
    }

    private fun convertToAICaveDisciple(
        disciple: Disciple,
        index: Int
    ): AICaveDisciple {
        val baseStats = disciple.getBaseStats()

        val battleItems = AISectDiscipleManager.generateBattleItems(disciple)

        val equipments = mutableListOf<AIRandomEquipment>()
        battleItems.equipments.forEach { (equipId, slot) ->
            createEquipmentFromBattleItem(equipId, slot, battleItems)?.let { equipments.add(it) }
        }

        val equipStats = calculateEquipmentStats(equipments)

        val manuals = battleItems.manuals.mapNotNull { (manualId, mastery) ->
            createManualFromBattleItem(manualId, mastery)
        }

        val manualStats = calculateManualStats(manuals)

        val finalHp = baseStats.hp + equipStats.hp + manualStats.hp
        val finalMp = baseStats.mp + equipStats.mp + manualStats.mp
        val finalAttack = baseStats.physicalAttack + equipStats.physicalAttack + manualStats.physicalAttack
        val finalMagicAttack = baseStats.magicAttack + equipStats.magicAttack + manualStats.magicAttack
        val finalDefense = baseStats.physicalDefense + equipStats.physicalDefense + manualStats.physicalDefense
        val finalMagicDefense = baseStats.magicDefense + equipStats.magicDefense + manualStats.magicDefense
        val finalSpeed = baseStats.speed + equipStats.speed + manualStats.speed

        return AICaveDisciple(
            id = disciple.id,
            name = disciple.name,
            realm = disciple.realm,
            realmName = GameConfig.Realm.getName(disciple.realm),
            hp = finalHp,
            maxHp = finalHp,
            mp = finalMp,
            maxMp = finalMp,
            physicalAttack = finalAttack,
            magicAttack = finalMagicAttack,
            physicalDefense = finalDefense,
            magicDefense = finalMagicDefense,
            speed = finalSpeed,
            critRate = baseStats.critRate,
            equipments = equipments,
            manuals = manuals
        )
    }

    private fun createEquipmentFromBattleItem(
        equipmentId: String,
        slot: EquipmentSlot,
        battleItems: AISectDiscipleManager.BattleItems
    ): AIRandomEquipment? {
        val template = EquipmentDatabase.getById(equipmentId) ?: return null

        val nurtureLevel = when (slot) {
            EquipmentSlot.WEAPON -> battleItems.weaponNurture.nurtureLevel
            EquipmentSlot.ARMOR -> battleItems.armorNurture.nurtureLevel
            EquipmentSlot.BOOTS -> battleItems.bootsNurture.nurtureLevel
            EquipmentSlot.ACCESSORY -> battleItems.accessoryNurture.nurtureLevel
        }

        val nurtureMult = 1.0 + nurtureLevel * 0.05

        return AIRandomEquipment(
            slot = slot,
            name = template.name,
            rarity = template.rarity,
            nurtureLevel = nurtureLevel,
            physicalAttack = (template.physicalAttack * nurtureMult).toInt(),
            magicAttack = (template.magicAttack * nurtureMult).toInt(),
            physicalDefense = (template.physicalDefense * nurtureMult).toInt(),
            magicDefense = (template.magicDefense * nurtureMult).toInt(),
            speed = (template.speed * nurtureMult).toInt(),
            hp = (template.hp * nurtureMult).toInt(),
            mp = (template.mp * nurtureMult).toInt()
        )
    }

    private fun createManualFromBattleItem(manualId: String, mastery: Int): AIRandomManual? {
        val template = ManualDatabase.getById(manualId) ?: return null

        val masteryBonus = when {
            mastery >= 300 -> 1.5
            mastery >= 200 -> 1.3
            mastery >= 100 -> 1.2
            mastery >= 60 -> 1.1
            mastery >= 20 -> 1.05
            else -> 1.0
        }

        val boostedStats = template.stats.mapValues { (_, value) ->
            (value * masteryBonus).toInt()
        }

        return AIRandomManual(
            name = template.name,
            rarity = template.rarity,
            mastery = mastery,
            stats = boostedStats
        )
    }

    private fun calculateEquipmentStats(equipments: List<AIRandomEquipment>): EquipmentStats {
        return EquipmentStats(
            physicalAttack = equipments.sumOf { it.physicalAttack },
            magicAttack = equipments.sumOf { it.magicAttack },
            physicalDefense = equipments.sumOf { it.physicalDefense },
            magicDefense = equipments.sumOf { it.magicDefense },
            speed = equipments.sumOf { it.speed },
            hp = equipments.sumOf { it.hp },
            mp = equipments.sumOf { it.mp }
        )
    }

    private fun calculateManualStats(manuals: List<AIRandomManual>): ManualStats {
        val stats = mutableMapOf<String, Int>()

        manuals.forEach { manual ->
            manual.stats.forEach { (key, value) ->
                stats[key] = (stats[key] ?: 0) + value
            }
        }

        return ManualStats(
            hp = stats["hp"] ?: 0,
            physicalAttack = stats["physicalAttack"] ?: 0,
            magicAttack = stats["magicAttack"] ?: 0,
            physicalDefense = stats["physicalDefense"] ?: 0,
            magicDefense = stats["magicDefense"] ?: 0,
            speed = stats["speed"] ?: 0,
            mp = stats["mp"] ?: 0
        )
    }

    private data class EquipmentStats(
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int,
        val hp: Int,
        val mp: Int
    )

    private data class ManualStats(
        val hp: Int,
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int,
        val mp: Int
    )
}
