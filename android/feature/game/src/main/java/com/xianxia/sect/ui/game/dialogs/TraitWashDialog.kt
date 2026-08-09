package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.TraitWashConfirmResult
import com.xianxia.sect.core.engine.TraitWashResult
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.game.GameViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/** 玉符不足提示文案（与洗炼灵根一致） */
private const val INSUFFICIENT_JADE_TEXT = "玉符不足，无法洗炼"

/** 洗炼结果未产出时的占位显示 */
private const val EMPTY_RESULT_TEXT = "——"

/** 空特质列表显示 */
private const val NONE_TEXT = "无"

/** 按洗炼类型分发洗炼请求（提取自 TraitWashContent，控 Cyclomatic 复杂度） */
private suspend fun GameViewModel.washByType(
    id: String,
    type: TraitWashType,
    pityCount: Int
): TraitWashResult = when (type) {
    TraitWashType.TALENT -> washTalent(id, pityCount)
    TraitWashType.PHYSIQUE -> washPhysique(id, pityCount)
    TraitWashType.AFFIX -> washAffix(id, pityCount)
}

/** 按洗炼类型分发确认替换请求 */
private suspend fun GameViewModel.confirmByType(
    id: String,
    type: TraitWashType,
    newIds: List<String>
): TraitWashConfirmResult = when (type) {
    TraitWashType.TALENT -> confirmTalent(id, newIds)
    TraitWashType.PHYSIQUE -> confirmPhysique(id, newIds)
    TraitWashType.AFFIX -> confirmAffix(id, newIds)
}

/**
 * 洗炼天赋/体质/词条弹窗（内联覆盖层，渲染在弟子详情内容 lambda 末尾）。
 *
 * 结构与流程完全镜像洗炼灵根：两段式（洗炼出产物 → 确认替换）、品质保底计数回传
 * （[WashSessionControl.onPityCountChanged]，弹窗关闭再打开不重置）、防连点/防中途关闭
 * （washing 拦截 onDismissRequest，防"玉符已扣、结果丢失"）、错误原因直接展示
 * （Error/Confirm Error 透传引擎 message，如"弟子已死亡"，不吞掉具体失败原因）。
 */
@Composable
internal fun TraitWashDialog(
    disciple: DiscipleAggregate,
    type: TraitWashType,
    jadeSymbols: Int,
    viewModel: GameViewModel?,
    washSession: WashSessionControl,
    onDismiss: () -> Unit
) {
    // 防连点/防中途关闭：洗炼与确认替换共用的引擎操作进行中标记（弹窗会话持有）
    var washing by remember { mutableStateOf(false) }
    InlineStandardPromptDialog(
        onDismissRequest = { if (!washing) onDismiss() },
        title = "洗炼${type.displayName}",
        showCloseButton = true,
        dismissOnClickOutside = true,
        dismissOnBackPress = true,
        content = {
            TraitWashContent(
                disciple = disciple,
                type = type,
                jadeSymbols = jadeSymbols,
                viewModel = viewModel,
                washSession = washSession.copy(
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
private fun TraitWashContent(
    disciple: DiscipleAggregate,
    type: TraitWashType,
    jadeSymbols: Int,
    viewModel: GameViewModel?,
    washSession: WashSessionControl,
    onDismiss: () -> Unit
) {
    // 洗炼产物（特质 id 列表），未洗炼为 null
    var washResult by remember { mutableStateOf<List<String>?>(null) }
    // 保底计数：以弹窗打开时的上层计数初始化，成功后回传上层（跨会话保持）
    var pityCount by remember { mutableIntStateOf(washSession.initialPityCount) }
    // 错误提示文案（null = 不显示）：玉符不足/洗炼失败/替换失败共用，文案区分
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val currentTraits: List<Pair<String, Int>> = when (type) {
        TraitWashType.TALENT -> TalentDatabase.getTalentsByIds(disciple.talentIds).map { it.name to it.rarity }
        TraitWashType.PHYSIQUE -> PhysiqueDatabase.getPhysiquesByIds(disciple.physiqueIds).map { it.name to it.rarity }
        TraitWashType.AFFIX -> AffixDatabase.getAffixesByIds(disciple.affixIds).map { it.name to it.rarity }
    }
    val jadeInsufficient = jadeSymbols < GameConfig.TraitWash.WASH_JADE_COST

    // 同帧连点防重入：washing 是 Compose 状态，同帧内第二次点击读旧值 false → 双扣玉符；
    // AtomicBoolean compareAndSet 立即生效不等重组（对抗性审查 2026-08-09 状态破坏者发现）
    val washInFlight = remember { AtomicBoolean(false) }

    fun onWashClick() {
        val vm = viewModel
        if (vm == null || washSession.washing || !washInFlight.compareAndSet(false, true)) return
        washSession.onWashingChange(true)
        scope.launch {
            try {
                val result = vm.washByType(disciple.id, type, pityCount)
                handleWashResult(
                    result = result,
                    onSuccess = { newIds, pity ->
                        washResult = newIds
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
                val result = vm.confirmByType(disciple.id, type, current)
                handleConfirmResult(
                    result = result,
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
        TraitResultRow(
            type = type,
            currentTraits = currentTraits,
            newIds = washResult
        )
        Spacer(modifier = Modifier.weight(1f))
        CostHintRow(jadeInsufficient)
        Spacer(modifier = Modifier.height(8.dp))
        WashActionButtons(
            type = type,
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

/** ① 当前特质列表 → 洗炼结果列表（未洗炼显示占位符，空列表显示"无"） */
@Composable
private fun TraitResultRow(
    type: TraitWashType,
    currentTraits: List<Pair<String, Int>>,
    newIds: List<String>?
) {
    val newTraits: List<Pair<String, Int>>? = newIds?.let { ids ->
        when (type) {
            TraitWashType.TALENT -> TalentDatabase.getTalentsByIds(ids).map { it.name to it.rarity }
            TraitWashType.PHYSIQUE -> PhysiqueDatabase.getPhysiquesByIds(ids).map { it.name to it.rarity }
            TraitWashType.AFFIX -> AffixDatabase.getAffixesByIds(ids).map { it.name to it.rarity }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TraitColumn(
            title = "当前${type.displayName}",
            traits = currentTraits,
            modifier = Modifier.weight(1f)
        )
        Text(text = "→", fontSize = 20.sp, color = Color.Black)
        TraitColumn(
            title = "洗炼结果",
            traits = newTraits,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 特质列：标题 + 条目列表（名称按品阶着色，仿详情页特质格子配色） */
@Composable
private fun TraitColumn(
    title: String,
    traits: List<Pair<String, Int>>?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, fontSize = 12.sp, color = Color.Black)
        Spacer(modifier = Modifier.height(4.dp))
        if (traits == null) {
            Text(text = EMPTY_RESULT_TEXT, fontSize = 14.sp, color = Color(0xFFAAAAAA))
        } else if (traits.isEmpty()) {
            Text(text = NONE_TEXT, fontSize = 14.sp, color = Color(0xFFAAAAAA))
        } else {
            traits.forEach { (name, rarity) ->
                Text(
                    text = name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = getTalentRarityColor(rarity)
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
            text = "消耗${GameConfig.TraitWash.WASH_JADE_COST}玉符",
            fontSize = 12.sp,
            color = if (jadeInsufficient) Color.Red else Color.White
        )
    }
}

/** ③ 按钮区：未洗炼单个"洗炼XX"按钮；洗炼后"确认替换"+"继续洗炼"（引擎操作中全部禁用） */
@Composable
private fun WashActionButtons(
    type: TraitWashType,
    hasResult: Boolean,
    washing: Boolean,
    onWashClick: () -> Unit,
    onConfirmReplaceClick: () -> Unit
) {
    if (!hasResult) {
        GameButton(
            text = "洗炼${type.displayName}",
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
    result: TraitWashResult,
    onSuccess: (newIds: List<String>, newPityCount: Int) -> Unit,
    onInsufficient: () -> Unit,
    onError: (message: String) -> Unit
) {
    when (result) {
        is TraitWashResult.Success -> onSuccess(result.newIds, result.newPityCount)
        is TraitWashResult.InsufficientJadeSymbols -> onInsufficient()
        is TraitWashResult.Error -> onError(result.message)
    }
}

/** 确认替换结果分发：成功关弹窗，失败透传引擎 message 展示具体原因 */
private fun handleConfirmResult(
    result: TraitWashConfirmResult,
    onSuccess: () -> Unit,
    onError: (message: String) -> Unit
) {
    when (result) {
        is TraitWashConfirmResult.Success -> onSuccess()
        is TraitWashConfirmResult.Error -> onError(result.message)
    }
}

/** 错误提示框（嵌套内联覆盖层，z 序高于洗炼弹窗） */
@Composable
private fun ErrorDialog(text: String, onDismiss: () -> Unit) {
    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "提示",
        text = text,
        confirmLabel = "知道了",
        onConfirm = onDismiss,
        dismissOnClickOutside = true
    )
}
