package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.*
import kotlinx.coroutines.flow.first

object EntityQueryHelper {
    
    data class DiscipleQuery(
        val name: String? = null,
        val minRealm: Int? = null,
        val maxRealm: Int? = null,
        val isAlive: Boolean? = true,
        val status: DiscipleStatus? = null,
        val discipleType: String? = null,
        val maxLoyalty: Int? = null,
        val minAge: Int? = null,
        val limit: Int? = null
    )
    
    data class EquipmentQuery(
        val name: String? = null,
        val minRarity: Int? = null,
        val slot: EquipmentSlot? = null,
        val ownerId: String? = null,
        val isEquipped: Boolean? = null,
        val maxRealm: Int? = null,
        val limit: Int? = null
    )
    
    data class ManualQuery(
        val name: String? = null,
        val minRarity: Int? = null,
        val type: ManualType? = null,
        val ownerId: String? = null,
        val isLearned: Boolean? = null,
        val maxRealm: Int? = null,
        val limit: Int? = null
    )
    
    data class PillQuery(
        val name: String? = null,
        val minRarity: Int? = null,
        val category: PillCategory? = null,
        val targetRealm: Int? = null,
        val limit: Int? = null
    )
    
    data class MaterialQuery(
        val name: String? = null,
        val minRarity: Int? = null,
        val category: MaterialCategory? = null,
        val limit: Int? = null
    )
    
    suspend fun queryDisciples(dao: DiscipleDao, query: DiscipleQuery): List<Disciple> {
        val baseList = when {
            query.name != null -> dao.searchByName(query.name)
            query.status != null -> dao.getByStatus(query.status)
            else -> dao.getAllAliveSync()
        }
        
        return baseList
            .filter { disciple ->
                query.minRealm?.let { disciple.realm >= it } ?: true
            }
            .filter { disciple ->
                query.maxRealm?.let { disciple.realm <= it } ?: true
            }
            .filter { disciple ->
                query.isAlive?.let { disciple.isAlive == it } ?: true
            }
            .filter { disciple ->
                query.discipleType?.let { disciple.discipleType == it } ?: true
            }
            .filter { disciple ->
                query.maxLoyalty?.let { disciple.loyalty <= it } ?: true
            }
            .filter { disciple ->
                query.minAge?.let { disciple.age >= it } ?: true
            }
            .let { list ->
                query.limit?.let { limit -> list.take(limit) } ?: list
            }
    }
    
    suspend fun queryEquipment(dao: EquipmentDao, query: EquipmentQuery): List<Equipment> {
        val baseList = when {
            query.ownerId != null -> dao.getByOwner(query.ownerId)
            query.name != null -> dao.searchByName(query.name)
            query.slot != null -> dao.getUnequippedBySlot(query.slot).first()
            else -> dao.getUnequipped().first()
        }
        
        return baseList
            .filter { equipment ->
                query.minRarity?.let { equipment.rarity >= it } ?: true
            }
            .filter { equipment ->
                query.isEquipped?.let { equipment.isEquipped == it } ?: true
            }
            .filter { equipment ->
                query.maxRealm?.let { equipment.minRealm <= it } ?: true
            }
            .let { list ->
                query.limit?.let { limit -> list.take(limit) } ?: list
            }
    }
    
    suspend fun queryManuals(dao: ManualDao, query: ManualQuery): List<Manual> {
        val baseList = when {
            query.ownerId != null -> dao.getByOwner(query.ownerId)
            query.name != null -> dao.searchByName(query.name)
            query.type != null -> dao.getByType(query.type).first()
            else -> dao.getUnlearned().first()
        }
        
        return baseList
            .filter { manual ->
                query.minRarity?.let { manual.rarity >= it } ?: true
            }
            .filter { manual ->
                query.isLearned?.let { manual.isLearned == it } ?: true
            }
            .filter { manual ->
                query.maxRealm?.let { manual.minRealm <= it } ?: true
            }
            .let { list ->
                query.limit?.let { limit -> list.take(limit) } ?: list
            }
    }
    
    suspend fun queryPills(dao: PillDao, query: PillQuery): List<Pill> {
        val baseList = when {
            query.name != null -> dao.searchByName(query.name)
            query.category != null -> dao.getByCategory(query.category).first()
            query.targetRealm != null -> dao.getByTargetRealm(query.targetRealm).first()
            else -> dao.getAll().first()
        }
        
        return baseList
            .filter { pill ->
                query.minRarity?.let { pill.rarity >= it } ?: true
            }
            .filter { pill -> pill.quantity > 0 }
            .let { list ->
                query.limit?.let { limit -> list.take(limit) } ?: list
            }
    }
    
    suspend fun queryMaterials(dao: MaterialDao, query: MaterialQuery): List<Material> {
        val baseList = when {
            query.name != null -> dao.searchByName(query.name)
            query.category != null -> dao.getByCategory(query.category).first()
            else -> dao.getAll().first()
        }
        
        return baseList
            .filter { material ->
                query.minRarity?.let { material.rarity >= it } ?: true
            }
            .filter { material -> material.quantity > 0 }
            .let { list ->
                query.limit?.let { limit -> list.take(limit) } ?: list
            }
    }
}

object DiscipleSorter {
    
    fun byRealm(disciples: List<Disciple>): List<Disciple> = 
        disciples.sortedByDescending { it.realm * 100 + it.realmLayer }
    
    fun byCultivation(disciples: List<Disciple>): List<Disciple> = 
        disciples.sortedByDescending { it.cultivation }
    
    fun byAge(disciples: List<Disciple>): List<Disciple> = 
        disciples.sortedByDescending { it.age }
    
    fun byLoyalty(disciples: List<Disciple>): List<Disciple> = 
        disciples.sortedBy { it.loyalty }
    
    fun byCombatPower(disciples: List<Disciple>): List<Disciple> = 
        disciples.sortedByDescending { disciple ->
            val stats = disciple.getBaseStats()
            stats.physicalAttack + stats.magicAttack + 
            stats.physicalDefense + stats.magicDefense + 
            stats.maxHp / 10 + stats.speed * 2
        }
}

object EquipmentSorter {
    
    fun byRarity(equipments: List<Equipment>): List<Equipment> = 
        equipments.sortedByDescending { it.rarity }
    
    fun bySlot(equipments: List<Equipment>): List<Equipment> = 
        equipments.sortedBy { it.slot.ordinal }
    
    fun byStats(equipments: List<Equipment>): List<Equipment> = 
        equipments.sortedByDescending { equipment ->
            equipment.physicalAttack + equipment.magicAttack + 
            equipment.physicalDefense + equipment.magicDefense + 
            equipment.hp + equipment.mp + equipment.speed
        }
}

object ManualSorter {
    
    fun byRarity(manuals: List<Manual>): List<Manual> = 
        manuals.sortedByDescending { it.rarity }
    
    fun byType(manuals: List<Manual>): List<Manual> = 
        manuals.sortedBy { it.type.ordinal }
}
