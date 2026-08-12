package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.clickableWithSound
import kotlin.math.roundToInt

// 一键拆除-区域选择模式常量（MainGameScreen 与 buildingsInSquare 共用）
internal const val AREA_MIN_DIAMETER = 3
internal const val AREA_MAX_DIAMETER = 20
internal const val AREA_DEFAULT_DIAMETER = 3

/** 进度条轨道颜色（白色） */
private val AREA_SLIDER_TRACK_COLOR = Color.White

/** 进度条滑块圆点颜色（青色） */
private val AREA_SLIDER_THUMB_COLOR = Color(0xFF00BCD4)

/** 轨道高度 */
private val AREA_SLIDER_TRACK_HEIGHT = 5.dp

/** 滑块圆点直径（中心钳制在轨道两端内——圆点永不超出进度条） */
private val AREA_SLIDER_THUMB_DIAMETER = 14.dp

/** 进度条总宽度 */
private val AREA_SLIDER_WIDTH = 160.dp

/** 区域选择按钮素材显示尺寸（方形） */
private val AREA_BUTTON_SIZE = 48.dp

/**
 * 区域选择按钮：素材图（水滴形按钮），文本叠加在素材内部正下方（未激活"区域"/激活"关闭"）。
 *
 * 点击切换区域选中模式（激活状态由调用方维护，本组件无状态）。
 * 素材为透明底水滴形——文本叠在素材底部圆弧内居中，不超出按钮轮廓。
 */
@Composable
internal fun AreaSelectButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.clickableWithSound(onClick = onClick),
        contentAlignment = Alignment.BottomCenter
    ) {
        SpriteImage(
            name = "area_select_button",
            contentDescription = if (isActive) "关闭区域选择" else "区域选择",
            modifier = Modifier.size(AREA_BUTTON_SIZE)
        )
        Text(
            text = if (isActive) "关闭" else "区域",
            fontSize = 11.sp,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}

/**
 * 区域直径调整进度条：数字行（左最小值 / 上方当前值 / 右最大值）+ 白色圆角轨道 + 青色圆点滑块。
 *
 * 滑块圆点中心钳制在 [thumbRadius, width - thumbRadius] 区间——圆点永不超出进度条；
 * 按下/拖动实时映射直径（整数档位，[AREA_MIN_DIAMETER]..[AREA_MAX_DIAMETER]）。
 */
@Composable
internal fun AreaDiameterSlider(
    diameter: Int,
    onDiameterChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.width(AREA_SLIDER_WIDTH)) {
        AreaSliderLabels(diameter = diameter)
        AreaSliderTrack(
            diameter = diameter,
            onDiameterChange = onDiameterChange
        )
    }
}

/** 数字行：左最小值 / 上方当前值 / 右最大值 */
@Composable
private fun AreaSliderLabels(diameter: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
    ) {
        Text(
            text = AREA_MIN_DIAMETER.toString(),
            fontSize = 10.sp,
            color = Color.Black,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = diameter.toString(),
            fontSize = 12.sp,
            color = Color.Black,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Text(
            text = AREA_MAX_DIAMETER.toString(),
            fontSize = 10.sp,
            color = Color.Black,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/** 轨道 + 滑块（可滑动区域高度 24dp 便于手指操作；拖动用 pointerInput 自绘手势） */
@Composable
private fun AreaSliderTrack(
    diameter: Int,
    onDiameterChange: (Int) -> Unit
) {
    val currentOnDiameterChange by rememberUpdatedState(onDiameterChange)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                val thumbRadiusPx = AREA_SLIDER_THUMB_DIAMETER.toPx() / 2f
                awaitEachGesture {
                    awaitFirstDown()
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val x = event.changes.first().position.x
                        val fraction = if (size.width <= thumbRadiusPx * 2f) 0f
                            else ((x - thumbRadiusPx) / (size.width - thumbRadiusPx * 2f))
                                .coerceIn(0f, 1f)
                        currentOnDiameterChange(
                            (AREA_MIN_DIAMETER + fraction * (AREA_MAX_DIAMETER - AREA_MIN_DIAMETER))
                                .roundToInt()
                        )
                        pressed = event.changes.any { it.pressed }
                    }
                }
            }
    ) {
        // 白色圆角轨道（绘制时垂直居中——与圆点同轴线）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeight = AREA_SLIDER_TRACK_HEIGHT.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            drawRoundRect(
                color = AREA_SLIDER_TRACK_COLOR,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f)
            )
        }
        // 青色圆点滑块（中心 = thumbRadius + fraction * (width - 2 * thumbRadius)，
        // 两端时圆点边缘恰与轨道端点对齐——不超出进度条）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thumbRadius = AREA_SLIDER_THUMB_DIAMETER.toPx() / 2f
            val fraction = (diameter - AREA_MIN_DIAMETER).toFloat() /
                (AREA_MAX_DIAMETER - AREA_MIN_DIAMETER)
            val cx = thumbRadius + fraction * (size.width - thumbRadius * 2f)
            drawCircle(
                color = AREA_SLIDER_THUMB_COLOR,
                radius = thumbRadius,
                center = Offset(cx, size.height / 2f)
            )
        }
    }
}
