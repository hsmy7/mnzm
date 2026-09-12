package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.theme.GameColors



object DiscipleCardStyles {
    val smallShape: Shape = RoundedCornerShape(8.dp)
    val mediumShape: Shape = RoundedCornerShape(12.dp)
    val largeShape: Shape = RoundedCornerShape(16.dp)
    val cardPadding = 12.dp
}

fun Modifier.discipleCardBorder(
    shape: Shape = DiscipleCardStyles.mediumShape,
    background: Color = Color.White
): Modifier = this
    .clip(shape)
    .background(background)
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                GameColors.SurfaceLightGray,
                GameColors.ButtonDisabled
            )
        ),
        shape = shape
    )

object DiscipleAttrDefaults {
    val Color = androidx.compose.ui.graphics.Color.Black
    val FontSize = 11.sp
}

/**
 * 格式化弟子年龄显示文本。
 *
 * @param age 弟子年龄
 * @return 用于 UI 显示的年龄文本，如 "18岁"
 */
fun formatDiscipleAge(age: Int): String = "${age}岁"

@Composable
fun DiscipleAttrText(
    name: String,
    value: Any,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = DiscipleAttrDefaults.FontSize,
    color: Color = DiscipleAttrDefaults.Color,
    fontWeight: FontWeight? = null
) {
    Text(
        text = "$name: $value",
        fontSize = fontSize,
        color = color,
        fontWeight = fontWeight,
        modifier = modifier
    )
}

@Composable
fun FollowedTag(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(GameColors.Gold)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "已关注",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * 统一的弟子卡片，左侧半身像 + 右侧多行信息。
 * 用于所有弟子列表和选择界面。
 * actions: 替换第一行右侧（状态/选中标记）
 * customAttributes: 替换第三行（悟性/忠诚）
 * extraAttributes: 追加在第三行后面
 */
@Composable
fun PortraitDiscipleCard(
    disciple: DiscipleAggregate,
    isSelected: Boolean = false,
    isCurrent: Boolean = false,
    showStatus: Boolean = true,
    extraAttributes: List<Pair<String, Int>> = emptyList(),
    customAttributes: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) GameColors.Gold else GameColors.SurfaceLightGray
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.08f) else Color.White)
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg_horizontal),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(DiscipleCardStyles.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PortraitDisciplePortraitColumn(disciple = disciple)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                PortraitDiscipleTopRow(
                    disciple = disciple,
                    isSelected = isSelected,
                    isCurrent = isCurrent,
                    showStatus = showStatus,
                    actions = actions
                )
                PortraitDiscipleRealmRow(disciple = disciple)
                PortraitDiscipleAttrsRow(
                    disciple = disciple,
                    extraAttributes = extraAttributes,
                    customAttributes = customAttributes
                )
            }
        }
    }
}

/** 弟子卡左半身像列 */
@Composable
private fun PortraitDisciplePortraitColumn(disciple: DiscipleAggregate) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(48.dp)
    ) {
        val resId = remember(disciple.portraitRes) {
            val preloaded = PortraitPool.getResourceId(disciple.portraitRes)
            if (preloaded != 0) preloaded
            else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
        }
        // 44×56dp ≤ 80dp 质量边界，优先命中 L0 预载头像缓存
        PortraitImage(
            name = disciple.portraitRes,
            resId = resId,
            modifier = Modifier.width(44.dp).height(56.dp)
        )
        Text(
            text = disciple.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 弟子卡信息区第一行：性别/年龄/状态/选中标记/操作槽位 */
@Composable
private fun PortraitDiscipleTopRow(
    disciple: DiscipleAggregate,
    isSelected: Boolean,
    isCurrent: Boolean,
    showStatus: Boolean,
    actions: (@Composable () -> Unit)?
) {
    val statusText = disciple.statusText
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = disciple.genderName, fontSize = 12.sp, color = Color.Black)
            Text(
                text = formatDiscipleAge(disciple.age),
                fontSize = 12.sp,
                color = Color.Black
            )
            // 当有自定义 actions 时，状态文字移至年龄右侧（避免与按钮挤在同一侧）
            if (actions != null && showStatus) {
                Text(text = statusText, fontSize = 12.sp, color = Color.Black, maxLines = 1)
            }
            if (disciple.isFollowed) FollowedTag()
            if (isCurrent) {
                Text(text = "当前", fontSize = 10.sp, color = Color(0xFFE74C3C))
            }
        }
        if (actions != null) {
            actions()
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showStatus) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = Color.Black,
                        maxLines = 1
                    )
                }
                if (isSelected) {
                    Text(
                        text = "✓",
                        fontSize = 13.sp,
                        color = GameColors.GoldDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** 弟子卡信息区第二行：境界/灵根 */
@Composable
private fun PortraitDiscipleRealmRow(disciple: DiscipleAggregate) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val spiritRootColor = try {
            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { Color.Black }
        Text(
            text = disciple.realmName,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            maxLines = 1
        )
        Text(
            text = disciple.spiritRootName,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = spiritRootColor,
            maxLines = 1
        )
    }
}

/** 弟子卡信息区第三行：悟性/忠诚/自定义属性/附加属性 */
@Composable
private fun PortraitDiscipleAttrsRow(
    disciple: DiscipleAggregate,
    extraAttributes: List<Pair<String, Int>>,
    customAttributes: (@Composable () -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (customAttributes != null) {
            customAttributes()
        } else {
            DiscipleAttrText(name = "悟性", value = disciple.comprehension)
            DiscipleAttrText(name = "忠诚", value = disciple.loyalty)
        }
        extraAttributes.forEach { (name, value) ->
            DiscipleAttrText(name = name, value = value)
        }
    }
}

/** 效果键 → 中文显示名映射表（formatEffectKey 查表） */
