package com.xianxia.sect.ui.game.dialogs

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.profession.ProfessionLevelInfo
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.profession.professionLevelInfos
import com.xianxia.sect.core.profession.promotionProgressStatus
import com.xianxia.sect.core.ui.R
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.DialogFocusGuard
import com.xianxia.sect.ui.components.DialogSystemBarGuard
import com.xianxia.sect.ui.theme.GameColors

/** 晋升进度条测试标签（UI 渲染测试定位进度条显隐用） */
internal const val PROFESSION_PROMOTION_BAR_TAG = "profession_promotion_bar"

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
 * 槽位上方职业晋升进度区：晋升进度条 + 未达标红色提示（进度条位于职业等级文本上方）。
 *
 * 显示规则（判定与 `applyPromotionProgress` 晋升三重门槛一致：数量/境界/属性）：
 * - 未任命弟子（[disciple] 为 null）或已满级（丹圣/器圣）时不渲染任何内容；
 * - 进度条显示晋升数量进度（已炼制符合晋升条件的数量 / 所需数量，仅计当前解锁最高阶成功炼制）；
 * - 数量已达标但境界未达标 → 红色提示"弟子境界需到XX（境界名）"；
 * - 数量已达标但炼丹/锻造属性未达标 → 红色提示"弟子炼丹（炼器）属性需到XX"。
 *
 * @param disciple 槽位上的工作弟子（null = 未任命）
 * @param isAlchemy true=炼丹职业（属性名"炼丹"），false=锻造（炼器）职业（属性名"炼器"）
 */
@Composable
fun ProfessionProgressSection(
    disciple: DiscipleAggregate?,
    isAlchemy: Boolean
) {
    if (disciple == null) return
    val level = if (isAlchemy) disciple.alchemyLevel else disciple.forgeLevel
    val count = if (isAlchemy) disciple.alchemyPromotionCount else disciple.forgePromotionCount
    val skill = if (isAlchemy) disciple.pillRefining else disciple.artifactRefining
    val status = promotionProgressStatus(level, count, disciple.realm, skill) ?: return

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (status.meetsCount && !status.meetsRealm) {
            Text(
                text = "弟子境界需到${GameConfig.Realm.getName(status.requiredRealm)}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.Error
            )
        }
        if (status.meetsCount && !status.meetsSkill) {
            Text(
                text = "弟子${if (isAlchemy) "炼丹" else "炼器"}属性需到${status.requiredSkill}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.Error
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val fraction = if (status.requiredCount > 0) {
            (status.currentCount.toFloat() / status.requiredCount).coerceIn(0f, 1f)
        } else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .testTag(PROFESSION_PROMOTION_BAR_TAG)
                .width(80.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = GameColors.Success,
            trackColor = GameColors.Border
        )
        Spacer(modifier = Modifier.height(4.dp))
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
