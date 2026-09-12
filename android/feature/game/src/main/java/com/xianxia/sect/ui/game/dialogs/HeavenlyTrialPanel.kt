package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.core.config.HeavenlyTrialConfig
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.theme.ButtonSizes
import androidx.compose.foundation.shape.CircleShape
import com.xianxia.sect.ui.components.clickableWithSound
import kotlinx.coroutines.launch

@Composable
fun HeavenlyTrialPanel(
    viewModel: HeavenlyTrialViewModel,
    onOpenClearRewards: () -> Unit = {},
    /** 是否绘制自带背景图（半屏嵌入时由宿主对话框提供背景，传 false 避免叠加） */
    showBackground: Boolean = true
) {
    val trialState by viewModel.trialState.collectAsStateWithLifecycle()
    val hasClaimable by viewModel.hasClaimableRewards.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        HeavenlyTrialIslandMap(
            showBackground = showBackground,
            layout = heavenlyTrialIslandLayout(),
            trialState = trialState,
            hasClaimable = hasClaimable,
            onLevelClick = { levelIndex ->
                // 延迟一帧切换挑战分支：指针事件处理中组合结构剧变
                // （半屏面板→全屏挑战）会中断当前 pointer input 丢失点击
                scope.launch { viewModel.enterBattlePrep(levelIndex) }
            },
            onOpenClearRewards = onOpenClearRewards
        )
    }
}

/** 天劫试炼面板布局数据：岛屿图资源名 + 相对坐标 */
private data class HeavenlyTrialIslandLayout(
    val islandNames: List<String>,
    val islandXFractions: List<Float>,
    val islandYFractions: List<Float>
)

/** 天劫试炼 8 岛布局：8 座岛屿图资源 + 高低差坐标 */
private fun heavenlyTrialIslandLayout(): HeavenlyTrialIslandLayout = HeavenlyTrialIslandLayout(
    islandNames = (1..8).map { "heavenly_trial_island_$it" },
    islandXFractions = listOf(0.09f, 0.23f, 0.37f, 0.50f, 0.63f, 0.76f, 0.87f, 0.96f),
    islandYFractions = listOf(0.42f, 0.28f, 0.50f, 0.22f, 0.48f, 0.30f, 0.44f, 0.26f)
)

/** 天劫试炼面板内容：背景 + 岛屿关卡 + 通关奖励入口 */
@Composable
private fun BoxWithConstraintsScope.HeavenlyTrialIslandMap(
    showBackground: Boolean,
    layout: HeavenlyTrialIslandLayout,
    trialState: HeavenlyTrialSaveData,
    hasClaimable: Boolean,
    onLevelClick: (Int) -> Unit,
    onOpenClearRewards: () -> Unit
) {
    // 与挑战界面共用背景图（宿主已提供背景时可关闭避免叠加）
    if (showBackground) {
        SpriteImage(
            name = "heavenly_trial_challenge_bg",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
    }

    HeavenlyTrialIslands(
        layout = layout,
        trialState = trialState,
        onLevelClick = onLevelClick
    )

    HeavenlyTrialClearRewardsButton(
        hasClaimable = hasClaimable,
        onOpenClearRewards = onOpenClearRewards
    )
}

/** 天劫试炼岛屿关卡：8 岛精灵图 + 关卡文本按钮 */
@Composable
private fun BoxWithConstraintsScope.HeavenlyTrialIslands(
    layout: HeavenlyTrialIslandLayout,
    trialState: HeavenlyTrialSaveData,
    onLevelClick: (Int) -> Unit
) {
    val containerWidth = maxWidth
    val containerHeight = maxHeight
    // 岛屿图尺寸（按容器宽度的 1/8 缩放）
    val islandSize = containerWidth * 0.14f

    // 放置 8 座岛屿 + 关卡按钮
    for (i in 0 until HeavenlyTrialConfig.levelCount) {
        val config = HeavenlyTrialConfig.getLevel(i) ?: continue
        val unlocked = i == 0 || trialState.isLevelFullyCleared(i - 1)
        val cleared = trialState.isLevelFullyCleared(i)

        val centerX = containerWidth * layout.islandXFractions[i]
        val centerY = containerHeight * layout.islandYFractions[i]

        // 岛屿图
        SpriteImage(
            name = layout.islandNames[i],
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
                .clickableWithSound(enabled = unlocked) {
                    onLevelClick(i)
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

/** 右下角"通关奖励"按钮：未领取红点角标 */
@Composable
private fun BoxScope.HeavenlyTrialClearRewardsButton(
    hasClaimable: Boolean,
    onOpenClearRewards: () -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 16.dp)
    ) {
        GameButton("通关奖励", onClick = onOpenClearRewards)
        if (hasClaimable) {
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
