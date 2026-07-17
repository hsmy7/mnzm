package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.flow.StateFlow

interface DiscipleFacade {
    val disciples: StateFlow<List<Disciple>>
    val discipleAggregates: StateFlow<List<DiscipleAggregate>>
    val highFrequencyData: StateFlow<HighFrequencyData>
    val realtimeCultivation: StateFlow<Map<String, Double>>

    fun addDisciple(disciple: Disciple)
    fun removeDisciple(discipleId: String): DomainResult<Unit>
    fun getDiscipleById(discipleId: String): Disciple?
    fun updateDisciple(disciple: Disciple)
    fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple)
    fun getDiscipleStatus(discipleId: String): DiscipleStatus
    fun syncAllDiscipleStatuses()
    suspend fun resetAllDisciplesStatus()
    fun recruitDisciple(): Disciple
    fun expelDisciple(discipleId: String): DomainResult<Unit>
    fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit>
    fun expelTheftDisciple(discipleId: String): DomainResult<Unit>
    fun imprisonTheftDisciple(discipleId: String, currentYear: Int)
    fun releaseTheftDisciple(discipleId: String): Int
    fun releaseReflectionDisciple(discipleId: String)
    fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit>
    fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit>
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean
    fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean)
    fun getAliveDisciplesCount(): Int
    fun getIdleDisciples(): List<Disciple>
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate?
    fun getAllDiscipleAggregates(): List<DiscipleAggregate>
    fun approveMarriage(maleId: String, femaleId: String)
    fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus)
    fun dismissDisciple(discipleId: String)
    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String)
    fun assignManual(discipleId: String, stackId: String)
    fun removeManual(discipleId: String, instanceId: String)
    fun recruitDiscipleFromList(discipleId: String): String
    fun addLifeEvent(discipleId: String, event: String)
    fun getLifeEvents(discipleId: String): List<String>
    fun initializeLifeEvents(discipleId: String)
    fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>): DomainResult<Unit>
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
