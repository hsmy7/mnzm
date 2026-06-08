package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.BorderStroke
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
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun SellConfirmDialog(
    itemName: String,
    maxQuantity: Int,
    basePrice: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sellQuantity by remember { mutableIntStateOf(1) }
    var isEditingQuantity by remember { mutableStateOf(false) }
    var quantityInput by remember { mutableStateOf("1") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(maxQuantity) {
        if (sellQuantity > maxQuantity) {
            sellQuantity = maxQuantity.coerceAtLeast(1)
            quantityInput = sellQuantity.toString()
        }
    }

    val totalPrice = GameConfig.Rarity.calculateSellPrice(basePrice, sellQuantity)

    LaunchedEffect(isEditingQuantity) {
        if (isEditingQuantity) {
            focusRequester.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent, tonalElevation = 0.dp,
        title = {
            Text(
                text = "售卖物品",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = itemName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "售卖数量",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "最大: $maxQuantity",
                        fontSize = 11.sp,
                        color = Color.Black
                    )
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
                                sellQuantity = (sellQuantity - 1).coerceAtLeast(1)
                                quantityInput = sellQuantity.toString()
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
                                    sellQuantity = 1
                                } else {
                                    val parsed = filtered.toIntOrNull() ?: 0
                                    if (parsed > maxQuantity) {
                                        quantityInput = maxQuantity.toString()
                                        sellQuantity = maxQuantity
                                    } else if (parsed == 0) {
                                        quantityInput = ""
                                        sellQuantity = 1
                                    } else {
                                        quantityInput = filtered
                                        sellQuantity = parsed
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
                                sellQuantity = (sellQuantity + 1).coerceAtMost(maxQuantity)
                                quantityInput = sellQuantity.toString()
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

                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "单价",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "${GameConfig.Rarity.calculateSellPrice(basePrice, 1)} 灵石",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "总计",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "$totalPrice 灵石",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6B35)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss
                )
                GameButton(
                    text = "确认售卖",
                    onClick = { onConfirm(sellQuantity) }
                )
            }
        }
    )
}
