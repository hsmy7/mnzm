package com.xianxia.sect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.ui.theme.GameColors


@Composable
internal fun SlotContent(
    name: String,
    realmName: String,
    portraitRes: String,
    isAlive: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = name,
            fontSize = 9.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isAlive) {
            val isBeastPortrait = portraitRes.startsWith("beast_")
            val resId = remember(portraitRes) {
                val id = if (isBeastPortrait) {
                    val suffix = portraitRes.removePrefix("beast_")
                    val index = suffix.toIntOrNull() ?: -1
                    if (index in 0..7) beastSpriteRes(index) ?: 0
                    else if (index > 0) index
                    else 0
                } else PortraitPool.getResourceId(portraitRes)
                if (id != 0) id else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
            }
            // 40×48dp ≤ 80dp 质量边界，命中预载头像缓存；
            // beast_* 名不在预载缓存 → 自动回退 painterResource
            PortraitImage(
                name = portraitRes,
                resId = resId,
                modifier = Modifier.width(40.dp).height(48.dp)
            )
        } else {
            // 阵亡：仅覆盖精灵图区域，名称和境界保持显示
            Box(
                modifier = Modifier.width(40.dp).height(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "死亡",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.Error,
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = realmName,
            fontSize = 10.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== 统一弟子槽位 ====================

/**
 * 统一的弟子槽位组件。
 * 所有弟子槽位（生产、建筑、战斗等）共用此组件。
 *
 * 布局：境界 → 分割线 → 精灵图 → 分割线 → 名称
 * 分割线样式与 DiscipleDetailScreen 标签页一致。
 */
@Composable
fun DiscipleSlot(
    disciple: DiscipleAggregate?,
    modifier: Modifier = Modifier,
    borderColor: Color = GameColors.Border,
    showActions: Boolean = false,
    onSlotClick: () -> Unit = {},
    onEmptySlotClick: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    onSwap: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 槽位本体
        DiscipleSlotBody(
            disciple = disciple,
            borderColor = borderColor,
            onSlotClick = onSlotClick,
            onEmptySlotClick = onEmptySlotClick
        )

        // 操作按钮（可选）
        if (showActions && disciple != null) {
            DiscipleSlotActions(
                onDismiss = onDismiss,
                onSwap = onSwap
            )
        }
    }
}

/** 弟子槽位本体：边框 + 点击 + 填充/空槽 */
@Composable
internal fun DiscipleSlotBody(
    disciple: DiscipleAggregate?,
    borderColor: Color,
    onSlotClick: () -> Unit,
    onEmptySlotClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(88.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (disciple != null) Color.White else GameColors.PageBackground)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable {
                if (disciple != null) onSlotClick() else onEmptySlotClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (disciple != null) {
            DiscipleSlotFilledContent(disciple = disciple)
        } else {
            Text(
                text = "+",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}

/** 弟子槽位填充内容：境界/精灵图/名称三段 */
@Composable
internal fun DiscipleSlotFilledContent(disciple: DiscipleAggregate) {
    val dividerColor = Color(0xFF757575)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DiscipleSlotRealmSection(
            disciple = disciple,
            dividerColor = dividerColor
        )
        DiscipleSlotPortraitSection(
            disciple = disciple,
            dividerColor = dividerColor
        )
        DiscipleSlotNameSection(disciple = disciple)
    }
}

/** 弟子槽位境界区 */
@Composable
internal fun DiscipleSlotRealmSection(
    disciple: DiscipleAggregate,
    dividerColor: Color
) {
    // 境界（顶部）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = disciple.realmName,
            fontSize = 9.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    // 分割线
    HorizontalDivider(thickness = 1.dp, color = dividerColor)
}

/** 弟子槽位精灵图区 */
@Composable
internal fun ColumnScope.DiscipleSlotPortraitSection(
    disciple: DiscipleAggregate,
    dividerColor: Color
) {
    // 精灵图（中部）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (disciple.isAlive) {
            val portraitRes = disciple.portraitRes
            val isBeastPortrait = portraitRes.startsWith("beast_")
            val resId = remember(portraitRes) {
                val id = if (isBeastPortrait) {
                    val suffix = portraitRes.removePrefix("beast_")
                    val index = suffix.toIntOrNull() ?: -1
                    if (index in 0..7) beastSpriteRes(index) ?: 0
                    else if (index > 0) index
                    else 0
                } else PortraitPool.getResourceId(portraitRes)
                if (id != 0) id else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
            }
            // 40×48dp ≤ 80dp 质量边界，命中预载头像缓存；
            // beast_* 名不在预载缓存 → 自动回退 painterResource
            PortraitImage(
                name = portraitRes,
                resId = resId,
                modifier = Modifier.width(40.dp).height(48.dp)
            )
        } else {
            Text(
                text = "死亡",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.Error,
                maxLines = 1
            )
        }
    }
    // 分割线
    HorizontalDivider(thickness = 1.dp, color = dividerColor)
}

/** 弟子槽位名称区 */
@Composable
internal fun DiscipleSlotNameSection(disciple: DiscipleAggregate) {
    // 名称（底部）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = disciple.name,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 弟子槽位操作按钮：卸任/更换 */
@Composable
internal fun DiscipleSlotActions(
    onDismiss: (() -> Unit)?,
    onSwap: (() -> Unit)?
) {
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onDismiss != null) {
            Text(
                text = "卸任",
                fontSize = 9.sp,
                color = Color(0xFFE53935),
                modifier = Modifier.clickable { onDismiss() }
            )
        }
        if (onSwap != null) {
            Text(
                text = "更换",
                fontSize = 9.sp,
                color = Color.Black,
                modifier = Modifier.clickable { onSwap() }
            )
        }
    }
}


@Composable
internal fun BattleParticipantSlot(
    name: String,
    realmName: String,
    hp: Int,
    maxHp: Int,
    isAlive: Boolean,
    portraitRes: String = "",
    showHpBar: Boolean = true
) {
    val hpPercent = maxHp.takeIf { it > 0 }?.let {
        (hp.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    val hpColor = when {
        hpPercent > 0.6f -> GameColors.Success
        hpPercent > 0.3f -> GameColors.Warning
        else -> GameColors.Error
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showHpBar) {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GameColors.SurfaceLightGray)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(hpPercent)
                        .background(hpColor)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
        }

        Box(
            modifier = Modifier
                .width(52.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isAlive) Color.White else Color(0xFFEEEEEE))
                .border(
                    1.dp, if (isAlive) GameColors.SurfaceLightGray else GameColors.DividerGray, RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            SlotContent(
                name = name,
                realmName = realmName,
                portraitRes = portraitRes,
                isAlive = isAlive
            )
        }
    }
}
