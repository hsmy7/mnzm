package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import javax.inject.Inject
import javax.inject.Singleton



/** 拜师校验结果：Valid 携带双方表内 id，Invalid 携带首个命中错误 */
private sealed class ApprenticeCheck {
    data class Valid(val did: Int, val mid: Int) : ApprenticeCheck()
    data class Invalid(val error: AppError.Domain.Disciple) : ApprenticeCheck()
}

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
    /**
     * 拜师：徒弟 [discipleId] 向师父 [masterId] 拜师，建立永久师徒关系。
     * 仅一方死亡方可解绑（见 DiscipleLifecycleProcessor.handleDiscipleDeath）。
     * - 师父最多 5 名徒弟
     * - 弟子最多 1 名师父
     */
    fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = null
        stateStore.update {
            when (val ids = checkApprenticeExists(discipleId, masterId)) {
                is ApprenticeCheck.Invalid -> error = ids.error
                is ApprenticeCheck.Valid -> {
                    val capacityError = checkApprenticeAliveDistinct(ids.did, ids.mid, discipleId, masterId)
                        ?: checkApprenticeCapacity(ids.did, masterId)
                    if (capacityError != null) {
                        error = capacityError
                    } else {
                        bindApprenticeToMaster(ids.did, ids.mid, masterId)
                    }
                }
            }
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    /**
     * 存在性校验相：解析 + 双方在弟子表内，
     * 校验序与原早退链一致，通过返回 [ApprenticeCheck.Valid]。
     */
    private fun MutableGameState.checkApprenticeExists(discipleId: String, masterId: String): ApprenticeCheck {
        val did = discipleId.toIntOrNull()
        val mid = masterId.toIntOrNull()
        if (did == null || !discipleTables.ids.contains(did)) {
            return ApprenticeCheck.Invalid(AppError.Domain.Disciple.NotFound(discipleId))
        }
        if (mid == null || !discipleTables.ids.contains(mid)) {
            return ApprenticeCheck.Invalid(AppError.Domain.Disciple.NotFound(masterId))
        }
        return ApprenticeCheck.Valid(did, mid)
    }

    /** 同一性与存活校验相：不可自拜 + 双方存活；返回 null 表示通过 */
    private fun MutableGameState.checkApprenticeAliveDistinct(
        did: Int,
        mid: Int,
        discipleId: String,
        masterId: String
    ): AppError.Domain.Disciple? {
        if (did == mid) {
            return AppError.Domain.Disciple.SlotInvalid("不能拜自己为师")
        }
        if (discipleTables.isAlive[did] != 1) {
            return AppError.Domain.Disciple.NotAlive(discipleId)
        }
        if (discipleTables.isAlive[mid] != 1) {
            return AppError.Domain.Disciple.NotAlive(masterId)
        }
        return null
    }

    /**
     * 名额校验相：弟子无既有师父、师父在徒名额未满，
     * 仅在身份校验相通过后调用；返回 null 表示通过。
     */
    private fun MutableGameState.checkApprenticeCapacity(did: Int, masterId: String): AppError.Domain.Disciple? {
        // 该弟子已有师父
        if (discipleTables.masterIds.getOrNull(did) != null) {
            return AppError.Domain.Disciple.SlotInvalid("弟子已有师父，师徒关系不可更改")
        }
        // 师父徒弟数 < 5（仅统计存活徒弟）
        val apprenticeCount = discipleTables.ids.count { otherId ->
            otherId != did &&
                discipleTables.isAlive[otherId] == 1 &&
                discipleTables.masterIds.getOrNull(otherId) == masterId
        }
        if (apprenticeCount >= DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER) {
            return AppError.Domain.Disciple.SlotInvalid(
                "师父徒弟已满（最多${DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER}名）")
        }
        return null
    }

    /** 建立师徒关系并写入双向日志：masterIds 落表 + 徒/师两侧 lifeEvents */
    private fun MutableGameState.bindApprenticeToMaster(did: Int, mid: Int, masterId: String) {
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
}
