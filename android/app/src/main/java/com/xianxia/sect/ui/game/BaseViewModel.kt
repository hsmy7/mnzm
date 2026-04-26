package com.xianxia.sect.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

abstract class BaseViewModel : ViewModel() {

    protected val sharingStarted = SharingStarted.WhileSubscribed(5_000)

    private val _errorEvents = Channel<String>(Channel.UNLIMITED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _successEvents = Channel<String>(Channel.UNLIMITED)
    val successEvents = _successEvents.receiveAsFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    protected fun showError(message: String) {
        _errorEvents.trySend(message)
        _errorMessage.value = message
    }

    protected fun showSuccess(message: String) {
        _successEvents.trySend(message)
        _successMessage.value = message
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    protected fun launchElderAction(
        action: suspend () -> ElderManagementUseCase.ElderResult,
        errorMessage: String = "操作失败"
    ) {
        viewModelScope.launch {
            try {
                when (val result = action()) {
                    is ElderManagementUseCase.ElderResult.Success -> showSuccess(result.message)
                    is ElderManagementUseCase.ElderResult.Error -> showError(result.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: errorMessage)
            }
        }
    }
}
