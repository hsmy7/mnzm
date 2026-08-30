package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.xianxia.sect.core.model.MapCoordinateSystem
import com.xianxia.sect.core.touch.CustomVelocityTracker
import com.xianxia.sect.core.touch.FlingPhysics
import com.xianxia.sect.ui.game.map.markers.LevelMarker
import com.xianxia.sect.ui.game.map.markers.SecretRealmMarker
import com.xianxia.sect.ui.game.map.markers.SectMarker
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import com.xianxia.sect.ui.game.map.world.rememberWorldCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@Composable
fun WorldMapScreen(
    items: List<MapItem>,
    cameraState: WorldCameraState = rememberWorldCamera(
        worldWidth = MapCoordinateSystem.WORLD_WIDTH,
        worldHeight = MapCoordinateSystem.WORLD_HEIGHT
    ),
    focusWorld: Offset? = null,
    onBack: () -> Unit = {},
    onItemClick: (MapItem) -> Unit = {},
    onUserInteraction: () -> Unit = {}
) {
    LaunchedEffect(focusWorld, cameraState.viewportWidth, cameraState.viewportHeight) {
        if (focusWorld != null) {
            cameraState.tryCenterOn(focusWorld.x, focusWorld.y)
        }
    }

    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                cameraState.updateViewport(size.width, size.height)
                val autoScale = maxOf(
                    size.width.toFloat() / cameraState.worldWidth,
                    size.height.toFloat() / cameraState.worldHeight
                )
                cameraState.updateScale(autoScale)
            }
            .pointerInput(cameraState) {
                worldMapPanAndFling(
                    cameraState = cameraState,
                    scope = scope,
                    onUserInteraction = onUserInteraction
                )
            }
    ) {
        // Layer 1: 地图背景
        MapBackground(
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: 标记（宗门 + 关卡）
        items.forEach { item ->
            if (!cameraState.isVisible(item.worldX, item.worldY)) return@forEach

            when (item) {
                is MapItem.Sect -> SectMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onItemClick(item) }
                )

                is MapItem.Level -> LevelMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onItemClick(item) }
                )

                is MapItem.SecretRealm -> SecretRealmMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onItemClick(item) }
                )
            }
        }

        // Layer 4: UI 控件
        MapControls(
            onBack = onBack
        )
    }
}

/**
 * 世界地图拖拽平移 + 惯性滑行（WorldMapScreen 拆分）：
 * - detectDragGestures 内置 touchSlop（tap 不吞、标记点击不受影响），
 *   onDrag 期间采样速度，松手后按 FlingPhysics 60fps 节拍惯性滚动——
 *   对齐宗门地图手势引擎手感（原实现无惯性，拖动视角松手即停、手感生硬）
 * - 惯性协程随手势生命周期取消（pointerInput 作用域）；取消时 delay 抛
 *   CancellationException 由协程机制传播，无需 try/catch（detekt 禁止同型重抛）
 */
private suspend fun PointerInputScope.worldMapPanAndFling(
    cameraState: WorldCameraState,
    scope: CoroutineScope,
    onUserInteraction: () -> Unit
) {
    val velocityTracker = CustomVelocityTracker()
    var flingJob: Job? = null
    detectDragGestures(
        onDragStart = {
            velocityTracker.clear()
            flingJob?.cancel()
        },
        onDragEnd = {
            val vel = velocityTracker.computeVelocity()
            val speed = sqrt(vel.x * vel.x + vel.y * vel.y)
            if (speed >= FlingPhysics.MIN_FLING_VELOCITY) {
                flingJob = scope.launch {
                    val physics = FlingPhysics()
                    physics.start(vel.x, vel.y)
                    // 固定 60fps 节拍 + 固定 dt（与宗门地图手势引擎同契约，
                    // 惯性物理确定性可测、与事件率解耦）
                    while (isActive && physics.isActive) {
                        val delta = physics.update(FLING_TICK_SEC)
                        if (delta.dx != 0f || delta.dy != 0f) {
                            cameraState.pan(delta.dx, delta.dy)
                            onUserInteraction()
                        }
                        delay(FLING_TICK_MS)
                    }
                }
            }
        },
        onDragCancel = {
            flingJob?.cancel()
            flingJob = null
        },
        onDrag = { change, dragAmount ->
            change.consume()
            velocityTracker.addPosition(
                change.position.x, change.position.y, change.uptimeMillis * 1_000_000L
            )
            cameraState.pan(dragAmount.x, dragAmount.y)
            onUserInteraction()
        }
    )
}

/** fling 固定节拍（ms/s）— 60fps，与宗门地图手势引擎同契约 */
private const val FLING_TICK_MS = 16L
private const val FLING_TICK_SEC = 0.016f
