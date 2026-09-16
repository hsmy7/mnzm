package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.assignDiscipleToLibrarySlot
import com.xianxia.sect.core.engine.assignWarehouseGarrisonAtomic
import com.xianxia.sect.core.engine.removeDiscipleFromLibrarySlot
import com.xianxia.sect.core.engine.removeWarehouseGarrison
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProductionViewModel @Inject constructor(
    /** internal：同包政策/长老扩展（Policy 与 Elder 族扩展文件）消费——TMF 收敛外移 */
    internal val gameEngine: GameEngine,
    /** internal：同包政策/长老扩展消费 */
    internal val sectPolicyToggle: SectPolicyToggleUseCase,
    /** internal：同包长老扩展消费 */
    internal val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    val productionSlots: StateFlow<List<ProductionSlot>> = gameEngine.productionSlots
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    fun assignWarehouseGarrison(
        buildingInstanceId: String,
        discipleId: String,
        discipleName: String,
        sectId: String
    ) {
        // 统一走引擎原子方法：事务内清理旧槽位 + gate 登记，防同一弟子多槽位
        gameEngine.assignWarehouseGarrisonAtomic(
            buildingInstanceId, discipleId, discipleName, sectId
        )
    }

    /** 卸任仓库驻守（写入迁入引擎层 GameEngine.removeWarehouseGarrison——§2.79 通道关闭配套） */
    suspend fun removeWarehouseGarrison(buildingInstanceId: String) {
        gameEngine.removeWarehouseGarrison(buildingInstanceId)
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToLibrarySlot(slotIndex, discipleId, discipleName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDiscipleFromLibrarySlot(slotIndex)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

}
