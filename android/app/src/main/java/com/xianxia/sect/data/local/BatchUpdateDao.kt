@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow

object SafeTableRegistry {
    private val ALLOWED_TABLES = setOf(
        "disciples", "disciples_core", "disciples_combat", "disciples_equipment",
        "disciples_extended", "disciples_attributes", "equipment", "manuals",
        "pills", "materials", "herbs", "seeds", "battle_logs", "game_events",
        "exploration_teams", "building_slots", "alchemy_slots", "forge_slots",
        "dungeons", "recipes", "change_log", "production_slots"
    )
    
    fun validateTableName(tableName: String): String {
        val normalized = tableName.lowercase().trim()
        require(normalized in ALLOWED_TABLES) { 
            "Invalid table name: $tableName. Allowed tables: ${ALLOWED_TABLES.joinToString()}" 
        }
        return normalized
    }
    
    fun isAllowedTable(tableName: String): Boolean {
        return tableName.lowercase().trim() in ALLOWED_TABLES
    }
}

@Dao
interface BatchUpdateDao {
    
    @Transaction
    @Query("""
        UPDATE disciples_core 
        SET realm = :realm, 
            realmLayer = :realmLayer, 
            cultivation = :cultivation,
            updatedAt = :updatedAt
        WHERE id IN (:ids)
    """)
    suspend fun batchUpdateDiscipleRealm(
        ids: List<String>,
        realm: Int,
        realmLayer: Int,
        cultivation: Double,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
    
    @Transaction
    @Query("""
        UPDATE disciples_core 
        SET status = :status, 
            updatedAt = :updatedAt
        WHERE id IN (:ids)
    """)
    suspend fun batchUpdateDiscipleStatus(
        ids: List<String>,
        status: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
    
    @Transaction
    @Query("""
        UPDATE disciples_core 
        SET isAlive = 0, 
            updatedAt = :updatedAt
        WHERE id IN (:ids)
    """)
    suspend fun batchMarkDisciplesDead(
        ids: List<String>,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
    
    @Transaction
    @Query("""
        UPDATE disciples_core 
        SET age = age + 1,
            updatedAt = :updatedAt
        WHERE isAlive = 1
    """)
    suspend fun incrementAllDiscipleAge(
        updatedAt: Long = System.currentTimeMillis()
    ): Int
    
    @Transaction
    @Query("""
        UPDATE disciples_combat 
        SET totalCultivation = totalCultivation + :amount
        WHERE discipleId IN (:discipleIds)
    """)
    suspend fun batchAddCultivation(discipleIds: List<String>, amount: Long): Int

    @Transaction
    @Query("""
        UPDATE disciples_attributes 
        SET loyalty = loyalty + :delta
        WHERE discipleId IN (:discipleIds) AND (loyalty + :delta) BETWEEN 0 AND 100
    """)
    suspend fun batchAdjustLoyalty(discipleIds: List<String>, delta: Int): Int
    
    @Transaction
    @Query("""
        UPDATE disciples_equipment 
        SET spiritStones = spiritStones + :amount
        WHERE discipleId IN (:discipleIds)
    """)
    suspend fun batchAddSpiritStones(discipleIds: List<String>, amount: Int): Int
    
    @Transaction
    @Query("DELETE FROM disciples_core WHERE id IN (:ids)")
    suspend fun batchDeleteDisciples(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM disciples_combat WHERE discipleId IN (:ids)")
    suspend fun batchDeleteDiscipleCombat(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM disciples_equipment WHERE discipleId IN (:ids)")
    suspend fun batchDeleteDiscipleEquipment(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM disciples_extended WHERE discipleId IN (:ids)")
    suspend fun batchDeleteDiscipleExtended(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM disciples_attributes WHERE discipleId IN (:ids)")
    suspend fun batchDeleteDiscipleAttributes(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM equipment WHERE id IN (:ids)")
    suspend fun batchDeleteEquipment(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM manuals WHERE id IN (:ids)")
    suspend fun batchDeleteManuals(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM pills WHERE id IN (:ids)")
    suspend fun batchDeletePills(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM materials WHERE id IN (:ids)")
    suspend fun batchDeleteMaterials(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM herbs WHERE id IN (:ids)")
    suspend fun batchDeleteHerbs(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM seeds WHERE id IN (:ids)")
    suspend fun batchDeleteSeeds(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM battle_logs WHERE id IN (:ids)")
    suspend fun batchDeleteBattleLogs(ids: List<String>): Int
    
    @Transaction
    @Query("DELETE FROM game_events WHERE id IN (:ids)")
    suspend fun batchDeleteGameEvents(ids: List<String>): Int
    
    @Transaction
    @Query("""
        UPDATE pills 
        SET quantity = quantity - :amount
        WHERE id = :id AND quantity >= :amount
    """)
    suspend fun consumePill(id: String, amount: Int = 1): Int
    
    @Transaction
    @Query("""
        UPDATE materials 
        SET quantity = quantity - :amount
        WHERE id = :id AND quantity >= :amount
    """)
    suspend fun consumeMaterial(id: String, amount: Int = 1): Int
    
    @Transaction
    @Query("""
        UPDATE herbs 
        SET quantity = quantity - :amount
        WHERE id = :id AND quantity >= :amount
    """)
    suspend fun consumeHerb(id: String, amount: Int = 1): Int
    
    @Transaction
    @Query("""
        UPDATE seeds 
        SET quantity = quantity - :amount
        WHERE id = :id AND quantity >= :amount
    """)
    suspend fun consumeSeed(id: String, amount: Int = 1): Int
    
    @Transaction
    @Query("DELETE FROM game_events WHERE timestamp < :before")
    suspend fun deleteOldEvents(before: Long): Int
    
    @Transaction
    @Query("DELETE FROM battle_logs WHERE timestamp < :before")
    suspend fun deleteOldBattleLogs(before: Long): Int
    
    @Transaction
    @Query("DELETE FROM pills WHERE quantity <= 0")
    suspend fun deleteEmptyPills(): Int
    
    @Transaction
    @Query("DELETE FROM materials WHERE quantity <= 0")
    suspend fun deleteEmptyMaterials(): Int
    
    @Transaction
    @Query("DELETE FROM herbs WHERE quantity <= 0")
    suspend fun deleteEmptyHerbs(): Int
    
    @Transaction
    @Query("DELETE FROM seeds WHERE quantity <= 0")
    suspend fun deleteEmptySeeds(): Int
    
    @Transaction
    @Query("DELETE FROM disciples_core WHERE isAlive = 0")
    suspend fun deleteDeadDisciples(): Int
}

fun Disciple.toSplitEntities(): SplitDiscipleEntities {
    val core = DiscipleCore(
        id = id,
        name = name,
        realm = realm,
        realmLayer = realmLayer,
        cultivation = cultivation,
        spiritRootType = spiritRootType,
        age = age,
        lifespan = lifespan,
        isAlive = isAlive,
        status = status.name,
        discipleType = discipleType,
        gender = gender,
        recruitedMonth = recruitedMonth
    )
    
    val combatStats = DiscipleCombatStats(
        discipleId = id,
        baseHp = baseHp,
        baseMp = baseMp,
        basePhysicalAttack = basePhysicalAttack,
        baseMagicAttack = baseMagicAttack,
        basePhysicalDefense = basePhysicalDefense,
        baseMagicDefense = baseMagicDefense,
        baseSpeed = baseSpeed,
        hpVariance = hpVariance,
        mpVariance = mpVariance,
        physicalAttackVariance = physicalAttackVariance,
        magicAttackVariance = magicAttackVariance,
        physicalDefenseVariance = physicalDefenseVariance,
        magicDefenseVariance = magicDefenseVariance,
        speedVariance = speedVariance,
        pillPhysicalAttackBonus = pillPhysicalAttackBonus,
        pillMagicAttackBonus = pillMagicAttackBonus,
        pillPhysicalDefenseBonus = pillPhysicalDefenseBonus,
        pillMagicDefenseBonus = pillMagicDefenseBonus,
        pillHpBonus = pillHpBonus,
        pillMpBonus = pillMpBonus,
        pillSpeedBonus = pillSpeedBonus,
        pillEffectDuration = pillEffectDuration,
        totalCultivation = totalCultivation,
        breakthroughCount = breakthroughCount,
        breakthroughFailCount = breakthroughFailCount,
        currentHp = currentHp,
        currentMp = currentMp
    )
    
    val equipment = DiscipleEquipment(
        discipleId = id,
        weaponId = weaponId,
        armorId = armorId,
        bootsId = bootsId,
        accessoryId = accessoryId,
        weaponNurture = weaponNurture,
        armorNurture = armorNurture,
        bootsNurture = bootsNurture,
        accessoryNurture = accessoryNurture,
        storageBagItems = storageBagItems,
        storageBagSpiritStones = storageBagSpiritStones,
        spiritStones = spiritStones,
        soulPower = soulPower
    )
    
    val extended = DiscipleExtended(
        discipleId = id,
        manualIds = manualIds,
        talentIds = talentIds,
        manualMasteries = manualMasteries,
        statusData = statusData,
        cultivationSpeedBonus = cultivationSpeedBonus,
        cultivationSpeedDuration = cultivationSpeedDuration,
        partnerId = partnerId,
        partnerSectId = partnerSectId,
        parentId1 = parentId1,
        parentId2 = parentId2,
        lastChildYear = lastChildYear,
        griefEndYear = griefEndYear,
        monthlyUsedPillIds = monthlyUsedPillIds,
        usedExtendLifePillIds = usedExtendLifePillIds,
        hasReviveEffect = hasReviveEffect,
        hasClearAllEffect = hasClearAllEffect
    )
    
    val attrs = DiscipleAttributes(
        discipleId = id,
        intelligence = intelligence,
        charm = charm,
        loyalty = loyalty,
        comprehension = comprehension,
        artifactRefining = artifactRefining,
        pillRefining = pillRefining,
        spiritPlanting = spiritPlanting,
        teaching = teaching,
        morality = morality,
        salaryPaidCount = salaryPaidCount,
        salaryMissedCount = salaryMissedCount
    )
    
    return SplitDiscipleEntities(core, combatStats, equipment, extended, attrs)
}

data class SplitDiscipleEntities(
    val core: DiscipleCore,
    val combatStats: DiscipleCombatStats,
    val equipment: DiscipleEquipment,
    val extended: DiscipleExtended,
    val attributes: DiscipleAttributes
)
