package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun SellConfirmDialog(
    itemName: String,
    maxQuantity: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sellQuantity by remember { mutableIntStateOf(1) }

    LaunchedEffect(maxQuantity) {
        if (sellQuantity > maxQuantity) {
            sellQuantity = maxQuantity.coerceAtLeast(1)
        }
    }

    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "出售（$itemName）",
        confirmLabel = "出售",
        onConfirm = { onConfirm(sellQuantity) },
        dismissLabel = "取消",
        onDismiss = onDismiss
    ) {
        Spacer(modifier = Modifier.weight(1f))
        SellQuantitySelector(
            sellQuantity = sellQuantity,
            maxQuantity = maxQuantity,
            onQuantityChange = { sellQuantity = it }
        )
    }
}

@Composable
private fun SellQuantitySelector(
    sellQuantity: Int,
    maxQuantity: Int,
    onQuantityChange: (Int) -> Unit
) {
    var isEditingQuantity by remember { mutableStateOf(false) }
    var quantityInput by remember { mutableStateOf("1") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditingQuantity) {
        if (isEditingQuantity) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (sellQuantity > 1) Color(0xFFE0E0E0) else Color(0xFFF5F5F5))
                .clickable(enabled = sellQuantity > 1) {
                    onQuantityChange((sellQuantity - 1).coerceAtLeast(1))
                    quantityInput = (sellQuantity - 1).coerceAtLeast(1).toString()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "−",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (sellQuantity > 1) Color.Black else Color(0xFFBDBDBD)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isEditingQuantity) {
            OutlinedTextField(
                value = quantityInput,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() }
                    if (filtered.isEmpty()) {
                        quantityInput = ""
                        onQuantityChange(1)
                    } else {
                        val parsed = filtered.toIntOrNull() ?: 0
                        if (parsed > maxQuantity) {
                            quantityInput = maxQuantity.toString()
                            onQuantityChange(maxQuantity)
                        } else if (parsed == 0) {
                            quantityInput = ""
                            onQuantityChange(1)
                        } else {
                            quantityInput = filtered
                            onQuantityChange(parsed)
                        }
                    }
                },
                modifier = Modifier
                    .width(80.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && isEditingQuantity) {
                            isEditingQuantity = false
                            quantityInput = sellQuantity.toString()
                        }
                    },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        isEditingQuantity = false
                        quantityInput = sellQuantity.toString()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GameColors.Primary,
                    unfocusedBorderColor = Color(0xFFCCCCCC)
                )
            )
        } else {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .clickable {
                        isEditingQuantity = true
                        quantityInput = sellQuantity.toString()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$sellQuantity",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (sellQuantity < maxQuantity) Color(0xFFE0E0E0) else Color(0xFFF5F5F5))
                .clickable(enabled = sellQuantity < maxQuantity) {
                    onQuantityChange((sellQuantity + 1).coerceAtMost(maxQuantity))
                    quantityInput = (sellQuantity + 1).coerceAtMost(maxQuantity).toString()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (sellQuantity < maxQuantity) Color.Black else Color(0xFFBDBDBD)
            )
        }
    }
}
