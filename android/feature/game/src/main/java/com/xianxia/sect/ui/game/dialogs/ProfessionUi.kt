package com.xianxia.sect.ui.game.dialogs

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.core.profession.ProfessionLevelInfo
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.profession.professionLevelInfos
import com.xianxia.sect.core.ui.R
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.DialogFocusGuard
import com.xianxia.sect.ui.components.DialogSystemBarGuard
import com.xianxia.sect.ui.theme.GameColors

/**
 * 职业等级标签颜色（用户指定：0 白 / 1 绿 / 2 蓝 / 3 紫 / 4 橙 / 5 红）。
 *
 * @param level 职业等级（0=无职业 ~ 5=丹圣/器圣）
 */
fun professionLabelColor(level: Int): Color = when (level.coerceIn(0, ProfessionRules.MAX_LEVEL)) {
    0 -> Color.White
    1 -> Color(0xFF4CAF50)
    2 -> Color(0xFF2196F3)
    3 -> Color(0xFF9C27B0)
    4 -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}

/**
 * 炼丹/锻造槽位外部上方的弟子职业标签。
 * 字体大小 12dp，颜色随职业等级变化（无职业=白色）。
 *
 * @param level 弟子职业等级（null=无弟子，不显示标签；0=已任命但无职业，仍显示"无职业"）
 * @param isAlchemy true=炼丹职业名（炼丹师…丹圣），false=炼器职业名（炼器师…器圣）
 */
@Composable
fun ProfessionLabel(level: Int?, isAlchemy: Boolean) {
    if (level != null) {
        Text(
            text = ProfessionRules.displayName(level, isAlchemy),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = professionLabelColor(level)
        )
    }
}

/**
 * 炼丹/锻造职业等级"详情"按钮 — 20dp 圆形按钮，点击弹出职业等级与晋升要求弹窗。
 * 样式对齐 ElderBonusInfoButton（执法长老等职务详情按钮）。
 *
 * @param isAlchemy true=炼丹职业（弹窗标题"炼丹等级"），false=锻造职业（"锻造等级"）
 * @param detailButtonRes 详情图标资源
 * @param backgroundRes 弹窗背景资源
 * @param closeButtonRes 弹窗关闭按钮资源
 */
@Composable
fun ProfessionInfoButton(
    isAlchemy: Boolean,
    modifier: Modifier = Modifier,
    @DrawableRes detailButtonRes: Int = R.drawable.ui_detail_button,
    @DrawableRes backgroundRes: Int = R.drawable.bg_horizontal,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button
) {
    var showDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable { showDialog = true },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = detailButtonRes),
            contentDescription = "详情",
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
    }

    if (showDialog) {
        ProfessionInfoDialog(
            isAlchemy = isAlchemy,
            onDismiss = { showDialog = false },
            backgroundRes = backgroundRes,
            closeButtonRes = closeButtonRes
        )
    }
}

/**
 * 职业等级与晋升要求弹窗 — 结构与 ElderBonusInfoDialog 一致，
 * 内容为 6 个等级条目（无职业 → 丹圣/器圣）及每级晋升要求。
 *
 * @param isAlchemy true=炼丹职业，false=锻造（炼器）职业
 * @param onDismiss 关闭回调
 * @param backgroundRes 弹窗背景资源
 * @param closeButtonRes 关闭按钮资源
 */
@Composable
fun ProfessionInfoDialog(
    isAlchemy: Boolean,
    onDismiss: () -> Unit,
    @DrawableRes backgroundRes: Int = R.drawable.bg_horizontal,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button
) {
    val levelInfos = remember(isAlchemy) { professionLevelInfos(isAlchemy) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 隐藏 Dialog Window 的系统状态栏/导航栏
        DialogSystemBarGuard()
        // 窗口销毁前清除焦点并隐藏软键盘，防文本选择 FloatingActionMode BadToken（Bugly #3026）
        DialogFocusGuard()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            Image(
                painter = painterResource(id = backgroundRes),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 标题行：等级标题 + 右上角关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAlchemy) "炼丹等级" else "锻造等级",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    CloseButton(onClick = onDismiss, closeButtonRes = closeButtonRes)
                }

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                // 6 个等级条目：内容较长，限制最大高度并允许滚动
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = DialogDefaults.CommonMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    levelInfos.forEach { info ->
                        ProfessionLevelEntry(info = info)
                    }
                }
            }
        }
    }
}

/**
 * 单个等级条目：等级名 + 可炼最高品阶 + 晋升要求。
 * 半透明白圆角框，对齐 ElderBonusInfoDialog 的"加成计算"框样式。
 */
@Composable
private fun ProfessionLevelEntry(info: ProfessionLevelInfo) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.5f))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = info.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextSecondary
                )
                Text(
                    text = "可炼最高：${info.maxTierName}",
                    fontSize = 13.sp,
                    color = GameColors.Primary
                )
            }
            Text(
                text = info.promotionRequirement ?: "已满级，无法继续晋升",
                fontSize = 12.sp,
                color = GameColors.TextTertiary,
                lineHeight = 18.sp
            )
        }
    }
}
