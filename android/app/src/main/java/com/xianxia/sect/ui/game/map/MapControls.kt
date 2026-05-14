package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xianxia.sect.ui.components.CloseButton

@Composable
fun BoxScope.MapControls(
    onBack: () -> Unit
) {
    CloseButton(
        onClick = onBack,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp),
        size = MapStyle.Dimensions.closeButtonSize
    )
}
