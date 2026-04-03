package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleIndex @Inject constructor() {
    
    private val byId = ConcurrentHashMap<String, Disciple>()
    private val byStatus = ConcurrentHashMap<DiscipleStatus, MutableSet<String>>()
    private val byRealm = TreeMap<Int, MutableSet<String>>(reverseOrder())
    private val byAlive = ConcurrentHashMap<Boolean, MutableSet<String>>()
    private val byDiscipleType = ConcurrentHashMap<String, MutableSet<String>>()
    
    private val lock = Any()
    
    fun index(disciple: Disciple) {
        synchronized(lock) {
            indexInternal(disciple)
        }
    }
    
    private fun indexInternal(disciple: Disciple) {
        byId[disciple.id] = disciple
        
        byStatus.getOrPut(disciple.status) { 
            ConcurrentHashMap.newKeySet() 
        }.add(disciple.id)
        
        byRealm.getOrPut(disciple.realm) { 
            ConcurrentHashMap.newKeySet() 
        }.add(disciple.id)
        
        byAlive.getOrPut(disciple.isAlive) { 
            ConcurrentHashMap.newKeySet() 
        }.add(disciple.id)
        
        byDiscipleType.getOrPut(disciple.discipleType) { 
            ConcurrentHashMap.newKeySet() 
        }.add(disciple.id)
    }
    
    fun indexAll(disciples: List<Disciple>) {
        synchronized(lock) {
            clearInternal()
            disciples.forEach { disciple ->
                byId[disciple.id] = disciple
                
                byStatus.getOrPut(disciple.status) { 
                    ConcurrentHashMap.newKeySet() 
                }.add(disciple.id)
                
                byRealm.getOrPut(disciple.realm) { 
                    ConcurrentHashMap.newKeySet() 
                }.add(disciple.id)
                
                byAlive.getOrPut(disciple.isAlive) { 
                    ConcurrentHashMap.newKeySet() 
                }.add(disciple.id)
                
                byDiscipleType.getOrPut(disciple.discipleType) { 
                    ConcurrentHashMap.newKeySet() 
                }.add(disciple.id)
            }
        }
    }
    
    fun update(disciple: Disciple) {
        synchronized(lock) {
            val existing = byId[disciple.id]
            if (existing == null) {
                indexInternal(disciple)
            } else {
                if (existing.status != disciple.status) {
                    byStatus[existing.status]?.remove(disciple.id)
                    byStatus.getOrPut(disciple.status) { 
                        ConcurrentHashMap.newKeySet() 
                    }.add(disciple.id)
                }
                
                if (existing.realm != disciple.realm) {
                    byRealm[existing.realm]?.remove(disciple.id)
                    byRealm.getOrPut(disciple.realm) { 
                        ConcurrentHashMap.newKeySet() 
                    }.add(disciple.id)
                }
                
                if (existing.isAlive != disciple.isAlive) {
                    byAlive[existing.isAlive]?.remove(disciple.id)
                    byAlive.getOrPut(disciple.isAlive) { 
                        ConcurrentHashMap.newKeySet() 
                    }.add(disciple.id)
                }
                
                if (existing.discipleType != disciple.discipleType) {
                    byDiscipleType[existing.discipleType]?.remove(disciple.id)
                    byDiscipleType.getOrPut(disciple.discipleType) { 
                        ConcurrentHashMap.newKeySet() 
                    }.add(disciple.id)
                }
                
                byId[disciple.id] = disciple
            }
        }
    }
    
    fun getById(id: String): Disciple? = synchronized(lock) { byId[id] }
    
    fun getByIds(ids: Collection<String>): List<Disciple> = synchronized(lock) {
        ids.mapNotNull { byId[it] }
    }
    
    fun getByStatus(status: DiscipleStatus): List<Disciple> = synchronized(lock) {
        byStatus[status]?.mapNotNull { byId[it] } ?: emptyList()
    }
    
    fun getByStatuses(vararg statuses: DiscipleStatus): List<Disciple> = synchronized(lock) {
        statuses.flatMap { status -> 
            byStatus[status]?.mapNotNull { byId[it] } ?: emptyList() 
        }
    }
    
    fun getByRealm(realm: Int): List<Disciple> = synchronized(lock) {
        byRealm[realm]?.mapNotNull { byId[it] } ?: emptyList()
    }
    
    fun getByRealmRange(minRealm: Int, maxRealm: Int): List<Disciple> = synchronized(lock) {
        byRealm.subMap(maxRealm, true, minRealm, true)
            .values.flatten()
            .mapNotNull { byId[it] }
    }
    
    fun getByAlive(isAlive: Boolean): List<Disciple> = synchronized(lock) {
        byAlive[isAlive]?.mapNotNull { byId[it] } ?: emptyList()
    }
    
    fun getByDiscipleType(type: String): List<Disciple> = synchronized(lock) {
        byDiscipleType[type]?.mapNotNull { byId[it] } ?: emptyList()
    }
    
    fun getAliveByStatus(status: DiscipleStatus): List<Disciple> = synchronized(lock) {
        byStatus[status]?.mapNotNull { id -> 
            byId[id]?.takeIf { it.isAlive } 
        } ?: emptyList()
    }
    
    fun getAliveByDiscipleType(type: String): List<Disciple> = synchronized(lock) {
        byDiscipleType[type]?.mapNotNull { id -> 
            byId[id]?.takeIf { it.isAlive } 
        } ?: emptyList()
    }
    
    fun getAliveByRealmRange(minRealm: Int, maxRealm: Int): List<Disciple> = synchronized(lock) {
        byRealm.subMap(maxRealm, true, minRealm, true)
            .values.flatten()
            .mapNotNull { byId[it]?.takeIf { d -> d.isAlive } }
    }
    
    fun remove(discipleId: String) {
        synchronized(lock) {
            byId[discipleId]?.let { disciple ->
                byStatus[disciple.status]?.remove(discipleId)
                byRealm[disciple.realm]?.remove(discipleId)
                byAlive[disciple.isAlive]?.remove(discipleId)
                byDiscipleType[disciple.discipleType]?.remove(discipleId)
                byId.remove(discipleId)
            }
        }
    }
    
    fun contains(id: String): Boolean = synchronized(lock) { byId.containsKey(id) }
    
    fun size(): Int = synchronized(lock) { byId.size }
    
    fun getAll(): List<Disciple> = synchronized(lock) { byId.values.toList() }
    
    fun clear() {
        synchronized(lock) {
            clearInternal()
        }
    }
    
    private fun clearInternal() {
        byId.clear()
        byStatus.clear()
        byRealm.clear()
        byAlive.clear()
        byDiscipleType.clear()
    }
    
    fun getIndexStats(): IndexStats = synchronized(lock) {
        IndexStats(
            totalCount = byId.size,
            byStatusCount = byStatus.mapValues { it.value.size },
            byRealmCount = byRealm.mapValues { it.value.size },
            aliveCount = byAlive[true]?.size ?: 0,
            deadCount = byAlive[false]?.size ?: 0,
            outerDisciplesCount = byDiscipleType["outer"]?.size ?: 0,
            innerDisciplesCount = byDiscipleType["inner"]?.size ?: 0
        )
    }
}

data class IndexStats(
    val totalCount: Int,
    val byStatusCount: Map<DiscipleStatus, Int>,
    val byRealmCount: Map<Int, Int>,
    val aliveCount: Int,
    val deadCount: Int,
    val outerDisciplesCount: Int,
    val innerDisciplesCount: Int
)
