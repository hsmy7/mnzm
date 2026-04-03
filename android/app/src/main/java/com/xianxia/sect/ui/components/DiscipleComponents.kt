package com.xianxia.sect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object DiscipleCardStyles {
    val smallShape: Shape = RoundedCornerShape(8.dp)
    val mediumShape: Shape = RoundedCornerShape(12.dp)
    val largeShape: Shape = RoundedCornerShape(16.dp)
    val cardPadding = 12.dp
}

fun Modifier.discipleCardBorder(
    shape: Shape = DiscipleCardStyles.mediumShape,
    background: Color = Color.White
): Modifier = this
    .clip(shape)
    .background(background)
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFE0E0E0),
                Color(0xFFBDBDBD)
            )
        ),
        shape = shape
    )
