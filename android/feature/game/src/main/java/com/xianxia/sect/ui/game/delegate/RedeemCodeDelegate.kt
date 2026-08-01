package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.RedeemResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class RedeemCodeDelegate(
    private val gameEngine: GameEngine,
    private val onShowSuccess: (String) -> Unit = {},
    private val onShowError: (String) -> Unit = {},
    private val onCapacityWarning: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "RedeemCodeDelegate"
    }

    private val _showRedeemCodeDialog = MutableStateFlow(false)
    val showRedeemCodeDialog: StateFlow<Boolean> = _showRedeemCodeDialog.asStateFlow()

    private val _redeemResult = MutableStateFlow<RedeemResult?>(null)
    val redeemResult: StateFlow<RedeemResult?> = _redeemResult.asStateFlow()

    fun openRedeemCodeDialog() {
        _showRedeemCodeDialog.value = true
        _redeemResult.value = null
    }

    fun closeRedeemCodeDialog() {
        _showRedeemCodeDialog.value = false
        _redeemResult.value = null
    }

    fun redeemCode(code: String) {
        gameEngine.launchOnEngine {
            try {
                val currentGameData = gameEngine.gameData.value
                val result = gameEngine.redeemCode(
                    code = code,
                    usedCodes = currentGameData.usedRedeemCodes,
                    currentYear = currentGameData.gameYear,
                    currentMonth = currentGameData.gameMonth
                )
                _redeemResult.value = result
                withContext(Dispatchers.Main) {
                    if (result.success) onShowSuccess(result.message)
                    else if (result.capacityInsufficient) onCapacityWarning(result.message)
                    else onShowError(result.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "Error redeeming code", e)
                withContext(Dispatchers.Main) {
                    onShowError("兑换失败: ${e.message}")
                }
            }
        }
    }

    fun clearRedeemResult() {
        _redeemResult.value = null
    }
}
