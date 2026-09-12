package com.xianxia.sect.ui.game.dialogs.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog

/**
 * 共享信息弹窗容器：垂直滚动 + 横向 12dp 内边距。
 *
 * 收敛各对话框手写的私有 CommonDialog 同构包装（ReflectionCliffDialog/
 * LibraryDialog/MissionHallDialog/LawEnforcementHallDialog 等）。
 * 业务特殊的变体（如 SpiritMineDialog 带产量统计 headerActions）不收敛。
 */
@Composable
fun ScrollableInfoDialog(
    title: String,
    onDismiss: () -> Unit,
    headerContent: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Half,
        scrollableContent = false,
        headerContent = headerContent,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                content()
            }
        }
    )
}
