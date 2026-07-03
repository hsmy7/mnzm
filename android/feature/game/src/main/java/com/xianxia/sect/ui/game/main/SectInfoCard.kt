package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.sectIconRes

/**
 * 宗门信息卡片 — 显示宗门名、年月、弟子数、灵石、战力。
 */
@Composable
internal fun SectInfoCard(
    sectName: String,
    gameYear: Int,
    gameMonth: Int,
    gamePhase: Int,
    lowStones: Long,
    midStones: Long,
    highStones: Long,
    discipleCount: Int,
    combatPower: Long,
    sectLevel: Int = SectLevel.MEDIUM,
    showRewardBadge: Boolean = false,
    onSectIconClick: () -> Unit = {},
    onSectNameClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg_horizontal),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sectIconResId = sectIconRes(sectLevel)
                if (sectIconResId != null) {
                    Box(modifier = Modifier.size(28.dp)) {
                        Image(
                            painter = painterResource(id = sectIconResId),
                            contentDescription = "宗门等级",
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { onSectIconClick() }
                        )
                        // 奖励可领取红点
                        if (showRewardBadge) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-4).dp)
                                    .size(7.dp)
                                    .background(Color.Red, CircleShape)
                            )
                        }
                    }
                }
                Text(
                    text = sectName,
                    modifier = Modifier.clickable { onSectNameClick() },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(modifier = Modifier.size(width = 150.dp, height = 38.dp)) {
                    val powerResId = SpriteResRegistry
                        .resolve("combat_power_bg")
                    if (powerResId != null) {
                        Image(
                            painter = painterResource(id = powerResId),
                            contentDescription = "战斗力",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                    // 右侧78%居中，字号自适应（最大12sp）
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .fillMaxHeight()
                            .align(Alignment.CenterEnd)
                    ) {
                        val text = "$combatPower"
                        val textMeasurer = rememberTextMeasurer()
                        val density = LocalDensity.current
                        val fontSize = remember(text, maxWidth, density) {
                            val measured = textMeasurer.measure(
                                text = AnnotatedString(text),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                constraints = Constraints(
                                    maxWidth = Int.MAX_VALUE
                                )
                            )
                            val textWidthPx = measured.size.width.toFloat()
                            val availablePx = with(density) {
                                maxWidth.toPx()
                            }
                            val fits = textWidthPx <= availablePx
                            if (fits || availablePx <= 0f) {
                                12.sp
                            } else {
                                12.sp * (availablePx / textWidthPx)
                            }
                        }
                        Text(
                            text = text,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Normal,
                            color = Color.Red,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = 4.dp)
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val phaseName = com.xianxia.sect.core.model.GamePhase.fromValue(gamePhase).displayName
                Text(
                    text = "${gameYear}年${gameMonth}月$phaseName",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Text(
                    text = "弟子 $discipleCount",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                val lowText = GameUtils.formatNumber(lowStones)
                val midText = GameUtils.formatNumber(midStones)
                val highText = GameUtils.formatNumber(highStones)
                SpriteImage(
                    name = "spirit_stone_low",
                    contentDescription = "下品灵石",
                    modifier = Modifier.size(12.dp)
                )
                Text(text = lowText, fontSize = 12.sp, color = Color.Black)
                SpriteImage(
                    name = "spirit_stone_mid",
                    contentDescription = "中品灵石",
                    modifier = Modifier.size(12.dp)
                )
                Text(text = midText, fontSize = 12.sp, color = Color.Black)
                SpriteImage(
                    name = "spirit_stone_high",
                    contentDescription = "上品灵石",
                    modifier = Modifier.size(12.dp)
                )
                Text(text = highText, fontSize = 12.sp, color = Color.Black)
            }
        }
    }
}
