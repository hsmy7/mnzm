package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.MapCoordinateSystem
import kotlin.math.roundToInt
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapStyle
import com.xianxia.sect.ui.game.map.MapCameraState

enum class TeamAction { VIEW, MOVE, ATTACK, DISBAND }

data class TeamBadgeInfo(
    val teamId: String,
    val teamName: String,
    val isExpanded: Boolean
)

@Composable
fun SectMarker(
    item: MapItem.Sect,
    cameraState: MapCameraState,
    teamBadges: List<TeamBadgeInfo> = emptyList(),
    onClick: () -> Unit,
    onTeamBadgeClick: (String) -> Unit = {},
    onTeamAction: (String, TeamAction) -> Unit = { _, _ -> }
) {
    val markerColor = if (item.isPlayerSect) MapStyle.Colors.sectPlayer else MapStyle.Colors.sectNormal
    val borderColor = if (item.isHighlighted) MapStyle.Colors.sectHighlighted else MapStyle.Colors.sectBorderNormal
    val textColor = if (item.isPlayerSect) MapStyle.Colors.sectTextPlayer else MapStyle.Colors.sectTextNormal
    val fontSize = if (item.isPlayerSect) MapStyle.Typography.sectNamePlayer else MapStyle.Typography.sectNameNormal
    val borderWidth = if (item.isHighlighted) MapStyle.Dimensions.sectHighlightedBorderWidth else MapStyle.Dimensions.sectBorderWidth

    val (nx, ny) = MapCoordinateSystem.worldToNormalized(item.worldX, item.worldY)
    val x = nx * cameraState.canvasWidth
    val y = ny * cameraState.canvasHeight

    val hasActionButtons = teamBadges.any { it.isExpanded }
    val expandedTeam = teamBadges.find { it.isExpanded }
    val badgeHeight = 20.dp
    val badgeSpacing = 2.dp
    val actionButtonWidth = 40.dp

    // Main container placed at sect position
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        (x - placeable.width / 2f).roundToInt(),
                        (y - placeable.height / 2f).roundToInt()
                    )
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Team badges stacked above sect name
            if (teamBadges.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(badgeSpacing)
                ) {
                    // Render badges bottom-up (1队 at bottom, 2队 above, etc.)
                    teamBadges.reversed().forEach { badge ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFF333333), RoundedCornerShape(4.dp))
                                .clickable { onTeamBadgeClick(badge.teamId) }
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = badge.teamName,
                                fontSize = 9.sp,
                                color = Color.Black,
                                fontWeight = if (badge.isExpanded) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(badgeSpacing))
            }

            // Action buttons row (shown right of sect, to the right side)
            Box {
                // Sect name badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(MapStyle.Dimensions.sectBorderRadius))
                        .background(markerColor)
                        .border(
                            width = borderWidth,
                            color = borderColor,
                            shape = RoundedCornerShape(MapStyle.Dimensions.sectBorderRadius)
                        )
                        .padding(
                            horizontal = MapStyle.Dimensions.sectPaddingH,
                            vertical = MapStyle.Dimensions.sectPaddingV
                        )
                        .clickable { onClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.name,
                        fontSize = fontSize,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Action buttons to the right of sect badge
                if (hasActionButtons && expandedTeam != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(
                                x = MapStyle.Dimensions.sectPaddingH + MapStyle.Dimensions.sectPaddingH + 60.dp,
                                y = 0.dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TeamActionButton("查看") { onTeamAction(expandedTeam.teamId, TeamAction.VIEW) }
                        TeamActionButton("移动") { onTeamAction(expandedTeam.teamId, TeamAction.MOVE) }
                        TeamActionButton("进攻") { onTeamAction(expandedTeam.teamId, TeamAction.ATTACK) }
                        TeamActionButton("解散") { onTeamAction(expandedTeam.teamId, TeamAction.DISBAND) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFF0F0F0))
            .border(0.5.dp, Color(0xFF999999), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            color = Color.Black,
            maxLines = 1
        )
    }
}
