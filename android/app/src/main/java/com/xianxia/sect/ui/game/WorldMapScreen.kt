package com.xianxia.sect.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.MapMarker
import com.xianxia.sect.core.model.MapMarkerType
import com.xianxia.sect.core.model.MapPath
import com.xianxia.sect.core.model.SupportTeam
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.ExplorationStatus
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FunctionButton(
    val icon: String,
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun WorldMapScreen(
    markers: List<MapMarker>,
    paths: List<MapPath> = emptyList(),
    supportTeams: List<SupportTeam> = emptyList(),
    scoutTeams: List<ExplorationTeam> = emptyList(),
    onBack: () -> Unit,
    onMarkerClick: (MapMarker) -> Unit = {},
    onFunctionClick: (String) -> Unit = {},
    initialFocusMarkerId: String? = null
) {
    val density = LocalDensity.current
    val baseMapWidthPx = with(density) { 4000.dp.roundToPx() }
    val baseMapHeightPx = with(density) { 3500.dp.roundToPx() }
    
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var screenWidth by remember { mutableIntStateOf(0) }
    var screenHeight by remember { mutableIntStateOf(0) }
    var hasInitialized by remember { mutableStateOf(false) }

    val minScale = 0.5f
    val maxScale = 3f

    val mapWidthPx = (baseMapWidthPx * scale).toInt()
    val mapHeightPx = (baseMapHeightPx * scale).toInt()

    val maxOffsetX = (mapWidthPx - screenWidth).coerceAtLeast(0)
    val maxOffsetY = (mapHeightPx - screenHeight).coerceAtLeast(0)

    val backgroundColor = Color(0xFFA8B878)
    val pathColor = Color(0xFF8B7355)

    // 初始化定位到指定宗门
    LaunchedEffect(screenWidth, screenHeight, markers, initialFocusMarkerId) {
        if (!hasInitialized && screenWidth > 0 && screenHeight > 0 && markers.isNotEmpty()) {
            val targetMarker = if (initialFocusMarkerId != null) {
                markers.find { it.id == initialFocusMarkerId }
            } else {
                markers.find { it.isCapital }
            }

            if (targetMarker != null) {
                val markerX = targetMarker.x * baseMapWidthPx * scale
                val markerY = targetMarker.y * baseMapHeightPx * scale

                // 计算偏移量使宗门位于屏幕中心
                offsetX = (screenWidth / 2 - markerX).coerceIn(-maxOffsetX.toFloat(), 0f)
                offsetY = (screenHeight / 2 - markerY).coerceIn(-maxOffsetY.toFloat(), 0f)
            }

            hasInitialized = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onSizeChanged { size ->
                screenWidth = size.width
                screenHeight = size.height
            }
            .pointerInput(maxOffsetX, maxOffsetY, scale) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (maxOffsetX > 0 || maxOffsetY > 0) {
                            offsetX = (offsetX + dragAmount.x).coerceIn(-maxOffsetX.toFloat(), 0f)
                            offsetY = (offsetY + dragAmount.y).coerceIn(-maxOffsetY.toFloat(), 0f)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    
                    if (newScale != scale) {
                        val scaleFactor = newScale / scale
                        
                        val centroidX = centroid.x - offsetX
                        val centroidY = centroid.y - offsetY
                        
                        offsetX -= (centroidX * (scaleFactor - 1)).toFloat()
                        offsetY -= (centroidY * (scaleFactor - 1)).toFloat()
                        
                        scale = newScale
                        
                        val newMapWidthPx = (baseMapWidthPx * scale).toInt()
                        val newMapHeightPx = (baseMapHeightPx * scale).toInt()
                        val newMaxOffsetX = (newMapWidthPx - screenWidth).coerceAtLeast(0)
                        val newMaxOffsetY = (newMapHeightPx - screenHeight).coerceAtLeast(0)
                        
                        offsetX = offsetX.coerceIn(-newMaxOffsetX.toFloat(), 0f)
                        offsetY = offsetY.coerceIn(-newMaxOffsetY.toFloat(), 0f)
                    }
                    
                    if (pan != Offset.Zero) {
                        val newMapWidthPx = (baseMapWidthPx * scale).toInt()
                        val newMapHeightPx = (baseMapHeightPx * scale).toInt()
                        val newMaxOffsetX = (newMapWidthPx - screenWidth).coerceAtLeast(0)
                        val newMaxOffsetY = (newMapHeightPx - screenHeight).coerceAtLeast(0)
                        
                        offsetX = (offsetX + pan.x).coerceIn(-newMaxOffsetX.toFloat(), 0f)
                        offsetY = (offsetY + pan.y).coerceIn(-newMaxOffsetY.toFloat(), 0f)
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        x = offsetX.toInt(),
                        y = offsetY.toInt()
                    )
                }
                .width((4000 * scale).dp)
                .height((3500 * scale).dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = backgroundColor)

                if (markers.isEmpty() || paths.isEmpty()) return@Canvas

                val markerMap = markers.associateBy { it.id }
                
                val canvasWidth = mapWidthPx.toFloat()
                val canvasHeight = mapHeightPx.toFloat()
                
                paths.forEach { path ->
                    val fromMarker = markerMap[path.fromId] ?: return@forEach
                    val toMarker = markerMap[path.toId] ?: return@forEach
                    
                    val fromX = fromMarker.x * canvasWidth
                    val fromY = fromMarker.y * canvasHeight
                    val toX = toMarker.x * canvasWidth
                    val toY = toMarker.y * canvasHeight

                    val distance = sqrt((toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY))
                    if (distance < 1f) return@forEach

                    // 计算控制点，创建弯曲效果
                    val midX = (fromX + toX) / 2
                    val midY = (fromY + toY) / 2
                    
                    // 计算垂直于连线的方向，添加随机偏移
                    val dx = toX - fromX
                    val dy = toY - fromY
                    val normalX = -dy / distance
                    val normalY = dx / distance
                    
                    // 使用路径ID生成确定性随机数，保持同一路径的弯曲一致
                    val randomOffset = ((path.fromId.hashCode() + path.toId.hashCode()) % 100 - 50) / 100f
                    val curveStrength = distance * 0.2f * randomOffset
                    
                    val controlX = midX + normalX * curveStrength
                    val controlY = midY + normalY * curveStrength

                    val pathObj = Path().apply {
                        moveTo(fromX, fromY)
                        quadraticTo(controlX, controlY, toX, toY)
                    }

                    drawPath(
                        path = pathObj,
                        color = pathColor.copy(alpha = 0.9f),
                        style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            markers.forEach { marker ->
                if (marker.x in 0f..1f && marker.y in 0f..1f) {
                    MapMarkerItem(
                        marker = marker,
                        mapWidthPx = mapWidthPx.toFloat(),
                        mapHeightPx = mapHeightPx.toFloat(),
                        onClick = { onMarkerClick(marker) }
                    )
                }
            }
            
            // 显示支援队伍
            supportTeams.filter { it.isMoving }.forEach { team ->
                SupportTeamMarker(
                    team = team,
                    mapWidthPx = mapWidthPx.toFloat(),
                    mapHeightPx = mapHeightPx.toFloat()
                )
            }
            
            // 显示探查队伍
            scoutTeams.filter { it.status == ExplorationStatus.SCOUTING && it.moveProgress < 1f }.forEach { team ->
                ScoutTeamMarker(
                    team = team,
                    mapWidthPx = mapWidthPx.toFloat(),
                    mapHeightPx = mapHeightPx.toFloat()
                )
            }
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF8B7355))
                .border(2.dp, Color(0xFF6B5344), CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✕",
                fontSize = 18.sp,
                color = Color(0xFFF5E6C8),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MapMarkerItem(
    marker: MapMarker,
    mapWidthPx: Float,
    mapHeightPx: Float,
    onClick: () -> Unit
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return
    
    val markerColor = if (marker.isCapital) Color(0xFFFF8C00) else Color(0xFFF5E6C8)
    val borderColor = Color(0xFF8B7355)
    val textColor = if (marker.isCapital) Color(0xFFFFD700) else Color(0xFF3D2914)
    
    val x = marker.x * mapWidthPx
    val y = marker.y * mapHeightPx
    
    val fontSize = if (marker.isCapital) 12.sp else 10.sp
    
    var boxWidth by remember { mutableIntStateOf(0) }
    var boxHeight by remember { mutableIntStateOf(0) }
    
    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                boxWidth = coordinates.size.width
                boxHeight = coordinates.size.height
            }
            .offset { 
                androidx.compose.ui.unit.IntOffset(
                    x = (x - boxWidth / 2).toInt(),
                    y = (y - boxHeight / 2).toInt()
                )
            }
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(markerColor)
                .border(2.dp, borderColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = marker.name,
                fontSize = fontSize,
                color = textColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SupportTeamMarker(
    team: SupportTeam,
    mapWidthPx: Float,
    mapHeightPx: Float
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return

    val density = LocalDensity.current
    
    val progress = team.progress
    
    val startX = (team.currentX / 4000f) * mapWidthPx
    val startY = (team.currentY / 3500f) * mapHeightPx
    val endX = (team.targetX / 4000f) * mapWidthPx
    val endY = (team.targetY / 3500f) * mapHeightPx
    
    val currentX = startX + (endX - startX) * progress
    val currentY = startY + (endY - startY) * progress
    
    val markerColor = Color(0xFFF5E6C8)
    val borderColor = Color(0xFF8B7355)
    val textColor = Color(0xFF3D2914)
    
    var boxWidth by remember { mutableIntStateOf(0) }
    var boxHeight by remember { mutableIntStateOf(0) }
    
    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                boxWidth = coordinates.size.width
                boxHeight = coordinates.size.height
            }
            .offset { 
                androidx.compose.ui.unit.IntOffset(
                    x = (currentX - boxWidth / 2).toInt(),
                    y = (currentY - boxHeight / 2).toInt()
                )
            }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(markerColor)
                .border(2.dp, borderColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = team.name,
                fontSize = 9.sp,
                color = textColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ScoutTeamMarker(
    team: ExplorationTeam,
    mapWidthPx: Float,
    mapHeightPx: Float
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return

    val relativeX = team.currentX / 4000f
    val relativeY = team.currentY / 3500f
    
    val markerColor = Color.White
    val borderColor = Color(0xFF2196F3)
    val textColor = Color(0xFF2196F3)
    
    var boxWidth by remember { mutableIntStateOf(0) }
    var boxHeight by remember { mutableIntStateOf(0) }
    
    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                boxWidth = coordinates.size.width
                boxHeight = coordinates.size.height
            }
            .offset { 
                androidx.compose.ui.unit.IntOffset(
                    x = (relativeX * mapWidthPx - boxWidth / 2).toInt(),
                    y = (relativeY * mapHeightPx - boxHeight / 2).toInt()
                )
            }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(markerColor)
                .border(2.dp, borderColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = team.name,
                fontSize = 9.sp,
                color = textColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

fun getSampleWorldMap(): List<MapMarker> {
    return listOf(
        MapMarker("1", "巨鹿", MapMarkerType.CITY, 0.5f, 0.05f),
        MapMarker("2", "渤海", MapMarkerType.CITY, 0.7f, 0.08f, isCapital = true),
        MapMarker("3", "北海", MapMarkerType.CITY, 0.75f, 0.12f, isCapital = true),
        MapMarker("4", "河间", MapMarkerType.CITY, 0.65f, 0.15f),
        MapMarker("5", "邺", MapMarkerType.CITY, 0.4f, 0.2f),
        MapMarker("6", "上党", MapMarkerType.CITY, 0.3f, 0.25f),
        MapMarker("7", "陈留", MapMarkerType.CITY, 0.5f, 0.35f),
        MapMarker("8", "濮阳", MapMarkerType.CITY, 0.6f, 0.55f, isCapital = true),
        MapMarker("9", "许昌", MapMarkerType.CITY, 0.35f, 0.65f),
        MapMarker("10", "南阳", MapMarkerType.CITY, 0.25f, 0.85f),
        MapMarker("11", "琅琊", MapMarkerType.CITY, 0.85f, 0.35f),
        MapMarker("12", "下邳", MapMarkerType.CITY, 0.8f, 0.6f),
        MapMarker("13", "广陵", MapMarkerType.CITY, 0.9f, 0.85f),
        MapMarker("14", "剑南", MapMarkerType.CITY, 0.7f, 0.25f)
    )
}

fun getSamplePaths(): List<MapPath> {
    return listOf(
        MapPath("1", "4"),
        MapPath("4", "2"),
        MapPath("2", "3"),
        MapPath("4", "5"),
        MapPath("5", "6"),
        MapPath("5", "7"),
        MapPath("7", "8"),
        MapPath("8", "11"),
        MapPath("11", "12"),
        MapPath("12", "13"),
        MapPath("7", "9"),
        MapPath("9", "10"),
        MapPath("14", "2"),
        MapPath("14", "11")
    )
}
