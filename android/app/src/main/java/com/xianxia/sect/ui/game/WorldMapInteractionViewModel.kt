package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.WorldMapDialogState
import com.xianxia.sect.core.model.WorldMapDialogType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorldMapInteractionViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {

    private val _dialogs = MutableStateFlow(WorldMapDialogState())
    val dialogs: StateFlow<WorldMapDialogState> = _dialogs.asStateFlow()

    // Convenience accessors for backward compatibility
    val showScoutDialog: StateFlow<Boolean> = dialogs.map { it.showScout }.stateIn(viewModelScope, sharingStarted, false)
    val selectedScoutSectId: StateFlow<String?> = dialogs.map { it.selectedScoutSectId }.stateIn(viewModelScope, sharingStarted, null)
    val showAllianceDialog: StateFlow<Boolean> = dialogs.map { it.showAlliance }.stateIn(viewModelScope, sharingStarted, false)
    val selectedAllianceSectId: StateFlow<String?> = dialogs.map { it.selectedAllianceSectId }.stateIn(viewModelScope, sharingStarted, null)
    val showEnvoyDiscipleSelectDialog: StateFlow<Boolean> = dialogs.map { it.showEnvoyDiscipleSelect }.stateIn(viewModelScope, sharingStarted, false)
    val showSectTradeDialog: StateFlow<Boolean> = dialogs.map { it.showTrade }.stateIn(viewModelScope, sharingStarted, false)
    val selectedTradeSectId: StateFlow<String?> = dialogs.map { it.selectedTradeSectId }.stateIn(viewModelScope, sharingStarted, null)
    val sectTradeItems: StateFlow<List<MerchantItem>> = dialogs.map { it.tradeItems }.stateIn(viewModelScope, sharingStarted, emptyList())
    val showGiftDialog: StateFlow<Boolean> = dialogs.map { it.showGift }.stateIn(viewModelScope, sharingStarted, false)
    val selectedGiftSectId: StateFlow<String?> = dialogs.map { it.selectedGiftSectId }.stateIn(viewModelScope, sharingStarted, null)

    fun openScoutDialog(sectId: String) {
        _dialogs.value = _dialogs.value.copy(showScout = true, selectedScoutSectId = sectId)
    }

    fun closeScoutDialog() {
        _dialogs.value = _dialogs.value.copy(showScout = false, selectedScoutSectId = null)
    }

    fun startScoutMission(memberIds: List<String>, sectId: String) {
        viewModelScope.launch {
            try {
                gameEngine.scoutSect(sectId, memberIds)
                closeScoutDialog()
            } catch (e: Exception) {
                showError(e.message ?: "探查失败")
            }
        }
    }

    fun giftSpiritStones(sectId: String, tier: Int) {
        viewModelScope.launch {
            try {
                gameEngine.giftSpiritStones(sectId, tier)
            } catch (e: Exception) {
                showError(e.message ?: "送礼失败")
            }
        }
    }

    fun openAllianceDialog(sectId: String) {
        _dialogs.value = _dialogs.value.copy(showAlliance = true, selectedAllianceSectId = sectId)
    }

    fun closeAllianceDialog() {
        _dialogs.value = _dialogs.value.copy(showAlliance = false, selectedAllianceSectId = null)
    }

    fun openEnvoyDiscipleSelectDialog() {
        _dialogs.value = _dialogs.value.copy(showEnvoyDiscipleSelect = true)
    }

    fun closeEnvoyDiscipleSelectDialog() {
        _dialogs.value = _dialogs.value.copy(showEnvoyDiscipleSelect = false)
    }

    fun requestAlliance(sectId: String, envoyDiscipleId: String) {
        viewModelScope.launch {
            try {
                val (success, message) = gameEngine.requestAlliance(sectId, envoyDiscipleId)
                if (success) {
                    closeEnvoyDiscipleSelectDialog()
                    closeAllianceDialog()
                } else {
                    showError(message)
                }
            } catch (e: Exception) {
                showError(e.message ?: "结盟失败")
            }
        }
    }

    fun dissolveAlliance(sectId: String) {
        viewModelScope.launch {
            try {
                val (success, message) = gameEngine.dissolveAlliance(sectId)
                if (success) {
                    closeAllianceDialog()
                } else {
                    showError(message)
                }
            } catch (e: Exception) {
                showError(e.message ?: "解除结盟失败")
            }
        }
    }

    fun getAllianceCost(sectLevel: Int): Long = gameEngine.getAllianceCost(sectLevel)

    fun getEnvoyRealmRequirement(sectLevel: Int): Int = gameEngine.getEnvoyRealmRequirement(sectLevel)

    fun isAlly(sectId: String): Boolean = gameEngine.isAlly(sectId)

    fun getAllianceRemainingYears(sectId: String): Int = gameEngine.getAllianceRemainingYears(sectId)

    fun openSectTradeDialog(sectId: String) {
        _dialogs.value = _dialogs.value.copy(
            showTrade = true,
            selectedTradeSectId = sectId,
            tradeItems = gameEngine.getOrRefreshSectTradeItems(sectId)
        )
    }

    fun closeSectTradeDialog() {
        _dialogs.value = _dialogs.value.copy(showTrade = false, selectedTradeSectId = null)
    }

    fun buyFromSectTrade(itemId: String, quantity: Int = 1) {
        viewModelScope.launch {
            try {
                val sectId = _dialogs.value.selectedTradeSectId ?: return@launch
                gameEngine.buyFromSectTradeSync(sectId, itemId, quantity)
                _dialogs.value = _dialogs.value.copy(
                    tradeItems = gameEngine.getOrRefreshSectTradeItems(sectId)
                )
            } catch (e: Exception) {
                showError(e.message ?: "购买失败")
            }
        }
    }

    fun openGiftDialog(sectId: String) {
        _dialogs.value = _dialogs.value.copy(showGift = true, selectedGiftSectId = sectId)
    }

    fun closeGiftDialog() {
        _dialogs.value = _dialogs.value.copy(showGift = false, selectedGiftSectId = null)
    }
}
