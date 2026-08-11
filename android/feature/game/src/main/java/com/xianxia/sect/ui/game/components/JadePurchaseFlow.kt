package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.StandardPromptDialog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * 玉符消耗购买流程（突破率/商人刷新共用）：小屏确认弹窗 + 三态结果分发 +
 * 玉符不足/错误提示。
 *
 * 玉符不足走平台 [StandardPromptDialog] 独立 Window 全屏覆盖提示，
 * 禁用嵌套 InlineStandardPromptDialog（2026-08-11 clip 事故教训）。
 * 防连点 AtomicBoolean compareAndSet 立即生效不等重组（对抗性审查教训，同洗炼弹窗）。
 *
 * 关闭语义：**仅成功调用 [onDismiss]**；不足/失败不关闭小屏弹窗，提示框
 * 以平台 Dialog 覆盖其上（2026-08-11 真机实测修复：先 onDismiss 再置提示
 * 状态会随组件销毁而永不渲染）。
 *
 * @param purchase 引擎购买调用（suspend），返回统一三态结果
 * @param onDismiss 弹窗关闭（成功/上限时由本组件触发回调）
 */
@Composable
internal fun JadePurchaseFlow(
    title: String,
    description: String,
    jadeSymbols: Int,
    insufficientText: String,
    purchase: suspend () -> JadePurchaseOutcome,
    onDismiss: () -> Unit
) {
    var showInsufficientDialog by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val purchaseInFlight = remember { AtomicBoolean(false) }

    JadePurchaseDialog(
        title = title,
        description = description,
        jadeSymbols = jadeSymbols,
        onConfirm = {
            if (!purchaseInFlight.compareAndSet(false, true)) return@JadePurchaseDialog
            scope.launch {
                try {
                    when (val outcome = purchase()) {
                        // 仅成功关闭小屏弹窗；不足/失败**不调 onDismiss**——
                        // onDismiss 由父级移除本组件（showJadeDialog=false），
                        // 先 onDismiss 再置 showInsufficientDialog/errorText 时
                        // 状态随组件销毁，提示框永不渲染（2026-08-11 真机实测：
                        // 玉符不足点「消耗玉符」弹窗关闭但无任何提示）
                        JadePurchaseOutcome.Success -> onDismiss()
                        JadePurchaseOutcome.Insufficient -> showInsufficientDialog = true
                        is JadePurchaseOutcome.Failed -> errorText = outcome.message
                    }
                } finally {
                    purchaseInFlight.set(false)
                }
            }
        },
        onDismiss = onDismiss
    )

    if (showInsufficientDialog) {
        StandardPromptDialog(
            onDismissRequest = { showInsufficientDialog = false },
            title = "提示",
            text = insufficientText,
            confirmLabel = "知道了",
            onConfirm = { showInsufficientDialog = false }
        )
    }

    errorText?.let { message ->
        StandardPromptDialog(
            onDismissRequest = { errorText = null },
            title = "提示",
            text = message,
            confirmLabel = "知道了",
            onConfirm = { errorText = null }
        )
    }
}

/**
 * 玉符消耗小屏弹窗（突破率/商人刷新共用）：标题 + 描述 + 底部
 * "消耗1玉符"小字（12sp + 玉符图标 12dp，玉符充足白/不足红，同洗炼弹窗 CostHintRow）
 * + "消耗玉符"按钮。防连点由调用方（onConfirm 侧）负责。
 */
@Composable
private fun JadePurchaseDialog(
    title: String,
    description: String,
    jadeSymbols: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val jadeInsufficient = jadeSymbols < GameConfig.JadePurchase.COST
    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = title,
        showCloseButton = true,
        content = {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                // 按钮上方一行小字（与宗门信息卡片小字/玉符图标一致；不足变红）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpriteImage(
                        name = "jade_symbol",
                        contentDescription = "玉符",
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "消耗${GameConfig.JadePurchase.COST}玉符",
                        fontSize = 12.sp,
                        color = if (jadeInsufficient) Color.Red else Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                GameButton(
                    text = "消耗玉符",
                    onClick = onConfirm
                )
            }
        }
    )
}
