package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.SlotAssignment
import com.xianxia.sect.core.model.SlotRef

/**
 * GameEngine 扩展 — DiscipleAssignmentGate 委托。
 *
 * ViewModel 层通过这些方法访问 Gate，不直接注入 Gate。
 */
fun GameEngine.confirmAssignDisciple(discipleId: String, slotRef: SlotRef) =
    assignmentGate.confirmAssign(discipleId, slotRef)

fun GameEngine.releaseDiscipleAssignment(discipleId: String) =
    assignmentGate.release(discipleId)

fun GameEngine.getDiscipleAssignment(discipleId: String): SlotAssignment? =
    assignmentGate.getAssignment(discipleId)

fun GameEngine.isDiscipleAssigned(discipleId: String): Boolean =
    assignmentGate.isAssigned(discipleId)
