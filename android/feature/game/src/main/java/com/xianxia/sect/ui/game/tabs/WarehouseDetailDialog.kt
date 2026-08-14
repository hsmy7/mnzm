package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.game.components.QuantitySelector

@Composable
internal fun SellConfirmDialog(
    itemName: String,
    maxQuantity: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sellQuantity by remember { mutableIntStateOf(1) }

    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "出售（$itemName）",
        confirmLabel = "出售",
        onConfirm = { onConfirm(sellQuantity) },
        dismissLabel = "取消",
        onDismiss = onDismiss,
        // 含数量常驻输入框：挂载期间冻结宿主窗口系统栏操作（荣耀X70键盘频闪根治）
        freezeSystemBars = true
    ) {
        Spacer(modifier = Modifier.weight(1f))
        QuantitySelector(
            quantity = sellQuantity,
            maxQuantity = maxQuantity,
            onQuantityChange = { sellQuantity = it }
        )
    }
}
