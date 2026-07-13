package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject

/**
 * 弟子死亡处理器。
 *
 * 统一处理标记死亡、补充 deathYears、装备断言守卫。
 *
 * 本类替代散落在各 Service 中的手动死亡标记逻辑
 * （`isAlive=0` + `status=DEAD` + `deathYears` 三字段写入），
 * 确保所有死亡路径使用统一的标记入口。
 */
class DiscipleDeathHandler @Inject constructor() {

    /**
     * 标记单个弟子死亡（Int ID）。
     * 写入 isAlive=0 + status=DEAD + deathYear，并执行装备断言守卫。
     */
    fun markDead(tables: DiscipleTables, discipleId: Int, deathYear: Int) {
        tables.isAlive[discipleId] = 0
        tables.statuses[discipleId] = DiscipleStatus.DEAD
        tables.deathYears[discipleId] = deathYear
        assertNoEquipmentHeld(tables, discipleId)
    }

    /**
     * 标记单个弟子死亡（String ID，自动解析为 Int）。
     * 若 [discipleId] 无法解析为 Int，静默跳过。
     */
    fun markDead(tables: DiscipleTables, discipleId: String, deathYear: Int) {
        val idInt = discipleId.toIntOrNull() ?: return
        markDead(tables, idInt, deathYear)
    }

    /**
     * 批量标记阵亡弟子（String ID 集合）。
     * 无法解析为 Int 的 ID 静默跳过。
     */
    fun markAllDead(tables: DiscipleTables, deadIds: Set<String>, deathYear: Int) {
        for (id in deadIds) {
            val idInt = id.toIntOrNull() ?: continue
            markDead(tables, idInt, deathYear)
        }
    }

    private fun assertNoEquipmentHeld(tables: DiscipleTables, discipleId: Int) {
        val hasEquipment = tables.weaponIds.getOrNull(discipleId)?.isNotEmpty() == true ||
            tables.armorIds.getOrNull(discipleId)?.isNotEmpty() == true ||
            tables.bootsIds.getOrNull(discipleId)?.isNotEmpty() == true ||
            tables.accessoryIds.getOrNull(discipleId)?.isNotEmpty() == true

        if (hasEquipment) {
            DomainLog.w(TAG, "disciple $discipleId died with equipped items still attached")
        }
    }

    companion object {
        private const val TAG = "DiscipleDeathHandler"
    }
}
