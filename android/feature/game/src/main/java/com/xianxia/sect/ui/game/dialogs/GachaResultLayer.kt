package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.GachaColors
import com.xianxia.sect.ui.components.GridRow
import com.xianxia.sect.ui.game.GachaRewardCellModel
import com.xianxia.sect.ui.game.GachaStarUpModel
import com.xianxia.sect.ui.theme.GameColors

/**
 * 寻访结果页（Q30 规格，v1.4 拍板）。
 *
 * 渲染在寻访窗口的**窗口级 overlay 槽位**上（同一窗口内最高 z 序，非独立 Dialog），
 * 于是「点框外关闭结果页」只关掉这一层、不会连带关掉下层主界面——这条能力由
 * `UnifiedGameDialogOverlayLayerTest` 实测钉住（G11 D-2）。
 * 因此容器必须 `dismissOnClickOutside = false`，本层自己吃掉框外点击。
 *
 * 叠下一轮：宿主只在 `DialogType` 变化时重组，所以调用方对本层包 `key(resultToken)`，
 * 换轮即重建网格并重播流光。
 */
@Composable
fun GachaResultLayer(
    inputs: GachaResultLayerInputs,
    onPullOnce: () -> Unit,
    onPullTen: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 升星层先于结果网格（时序 roll → 升星层 → 结果页）；十连一次跨星多名时逐条展示。
    // 游标用「已确认条数」而不是「剩余列表」：跨星清单是 `starMap` 流的派生值，
    // 可能在结果层首帧之后才补齐，剩余列表式写法会把后到的条目永久吞掉。
    var confirmed by remember { mutableIntStateOf(0) }
    val pendingStarUps = inputs.starUps.drop(confirmed)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(GACHA_RESULT_LAYER_TAG)
            .background(LAYER_BACKDROP)
            .clickable(onClick = onDismiss)
    ) {
        if (pendingStarUps.isNotEmpty()) {
            GachaStarUpPanel(
                entry = pendingStarUps.first(),
                onConfirm = { confirmed += 1 }
            )
        } else {
            ResultPanel(
                inputs = inputs,
                onPullOnce = onPullOnce,
                onPullTen = onPullTen,
            )
        }
    }
}

/** 结果面板主体：金色标题 + 上下横线 + 居中网格 + 底部双按钮 */
@Composable
private fun ResultPanel(
    inputs: GachaResultLayerInputs,
    onPullOnce: () -> Unit,
    onPullTen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PANEL_H_PADDING_DP.dp, vertical = PANEL_V_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = TITLE_TEXT,
            fontSize = 18.sp,
            color = GameColors.Gold,
        )
        RuleLine()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            RewardGrid(cells = inputs.cells, shimmer = inputs.shimmer)
        }
        RuleLine()
        Spacer(modifier = Modifier.height(BUTTON_GAP_DP.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                BUTTON_GAP_DP.dp,
                Alignment.CenterHorizontally
            )
        ) {
            GameButton(
                text = PULL_ONCE_TEXT,
                onClick = onPullOnce,
                enabled = inputs.canAffordOnce,
                // 下层主界面有同名按钮，结果层的两个按钮必须可独立寻址（渲染测试与无障碍均按它定位）
                modifier = Modifier.testTag(GACHA_LAYER_PULL_ONCE_TAG)
            )
            GameButton(
                text = PULL_TEN_TEXT,
                onClick = onPullTen,
                enabled = inputs.canAffordTen,
                modifier = Modifier.testTag(GACHA_LAYER_PULL_TEN_TAG)
            )
        }
    }
}

/** 两行 × 五列铺满；单抽只有 1 格时在网格区域内居中（不贴左上角） */
@Composable
private fun RewardGrid(cells: List<GachaRewardCellModel>, shimmer: Boolean) {
    if (cells.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellSize = gachaSquareCellSize(
            columns = GRID_COLUMNS,
            rowLines = rowLinesOf(cells.size),
            availableWidth = maxWidth,
            availableHeight = maxHeight,
            gap = GRID_GAP_DP.dp,
        )
        if (cells.size == SINGLE_CELL_COUNT) {
            GachaRewardCell(
                cell = cells.first(),
                shimmer = shimmer,
                modifier = Modifier.size(cellSize),
            )
        } else {
            GridRow(
                items = cells,
                columns = GRID_COLUMNS,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
                verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
            ) { cell ->
                GachaRewardCell(
                    cell = cell,
                    shimmer = shimmer,
                    modifier = Modifier.weight(1f).height(cellSize),
                )
            }
        }
    }
}

/** 全屏升星提示（Q30「星级跳变 + 加成数值」；点任意处=确认下一条） */
@Composable
private fun GachaStarUpPanel(entry: GachaStarUpModel, onConfirm: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onConfirm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = entry.name, fontSize = 20.sp, color = GameColors.Gold)
        Spacer(modifier = Modifier.height(STAR_UP_GAP_DP.dp))
        Text(
            text = "★${entry.starBefore} → ★${entry.starAfter}",
            fontSize = 18.sp,
            color = GachaColors.rarityColor(entry.starAfter),
        )
        Spacer(modifier = Modifier.height(STAR_UP_GAP_DP.dp))
        Text(text = entry.battleBonusText, fontSize = 13.sp, color = GameColors.TextPrimary)
        Text(text = entry.cultivationBonusText, fontSize = 13.sp, color = GameColors.TextPrimary)
        Spacer(modifier = Modifier.height(STAR_UP_GAP_DP.dp))
        Text(text = CONFIRM_HINT_TEXT, fontSize = 11.sp, color = GameColors.TextSecondary)
    }
}

/** Q30 的上下横线 */
@Composable
private fun RuleLine() {
    HorizontalDivider(
        thickness = RULE_THICKNESS_DP.dp,
        color = GameColors.GoldDark,
        modifier = Modifier.padding(vertical = RULE_PADDING_DP.dp)
    )
}

/** 行线数（两行 × 五列，末行不满也占一行） */
private fun rowLinesOf(cells: Int): Int = (cells + GRID_COLUMNS - 1) / GRID_COLUMNS

/** 结果层输入（Composable 参数上限 6 ⇒ 归组传入） */
data class GachaResultLayerInputs(
    val cells: List<GachaRewardCellModel>,
    val starUps: List<GachaStarUpModel>,
    val shimmer: Boolean,
    val canAffordOnce: Boolean,
    val canAffordTen: Boolean,
)

private const val TITLE_TEXT = "恭喜获得"

/** 结果层的测试标识（渲染/交互测试按它判断叠层与关闭） */
const val GACHA_RESULT_LAYER_TAG = "gacha_result_layer"

/** 结果层两个招募按钮的标识（下层主界面有同名文案，必须靠标识区分） */
const val GACHA_LAYER_PULL_ONCE_TAG = "gacha_layer_pull_once"
const val GACHA_LAYER_PULL_TEN_TAG = "gacha_layer_pull_ten"
private const val PULL_ONCE_TEXT = "招募一次"
private const val PULL_TEN_TEXT = "招募十次"
private const val CONFIRM_HINT_TEXT = "点击任意处继续"

/** 十连的固定列数（Q30 两行 × 五列） */
private const val GRID_COLUMNS = 5

/** 单抽布局分支的触发格数 */
private const val SINGLE_CELL_COUNT = 1
private const val GRID_GAP_DP = 6
private const val PANEL_H_PADDING_DP = 24
private const val PANEL_V_PADDING_DP = 12
private const val BUTTON_GAP_DP = 10
private const val STAR_UP_GAP_DP = 10
private const val RULE_THICKNESS_DP = 1
private const val RULE_PADDING_DP = 6

/** 结果层底：Q30「中间半透明面板」，压住下层主界面但不全遮 */
private val LAYER_BACKDROP = Color(0xB3000000)
