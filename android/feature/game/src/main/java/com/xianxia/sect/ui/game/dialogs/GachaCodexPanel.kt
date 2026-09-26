package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.GridRow
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.game.GachaCodexCellModel
import com.xianxia.sect.ui.theme.GameColors

/**
 * 寻访图鉴（Q30/§4.4 六格）。
 *
 * 六格 = [com.xianxia.sect.core.model.CharacterTemplateDb] 的全部模板，状态由
 * `GachaRenderModel.codexCells` 纯函数产出：未解锁判据是**星级账本里没有这个键**
 * （账本稀疏，0 星不落键），立绘置灰；已解锁显示全身立绘（1024 档 `portraitKey`，
 * 按需解码，不进预加载）+ 星级 + 「下一星 x/100」+ 战斗/修炼收益预览，满星显示 MAX。
 * 底部一句来源说明（模板表无来源字段，六格角色均出自寻访）。
 */
@Composable
fun GachaCodexPanel(
    cells: List<GachaCodexCellModel>,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CODEX_H_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // 立绘边长与结果页共用同一算式：写死 dp 在窄横屏会把最后一列挤出屏幕
            val portraitSize = gachaSquareCellSize(
                columns = CODEX_COLUMNS,
                rowLines = CODEX_ROW_LINES,
                availableWidth = maxWidth,
                availableHeight = maxHeight - TEXT_STACK_DP.dp,
                gap = CODEX_GAP_DP.dp,
            )
            GridRow(
                items = cells,
                columns = CODEX_COLUMNS,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CODEX_GAP_DP.dp),
                verticalArrangement = Arrangement.spacedBy(CODEX_GAP_DP.dp),
            ) { cell ->
                CodexCell(
                    cell = cell,
                    portraitSize = portraitSize,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = SOURCE_NOTE_TEXT,
            fontSize = 10.sp,
            color = GameColors.TextSecondary,
            modifier = Modifier.padding(bottom = SOURCE_NOTE_GAP_DP.dp),
        )
        GameButton(text = BACK_TEXT, onClick = onBack)
    }
}

/** 一格图鉴：立绘 + 姓名 + 星级 + 下一星进度 + 收益预览（未解锁整体压暗并标「未解锁」） */
@Composable
private fun CodexCell(
    cell: GachaCodexCellModel,
    portraitSize: Dp,
    modifier: Modifier = Modifier,
) {
    val contentAlpha = if (cell.unlocked) LOCKED_ALPHA_DIM else FULL_ALPHA
    Column(
        modifier = modifier.alpha(contentAlpha),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(portraitSize),
            contentAlignment = Alignment.Center
        ) {
            SpriteImage(
                name = cell.portraitKey,
                contentDescription = cell.name,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = cell.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StarLine(cell)
        ProgressLine(cell)
        if (cell.unlocked) {
            BonusLine(cell.battleBonusText)
            BonusLine(cell.cultivationBonusText)
        }
    }
}

/** 星级行：`★×star`，满星改显 MAX（Q30 图鉴口径） */
@Composable
private fun StarLine(cell: GachaCodexCellModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(STAR_GAP_DP.dp)) {
        Text(
            text = starText(cell),
            fontSize = 11.sp,
            color = GameColors.Gold,
        )
    }
}

private fun starText(cell: GachaCodexCellModel): String = when {
    !cell.unlocked -> LOCKED_LABEL
    cell.isMaxStar -> MAX_STAR_LABEL
    else -> STAR_GLYPH.repeat(cell.star)
}

/** 下一星进度行：`fragmentCounts[tid]/100`；满星后碎片继续累加、不再折算，故改显示累计量 */
@Composable
private fun ProgressLine(cell: GachaCodexCellModel) {
    val text = when {
        cell.isMaxStar -> "碎片 ${cell.fragmentsToNextStar}"
        !cell.unlocked && cell.fragmentsToNextStar <= 0 -> PROGRESS_EMPTY_LABEL
        else -> "下一星 ${cell.fragmentsToNextStar}/${GameConfig.Gacha.FRAGMENTS_PER_STAR}"
    }
    Text(
        text = text,
        fontSize = 10.sp,
        color = GameColors.TextSecondary,
        modifier = Modifier.width(INDEX_TEXT_MAX_WIDTH_DP.dp),
    )
}

/** 收益预览行：与升星层同一份倍率文案（口径 A），字号取进度行同档 */
@Composable
private fun BonusLine(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = GameColors.TextSecondary,
        modifier = Modifier.width(INDEX_TEXT_MAX_WIDTH_DP.dp),
    )
}

private const val CODEX_COLUMNS = 6
private const val CODEX_ROW_LINES = 1

/** 姓名 + 星级 + 进度 + 收益预览五行文本的预留高度（立绘只能在剩下的可用区里取方边长） */
private const val TEXT_STACK_DP = 76
private const val CODEX_H_PADDING_DP = 12
private const val CODEX_GAP_DP = 4
private const val STAR_GAP_DP = 1
private const val INDEX_TEXT_MAX_WIDTH_DP = 96
private const val SOURCE_NOTE_GAP_DP = 4
private const val SOURCE_NOTE_TEXT = "全部角色均通过寻访获得"
private const val BACK_TEXT = "返回"
private const val STAR_GLYPH = "★"
private const val MAX_STAR_LABEL = "MAX"
private const val LOCKED_LABEL = "未解锁"
private const val PROGRESS_EMPTY_LABEL = "—"

/** 未解锁压暗系数（看得见立绘轮廓，但不与已解锁混淆） */
private const val LOCKED_ALPHA_DIM = 0.35f
private const val FULL_ALPHA = 1f
