package com.xianxia.sect.ui.components

import androidx.compose.runtime.*

data class DialogState<T>(
    val isVisible: Boolean = false,
    val data: T? = null
)

@Composable
fun <T> rememberDialogState(initialVisible: Boolean = false, initialData: T? = null): MutableState<DialogState<T>> {
    return remember { mutableStateOf(DialogState(initialVisible, initialData)) }
}

fun <T> MutableState<DialogState<T>>.show(data: T? = null) {
    value = DialogState(true, data)
}

fun <T> MutableState<DialogState<T>>.hide() {
    value = value.copy(isVisible = false)
}

fun <T> MutableState<DialogState<T>>.toggle() {
    value = DialogState(!value.isVisible, value.data)
}
