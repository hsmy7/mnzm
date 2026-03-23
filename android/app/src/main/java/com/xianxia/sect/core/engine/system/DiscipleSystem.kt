package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleSystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "DiscipleSystem"
        const val SYSTEM_NAME = "DiscipleSystem"
    }
    
    private val mutex = Mutex()
    
    private val _disciples = MutableStateFlow<List<Disciple>>(emptyList())
    val disciples: StateFlow<List<Disciple>> = _disciples.asStateFlow()
    internal val mutableDisciples: MutableStateFlow<List<Disciple>> get() = _disciples
    
    private val _recruitList = MutableStateFlow<List<Disciple>>(emptyList())
    val recruitList: StateFlow<List<Disciple>> = _recruitList.asStateFlow()
    private val _lastRecruitYear = MutableStateFlow(0)
    val lastRecruitYear: StateFlow<Int> = _lastRecruitYear.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "DiscipleSystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "DiscipleSystem released")
    }
    
    override fun clear() {
        StateFlowListUtils.clearList(_disciples)
        StateFlowListUtils.clearList(_recruitList)
        _lastRecruitYear.value = 0
    }
    
    suspend fun <T> withLock(block: suspend DiscipleSystem.() -> T): T {
        return mutex.withLock { block() }
    }
    
    fun loadDisciples(disciples: List<Disciple>) = StateFlowListUtils.setList(_disciples, disciples)
    
    fun loadRecruitList(recruits: List<Disciple>, lastYear: Int) {
        if (lastYear < 0) {
            Log.w(TAG, "Invalid lastRecruitYear: $lastYear, must be non-negative")
            return
        }
        StateFlowListUtils.setList(_recruitList, recruits)
        _lastRecruitYear.value = lastYear
    }
    
    fun getDisciples(): List<Disciple> = _disciples.value
    fun getRecruitList(): List<Disciple> = _recruitList.value
    
    fun addDisciple(disciple: Disciple): Boolean {
        if (disciple.id.isBlank()) {
            Log.w(TAG, "Cannot add disciple with blank id")
            return false
        }
        if (_disciples.value.any { it.id == disciple.id }) {
            Log.w(TAG, "Disciple with id ${disciple.id} already exists")
            return false
        }
        StateFlowListUtils.addItem(_disciples, disciple)
        return true
    }
    
    fun addDisciples(newDisciples: List<Disciple>): Int {
        val existingIds = _disciples.value.map { it.id }.toSet()
        val validDisciples = newDisciples.filter { 
            it.id.isNotBlank() && it.id !in existingIds 
        }
        if (validDisciples.size < newDisciples.size) {
            Log.w(TAG, "Skipped ${newDisciples.size - validDisciples.size} disciples with blank or duplicate ids")
        }
        StateFlowListUtils.addAll(_disciples, validDisciples)
        return validDisciples.size
    }
    
    fun removeDisciple(discipleId: String): Boolean = 
        StateFlowListUtils.removeItemById(_disciples, discipleId, getId = { it.id })
    
    fun removeDisciples(discipleIds: List<String>): Int {
        if (discipleIds.isEmpty()) return 0
        return StateFlowListUtils.removeWhere(_disciples) { it.id in discipleIds }
    }
    
    fun updateDisciples(transform: (List<Disciple>) -> List<Disciple>) {
        _disciples.value = transform(_disciples.value)
    }
    
    fun updateDisciple(discipleId: String, transform: (Disciple) -> Disciple): Boolean =
        StateFlowListUtils.updateItemById(_disciples, discipleId, { it.id }, transform)
    
    fun getDiscipleById(id: String): Disciple? = StateFlowListUtils.findItemById(_disciples, id) { it.id }
    
    fun getDisciplesByIds(ids: List<String>): List<Disciple> {
        if (ids.isEmpty()) return emptyList()
        val idSet = ids.toSet()
        return _disciples.value.filter { it.id in idSet }
    }
    
    fun setDiscipleStatus(discipleId: String, status: DiscipleStatus): Boolean {
        return updateDisciple(discipleId) { it.copy(status = status) }
    }
    
    fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus): Boolean {
        return updateDisciple(discipleId) { it.copy(status = status) }
    }
    
    fun updateDiscipleStatuses(updates: Map<String, DiscipleStatus>): Int {
        if (updates.isEmpty()) return 0
        var updated = 0
        _disciples.value = _disciples.value.map { disciple ->
            updates[disciple.id]?.let { newStatus -> 
                updated++
                disciple.copy(status = newStatus) 
            } ?: disciple
        }
        return updated
    }
    
    fun getAliveDisciples(): List<Disciple> = _disciples.value.filter { it.isAlive }
    
    fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> = 
        _disciples.value.filter { it.status == status && it.isAlive }
    
    fun getIdleDisciples(): List<Disciple> = 
        _disciples.value.filter { it.status == DiscipleStatus.IDLE && it.isAlive }
    
    fun getDisciplesByRealm(realm: Int): List<Disciple> {
        if (realm < 0 || realm > 9) {
            Log.w(TAG, "Invalid realm: $realm, must be in range [0, 9]")
            return emptyList()
        }
        return _disciples.value.filter { it.realm == realm && it.isAlive }
    }
    
    fun getDisciplesByRealmRange(minRealm: Int, maxRealm: Int): List<Disciple> {
        if (minRealm < 0 || maxRealm > 9 || minRealm > maxRealm) {
            Log.w(TAG, "Invalid realm range: [$minRealm, $maxRealm]")
            return emptyList()
        }
        return _disciples.value.filter { it.realm in minRealm..maxRealm && it.isAlive }
    }
    
    fun getDiscipleCount(): Int = _disciples.value.count { it.isAlive }
    
    fun getDiscipleCountByStatus(status: DiscipleStatus): Int = 
        _disciples.value.count { it.status == status && it.isAlive }
    
    fun getDiscipleCountByRealm(realm: Int): Int = 
        _disciples.value.count { it.realm == realm && it.isAlive }
    
    fun getAverageRealm(): Double {
        val alive = _disciples.value.filter { it.isAlive }
        if (alive.isEmpty()) return 0.0
        return alive.map { 9 - it.realm }.average()
    }
    
    fun getTotalCultivation(): Double = 
        _disciples.value.filter { it.isAlive }.sumOf { it.cultivation }
    
    fun getTopDisciples(count: Int, by: (Disciple) -> Double): List<Disciple> {
        if (count <= 0) return emptyList()
        return _disciples.value.filter { it.isAlive }.sortedByDescending(by).take(count)
    }
    
    fun hasDisciple(discipleId: String): Boolean = 
        StateFlowListUtils.hasItemById(_disciples, discipleId) { it.id }
    
    fun isDiscipleAlive(discipleId: String): Boolean = 
        _disciples.value.find { it.id == discipleId }?.isAlive ?: false
    
    fun isDiscipleAvailable(discipleId: String): Boolean {
        val disciple = StateFlowListUtils.findItemById(_disciples, discipleId) { it.id } ?: return false
        return disciple.isAlive && disciple.status == DiscipleStatus.IDLE
    }
    
    fun refreshRecruitList(newRecruits: List<Disciple>, year: Int): Boolean {
        if (year < 0) {
            Log.w(TAG, "Invalid year: $year, must be non-negative")
            return false
        }
        StateFlowListUtils.setList(_recruitList, newRecruits)
        _lastRecruitYear.value = year
        return true
    }
    
    fun removeFromRecruitList(discipleId: String): Boolean = 
        StateFlowListUtils.removeItemById(_recruitList, discipleId, getId = { it.id })
    
    fun clearRecruitList() = StateFlowListUtils.clearList(_recruitList)
    
    fun recruitDisciple(discipleId: String, recruitedMonth: Int): Disciple? {
        if (recruitedMonth < 0) {
            Log.w(TAG, "Invalid recruitedMonth: $recruitedMonth, must be non-negative")
            return null
        }
        val recruit = _recruitList.value.find { it.id == discipleId } ?: return null
        val recruitedDisciple = recruit.copy(
            status = DiscipleStatus.IDLE,
            recruitedMonth = recruitedMonth
        )
        if (!addDisciple(recruitedDisciple)) {
            return null
        }
        removeFromRecruitList(discipleId)
        return recruitedDisciple
    }
    
    fun syncDiscipleStatuses(statusMap: Map<String, DiscipleStatus>): Int {
        if (statusMap.isEmpty()) return 0
        var synced = 0
        _disciples.value = _disciples.value.map { disciple ->
            if (!disciple.isAlive) disciple
            else statusMap[disciple.id]?.let { newStatus -> 
                synced++
                disciple.copy(status = newStatus) 
            } ?: disciple
        }
        return synced
    }
    
    fun handleDiscipleDeath(discipleId: String): Boolean {
        return updateDisciple(discipleId) { it.copy(isAlive = false, status = DiscipleStatus.IDLE) }
    }
    
    fun reviveDisciple(discipleId: String): Boolean {
        return updateDisciple(discipleId) { it.copy(isAlive = true) }
    }
    
    fun getDiscipleStats(): DiscipleStats {
        val alive = _disciples.value.filter { it.isAlive }
        return DiscipleStats(
            totalCount = alive.size,
            byStatus = DiscipleStatus.values().associateWith { status -> 
                alive.count { it.status == status } 
            },
            byRealm = (0..9).associateWith { realm -> 
                alive.count { it.realm == realm } 
            },
            averageCultivation = if (alive.isEmpty()) 0.0 else alive.map { it.cultivation }.average(),
            totalCombatPower = if (alive.isEmpty()) 0.0 else alive.map { 
                val stats = it.getBaseStats()
                stats.physicalAttack + stats.magicAttack + stats.physicalDefense + stats.magicDefense + stats.speed
            }.sum().toDouble()
        )
    }
}

data class DiscipleStats(
    val totalCount: Int,
    val byStatus: Map<DiscipleStatus, Int>,
    val byRealm: Map<Int, Int>,
    val averageCultivation: Double,
    val totalCombatPower: Double
)
