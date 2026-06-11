package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.config.HeavenlyTrialConfig
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun HeavenlyTrialPanel(viewModel: HeavenlyTrialViewModel) {
    val trialState by viewModel.trialState.collectAsStateWithLifecycle()

    // 8 座岛屿图资源
    val islandDrawables = listOf(
        R.drawable.heavenly_trial_island_1,
        R.drawable.heavenly_trial_island_2,
        R.drawable.heavenly_trial_island_3,
        R.drawable.heavenly_trial_island_4,
        R.drawable.heavenly_trial_island_5,
        R.drawable.heavenly_trial_island_6,
        R.drawable.heavenly_trial_island_7,
        R.drawable.heavenly_trial_island_8
    )

    // 岛屿在图中的相对位置（中心点 X 比例，从左到右）
    val islandXFractions = listOf(0.09f, 0.23f, 0.37f, 0.50f, 0.63f, 0.76f, 0.87f, 0.96f)
    // Y 位置带高低差
    val islandYFractions = listOf(0.42f, 0.28f, 0.50f, 0.22f, 0.48f, 0.30f, 0.44f, 0.26f)

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        // 岛屿图尺寸（按容器宽度的 1/8 缩放）
        val islandSize = containerWidth * 0.14f

        // 与挑战界面共用背景图
        Image(
            painter = painterResource(R.drawable.heavenly_trial_challenge_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        // 放置 8 座岛屿 + 关卡按钮
        for (i in 0 until HeavenlyTrialConfig.levelCount) {
            val config = HeavenlyTrialConfig.getLevel(i) ?: continue
            val unlocked = i == 0 || trialState.highestClearedLevel >= i - 1
            val cleared = trialState.highestClearedLevel >= i

            val centerX = containerWidth * islandXFractions[i]
            val centerY = containerHeight * islandYFractions[i]

            // 岛屿图
            Image(
                painter = painterResource(islandDrawables[i]),
                contentDescription = config.label,
                modifier = Modifier
                    .offset(
                        x = centerX - islandSize / 2,
                        y = centerY - islandSize / 2
                    )
                    .size(islandSize),
                contentScale = ContentScale.Fit
            )

            // 文本按钮（岛屿中心偏下）
            val btnBgColor = when {
                cleared -> Color.Red
                unlocked -> Color.Red
                else -> Color.Gray
            }

            Box(
                modifier = Modifier
                    .offset(
                        x = centerX - ButtonSizes.StandardWidth / 2,
                        y = centerY + islandSize * 0.1f
                    )
                    .clip(RoundedCornerShape(4.dp))
                    .background(btnBgColor)
                    .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                    .clickable(enabled = unlocked) {
                        viewModel.enterBattlePrep(i)
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = config.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}
