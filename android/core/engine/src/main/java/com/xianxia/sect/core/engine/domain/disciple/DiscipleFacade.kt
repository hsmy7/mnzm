package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.flow.StateFlow



@Suppress("TooManyFunctions") // 弟子域门面契约：模板实例化/管理/状态/关系全生命周期端口，函数数即门面协议面
interface DiscipleFacade {
    val disciples: StateFlow<List<Disciple>>
    val discipleAggregates: StateFlow<List<DiscipleAggregate>>
    val highFrequencyData: StateFlow<HighFrequencyData>

    fun addDisciple(disciple: Disciple)
    fun removeDisciple(discipleId: String): DomainResult<Unit>
    fun getDiscipleById(discipleId: String): Disciple?
    fun updateDisciple(disciple: Disciple)
    fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple)
    fun getDiscipleStatus(discipleId: String): DiscipleStatus
    fun syncAllDiscipleStatuses()
    fun syncSingleDiscipleStatus(discipleId: String)
    suspend fun resetAllDisciplesStatus()

    /**
     * 角色模板实例化入册（弟子构造的唯一生产端口，签名对齐
     * [DiscipleService.instantiateTemplate]）。开局名册与寻访解锁入册（G09）共用此入口。
     */
    fun instantiateTemplate(templateId: String): DomainResult<Disciple>
    fun releaseReflectionDisciple(discipleId: String)
    fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit>
    fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit>
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean
    fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean)
    fun getAliveDisciplesCount(): Int
    fun getIdleDisciples(): List<Disciple>
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate?
    fun getAllDiscipleAggregates(): List<DiscipleAggregate>
    fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus)
    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String)
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
