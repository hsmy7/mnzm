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
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.BattleTeam
import com.xianxia.sect.core.model.AIBattleTeam
import com.xianxia.sect.core.model.WorldSect
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
    caves: List<CultivatorCave> = emptyList(),
    caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),
    battleTeam: BattleTeam? = null,
    aiBattleTeams: List<AIBattleTeam> = emptyList(),
    worldSects: List<WorldSect> = emptyList(),
    playerSectX: Float = 2000f,
    playerSectY: Float = 1750f,
    movableTargetSectIds: List<String> = emptyList(),
    onBack: () -> Unit,
    onMarkerClick: (MapMarker) -> Unit = {},
    onCaveClick: (CultivatorCave) -> Unit = {},
    onCreateTeamClick: () -> Unit,
    onManageTeamClick: () -> Unit,
    onBattleTeamMarkerClick: (BattleTeam) -> Unit = {},
    onMovableTargetClick: (String) -> Unit = {},
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
                    val isMovableTarget = movableTargetSectIds.contains(marker.id)
                    MapMarkerItem(
                        marker = marker,
                        mapWidthPx = mapWidthPx.toFloat(),
                        mapHeightPx = mapHeightPx.toFloat(),
                        isHighlighted = isMovableTarget,
                        onClick = { 
                            if (isMovableTarget) {
                                onMovableTargetClick(marker.id)
                            } else {
                                onMarkerClick(marker)
                            }
                        }
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
            
            // 显示洞府探索队伍路径和标记
            caveExplorationTeams.filter { it.isMoving }.forEach { team ->
                val cave = caves.find { it.id == team.caveId }
                if (cave != null) {
                    CaveExplorationPath(
                        startX = team.startX,
                        startY = team.startY,
                        targetX = team.targetX,
                        targetY = team.targetY,
                        mapWidthPx = mapWidthPx.toFloat(),
                        mapHeightPx = mapHeightPx.toFloat()
                    )
                    CaveExplorationTeamMarker(
                        team = team,
                        mapWidthPx = mapWidthPx.toFloat(),
                        mapHeightPx = mapHeightPx.toFloat()
                    )
                }
            }
            
            // 显示洞府标记
            caves.filter { it.status != CaveStatus.EXPIRED && it.status != CaveStatus.EXPLORED }.forEach { cave ->
                CaveMarker(
                    cave = cave,
                    mapWidthPx = mapWidthPx.toFloat(),
                    mapHeightPx = mapHeightPx.toFloat(),
                    onClick = { onCaveClick(cave) }
                )
            }
            
            // 显示战斗队伍标记（在玩家宗门上方）
            if (battleTeam != null && battleTeam.isAtSect) {
                val playerSectMarker = markers.find { it.isCapital }
                if (playerSectMarker != null) {
                    BattleTeamMarker(
                        battleTeam = battleTeam,
                        sectX = playerSectMarker.x * mapWidthPx.toFloat(),
                        sectY = playerSectMarker.y * mapHeightPx.toFloat(),
                        onClick = { onBattleTeamMarkerClick(battleTeam) }
                    )
                }
            }
            
            // 显示移动中的战斗队伍
            if (battleTeam != null && battleTeam.status == "moving") {
                val currentX = battleTeam.currentX
                val currentY = battleTeam.currentY
                if (currentX > 0 && currentY > 0) {
                    Box(
                        modifier = Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    x = (currentX * mapWidthPx.toFloat()).toInt() - 20,
                                    y = (currentY * mapHeightPx.toFloat()).toInt() - 20
                                )
                            }
                    ) {
                        Text(
                            text = "⚔",
                            fontSize = 20.sp,
                            color = Color(0xFFFF0000),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            // 显示AI进攻队伍移动动画
            aiBattleTeams.filter { it.status == "moving" }.forEach { aiTeam ->
                val attackerSect = worldSects.find { it.id == aiTeam.attackerSectId }
                val teamColor = if (attackerSect != null) {
                    if (attackerSect.isRighteous) Color(0xFF4CAF50) else Color(0xFF9C27B0)
                } else {
                    Color(0xFFFF5722)
                }
                AIBattleTeamMarker(
                    aiTeam = aiTeam,
                    mapWidthPx = mapWidthPx.toFloat(),
                    mapHeightPx = mapHeightPx.toFloat(),
                    teamColor = teamColor
                )
            }
            
            // 显示战斗标记（被进攻的宗门）
            aiBattleTeams.filter { it.status == "battling" || it.status == "moving" }.forEach { aiTeam ->
                val defenderMarker = markers.find { it.id == aiTeam.defenderSectId }
                if (defenderMarker != null) {
                    BattleMarker(
                        sectX = defenderMarker.x * mapWidthPx.toFloat(),
                        sectY = defenderMarker.y * mapHeightPx.toFloat(),
                        isBattling = aiTeam.status == "battling"
                    )
                }
            }
            
            // 显示驻扎的支援队伍（在玩家宗门上方）
            supportTeams.filter { it.isStationed }.forEachIndexed { index, team ->
                val playerSectMarker = markers.find { it.isCapital }
                if (playerSectMarker != null) {
                    StationedSupportTeamMarker(
                        team = team,
                        sectX = playerSectMarker.x * mapWidthPx.toFloat(),
                        sectY = playerSectMarker.y * mapHeightPx.toFloat(),
                        offsetIndex = index
                    )
                }
            }
        }
        
        // 左下角按钮区域
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (battleTeam != null && battleTeam.isAtSect) Color(0xFFCCCCCC) else Color(0xFF4CAF50))
                    .border(1.dp, Color(0xFF6B5344), RoundedCornerShape(6.dp))
                    .clickable { if (battleTeam == null || !battleTeam.isAtSect) onCreateTeamClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (battleTeam != null && battleTeam.isAtSect) "已组建" else "组建队伍",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (battleTeam != null && battleTeam.isAtSect) Color(0xFF666666) else Color.White
                )
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (battleTeam != null) Color(0xFF4CAF50) else Color(0xFFCCCCCC))
                    .border(1.dp, Color(0xFF6B5344), RoundedCornerShape(6.dp))
                    .clickable { if (battleTeam != null) onManageTeamClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "管理队伍",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (battleTeam != null) Color.White else Color(0xFF666666)
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
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return
    
    val markerColor = if (marker.isCapital) Color(0xFFFF8C00) else Color(0xFFF5E6C8)
    val borderColor = if (isHighlighted) Color(0xFFFF0000) else Color(0xFF8B7355)
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
                .border(
                    width = if (isHighlighted) 3.dp else 2.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(6.dp)
                )
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
    
    val currentX = (team.currentX / 4000f) * mapWidthPx
    val currentY = (team.currentY / 3500f) * mapHeightPx
    
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

@Composable
private fun CaveMarker(
    cave: CultivatorCave,
    mapWidthPx: Float,
    mapHeightPx: Float,
    onClick: () -> Unit
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return

    val relativeX = cave.x / 4000f
    val relativeY = cave.y / 3500f
    
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
            .clickable { onClick() }
    ) {
        Text(
            text = cave.name,
            fontSize = 10.sp,
            color = Color.Red,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun CaveExplorationPath(
    startX: Float,
    startY: Float,
    targetX: Float,
    targetY: Float,
    mapWidthPx: Float,
    mapHeightPx: Float
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return

    val relativeStartX = startX / 4000f
    val relativeStartY = startY / 3500f
    val relativeTargetX = targetX / 4000f
    val relativeTargetY = targetY / 3500f

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val pathStartX = relativeStartX * mapWidthPx
        val pathStartY = relativeStartY * mapHeightPx
        val pathEndX = relativeTargetX * mapWidthPx
        val pathEndY = relativeTargetY * mapHeightPx

        val path = Path().apply {
            moveTo(pathStartX, pathStartY)
            lineTo(pathEndX, pathEndY)
        }

        drawPath(
            path = path,
            color = Color(0xFFFF9800).copy(alpha = 0.6f),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    intervals = floatArrayOf(8.dp.toPx(), 4.dp.toPx())
                )
            )
        )
    }
}

@Composable
private fun CaveExplorationTeamMarker(
    team: CaveExplorationTeam,
    mapWidthPx: Float,
    mapHeightPx: Float
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return

    val relativeX = team.currentX / 4000f
    val relativeY = team.currentY / 3500f
    
    val markerColor = Color(0xFFFF9800)
    val borderColor = Color(0xFFF57C00)
    
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
                text = "探索队",
                fontSize = 9.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BattleTeamMarker(
    battleTeam: BattleTeam,
    sectX: Float,
    sectY: Float,
    onClick: () -> Unit
) {
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
                    x = (sectX - boxWidth / 2).toInt(),
                    y = (sectY - boxHeight / 2 - 30).toInt()
                )
            }
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFE91E63))
                .border(2.dp, Color(0xFFC62828), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "战斗队伍",
                fontSize = 9.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StationedSupportTeamMarker(
    team: SupportTeam,
    sectX: Float,
    sectY: Float,
    offsetIndex: Int = 0
) {
    var boxWidth by remember { mutableIntStateOf(0) }
    var boxHeight by remember { mutableIntStateOf(0) }
    
    val yOffset = -30 - (offsetIndex * 25)
    
    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                boxWidth = coordinates.size.width
                boxHeight = coordinates.size.height
            }
            .offset { 
                androidx.compose.ui.unit.IntOffset(
                    x = (sectX - boxWidth / 2).toInt(),
                    y = (sectY - boxHeight / 2 + yOffset).toInt()
                )
            }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF4CAF50))
                .border(2.dp, Color(0xFF2E7D32), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = team.name,
                fontSize = 9.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AIBattleTeamMarker(
    aiTeam: AIBattleTeam,
    mapWidthPx: Float,
    mapHeightPx: Float,
    teamColor: Color
) {
    if (mapWidthPx <= 0f || mapHeightPx <= 0f) return

    val relativeX = aiTeam.currentX / 4000f
    val relativeY = aiTeam.currentY / 3500f
    
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
                .background(teamColor)
                .border(2.dp, teamColor.copy(red = (teamColor.red * 0.7f), green = (teamColor.green * 0.7f), blue = (teamColor.blue * 0.7f)), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚔${aiTeam.attackerSectName}",
                fontSize = 8.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BattleMarker(
    sectX: Float,
    sectY: Float,
    isBattling: Boolean
) {
    var boxWidth by remember { mutableIntStateOf(0) }
    var boxHeight by remember { mutableIntStateOf(0) }
    
    val yOffset = 25
    
    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                boxWidth = coordinates.size.width
                boxHeight = coordinates.size.height
            }
            .offset { 
                androidx.compose.ui.unit.IntOffset(
                    x = (sectX - boxWidth / 2).toInt(),
                    y = (sectY - boxHeight / 2 + yOffset).toInt()
                )
            }
    ) {
        Text(
            text = if (isBattling) "⚔战" else "⚠攻",
            fontSize = 12.sp,
            color = if (isBattling) Color(0xFFFF0000) else Color(0xFFFF9800),
            fontWeight = FontWeight.Bold
        )
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
