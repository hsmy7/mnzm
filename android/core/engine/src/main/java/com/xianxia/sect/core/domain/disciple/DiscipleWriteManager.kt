package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.AppError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EntityManager 模式的写入 Facade（对标 Unity DOTS EntityManager）。
 *
 * 所有弟子实体的结构性写操作（创建、删除、全量替换、日志追加、
 * 死亡标记、检查点同步等）均通过此类完成，内部自动路由到
 * [GameStateStore.update] 事务，外部调用方无需关注 update 细节。
 *
 * 读操作仍直接通过 [GameStateStore.discipleTables] 访问。
 *
 * 使用方式：
 *   discipleWriteManager.addDisciple(disciple)
 *   discipleWriteManager.removeDisciple(42)
 *   discipleWriteManager.updateDisciple(disciple)
 */
@Singleton
class DiscipleWriteManager @Inject constructor(
    private val stateStore: GameStateStore
) {
    /**
     * 添加新弟子。
     * 内部使用 [DiscipleTables.insert] 在 update{} 事务内完成。
     */
    fun addDisciple(disciple: Disciple) {
        stateStore.update { discipleTables.insert(disciple) }
    }

    /**
     * 按 ID 移除弟子。
     * 返回 Success 表示移除成功，NotFound 表示 ID 不存在。
     */
    fun removeDisciple(discipleId: Int): DomainResult<Unit> {
        return stateStore.updateAndReturn {
            if (discipleTables.ids.contains(discipleId)) {
                discipleTables.remove(discipleId)
                DomainResult.Success(Unit)
            } else {
                DomainResult.Failure(AppError.Domain.Disciple.NotFound(discipleId.toString()))
            }
        }
    }

    /**
     * 替换已有弟子。
     * update{} 事务内完成 [DiscipleTables.remove] + [DiscipleTables.insert]。
     */
    fun updateDisciple(disciple: Disciple) {
        val id = disciple.id.toIntOrNull() ?: return
        stateStore.update {
            if (discipleTables.ids.contains(id)) {
                discipleTables.remove(id)
                discipleTables.insert(disciple)
            }
        }
    }

    /**
     * 原子全量替换所有弟子数据。
     * 对标 Unity DOTS EntityManager.CreateEntity 的批量变体。
     */
    fun replaceAll(disciples: List<Disciple>) {
        stateStore.update { discipleTables.replaceAll(disciples) }
    }

    /**
     * 为指定弟子追加一条日志事件。
     */
    fun addLifeEvent(discipleId: Int, event: String) {
        stateStore.update {
            if (!discipleTables.ids.contains(discipleId)) return@update
            val currentEvents = discipleTables.lifeEvents.getOrDefault(discipleId, emptyList())
            discipleTables.lifeEvents[discipleId] = currentEvents + event
        }
    }

    /**
     * 集中标记弟子死亡。
     * [cause] 取值："age" / "battle" / "scout" / "exploration" / "cave" / "unknown"
     */
    fun markDead(id: Int, currentYear: Int, cause: String = "unknown") {
        stateStore.update { discipleTables.markDead(id, currentYear, cause) }
    }

    /**
     * 修炼检查点——将当前修炼值同步到检查点。
     */
    fun checkpointDisciple(id: Int, currentMonth: Int) {
        stateStore.update { discipleTables.checkpointDisciple(id, currentMonth) }
    }

    /**
     * 全量弟子检查点——对所有存活弟子同步检查点。
     */
    fun checkpointAllDisciples(currentMonth: Int) {
        stateStore.update { discipleTables.checkpointAllDisciples(currentMonth) }
    }
}
