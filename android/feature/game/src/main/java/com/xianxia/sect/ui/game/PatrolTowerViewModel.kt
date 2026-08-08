package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.assignPatrolAtomic
import com.xianxia.sect.core.engine.autoAssignPatrolAtomic
import com.xianxia.sect.core.engine.removeDisciple
import com.xianxia.sect.core.engine.removePatrolAtomic
import com.xianxia.sect.core.engine.updatePatrolConfig
import com.xianxia.sect.core.engine.updatePatrolConfigs
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.util.DomainResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject



@HiltViewModel
class PatrolTowerViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val buildingConfigService: BuildingConfigService
) : BaseViewModel() {

    val slotsPerTower: Int get() = buildingConfigService.getSlotCountByDisplayName("巡视楼")

    fun getTowerIndex(buildingInstanceId: String): Int {
        val towers = gameEngine.gameDataSnapshot.placedBuildings
            .filter { it.displayName == "巡视楼" }
        return towers.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)
    }

    fun slotRange(towerIndex: Int): IntRange = (towerIndex * slotsPerTower) until (towerIndex * slotsPerTower + slotsPerTower)

    fun getAvailableDisciples(towerIndex: Int): List<DiscipleAggregate> {
        val range = slotRange(towerIndex)
        val assignedIds = gameEngine.gameDataSnapshot.patrolSlots
            .filter { it.discipleId.isNotEmpty() && it.index in range }
            .map { it.discipleId }.toSet()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isAlive && it.status == DiscipleStatus.IDLE && it.id !in assignedIds }
            .sortedWith(compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer })
    }

    /** 分配弟子到巡视槽位（原子操作，返回 DomainResult） */
    suspend fun assignDisciple(towerIndex: Int, slotOffset: Int, discipleId: String): DomainResult<Unit> {
        return gameEngine.assignPatrolAtomic(
            discipleId = discipleId,
            towerIndex = towerIndex,
            slotOffset = slotOffset,
            slotsPerTower = slotsPerTower
        )
    }

    /** 巡视楼分配 fire-and-forget 版本（用于对话框现有调用，内部处理异常） */
    fun assignDiscipleAsync(towerIndex: Int, slotOffset: Int, discipleId: String) {
        gameEngine.launchOnEngine {
            try {
                assignDisciple(towerIndex, slotOffset, discipleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "任命失败")
            }
        }
    }

    /** 移除巡视弟子（原子操作） */
    suspend fun removeDisciple(towerIndex: Int, slotOffset: Int): DomainResult<Unit> {
        return gameEngine.removePatrolAtomic(
            towerIndex = towerIndex,
            slotOffset = slotOffset,
            slotsPerTower = slotsPerTower
        )
    }

    /** 巡视楼移除 fire-and-forget 版本 */
    fun removeDiscipleAsync(towerIndex: Int, slotOffset: Int) {
        gameEngine.launchOnEngine {
            try {
                removeDisciple(towerIndex, slotOffset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    /** 交换巡视弟子（原子操作） */
    fun swapDisciple(towerIndex: Int, slotOffset: Int, newDiscipleId: String) {
        gameEngine.launchOnEngine {
            try {
                // 获取当前槽位旧弟子的全局索引
                val fromGlobalIndex = towerIndex * slotsPerTower + slotOffset
                // 先分配新弟子到目标槽位（原子方法内部已处理原住户释放）
                val result = gameEngine.assignPatrolAtomic(
                    discipleId = newDiscipleId,
                    globalIndex = fromGlobalIndex
                )
                if (result is DomainResult.Failure) {
                    showError(result.error.message ?: "更换失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "更换失败")
            }
        }
    }

    /** 一键任命（原子操作，单事务完成所有分配） */
    fun autoAssign(towerIndex: Int) {
        gameEngine.launchOnEngine {
            try {
                val available = getAvailableDisciples(towerIndex)
                if (available.isEmpty()) return@launchOnEngine

                val start = towerIndex * slotsPerTower
                val end = start + slotsPerTower

                // 构建批量分配列表（只填空槽）
                val assignments = mutableListOf<Pair<Int, String>>()
                val data = gameEngine.gameDataSnapshot
                var idx = 0
                for (i in start until end) {
                    if (idx >= available.size) break
                    val slot = data.patrolSlots.getOrNull(i)
                    if (slot == null || slot.discipleId.isEmpty()) {
                        assignments.add(i to available[idx].id)
                        idx++
                    }
                }

                if (assignments.isEmpty()) return@launchOnEngine

                val result = gameEngine.autoAssignPatrolAtomic(assignments)
                if (result is DomainResult.Failure) {
                    showError(result.error.message ?: "一键任命失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "一键任命失败")
            }
        }
    }

    fun updatePatrolConfig(towerIndex: Int, config: PatrolConfig) {
        viewModelScope.launch {
            val configs = gameEngine.gameDataSnapshot.patrolConfigs.toMutableList()
            while (configs.size <= towerIndex) configs.add(PatrolConfig())
            configs[towerIndex] = config
            gameEngine.updatePatrolConfigs(configs)
        }
    }

    fun getPatrolConfig(towerIndex: Int): PatrolConfig {
        val configs = gameEngine.gameDataSnapshot.patrolConfigs
        return configs.getOrElse(towerIndex) { PatrolConfig() }
    }

    fun updateRequireFullStatus(towerIndex: Int, requireFullStatus: Boolean) {
        val current = getPatrolConfig(towerIndex)
        updatePatrolConfig(towerIndex, current.copy(requireFullStatus = requireFullStatus))
    }
}
