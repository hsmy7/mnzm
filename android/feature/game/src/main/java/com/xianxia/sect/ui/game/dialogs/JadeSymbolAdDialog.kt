package com.xianxia.sect.ui.game.dialogs

import androidx.compose.runtime.Composable
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.GameViewModel

/**
 * 玉符广告确认对话框：点击玉符栏"+"按钮后弹出。
 *
 * 三态流（与 MerchantDialog 广告确认框判定序一致，composable 首帧判定）：
 * 1. Limit — 每日广告观看次数已达 20 次上限
 * 2. Cooldown — 距上次观看不足 60 秒
 * 3. Confirm — 正常确认框（观看广告获得 3 玉符）
 *
 * 广告玉符**不计入**每日 30 枚上限（用户决策），但广告观看次数仍受
 * 每日 20 次与 60 秒冷却限制（AdsDelegate 统一控制，与突破/商人广告一致）。
 */
@Composable
internal fun JadeSymbolAdDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    when {
        viewModel.isDailyAdLimitReached() -> {
            StandardPromptDialog(
                onDismissRequest = onDismiss,
                title = "提示",
                text = "观看次数已达上限",
                confirmLabel = "知道了",
                onConfirm = onDismiss,
                scrimEnabled = false,
                dismissOnClickOutside = true
            )
        }
        viewModel.isAdOnCooldown() -> {
            StandardPromptDialog(
                onDismissRequest = onDismiss,
                title = "不可播放广告",
                text = "一分钟内只可观看一次广告",
                confirmLabel = "确认",
                onConfirm = onDismiss,
                scrimEnabled = false,
                dismissOnClickOutside = true
            )
        }
        else -> {
            StandardPromptDialog(
                onDismissRequest = onDismiss,
                title = "获得玉符",
                text = "观看广告获得3玉符，最多观看20次广告。",
                dismissLabel = "取消",
                confirmLabel = "观看",
                onConfirm = {
                    onDismiss()
                    viewModel.watchAdForJadeSymbols()
                },
                scrimEnabled = false,
                dismissOnClickOutside = true
            )
        }
    }
}
