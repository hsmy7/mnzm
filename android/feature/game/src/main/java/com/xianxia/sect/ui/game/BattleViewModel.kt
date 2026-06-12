package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class BattleViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase
) : BaseViewModel() {

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value)

    /**
     * 转换后的弟子列表（使用新的 DiscipleAggregate 模型）
     * 用于 UI 层展示，避免使用废弃的 Disciple 类
     */
    val disciplesAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = disciplesAggregates

    fun isPositionWorkStatus(discipleId: String): Boolean {
        return disciplePositionQuery.isPositionWorkStatus(discipleId)
    }

    fun getDisciplePosition(discipleId: String): String? {
        return disciplePositionQuery.getDisciplePosition(discipleId)
    }

    fun hasDisciplePosition(discipleId: String): Boolean {
        return disciplePositionQuery.hasDisciplePosition(discipleId)
    }

    fun isReserveDisciple(discipleId: String): Boolean {
        return disciplePositionQuery.isReserveDisciple(discipleId)
    }
}
