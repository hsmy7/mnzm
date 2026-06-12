package com.xianxia.sect.core.model

import com.xianxia.sect.core.model.DiscipleStatus

object DiscipleFilters {
    /** 可用于派遣的空闲弟子（含最低年龄要求） */
    fun DiscipleAggregate.isDeployable(minAge: Int = 5): Boolean =
        isAlive && status == DiscipleStatus.IDLE && realmLayer > 0 && age >= minAge

    /** 基本空闲弟子过滤 */
    fun DiscipleAggregate.isIdleAndAlive(): Boolean =
        isAlive && status == DiscipleStatus.IDLE
}
