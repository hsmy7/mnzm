package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子师徒关系管理服务。
 *
 * ## 职责
 * 1. **拜师** — [apprenticeToMaster] 建立师徒关系，校验师徒存活、名额限制等
 * 2. **关系维护** — 师徒关系的生命周期管理（死亡自动解绑由 [DiscipleLifecycleProcessor] 处理）
 */
@Singleton
class DiscipleMasterApprenticeService @Inject constructor(
    private val stateStore: GameStateStore,
) {
    companion object {
        private const val TAG = "DiscipleMasterApprenticeService"
    }

    /**
     * 拜师：徒弟 [discipleId] 向师父 [masterId] 拜师，建立永久师徒关系。
     * 仅一方死亡方可解绑（见 DiscipleLifecycleProcessor.handleDiscipleDeath）。
     * - 师父最多 5 名徒弟
     * - 弟子最多 1 名师父
     */
    fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = null
        stateStore.update {
            val did = discipleId.toIntOrNull()
            val mid = masterId.toIntOrNull()
            if (did == null || !discipleTables.ids.contains(did)) {
                error = AppError.Domain.Disciple.NotFound(discipleId)
                return@update
            }
            if (mid == null || !discipleTables.ids.contains(mid)) {
                error = AppError.Domain.Disciple.NotFound(masterId)
                return@update
            }
            if (did == mid) {
                error = AppError.Domain.Disciple.SlotInvalid("不能拜自己为师")
                return@update
            }
            if (discipleTables.isAlive[did] != 1) {
                error = AppError.Domain.Disciple.NotAlive(discipleId)
                return@update
            }
            if (discipleTables.isAlive[mid] != 1) {
                error = AppError.Domain.Disciple.NotAlive(masterId)
                return@update
            }
            // 该弟子已有师父
            if (discipleTables.masterIds.getOrNull(did) != null) {
                error = AppError.Domain.Disciple.SlotInvalid("弟子已有师父，师徒关系不可更改")
                return@update
            }
            // 师父徒弟数 < 5（仅统计存活徒弟）
            val apprenticeCount = discipleTables.ids.count { otherId ->
                otherId != did &&
                discipleTables.isAlive[otherId] == 1 &&
                discipleTables.masterIds.getOrNull(otherId) == masterId
            }
            if (apprenticeCount >= DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER) {
                error = AppError.Domain.Disciple.SlotInvalid(
                    "师父徒弟已满（最多${DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER}名）")
                return@update
            }
            // 通过校验，建立师徒关系
            discipleTables.masterIds[did] = masterId

            // 记录拜师日志（徒弟视角）
            val masterName = discipleTables.names[mid] ?: "未知"
            val discipleName = discipleTables.names[did] ?: "未知"
            val discipleAge = discipleTables.ages[did]
            val masterAge = discipleTables.ages[mid]
            val currentEvents = discipleTables.lifeEvents.getOrDefault(did, emptyList())
            discipleTables.lifeEvents[did] = currentEvents + "${discipleAge}岁：拜${masterName}为师"
            // 记录收徒日志（师父视角）
            val masterEvents = discipleTables.lifeEvents.getOrDefault(mid, emptyList())
            discipleTables.lifeEvents[mid] = masterEvents + "${masterAge}岁：收${discipleName}为徒"
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }
}
