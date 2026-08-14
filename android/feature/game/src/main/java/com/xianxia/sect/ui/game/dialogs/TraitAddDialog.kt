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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig.TraitAdd
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.TraitAddConfirmResult
import com.xianxia.sect.core.engine.TraitAddResult
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.game.GameViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/** 玉符不足提示文案（对齐洗炼"玉符不足，无法洗炼"的固定文案语义） */
private const val INSUFFICIENT_JADE_TEXT = "玉符不足，无法新增"

/**
 * 新增弹窗会话控制参数组（Composable 参数上限约束，聚合防连点状态通道）。
 *
 * [adding] 由弹窗主函数 remember 持有：一方面供弹窗 onDismissRequest 在引擎操作进行中
 * 拦截关闭（防"玉符已扣、结果丢失"），另一方面下传内容区用于按钮 enabled/防连点。
 */
internal data class TraitAddSessionControl(
    val adding: Boolean,
    val onAddingChange: (Boolean) -> Unit
)

/** 按类型分发刷新请求（提取自 TraitAddContent，控 Cyclomatic 复杂度） */
private suspend fun GameViewModel.addByType(
    id: String,
    type: TraitWashType
): TraitAddResult = when (type) {
    TraitWashType.TALENT -> addTalent(id)
    TraitWashType.PHYSIQUE -> addPhysique(id)
    TraitWashType.AFFIX -> addAffix(id)
}

/** 按类型分发确认新增请求 */
private suspend fun GameViewModel.confirmAddByType(
    id: String,
    type: TraitWashType,
    newId: String
): TraitAddConfirmResult = when (type) {
    TraitWashType.TALENT -> confirmAddTalent(id, newId)
    TraitWashType.PHYSIQUE -> confirmAddPhysique(id, newId)
    TraitWashType.AFFIX -> confirmAddAffix(id, newId)
}

/**
 * 新增天赋/体质/词条弹窗（内联覆盖层，渲染在弟子详情根 Box 最末）。
 *
 * 结构与流程复用洗炼弹窗 [TraitWashDialog]：标题"新增XX" + 右上角关闭 + 点击屏幕外
 * 关闭 + 防连点/防中途关闭（adding 拦截 onDismissRequest，防"玉符已扣、结果丢失"）
 * + 错误原因直接展示（Error/Confirm Error 透传引擎 message）。
 *
 * 与洗炼的两处差异：
 * 1. 语义为**追加**而非替换——初始显示一道下横线（横线上为空），刷新后横线上方显示产物；
 * 2. 刷新结果由引擎**持久化**到 GameData.pendingTraitAdds——[pendingTraitId] 为上次
 *    未确认的产物（关闭界面再打开仍显示，可直接确认新增）；确认新增不消耗玉符。
 *
 * @param pendingTraitId 该弟子+类型已持久化的未确认产物（无则 null）
 */
@Composable
internal fun TraitAddDialog(
    disciple: DiscipleAggregate,
    type: TraitWashType,
    jadeSymbols: Int,
    pendingTraitId: String?,
    viewModel: GameViewModel?,
    onDismiss: () -> Unit
) {
    // 防连点/防中途关闭：刷新与确认新增共用的引擎操作进行中标记（弹窗会话持有）
    var adding by remember { mutableStateOf(false) }
    InlineStandardPromptDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = "新增${type.displayName}",
        showCloseButton = true,
        dismissOnClickOutside = true,
        dismissOnBackPress = true,
        content = {
            TraitAddContent(
                disciple = disciple,
                type = type,
                jadeSymbols = jadeSymbols,
                pendingTraitId = pendingTraitId,
                viewModel = viewModel,
                session = TraitAddSessionControl(
                    adding = adding,
                    onAddingChange = { adding = it }
                ),
                onDismiss = onDismiss
            )
        }
    )
}

/** 新增弹窗内容：会话状态（持久化产物/错误提示）+ 布局 + 动态按钮区 */
@Composable
private fun TraitAddContent(
    disciple: DiscipleAggregate,
    type: TraitWashType,
    jadeSymbols: Int,
    pendingTraitId: String?,
    viewModel: GameViewModel?,
    session: TraitAddSessionControl,
    onDismiss: () -> Unit
) {
    // 刷新产物：以引擎持久化的 pending 初始化（关闭再打开仍显示），成功后更新
    var addResult by remember(pendingTraitId) { mutableStateOf(pendingTraitId) }
    // 错误提示文案（null = 不显示）：玉符不足/刷新失败/新增失败共用，文案区分
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val jadeInsufficient = jadeSymbols < TraitAdd.JADE_COST

    // 同帧连点防重入：adding 是 Compose 状态，同帧内第二次点击读旧值 false → 双扣玉符；
    // AtomicBoolean compareAndSet 立即生效不等重组（对抗性审查教训，同洗炼弹窗）
    val addInFlight = remember { AtomicBoolean(false) }

    fun onConsumeClick() {
        val vm = viewModel
        if (vm == null || session.adding || !addInFlight.compareAndSet(false, true)) return
        session.onAddingChange(true)
        scope.launch {
            try {
                val result = vm.addByType(disciple.id, type)
                handleAddResult(
                    result = result,
                    onSuccess = { newId -> addResult = newId },
                    onInsufficient = { errorText = INSUFFICIENT_JADE_TEXT },
                    onError = { message -> errorText = message }
                )
            } finally {
                addInFlight.set(false)
                session.onAddingChange(false)
            }
        }
    }

    fun onConfirmAddClick() {
        val current = addResult
        val vm = viewModel
        if (current == null || vm == null) return
        if (session.adding || !addInFlight.compareAndSet(false, true)) return
        session.onAddingChange(true)
        scope.launch {
            try {
                val result = vm.confirmAddByType(disciple.id, type, current)
                handleConfirmAddResult(
                    result = result,
                    onSuccess = onDismiss,
                    onError = { message -> errorText = message }
                )
            } finally {
                addInFlight.set(false)
                session.onAddingChange(false)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 上留白：把结果区（下横线）推到弹窗中部（需求："中间初始显示一道下横线"）
        Spacer(modifier = Modifier.weight(1f))
        // ① 刷新结果区：横线上方显示刷新出的特质（初始为空；解析失败显示"无"——旧快照防御）
        AddResultArea(type = type, newId = addResult)
        // 下留白：把消耗提示与按钮压到底部
        Spacer(modifier = Modifier.weight(1f))
        // ② 消耗提示（红白双色，与洗炼一致）
        AddCostHintRow(jadeInsufficient)
        Spacer(modifier = Modifier.height(8.dp))
        // ③ 按钮区
        AddActionButtons(
            hasResult = addResult != null,
            adding = session.adding,
            onConsumeClick = ::onConsumeClick,
            onConfirmAddClick = ::onConfirmAddClick
        )
    }

    errorText?.let { text ->
        AddErrorDialog(text = text, onDismiss = { errorText = null })
    }
}

/** ① 刷新结果区：中间一道下横线，横线上方为刷新产物（初始为空） */
@Composable
private fun AddResultArea(
    type: TraitWashType,
    newId: String?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val newTraits: List<Pair<String, Int>> = newId?.let { id ->
            when (type) {
                TraitWashType.TALENT -> TalentDatabase.getTalentsByIds(listOf(id)).map { it.name to it.rarity }
                TraitWashType.PHYSIQUE -> PhysiqueDatabase.getPhysiquesByIds(listOf(id)).map { it.name to it.rarity }
                TraitWashType.AFFIX -> AffixDatabase.getAffixesByIds(listOf(id)).map { it.name to it.rarity }
            }
        } ?: emptyList()
        if (newTraits.isEmpty()) {
            // 初始状态：横线上方为空（占位保持高度稳定）
            Spacer(modifier = Modifier.height(18.dp))
        } else {
            newTraits.forEach { (name, rarity) ->
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = getTalentRarityColor(rarity)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 下横线（需求："中间初始显示一道下横线"）
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = Color(0xFF757575)
        )
    }
}

/** ② 消耗提示（白色小字 12sp + 玉符图标 12dp，与洗炼弹窗一致；不足变红） */
@Composable
private fun AddCostHintRow(jadeInsufficient: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SpriteImage(
            name = "jade_symbol",
            contentDescription = "玉符",
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "消耗${TraitAdd.JADE_COST}玉符",
            fontSize = 12.sp,
            color = if (jadeInsufficient) Color.Red else Color.White
        )
    }
}

/** ③ 按钮区：未刷新单个"消耗玉符"按钮；刷新后"确认新增"+"继续消耗"（引擎操作中全部禁用） */
@Composable
private fun AddActionButtons(
    hasResult: Boolean,
    adding: Boolean,
    onConsumeClick: () -> Unit,
    onConfirmAddClick: () -> Unit
) {
    if (!hasResult) {
        GameButton(
            text = "消耗玉符",
            onClick = onConsumeClick,
            enabled = !adding
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton(
                text = "确认新增",
                onClick = onConfirmAddClick,
                enabled = !adding
            )
            GameButton(
                text = "继续消耗",
                onClick = onConsumeClick,
                enabled = !adding
            )
        }
    }
}

/**
 * 刷新结果分发：Success 回传产物；不足用固定文案（余额问题），
 * Error 直接透传引擎 message（玩家可读中文，如"弟子已死亡"，不吞掉具体失败原因）。
 */
private fun handleAddResult(
    result: TraitAddResult,
    onSuccess: (newId: String) -> Unit,
    onInsufficient: () -> Unit,
    onError: (message: String) -> Unit
) {
    when (result) {
        is TraitAddResult.Success -> onSuccess(result.newId)
        is TraitAddResult.InsufficientJadeSymbols -> onInsufficient()
        is TraitAddResult.Error -> onError(result.message)
    }
}

/** 确认新增结果分发：成功关弹窗，失败透传引擎 message 展示具体原因 */
private fun handleConfirmAddResult(
    result: TraitAddConfirmResult,
    onSuccess: () -> Unit,
    onError: (message: String) -> Unit
) {
    when (result) {
        is TraitAddConfirmResult.Success -> onSuccess()
        is TraitAddConfirmResult.Error -> onError(result.message)
    }
}

/**
 * 错误提示框（平台 Dialog 独立窗口）——与洗炼弹窗同源修复：
 * 嵌套 InlineStandardPromptDialog 会被外层弹窗 clip 裁剪，错误文案不可见；
 * 平台 Dialog 创建独立 Window 全屏覆盖，不受父级布局约束，必定可见。
 */
@Composable
private fun AddErrorDialog(text: String, onDismiss: () -> Unit) {
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "提示",
        text = text,
        confirmLabel = "知道了",
        onConfirm = onDismiss,
        dismissOnClickOutside = true
    )
}
