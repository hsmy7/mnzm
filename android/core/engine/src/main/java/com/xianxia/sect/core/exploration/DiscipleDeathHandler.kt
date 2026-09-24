package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject

/**
 * 弟子败北处理器（G07：玩家侧不可战死 → 重伤）。
 *
 * 统一写入 **重伤**：`currentHp=1`、`isAlive` 保持 1、不写 DEAD/deathYear、
 * 不计入年报死亡、不清袋/不清装/不解绑。回血走既有每旬
 * [com.xianxia.sect.core.GameConfig.Cultivation.PHASE_HP_MP_RECOVERY_RATE]。
 *
 * UI 由 `isAlive && currentHp==1` 派生「重伤」（不新增状态枚举）。
 * AI/妖兽死亡路径不走本类。
 */
class DiscipleDeathHandler @Inject constructor() {

    /**
     * 标记单个弟子重伤（原 markDead 语义，G07 改为 HP=1 存活）。
     * 必须在 stateStore.update 事务内调用。
     */
    fun markDead(state: MutableGameState, discipleId: Int, @Suppress("UNUSED_PARAMETER") deathYear: Int) {
        markInjured(state.discipleTables, discipleId)
    }

    /** 弟子重伤（G07）：currentHp=1，isAlive 不变，不写死亡三元组。 */
    fun markInjured(tables: DiscipleTables, discipleId: Int) {
        tables.markDead(discipleId, currentYear = 0, cause = "battle")
    }

    fun markDead(state: MutableGameState, discipleId: String, deathYear: Int) {
        val idInt = discipleId.toIntOrNull() ?: return
        markDead(state, idInt, deathYear)
    }

    fun markAllDead(state: MutableGameState, deadIds: Set<String>, deathYear: Int) {
        for (id in deadIds) {
            val idInt = id.toIntOrNull() ?: continue
            markDead(state, idInt, deathYear)
        }
    }

    /**
     * 列表 copy 模式补写 deathYears——玩家侧无新 `isAlive=false` 写入方，
     * 本函数仅服务旧档（历史已死亡行缺 deathYears）与 AI 侧死亡行的补缺，
     * 玩家侧重伤路径不写 deathYear。
     * 保留空参数以维持 replaceAll 流水线调用点兼容。
     */
    fun backfillDeathYears(
        tables: DiscipleTables,
        disciples: List<Disciple>,
        @Suppress("UNUSED_PARAMETER") deathYear: Int,
    ) {
        // 重伤弟子仍存活且不写 deathYears；仅对历史已死亡行补缺（兼容旧档）
        disciples.filter { !it.isAlive }.forEach {
            val idInt = it.id.toIntOrNull()
            if (idInt != null && !tables.deathYears.contains(idInt)) {
                tables.deathYears[idInt] = deathYear
            }
        }
    }

    companion object {
        /** 重伤恒定 HP（Q20/Q41）——单一源 = [com.xianxia.sect.core.GameConfig.Disciple.INJURED_HP] */
        const val INJURED_HP = GameConfig.Disciple.INJURED_HP

        /** 是否呈「重伤」：存活且 HP=1（UI 派生，不新增枚举） */
        fun isInjured(tables: DiscipleTables, discipleId: Int): Boolean {
            val alive = tables.isAlive.getOrNull(discipleId) == 1
            val hp = tables.currentHps.getOrNull(discipleId) ?: return false
            return alive && hp == INJURED_HP
        }
    }
}
