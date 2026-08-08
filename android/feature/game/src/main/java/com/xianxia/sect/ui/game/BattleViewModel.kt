package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject



@HiltViewModel
class BattleViewModel @Inject constructor(
    private val gameEngine: GameEngine
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
}
