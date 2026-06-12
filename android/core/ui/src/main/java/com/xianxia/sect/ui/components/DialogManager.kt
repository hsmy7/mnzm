package com.xianxia.sect.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

data class DialogState<T>(
    val isVisible: Boolean = false,
    val data: T? = null
)

@Composable
fun <T> ManagedDialog(
    state: DialogState<T>,
    onDismiss: () -> Unit,
    dialogContent: @Composable (T?) -> Unit
) {
    if (state.isVisible) {
        dialogContent(state.data)
    }
}

@Composable
fun DialogHost(
    modifier: Modifier = Modifier,
    content: @Composable DialogHostScope.() -> Unit
) {
    DialogHostScope().content()
}

class DialogHostScope {
    @Composable
    fun <T> Dialog(
        isVisible: Boolean,
        data: T? = null,
        onDismiss: () -> Unit,
        content: @Composable (T?) -> Unit
    ) {
        if (isVisible) {
            content(data)
        }
    }
}

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
