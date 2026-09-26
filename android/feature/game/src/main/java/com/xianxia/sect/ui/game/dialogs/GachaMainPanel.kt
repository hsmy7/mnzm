package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GachaPullShowcase
import com.xianxia.sect.ui.game.GachaRenderModel
import com.xianxia.sect.ui.game.GachaViewModel
import com.xianxia.sect.ui.game.delegate.GachaDelegate
import com.xianxia.sect.ui.theme.GameColors

/**
 * 寻访对话框（G09 核心 → 界面的接线体，G11）。
 *
 * 一个 `DialogType.Recruit` 窗口承载四个面：主界面 / 结果层（窗口级 overlay）/ 图鉴 /
 * 公示 / 历史——子面切换是窗口内的本地状态，不新增 `DialogType`、不开第二扇窗、
 * 也不接第二条通知总线（D-1 / D-3）。
 *
 * 数据面：价格、概率、保底全部读 [GachaPoolSpec]（`db.gachaPools` 单源），四本账读
 * [GachaViewModel] 的门面只读流；写路径只在 [gacha]（引擎线程派发）。本面板不认识
 * `GameViewModel`——依赖收窄成「一个委托 + 一个降级开关 + 余额」，于是能被 Compose 测试
 * 用手工依赖直接渲染（`GachaRecruitDialogTest`），而不必搬整套 GameViewModel 替身。
 */
@Composable
fun GachaRecruitDialog(
    gacha: GachaDelegate,
    gachaVm: GachaViewModel,
    spiritStones: Long,
    shimmer: Boolean,
    onDismiss: () -> Unit,
) {
    var panel by remember { mutableStateOf(GachaPanel.MAIN) }
    val mainInputs = gachaMainInputs(gachaVm, spiritStones)
    val showcase by gachaVm.showcase.collectAsStateWithLifecycle()
    val resultToken by gachaVm.resultToken.collectAsStateWithLifecycle()
    val resultInputs = gachaResultInputs(gachaVm, showcase, mainInputs, shimmer)

    val requestOnce: () -> Unit = {
        gachaVm.onPullRequested()
        gacha.pullOnce { gachaVm.onPullResult(it) }
    }
    val requestTen: () -> Unit = {
        gachaVm.onPullRequested()
        gacha.pullTen { gachaVm.onPullResult(it) }
    }
    val actions = GachaPanelActions(
        onPullOnce = requestOnce,
        onPullTen = requestTen,
        onOpenCodex = { panel = GachaPanel.CODEX },
        onOpenOdds = { panel = GachaPanel.ODDS },
        onOpenHistory = { panel = GachaPanel.HISTORY },
        onBack = { panel = GachaPanel.MAIN },
    )

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = panelTitle(panel),
        mode = DialogMode.Full,
        titleColor = GameColors.Gold,
        scrollableContent = false,
        // 关闭手势由结果层自管（点框外只关结果层），故容器不吃框外点击
        dismissOnClickOutside = false,
        headerActions = { BalanceInHeader(spiritStones) },
        overlay = {
            GachaResultOverlay(
                inputs = resultInputs,
                resultToken = resultToken,
                onPullOnce = requestOnce,
                onPullTen = requestTen,
                onDismiss = { gachaVm.dismissResult() },
            )
        }
    ) {
        // 结果层在时返回键先退层，不退整窗（分层 BackHandler 与历战面板同一套写法）
        BackHandler(enabled = resultInputs != null) { gachaVm.dismissResult() }
        GachaPanelContent(panel = panel, mainInputs = mainInputs, actions = actions, gachaVm = gachaVm)
    }
}

/** 主界面输入：价格读池配置、余额读游戏数据、保底与忙碌态读账本流 */
@Composable
private fun gachaMainInputs(gachaVm: GachaViewModel, spiritStones: Long): GachaMainInputs {
    val poolSpec by gachaVm.poolSpec.collectAsStateWithLifecycle()
    val pityCounters by gachaVm.pityCounters.collectAsStateWithLifecycle()
    val pulling by gachaVm.pulling.collectAsStateWithLifecycle()
    val failureMessage by gachaVm.failureMessage.collectAsStateWithLifecycle()
    return GachaMainInputs(
        pool = poolSpec,
        spiritStones = spiritStones,
        pityCount = pityCounters[STANDARD_POOL_ID] ?: 0,
        pulling = pulling,
        failureMessage = failureMessage,
    )
}

/**
 * 结果层输入（null = 当前没有结果页）。
 *
 * 跨星清单要两侧一起算：抽前的星级快照来自 [showcase]（请求发出那一刻取的一致值），
 * 抽后的星级来自 [GachaViewModel.starMap] 流。在引擎线程回调里直读 `starMap.value`
 * 会拿到还没追上来的旧值，所以这里在组合期读流。
 */
@Composable
private fun gachaResultInputs(
    gachaVm: GachaViewModel,
    showcase: GachaPullShowcase?,
    mainInputs: GachaMainInputs,
    shimmer: Boolean,
): GachaResultLayerInputs? {
    val starMap by gachaVm.starMap.collectAsStateWithLifecycle()
    return showcase?.let {
        GachaResultLayerInputs(
            cells = GachaRenderModel.resultCells(it.result.rows),
            starUps = GachaRenderModel.starJumps(it.anchorStarMap, starMap),
            shimmer = shimmer,
            canAffordOnce = mainInputs.canAffordOnce,
            canAffordTen = mainInputs.canAffordTen,
        )
    }
}

/**
 * 结果层（窗口级 overlay 槽位）。
 *
 * 叠下一轮 = 换 `resultToken` 重建：宿主只按 `DialogType` 重组（`GameOverlayHost` 的
 * `key(currentDialogType)`），同一窗口内换内容不会自动重播流光，故自己带 key。
 */
@Composable
private fun GachaResultOverlay(
    inputs: GachaResultLayerInputs?,
    resultToken: Int,
    onPullOnce: () -> Unit,
    onPullTen: () -> Unit,
    onDismiss: () -> Unit,
) {
    inputs?.let {
        key(resultToken) {
            GachaResultLayer(
                inputs = it,
                onPullOnce = onPullOnce,
                onPullTen = onPullTen,
                onDismiss = onDismiss,
            )
        }
    }
}

/** 主界面 / 图鉴 / 公示 / 历史四面切换（都在同一个窗口内，不开第二扇窗） */
@Composable
private fun GachaPanelContent(
    panel: GachaPanel,
    mainInputs: GachaMainInputs,
    actions: GachaPanelActions,
    gachaVm: GachaViewModel,
) {
    val starMap by gachaVm.starMap.collectAsStateWithLifecycle()
    val fragmentCounts by gachaVm.fragmentCounts.collectAsStateWithLifecycle()
    val history by gachaVm.history.collectAsStateWithLifecycle()
    when (panel) {
        GachaPanel.MAIN -> GachaMainPanel(inputs = mainInputs, actions = actions)

        GachaPanel.CODEX -> GachaCodexPanel(
            cells = GachaRenderModel.codexCells(starMap, fragmentCounts),
            onBack = actions.onBack,
        )

        GachaPanel.ODDS -> GachaOddsPanel(pool = mainInputs.pool, onBack = actions.onBack)

        GachaPanel.HISTORY -> GachaHistoryPanel(
            rows = GachaRenderModel.historyRows(history),
            pityCount = mainInputs.pityCount,
            pityThreshold = mainInputs.pool?.pity?.pullThreshold,
            onBack = actions.onBack,
        )
    }
}

/** 寻访主界面：池名 / 双价 / 保底进度 / 双按钮 / 三个只读入口 / 失败内联文案 */
@Composable
fun GachaMainPanel(
    inputs: GachaMainInputs,
    actions: GachaPanelActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = H_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)
    ) {
        Text(text = POOL_DISPLAY_NAME, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
        if (inputs.pool == null) {
            Text(text = POOL_UNAVAILABLE_TEXT, fontSize = 12.sp, color = GameColors.TextPrimary)
        } else {
            PriceLines(inputs)
            Text(
                text = gachaPityProgressText(inputs.pityCount, inputs.pool.pity.pullThreshold) +
                    PITY_PROMISE_SUFFIX,
                fontSize = 12.sp,
                color = GameColors.TextPrimary,
            )
        }
        PullButtons(inputs = inputs, onPullOnce = actions.onPullOnce, onPullTen = actions.onPullTen)
        inputs.failureMessage?.let { message ->
            Text(text = message, fontSize = 12.sp, color = Color.Red)
        }
        Spacer(modifier = Modifier.height(ENTRY_GAP_DP.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(ENTRY_GAP_DP.dp)) {
            GameButton(text = ODDS_ENTRY_TEXT, onClick = actions.onOpenOdds)
            GameButton(text = HISTORY_ENTRY_TEXT, onClick = actions.onOpenHistory)
            GameButton(text = CODEX_ENTRY_TEXT, onClick = actions.onOpenCodex)
        }
    }
}

/** 单抽价与十连价（余额不足时价格转红，与商人面板 `PurchasePanel` 同一套判据） */
@Composable
private fun PriceLines(inputs: GachaMainInputs) {
    val price = inputs.pricePerPull
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = "单次寻访：${GameUtils.formatNumber(price)} 灵石",
            fontSize = 12.sp,
            color = if (inputs.canAffordOnce) GameColors.TextPrimary else Color.Red,
        )
        Text(
            text = "十次寻访：${GameUtils.formatNumber(price.toLong() * TEN_PULL_COUNT)} 灵石",
            fontSize = 12.sp,
            color = if (inputs.canAffordTen) GameColors.TextPrimary else Color.Red,
        )
    }
}

@Composable
private fun PullButtons(inputs: GachaMainInputs, onPullOnce: () -> Unit, onPullTen: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP_DP.dp)) {
        GameButton(
            text = PULL_ONCE_TEXT,
            onClick = onPullOnce,
            enabled = inputs.canAffordOnce && !inputs.pulling
        )
        GameButton(
            text = PULL_TEN_TEXT,
            onClick = onPullTen,
            enabled = inputs.canAffordTen && !inputs.pulling
        )
    }
}

/** 标题栏余额（寻访只花下品灵石，故单值展示，口径同商人面板） */
@Composable
private fun BalanceInHeader(spiritStones: Long) {
    Text(
        text = "灵石:${GameUtils.formatNumber(spiritStones)}",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = Modifier.fillMaxWidth().padding(end = HEADER_BALANCE_END_DP.dp),
    )
}

/** 窗口内的面切换（不新增 DialogType） */
enum class GachaPanel { MAIN, CODEX, ODDS, HISTORY }

/** 主界面输入（价格与阈值只从池配置取，UI 侧零数值字面量） */
data class GachaMainInputs(
    val pool: GachaPoolSpec?,
    val spiritStones: Long,
    val pityCount: Int,
    val pulling: Boolean,
    val failureMessage: String?,
) {
    /** 单抽价（池未装载时为 0 ⇒ 下面的可购判据自然为假，按钮禁用） */
    val pricePerPull: Int
        get() = pool?.pricePerPull ?: 0

    val canAffordOnce: Boolean
        get() = pool != null && !pulling && spiritStones >= pricePerPull

    val canAffordTen: Boolean
        get() = pool != null && !pulling && spiritStones >= pricePerPull.toLong() * TEN_PULL_COUNT
}

/** 寻访窗口内的全部动作（两个招募口 + 三个只读子面入口 + 返回主界面） */
data class GachaPanelActions(
    val onPullOnce: () -> Unit,
    val onPullTen: () -> Unit,
    val onOpenCodex: () -> Unit,
    val onOpenOdds: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onBack: () -> Unit,
)

private const val POOL_DISPLAY_NAME = "常驻寻访"
private const val POOL_UNAVAILABLE_TEXT = "这个去处暂时寻访不了"
private const val PULL_ONCE_TEXT = "寻访一次"
private const val PULL_TEN_TEXT = "寻访十次"
private const val PITY_PROMISE_SUFFIX = "，满次必得角色碎片"
private const val ODDS_ENTRY_TEXT = "概率公示"
private const val HISTORY_ENTRY_TEXT = "寻访记录"
private const val CODEX_ENTRY_TEXT = "图鉴"
private const val MAIN_TITLE_TEXT = "寻访"
private const val STANDARD_POOL_ID = "standard"
private const val TEN_PULL_COUNT = 10
private const val H_PADDING_DP = 16
private const val ROW_GAP_DP = 8
private const val ENTRY_GAP_DP = 8
private const val BUTTON_GAP_DP = 8
private const val HEADER_BALANCE_END_DP = 8

/** 保底进度的玩家可读文案（主界面与历史页同一句、同一份计数流，禁各写一份） */
internal fun gachaPityProgressText(pityCount: Int, pityThreshold: Int): String =
    "本期已寻访 $pityCount/$pityThreshold 次"

/** 窗口标题随面切换（结果层不需要标题——它自己带「恭喜获得」，容器标题保持寻访） */
private fun panelTitle(panel: GachaPanel): String = when (panel) {
    GachaPanel.MAIN -> MAIN_TITLE_TEXT
    GachaPanel.CODEX -> CODEX_ENTRY_TEXT
    GachaPanel.ODDS -> ODDS_ENTRY_TEXT
    GachaPanel.HISTORY -> HISTORY_ENTRY_TEXT
}
