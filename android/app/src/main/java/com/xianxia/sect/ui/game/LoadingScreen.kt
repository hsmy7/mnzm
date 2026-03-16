package com.xianxia.sect.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.R
import com.xianxia.sect.ui.theme.GameColors

/**
 * 全屏加载界面组件
 * 
 * @param progress 加载进度 (0.0 - 1.0)
 * @param showProgress 是否显示进度条和百分比
 */
@Composable
fun LoadingScreen(
    progress: Float = 0f,
    showProgress: Boolean = false
) {
    LoadingScreenContent(
        progress = progress,
        showProgress = showProgress
    )
}

/**
 * 全屏加载对话框（用于需要对话框语义的场景）
 * 
 * @param progress 加载进度 (0.0 - 1.0)
 * @param showProgress 是否显示进度条和百分比
 * @param onDismiss 对话框关闭回调（目前未使用，保留用于未来扩展）
 */
@Composable
fun LoadingDialog(
    progress: Float = 0f,
    showProgress: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        LoadingScreenContent(
            progress = progress,
            showProgress = showProgress
        )
    }
}

/**
 * 加载界面通用内容组件
 * 
 * @param progress 加载进度 (0.0 - 1.0)
 * @param showProgress 是否显示进度条和百分比
 */
@Composable
private fun LoadingScreenContent(
    progress: Float,
    showProgress: Boolean
) {
    // 平滑进度动画
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        ),
        label = "progressAnimation"
    )
    
    // 进度百分比文本
    val progressPercent = (animatedProgress * 100).toInt()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
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
                // 百分比文本（移到进度条上方）
                Text(
                    text = "$progressPercent%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD700)
                )

                // 自定义金色进度条
                CustomGoldenProgressBar(
                    progress = animatedProgress,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(14.dp)
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
            val barHeight = canvasHeight * 0.5f
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
