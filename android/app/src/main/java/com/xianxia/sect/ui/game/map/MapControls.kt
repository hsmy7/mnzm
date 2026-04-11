package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BoxScope.MapControls(
    hasBattleTeam: Boolean,
    isBattleTeamAtSect: Boolean,
    onBack: () -> Unit,
    onCreateTeamClick: () -> Unit,
    onManageTeamClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MapStyle.Dimensions.controlButtonBorderRadius))
                .background(
                    if (hasBattleTeam && isBattleTeamAtSect) MapStyle.Colors.controlButtonDisabled
                    else MapStyle.Colors.controlButtonBg
                )
                .border(
                    MapStyle.Dimensions.controlButtonBorderWidth,
                    MapStyle.Colors.controlButtonBorder,
                    RoundedCornerShape(MapStyle.Dimensions.controlButtonBorderRadius)
                )
                .clickable { if (!hasBattleTeam || !isBattleTeamAtSect) onCreateTeamClick() }
                .padding(
                    horizontal = MapStyle.Dimensions.controlButtonPaddingH,
                    vertical = MapStyle.Dimensions.controlButtonPaddingV
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (hasBattleTeam && isBattleTeamAtSect) "已组建" else "组建队伍",
                fontSize = MapStyle.Typography.controlButton,
                fontWeight = FontWeight.Bold,
                color = if (hasBattleTeam && isBattleTeamAtSect) MapStyle.Colors.controlButtonDisabledText
                else MapStyle.Colors.controlButtonText
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MapStyle.Dimensions.controlButtonBorderRadius))
                .background(if (hasBattleTeam) MapStyle.Colors.controlButtonBg else MapStyle.Colors.controlButtonDisabled)
                .border(
                    MapStyle.Dimensions.controlButtonBorderWidth,
                    MapStyle.Colors.controlButtonBorder,
                    RoundedCornerShape(MapStyle.Dimensions.controlButtonBorderRadius)
                )
                .clickable { if (hasBattleTeam) onManageTeamClick() }
                .padding(
                    horizontal = MapStyle.Dimensions.controlButtonPaddingH,
                    vertical = MapStyle.Dimensions.controlButtonPaddingV
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "管理队伍",
                fontSize = MapStyle.Typography.controlButton,
                fontWeight = FontWeight.Bold,
                color = if (hasBattleTeam) MapStyle.Colors.controlButtonText else MapStyle.Colors.controlButtonDisabledText
            )
        }
    }

    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .size(MapStyle.Dimensions.closeButtonSize)
            .clip(CircleShape)
            .background(MapStyle.Colors.closeButtonBg)
            .border(
                MapStyle.Dimensions.closeButtonBorderWidth,
                MapStyle.Colors.closeButtonBorder,
                CircleShape
            )
            .clickable { onBack() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✕",
            fontSize = MapStyle.Typography.closeButton,
            color = MapStyle.Colors.closeButtonText,
            fontWeight = FontWeight.Bold
        )
    }
}
