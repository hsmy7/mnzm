package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.SpiritRootWashConfirmResult
import com.xianxia.sect.core.engine.SpiritRootWashResult
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.SpiritRoot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.game.GameViewModel
import kotlinx.coroutines.launch

/** 玉符不足提示文案（需求：点洗炼/继续洗炼且玉符不足时弹提示框） */
private const val INSUFFICIENT_JADE_TEXT = "玉符不足，无法洗炼"

/** 洗炼结果未产出时的占位显示 */
private const val EMPTY_RESULT_TEXT = "——"

/** 解析灵根数量色（仿 BasicInfoSection 的 countColor 解析，兜底黑色；runCatching 避 detekt） */
@Composable
private fun parseRootColor(hex: String): Color = remember(hex) {
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Black)
}

/**
 * 洗炼灵根弹窗（内联覆盖层，渲染在弟子详情内容 lambda 末尾）。
 *
 * 布局：标题"洗炼灵根" + 右上角关闭（[InlineStandardPromptDialog] showCloseButton）、
 * 左侧当前灵根 → 右箭头 → 右侧洗炼结果、按钮上方"消耗1玉符"小字（12sp + 玉符图标 12dp，
 * 与宗门信息卡片小字/灵石图标一致；玉符不足变红）、按钮区动态切换。
 *
 * 保底计数由本弹窗会话持有（remember，关闭即重置）；洗炼产物未确认替换前不写弟子。
 */
@Composable
fun SpiritRootWashDialog(
    disciple: DiscipleAggregate,
    jadeSymbols: Int,
    viewModel: GameViewModel?,
    onDismiss: () -> Unit
) {
    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "洗炼灵根",
        showCloseButton = true,
        dismissOnClickOutside = true,
        dismissOnBackPress = true,
        content = {
            SpiritRootWashContent(
                disciple = disciple,
                jadeSymbols = jadeSymbols,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
    )
}

/** 洗炼弹窗内容：会话状态（产物/保底计数/防连点）+ 布局 + 玉符不足提示框 */
@Composable
private fun SpiritRootWashContent(
    disciple: DiscipleAggregate,
    jadeSymbols: Int,
    viewModel: GameViewModel?,
    onDismiss: () -> Unit
) {
    // 洗炼产物（英文元素 key 逗号串），未洗炼为 null
    var washResult by remember { mutableStateOf<String?>(null) }
    // 保底计数（连续未出单灵根次数，UI 会话持有）
    var pityCount by remember { mutableIntStateOf(0) }
    // 防连点：引擎操作串行化但快速连点会排队消耗多次玉符
    var washing by remember { mutableStateOf(false) }
    var showInsufficientDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val currentRootColor = parseRootColor(disciple.spiritRoot.countColor)
    val newRoot = washResult?.let { SpiritRoot(it) }
    val newRootColor = newRoot?.let { parseRootColor(it.countColor) } ?: Color.Black
    val jadeInsufficient = jadeSymbols < GameConfig.SpiritRoot.WASH_JADE_COST

    fun onWashClick() {
        if (washing) return
        val vm = viewModel ?: return
        washing = true
        scope.launch {
            try {
                handleWashResult(
                    result = vm.washSpiritRoot(disciple.id, pityCount),
                    onSuccess = { root, pity ->
                        washResult = root
                        pityCount = pity
                    },
                    onFailure = { showInsufficientDialog = true }
                )
            } finally {
                washing = false
            }
        }
    }

    fun onConfirmReplaceClick() {
        val current = washResult ?: return
        val vm = viewModel ?: return
        scope.launch {
            handleConfirmResult(
                result = vm.confirmSpiritRootWash(disciple.id, current),
                onSuccess = onDismiss,
                onFailure = { showInsufficientDialog = true }
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WashResultRow(
            currentRootName = disciple.spiritRootName,
            currentRootColor = currentRootColor,
            newRoot = newRoot,
            newRootColor = newRootColor
        )
        Spacer(modifier = Modifier.weight(1f))
        CostHintRow(jadeInsufficient)
        Spacer(modifier = Modifier.height(8.dp))
        WashActionButtons(
            hasResult = washResult != null,
            washing = washing,
            onWashClick = ::onWashClick,
            onConfirmReplaceClick = ::onConfirmReplaceClick
        )
    }

    InsufficientJadeDialog(showInsufficientDialog) { showInsufficientDialog = false }
}

/** ① 当前灵根 → 洗炼结果（未洗炼显示占位符） */
@Composable
private fun WashResultRow(
    currentRootName: String,
    currentRootColor: Color,
    newRoot: SpiritRoot?,
    newRootColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "当前灵根", fontSize = 12.sp, color = Color.Black)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentRootName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = currentRootColor
            )
        }
        Text(text = "→", fontSize = 20.sp, color = Color.Black)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "洗炼结果", fontSize = 12.sp, color = Color.Black)
            Spacer(modifier = Modifier.height(4.dp))
            if (newRoot != null) {
                Text(
                    text = newRoot.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = newRootColor
                )
            } else {
                Text(
                    text = EMPTY_RESULT_TEXT,
                    fontSize = 14.sp,
                    color = Color(0xFFAAAAAA)
                )
            }
        }
    }
}

/** ② 消耗提示（白色小字 12sp + 玉符图标 12dp，与宗门信息卡片一致；不足变红） */
@Composable
private fun CostHintRow(jadeInsufficient: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SpriteImage(
            name = "jade_symbol",
            contentDescription = "玉符",
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "消耗${GameConfig.SpiritRoot.WASH_JADE_COST}玉符",
            fontSize = 12.sp,
            color = if (jadeInsufficient) Color.Red else Color.White
        )
    }
}

/** ③ 按钮区：未洗炼单个"洗炼灵根"按钮；洗炼后"确认替换"+"继续洗炼" */
@Composable
private fun WashActionButtons(
    hasResult: Boolean,
    washing: Boolean,
    onWashClick: () -> Unit,
    onConfirmReplaceClick: () -> Unit
) {
    if (!hasResult) {
        GameButton(
            text = "洗炼灵根",
            onClick = onWashClick,
            enabled = !washing
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton(text = "确认替换", onClick = onConfirmReplaceClick)
            GameButton(
                text = "继续洗炼",
                onClick = onWashClick,
                enabled = !washing
            )
        }
    }
}

/** 洗炼结果分发：Success 回传产物与保底计数，失败（不足/其他错误）统一走 onFailure */
private fun handleWashResult(
    result: SpiritRootWashResult,
    onSuccess: (newRootType: String, newPityCount: Int) -> Unit,
    onFailure: () -> Unit
) {
    when (result) {
        is SpiritRootWashResult.Success -> onSuccess(result.newRootType, result.newPityCount)
        is SpiritRootWashResult.InsufficientJadeSymbols -> onFailure()
        is SpiritRootWashResult.Error -> onFailure()
    }
}

/** 确认替换结果分发：成功关弹窗，失败弹提示 */
private fun handleConfirmResult(
    result: SpiritRootWashConfirmResult,
    onSuccess: () -> Unit,
    onFailure: () -> Unit
) {
    when (result) {
        is SpiritRootWashConfirmResult.Success -> onSuccess()
        is SpiritRootWashConfirmResult.Error -> onFailure()
    }
}

/** 玉符不足提示框（嵌套内联覆盖层，z 序高于洗炼弹窗） */
@Composable
private fun InsufficientJadeDialog(show: Boolean, onDismiss: () -> Unit) {
    if (show) {
        InlineStandardPromptDialog(
            onDismissRequest = onDismiss,
            title = "提示",
            text = INSUFFICIENT_JADE_TEXT,
            confirmLabel = "知道了",
            onConfirm = onDismiss,
            dismissOnClickOutside = true
        )
    }
}
