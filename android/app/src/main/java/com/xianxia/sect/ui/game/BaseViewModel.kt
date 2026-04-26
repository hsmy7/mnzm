package com.xianxia.sect.ui.game

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

abstract class BaseViewModel : ViewModel() {

    private val _errorEvents = Channel<String>(Channel.BUFFERED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _successEvents = Channel<String>(Channel.BUFFERED)
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
}
