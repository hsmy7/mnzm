package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameNotification
import kotlinx.coroutines.flow.StateFlow

interface DiscipleFacade {
    val disciples: StateFlow<List<Disciple>>
    val discipleAggregates: StateFlow<List<DiscipleAggregate>>
    val highFrequencyData: StateFlow<HighFrequencyData>
    val realtimeCultivation: StateFlow<Map<String, Double>>

    fun addDisciple(disciple: Disciple)
    fun removeDisciple(discipleId: String): Boolean
    fun getDiscipleById(discipleId: String): Disciple?
    fun updateDisciple(disciple: Disciple)
    suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple)
    fun getDiscipleStatus(discipleId: String): DiscipleStatus
    fun syncAllDiscipleStatuses()
    suspend fun resetAllDisciplesStatus()
    fun recruitDisciple(): Disciple
    suspend fun expelDisciple(discipleId: String): Boolean
    suspend fun expelTheftDisciple(discipleId: String): Boolean
    suspend fun imprisonTheftDisciple(discipleId: String, currentYear: Int)
    suspend fun releaseTheftDisciple(discipleId: String): Int
    suspend fun equipEquipment(discipleId: String, equipmentId: String): Boolean
    suspend fun unequipEquipment(discipleId: String, equipmentId: String): Boolean
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean
    fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean)
    fun getAliveDisciplesCount(): Int
    fun getIdleDisciples(): List<Disciple>
    suspend fun autoFillLawEnforcementSlots(): Int
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate?
    fun getAllDiscipleAggregates(): List<DiscipleAggregate>
    suspend fun approveMarriage(maleId: String, femaleId: String)
    suspend fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus)
    suspend fun updateFocusedDisciple(discipleId: String)
    suspend fun dismissDisciple(discipleId: String)
    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String)
    fun assignManual(discipleId: String, stackId: String)
    fun removeManual(discipleId: String, instanceId: String)
    fun recruitDiscipleFromList(discipleId: String)
    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>)
    fun updateElderSlots(newElderSlots: ElderSlots)
    fun assignDirectDisciple(
        elderSlotType: String,
        slotIndex: Int,
        discipleId: String,
        discipleName: String,
        discipleRealm: String,
        discipleSpiritRootColor: String
    )
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int)
    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String)
    fun removeDiscipleFromLibrarySlot(slotIndex: Int)
    fun clearPendingNotification()
    val pendingNotification: StateFlow<GameNotification?>
}
