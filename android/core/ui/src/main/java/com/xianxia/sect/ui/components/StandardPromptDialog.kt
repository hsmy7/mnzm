package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.annotation.DrawableRes
import com.xianxia.sect.core.ui.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun StandardPromptDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit = onDismissRequest,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    customButtons: (@Composable RowScope.() -> Unit)? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    showCloseButton: Boolean = false,
    titleColor: Color = Color.Black,
    @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box,
    @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button,
    content: @Composable (ColumnScope.() -> Unit) = {}
) {
    val config = LocalConfiguration.current
    val dialogWidth = (config.screenWidthDp * 0.5f).dp
    val dialogHeight = (config.screenHeightDp * 0.55f).dp

    Dialog(
        onDismissRequest = if (dismissOnClickOutside) onDismissRequest else {{}},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        Box(
            modifier = Modifier
                .width(dialogWidth)
                .height(dialogHeight)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = dialogBackgroundRes),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showCloseButton) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        CloseButton(onClick = onDismissRequest, closeButtonRes = closeButtonRes)
                    }
                } else {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (text != null) {
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        color = Color.Black,
                        textAlign = TextAlign.Center
                    )
                }

                if (text != null && (customButtons != null || !showCloseButton)) {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Content area fills remaining space, pushing buttons to bottom
                Column(modifier = Modifier.weight(1f)) {
                    content()
                }

                // Bottom buttons (only when not in close-button-only mode)
                if (!showCloseButton) {
                    if (customButtons != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            customButtons()
                        }
                    } else if (dismissLabel != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            GameButton(
                                text = dismissLabel,
                                onClick = { (onDismiss ?: onDismissRequest)() },
                                buttonBackgroundRes = buttonBackgroundRes
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            GameButton(
                                text = confirmLabel,
                                onClick = onConfirm,
                                buttonBackgroundRes = buttonBackgroundRes
                            )
                        }
                    } else {
                        GameButton(
                            text = confirmLabel,
                            onClick = onConfirm,
                            buttonBackgroundRes = buttonBackgroundRes
                        )
                    }
                }
            }
        }
    }
}
