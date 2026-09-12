package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.domain.building.updateDiscipleStatus





fun GameEngine.addDisciple(disciple: Disciple) = discipleFacade.addDisciple(disciple)
fun GameEngine.removeDisciple(discipleId: String): DomainResult<Unit> = discipleFacade.removeDisciple(discipleId)
fun GameEngine.getDiscipleById(discipleId: String): Disciple? = discipleFacade.getDiscipleById(discipleId)
fun GameEngine.updateDisciple(disciple: Disciple) = discipleFacade.updateDisciple(disciple)
/**
 * Get disciple status based on current assignments
 */
fun GameEngine.getDiscipleStatus(discipleId: String): DiscipleStatus = discipleFacade.getDiscipleStatus(discipleId)
suspend fun GameEngine.syncAllDiscipleStatuses() = discipleFacade.syncAllDiscipleStatuses()
fun GameEngine.syncSingleDiscipleStatus(discipleId: String) = discipleFacade.syncSingleDiscipleStatus(discipleId)
suspend fun GameEngine.resetAllDisciplesStatus() = discipleFacade.resetAllDisciplesStatus()
fun GameEngine.recruitDisciple(): Disciple = discipleFacade.recruitDisciple()
/**
 * Expel disciple from sect
 */
suspend fun GameEngine.expelDisciple(discipleId: String): DomainResult<Unit> = discipleFacade.expelDisciple(discipleId)
suspend fun GameEngine.apprenticeToMaster(discipleId: String,
    masterId: String): DomainResult<Unit> = discipleFacade.apprenticeToMaster(discipleId, masterId)
suspend fun GameEngine.releaseReflectionDisciple(discipleId: String) = discipleFacade
    .releaseReflectionDisciple(discipleId)
suspend fun GameEngine.recruitDiscipleFromList(discipleId: String): String = discipleFacade
    .recruitDiscipleFromList(discipleId)
suspend fun GameEngine.updateDiscipleStatus(discipleId: String,
    status: DiscipleStatus) = discipleFacade.updateDiscipleStatus(discipleId, status)
