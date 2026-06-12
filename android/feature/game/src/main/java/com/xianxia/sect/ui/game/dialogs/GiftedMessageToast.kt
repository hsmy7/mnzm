package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun GiftedMessageToast(
    message: String,
    onDismiss: () -> Unit
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    var alpha by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        delay(100)
        repeat(6) {
            offsetY -= 3f
            alpha -= 1f / 6f
            delay(150)
        }
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(x = 0, y = with(density) { offsetY.dp.roundToPx() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)),
            textAlign = TextAlign.Center
        )
    }
}
