package com.xianxia.sect.ui.game

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

abstract class BaseViewModel : ViewModel() {

    private val _errorEvents = Channel<String>(Channel.BUFFERED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _successEvents = Channel<String>(Channel.BUFFERED)
    val successEvents = _successEvents.receiveAsFlow()

    protected fun showError(message: String) {
        _errorEvents.trySend(message)
    }

    protected fun showSuccess(message: String) {
        _successEvents.trySend(message)
    }
}
