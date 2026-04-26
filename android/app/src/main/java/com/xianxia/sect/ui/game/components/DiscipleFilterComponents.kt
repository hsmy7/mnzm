package com.xianxia.sect.ui.game.components

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun DropdownFilterButton(
    displayText: String,
    hasSelection: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (hasSelection) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
            .border(1.dp, if (hasSelection) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(vertical = if (isCompact) 4.dp else 8.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayText,
            fontSize = if (isCompact) 9.sp else 12.sp,
            fontWeight = if (hasSelection) FontWeight.Bold else FontWeight.Normal,
            color = if (hasSelection) GameColors.GoldDark else Color.Black,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(if (isCompact) 14.dp else 18.dp),
            tint = if (hasSelection) GameColors.GoldDark else Color.Black
        )
    }
}

@Composable
internal fun SpiritRootAttributeFilterBar(
    selectedSpiritRootFilter: Set<Int>,
    selectedAttributeSort: String?,
    spiritRootExpanded: Boolean,
    attributeExpanded: Boolean,
    spiritRootCounts: Map<Int, Int>,
    onSpiritRootFilterSelected: (Int) -> Unit,
    onSpiritRootFilterRemoved: (Int) -> Unit,
    onAttributeSortSelected: (String?) -> Unit,
    onSpiritRootExpandToggle: () -> Unit,
    onAttributeExpandToggle: () -> Unit,
    isCompact: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DropdownFilterButton(
                displayText = "灵根",
                hasSelection = selectedSpiritRootFilter.isNotEmpty(),
                isExpanded = spiritRootExpanded,
                onClick = onSpiritRootExpandToggle,
                isCompact = isCompact,
                modifier = Modifier.weight(1f)
            )
            DropdownFilterButton(
                displayText = "属性",
                hasSelection = selectedAttributeSort != null,
                isExpanded = attributeExpanded,
                onClick = onAttributeExpandToggle,
                isCompact = isCompact,
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(
            visible = spiritRootExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SPIRIT_ROOT_FILTER_OPTIONS.forEach { (count, name) ->
                    val isSelected = count in selectedSpiritRootFilter
                    val cnt = spiritRootCounts[count] ?: 0
                    FilterChip(
                        text = "$name $cnt",
                        isSelected = isSelected,
                        onClick = {
                            if (isSelected) onSpiritRootFilterRemoved(count)
                            else onSpiritRootFilterSelected(count)
                        },
                        modifier = Modifier.weight(1f),
                        isCompact = isCompact
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = attributeExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ATTRIBUTE_FILTER_OPTIONS.chunked(5).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        row.forEach { option ->
                            val isSelected = option.key == selectedAttributeSort
                            FilterChip(
                                text = option.name,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) onAttributeSortSelected(null)
                                    else onAttributeSortSelected(option.key)
                                },
                                modifier = Modifier.weight(1f),
                                isCompact = isCompact
                            )
                        }
                        if (row.size < 5) {
                            repeat(5 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
            .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (isCompact) 4.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = if (isCompact) 9.sp else 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) GameColors.GoldDark else Color.Black
        )
    }
}
