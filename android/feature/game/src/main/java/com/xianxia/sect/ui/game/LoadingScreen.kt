package com.xianxia.sect.ui.game

import com.xianxia.sect.ui.components.rememberChasingProgress
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.feature.game.R

/**
 * 全屏加载界面组件
 *
 * @param progress 加载进度 (0.0 - 1.0)
 * @param showProgress 是否显示进度条和百分比
 * @param phaseText 当前加载阶段标签
 */
@Composable
fun LoadingScreen(
    progress: Float = 0f,
    showProgress: Boolean = false,
    phaseText: String = ""
) {
    LoadingScreenContent(
        progress = progress,
        showProgress = showProgress,
        phaseText = phaseText
    )
}

/**
 * 全屏加载对话框（用于需要对话框语义的场景）
 *
 * @param progress 加载进度 (0.0 - 1.0)
 * @param showProgress 是否显示进度条和百分比
 * @param phaseText 当前加载阶段标签
 * @param onDismiss 对话框关闭回调
 */
@Composable
private fun LoadingScreenContent(
    progress: Float,
    showProgress: Boolean,
    phaseText: String
) {
    // 加载进度动画 — 100ms lerp 追赶
    val animatedProgress by rememberChasingProgress(target = progress)

    // 进度百分比文本
    val progressPercent = (animatedProgress * 100).toInt()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics {
                contentDescription = "加载中 $progressPercent%"
            },
        contentAlignment = Alignment.Center
    ) {
        // 背景图片
        Image(
            painter = painterResource(id = R.drawable.loading_background),
            contentDescription = "加载界面背景",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 底部进度条和百分比
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 60.dp, start = 32.dp, end = 32.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showProgress) {
                // 阶段标签文本
                if (phaseText.isNotEmpty()) {
                    Text(
                        text = phaseText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 自定义金色进度条（百分比嵌入内部居中）
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CustomGoldenProgressBar(
                        progress = animatedProgress,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        text = "$progressPercent%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // 游戏玩法提示（每2秒轮换）
                var currentTip by remember { mutableStateOf(LoadingTips.randomTip()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(2000)
                        currentTip = LoadingTips.randomTip()
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = currentTip,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 自定义金色进度条组件（带镂空边框和半菱形装饰）
 * 
 * @param progress 进度值 (0.0 - 1.0)
 * @param modifier 修饰符
 * @param borderColor 边框颜色
 * @param progressColor 进度颜色
 */
@Composable
private fun CustomGoldenProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    borderColor: Color = Color(0xFFFFD700),
    progressColor: Color = Color(0xFFFFE55F)
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val barHeight = canvasHeight * 0.7f
            val diamondWidth = canvasHeight * 0.6f
            val borderWidth = 2.dp.toPx()
            
            val barStartX = diamondWidth
            val barEndX = canvasWidth - diamondWidth
            val barWidth = barEndX - barStartX
            val barTop = (canvasHeight - barHeight) / 2
            val barBottom = barTop + barHeight
            
            val cornerRadius = CornerRadius(4.dp.toPx())
            
            // 1. 绘制进度条背景槽
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.4f),
                topLeft = Offset(barStartX, barTop),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius
            )
            
            // 2. 绘制金色进度
            val progressWidth = barWidth * progress
            if (progressWidth > 0) {
                val gradient = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFFFD700),
                        Color(0xFFFFE55F),
                        Color(0xFFFFD700)
                    ),
                    startX = barStartX,
                    endX = barStartX + progressWidth
                )
                
                drawRoundRect(
                    brush = gradient,
                    topLeft = Offset(barStartX, barTop),
                    size = Size(progressWidth, barHeight),
                    cornerRadius = cornerRadius
                )
                
                // 进度条高亮效果
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.3f),
                    topLeft = Offset(barStartX, barTop),
                    size = Size(progressWidth, barHeight * 0.3f),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
            
            // 3. 绘制整个进度条的镂空边框（包括中间矩形和两侧半菱形）
            val borderPath = Path().apply {
                // 左侧半菱形（向左的三角形）
                moveTo(barStartX, barTop - borderWidth)
                lineTo(barStartX - diamondWidth, canvasHeight / 2)
                lineTo(barStartX, barBottom + borderWidth)
                
                // 底边
                lineTo(barEndX, barBottom + borderWidth)
                
                // 右侧半菱形（向右的三角形）
                lineTo(barEndX + diamondWidth, canvasHeight / 2)
                lineTo(barEndX, barTop - borderWidth)
                
                // 顶边
                close()
            }
            
            drawPath(
                path = borderPath,
                color = borderColor,
                style = Stroke(width = borderWidth * 1.5f)
            )
        }
    }
}
