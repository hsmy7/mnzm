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
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.GameViewModel
import java.util.concurrent.atomic.AtomicBoolean
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
 * 保底计数：弹窗会话持有，初始化自 [WashSessionControl.initialPityCount]（DiscipleDetailScreen
 * 层常驻计数），洗炼成功时经 [WashSessionControl.onPityCountChanged] 回传——弹窗关闭再打开
 * 保底不重置，连续 3 次保底语义不被弹窗开关破坏。
 *
 * 防丢失：洗炼/确认替换进行中（washing）拦截 onDismissRequest，防止引擎操作
 * 中途关闭导致"玉符已扣、产物丢失"。
 */
@Composable
fun SpiritRootWashDialog(
    disciple: DiscipleAggregate,
    jadeSymbols: Int,
    viewModel: GameViewModel?,
    initialPityCount: Int,
    onPityCountChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 防连点/防中途关闭：洗炼与确认替换共用的引擎操作进行中标记（弹窗会话持有）
    var washing by remember { mutableStateOf(false) }
    InlineStandardPromptDialog(
        onDismissRequest = { if (!washing) onDismiss() },
        title = "洗炼灵根",
        showCloseButton = true,
        dismissOnClickOutside = true,
        dismissOnBackPress = true,
        content = {
            SpiritRootWashContent(
                disciple = disciple,
                jadeSymbols = jadeSymbols,
                viewModel = viewModel,
                washSession = WashSessionControl(
                    initialPityCount = initialPityCount,
                    onPityCountChanged = onPityCountChanged,
                    washing = washing,
                    onWashingChange = { washing = it }
                ),
                onDismiss = onDismiss
            )
        }
    )
}

/** 洗炼弹窗内容：会话状态（产物/保底计数/错误提示）+ 布局 + 动态按钮区 */
@Composable
private fun SpiritRootWashContent(
    disciple: DiscipleAggregate,
    jadeSymbols: Int,
    viewModel: GameViewModel?,
    washSession: WashSessionControl,
    onDismiss: () -> Unit
) {
    // 洗炼产物（英文元素 key 逗号串），未洗炼为 null
    var washResult by remember { mutableStateOf<String?>(null) }
    // 保底计数：以弹窗打开时的上层计数初始化，成功后回传上层（跨会话保持）
    var pityCount by remember { mutableIntStateOf(washSession.initialPityCount) }
    // 错误提示文案（null = 不显示）：玉符不足/洗炼失败/替换失败共用，文案区分
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val currentRootColor = parseRootColor(disciple.spiritRoot.countColor)
    val newRoot = washResult?.let { SpiritRoot(it) }
    val newRootColor = newRoot?.let { parseRootColor(it.countColor) } ?: Color.Black
    val jadeInsufficient = jadeSymbols < GameConfig.SpiritRoot.WASH_JADE_COST

    // 同帧连点防重入：washing 是 Compose 状态，同帧内第二次点击读旧值 false → 双扣玉符；
    // AtomicBoolean compareAndSet 立即生效不等重组（对抗性审查 2026-08-09 状态破坏者发现）
    val washInFlight = remember { AtomicBoolean(false) }

    fun onWashClick() {
        if (washSession.washing || !washInFlight.compareAndSet(false, true)) return
        val vm = viewModel ?: return
        washSession.onWashingChange(true)
        scope.launch {
            try {
                handleWashResult(
                    result = vm.washSpiritRoot(disciple.id, pityCount),
                    onSuccess = { root, pity ->
                        washResult = root
                        pityCount = pity
                        washSession.onPityCountChanged(pity)
                    },
                    onInsufficient = { errorText = INSUFFICIENT_JADE_TEXT },
                    onError = { message -> errorText = message }
                )
            } finally {
                washInFlight.set(false)
                washSession.onWashingChange(false)
            }
        }
    }

    fun onConfirmReplaceClick() {
        val current = washResult
        val vm = viewModel
        if (current == null || vm == null) return
        if (washSession.washing || !washInFlight.compareAndSet(false, true)) return
        washSession.onWashingChange(true)
        scope.launch {
            try {
                handleConfirmResult(
                    result = vm.confirmSpiritRootWash(disciple.id, current),
                    onSuccess = onDismiss,
                    onError = { message -> errorText = message }
                )
            } finally {
                washInFlight.set(false)
                washSession.onWashingChange(false)
            }
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
            washing = washSession.washing,
            onWashClick = ::onWashClick,
            onConfirmReplaceClick = ::onConfirmReplaceClick
        )
    }

    errorText?.let { text ->
        ErrorDialog(text = text, onDismiss = { errorText = null })
    }
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

/** ③ 按钮区：未洗炼单个"洗炼灵根"按钮；洗炼后"确认替换"+"继续洗炼"（引擎操作中全部禁用） */
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
            GameButton(
                text = "确认替换",
                onClick = onConfirmReplaceClick,
                enabled = !washing
            )
            GameButton(
                text = "继续洗炼",
                onClick = onWashClick,
                enabled = !washing
            )
        }
    }
}

/**
 * 洗炼结果分发：Success 回传产物与保底计数；不足用固定文案（余额问题），
 * Error 直接透传引擎 message（玩家可读中文，如"弟子已死亡"，不吞掉具体失败原因）。
 */
private fun handleWashResult(
    result: SpiritRootWashResult,
    onSuccess: (newRootType: String, newPityCount: Int) -> Unit,
    onInsufficient: () -> Unit,
    onError: (message: String) -> Unit
) {
    when (result) {
        is SpiritRootWashResult.Success -> onSuccess(result.newRootType, result.newPityCount)
        is SpiritRootWashResult.InsufficientJadeSymbols -> onInsufficient()
        is SpiritRootWashResult.Error -> onError(result.message)
    }
}

/** 确认替换结果分发：成功关弹窗，失败透传引擎 message 展示具体原因 */
private fun handleConfirmResult(
    result: SpiritRootWashConfirmResult,
    onSuccess: () -> Unit,
    onError: (message: String) -> Unit
) {
    when (result) {
        is SpiritRootWashConfirmResult.Success -> onSuccess()
        is SpiritRootWashConfirmResult.Error -> onError(result.message)
    }
}

/**
 * 错误提示框（平台 Dialog 独立窗口）。
 *
 * 原实现用嵌套 InlineStandardPromptDialog：其 fillMaxSize 填满洗炼弹窗内容区后，
 * 内部 50%W×55%H 弹窗超出内容区，被外层弹窗 clip 裁剪——错误文案（玉符不足/洗炼失败）
 * 完全不可见，玩家点击洗炼后"无任何反馈"误判为洗炼无效（2026-08-11 修复，
 * 与 TraitWashDialog.ErrorDialog 同源缺陷）。
 * 平台 Dialog 创建独立 Window 全屏覆盖，不受父级布局约束，必定可见。
 */
@Composable
private fun ErrorDialog(text: String, onDismiss: () -> Unit) {
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "提示",
        text = text,
        confirmLabel = "知道了",
        onConfirm = onDismiss,
        dismissOnClickOutside = true
    )
}
