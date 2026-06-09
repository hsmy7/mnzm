package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.WorldMapRenderData
import com.xianxia.sect.core.model.GameData
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class WorldMapViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {

    val worldMapRenderData: StateFlow<WorldMapRenderData> = gameEngine.worldMapRenderData
        .stateIn(viewModelScope, sharingStarted, WorldMapRenderData())

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, sharingStarted, GameData())
}
