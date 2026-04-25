package com.xianxia.sect.ui.game.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MapStyle {
    object Colors {
        val background = Color(0xFFA8B878)
        val path = Color(0xFF8B7355)
        val pathAlpha = 0.9f

        val sectPlayer = Color(0xFFFF8C00)
        val sectNormal = Color(0xFFF5E6C8)
        val sectHighlighted = Color(0xFFFF0000)
        val sectBorderNormal = Color(0xFF8B7355)
        val sectTextPlayer = Color(0xFFFFD700)
        val sectTextNormal = Color(0xFF3D2914)

        val righteousTeam = Color(0xFF4CAF50)
        val evilTeam = Color(0xFF9C27B0)
        val unknownTeam = Color(0xFFFF5722)

        val scoutTeamBg = Color.White
        val scoutTeamBorder = Color(0xFF2196F3)
        val scoutTeamText = Color(0xFF2196F3)

        val caveText = Color.Red

        val caveExplorationBg = Color(0xFFFF9800)
        val caveExplorationBorder = Color(0xFFF57C00)
        val caveExplorationPath = Color(0xFFFF9800)

        val battleTeamBg = Color(0xFFE91E63)
        val battleTeamBorder = Color(0xFFC62828)

        val battleBattling = Color(0xFFFF0000)
        val battleAttacking = Color(0xFFFF9800)

        val controlButtonBg = Color(0xFF4CAF50)
        val controlButtonDisabled = Color(0xFFCCCCCC)
        val controlButtonBorder = Color(0xFF6B5344)
        val controlButtonText = Color.White
        val controlButtonDisabledText = Color(0xFF666666)

        val closeButtonBg = Color(0xFF8B7355)
        val closeButtonBorder = Color(0xFF6B5344)
        val closeButtonText = Color(0xFFF5E6C8)
    }

    object Typography {
        val sectNamePlayer = 12.sp
        val sectNameNormal = 10.sp
        val teamLabel = 9.sp
        val aiTeamLabel = 8.sp
        val battleIndicator = 12.sp
        val controlButton = 11.sp
        val closeButton = 18.sp
    }

    object Dimensions {
        val sectPaddingH = 8.dp
        val sectPaddingV = 4.dp
        val sectBorderWidth = 2.dp
        val sectHighlightedBorderWidth = 3.dp
        val sectBorderRadius = 6.dp

        val teamPaddingH = 6.dp
        val teamPaddingV = 3.dp
        val teamBorderWidth = 2.dp
        val teamBorderRadius = 6.dp

        val pathStrokeWidth = 4.dp
        val cavePathStrokeWidth = 2.dp
        val battlePathStrokeWidth = 2.dp

        val controlButtonPaddingH = 12.dp
        val controlButtonPaddingV = 6.dp
        val controlButtonBorderWidth = 1.dp
        val controlButtonBorderRadius = 6.dp

        val closeButtonSize = 32.dp
        val closeButtonBorderWidth = 2.dp

        val battleTeamYOffset = 30
        val battleIndicatorYOffset = 25
    }
}
