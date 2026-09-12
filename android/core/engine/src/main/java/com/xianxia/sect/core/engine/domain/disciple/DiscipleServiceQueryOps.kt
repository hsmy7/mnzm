package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus

// ── 弟子查询域（自 DiscipleService 拆出，行为零变更） ─────────────────────────
fun DiscipleService.getDisciplesByStatus(status: DiscipleStatus): List<Disciple> = discipleLifecycleManager
    .getDisciplesByStatus(status)

/**
 * Get idle disciples
 */

fun DiscipleService.getIdleDisciples(): List<Disciple> = discipleLifecycleManager.getIdleDisciples()

// ==================== DiscipleAggregate 查询接口 ====================

/**
 * 获取单个弟子的聚合数据
 *
 * 由现有 [Disciple] 单表实体转换而来。
 *
 * @param discipleId 弟子 ID
 * @return 完整的 DiscipleAggregate 实例，如果弟子不存在则返回 null
 */

fun DiscipleService.getDiscipleAggregate(discipleId: String): DiscipleAggregate? = discipleLifecycleManager
    .getDiscipleAggregate(discipleId)

/**
 * 获取所有弟子的聚合数据列表
 *
 * 由现有 [Disciple] 列表批量转换而来。
 *
 * @return 所有弟子的 DiscipleAggregate 列表
 */

fun DiscipleService.getAllDiscipleAggregates(): List<DiscipleAggregate> =
    discipleLifecycleManager.getAllDiscipleAggregates()

/**
 * Update yearly salary enabled/disabled for a realm
 */

fun DiscipleService.getAliveDisciplesCount(): Int = discipleLifecycleManager.getAliveDisciplesCount()

/**
 * Get disciples by status
 */

fun DiscipleService.updateYearlySalaryEnabled(realm: Int,
    enabled: Boolean) = discipleLifecycleManager.updateYearlySalaryEnabled(realm, enabled)
