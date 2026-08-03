package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.domain.favor.GiftResult
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.WorldMapDialogState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
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
    val showSectTradeDialog: StateFlow<Boolean> = dialogs.map { it.showTrade }.stateIn(viewModelScope, sharingStarted, false)
    val selectedTradeSectId: StateFlow<String?> = dialogs.map { it.selectedTradeSectId }.stateIn(viewModelScope, sharingStarted, null)
    val sectTradeItems: StateFlow<List<MerchantItem>> = dialogs.map { it.tradeItems }.stateIn(viewModelScope, sharingStarted, emptyList())
    val showSectDiplomacyDialog: StateFlow<Boolean> = dialogs.map { it.showSectDiplomacy }.stateIn(viewModelScope, sharingStarted, false)
    val selectedSectDiplomacySectId: StateFlow<String?> = dialogs.map { it.selectedSectDiplomacySectId }.stateIn(viewModelScope, sharingStarted, null)

    fun openScoutDialog(sectId: String) {
        _dialogs.value = _dialogs.value.copy(showScout = true, selectedScoutSectId = sectId)
    }

    fun closeScoutDialog() {
        _dialogs.value = _dialogs.value.copy(showScout = false, selectedScoutSectId = null)
    }

    fun startScoutMission(memberIds: List<String>, sectId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.scoutSect(sectId, memberIds)
                closeScoutDialog()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "探查失败")
            }
        }
    }

    /** 送礼并返回结果（聊天式送礼使用） */
    suspend fun performGiftSpiritStones(sectId: String, tier: Int): GiftResult? {
        return try {
            gameEngine.giftSpiritStones(sectId, tier)
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            showError(e.message ?: "送礼失败")
            null
        }
    }

    /** 获取玩家第一个弟子名（用于聊天显示） */
    fun getFirstPlayerDiscipleName(): String = gameEngine.getFirstPlayerDiscipleName()

    /** 获取玩家第一个弟子头像资源名（用于聊天头像） */
    fun getFirstPlayerDisciplePortrait(): String = gameEngine.getFirstPlayerDisciplePortrait()

    /** 简化版结盟请求 */
    suspend fun requestAllianceSimple(sectId: String): Boolean = gameEngine.requestAllianceSimple(sectId)

    /** 简化版解除结盟 */
    suspend fun dissolveAllianceSimple(sectId: String): Boolean = gameEngine.dissolveAllianceSimple(sectId)

    fun isAlly(sectId: String): Boolean = gameEngine.isAlly(sectId)

    // ═══ 附属宗门 ═══
    suspend fun requestVassalContract(sectId: String): Boolean = gameEngine.requestVassalContract(sectId)
    suspend fun dissolveVassalContract(sectId: String): Boolean = gameEngine.dissolveVassalContract(sectId)
    fun isPlayerVassal(sectId: String): Boolean = gameEngine.isPlayerVassal(sectId)
    fun getPlayerVassals(): List<String> = gameEngine.getPlayerVassals()

    fun openSectTradeDialog(sectId: String) {
        gameEngine.launchOnEngine {
            _dialogs.value = _dialogs.value.copy(
                showTrade = true,
                selectedTradeSectId = sectId,
                tradeItems = gameEngine.getOrRefreshSectTradeItems(sectId)
            )
        }
    }

    fun closeSectTradeDialog() {
        _dialogs.value = _dialogs.value.copy(showTrade = false, selectedTradeSectId = null)
    }

    fun buyFromSectTrade(itemId: String, quantity: Int = 1) {
        gameEngine.launchOnEngine {
            try {
                val sectId = _dialogs.value.selectedTradeSectId ?: return@launchOnEngine
                gameEngine.buyFromSectTradeSync(sectId, itemId, quantity)
                _dialogs.value = _dialogs.value.copy(
                    tradeItems = gameEngine.getOrRefreshSectTradeItems(sectId)
                )
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "购买失败")
            }
        }
    }

    fun openSectDiplomacyDialog(sectId: String) {
        _dialogs.value = _dialogs.value.copy(showSectDiplomacy = true, selectedSectDiplomacySectId = sectId)
    }

    fun closeSectDiplomacyDialog() {
        _dialogs.value = _dialogs.value.copy(showSectDiplomacy = false, selectedSectDiplomacySectId = null)
    }
}
