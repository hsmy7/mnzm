package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject



@HiltViewModel
class HerbGardenViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    fun selectPlantSlot(slotIndex: Int) {
        _selectedPlantSlotIndex.value = slotIndex
    }

}
