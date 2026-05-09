# UI Layout Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify spacing, sizing, typography, and dialog patterns across 30+ game screens by establishing design tokens and shared components, then migrating all screens to use them.

**Architecture:** Phase 1 creates infrastructure (tokens + shared composables). Phases 2-4 migrate screens in groups. Phase 5 cleans up duplicated code. Every phase ends with compile verification.

**Tech Stack:** Kotlin/Compose, Material3, existing bg_horizontal/button image assets

---

### Task 1: Create DesignTokens.kt

**Files:**
- Create: `android/app/src/main/java/com/xianxia/sect/ui/theme/DesignTokens.kt`

- [ ] **Step 1: Write DesignTokens.kt**

```kotlin
package com.xianxia.sect.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object Spacing {
    val XS: Dp = 4.dp
    val SM: Dp = 8.dp
    val MD: Dp = 12.dp
    val LG: Dp = 16.dp
    val XL: Dp = 20.dp
}

object SlotSize {
    val Tiny: Dp = 44.dp
    val Small: Dp = 52.dp
    val Medium: Dp = 60.dp
    val Large: Dp = 70.dp
}

object AppTypography {
    val Title: TextUnit = 16.sp
    val Subtitle: TextUnit = 13.sp
    val Body: TextUnit = 11.sp
    val Caption: TextUnit = 9.sp
}

object CornerRadius {
    val SM: Dp = 6.dp
    val MD: Dp = 8.dp
    val LG: Dp = 12.dp
}

object BorderWidth {
    val Standard: Dp = 1.dp
    val Accent: Dp = 2.dp
}
```

- [ ] **Step 2: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/theme/DesignTokens.kt
git commit -m "feat: add Design Tokens for spacing, slot sizes, typography, corners, borders"
```

---

### Task 2: Rewrite Typography.kt with real hierarchy

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/theme/Typography.kt`

- [ ] **Step 1: Rewrite Typography.kt**

Replace the entire file content with:

```kotlin
package com.xianxia.sect.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = AppTypography.Title,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = AppTypography.Subtitle,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = AppTypography.Title,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = AppTypography.Subtitle,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = AppTypography.Body,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = AppTypography.Body,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = AppTypography.Caption,
        lineHeight = 12.sp,
        letterSpacing = 0.5.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = AppTypography.Body,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = AppTypography.Caption,
        lineHeight = 12.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 8.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.5.sp
    )
)
```

- [ ] **Step 2: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/theme/Typography.kt
git commit -m "feat: populate Material3 Typography with real hierarchy values"
```

---

### Task 3: Create EmptyState.kt

**Files:**
- Create: `android/app/src/main/java/com/xianxia/sect/ui/components/EmptyState.kt`

- [ ] **Step 1: Write EmptyState.kt**

```kotlin
package com.xianxia.sect.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xianxia.sect.ui.theme.AppTypography

@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 120.dp
) {
    Box(
        modifier = modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = AppTypography.Body,
            fontWeight = FontWeight.Normal,
            color = Color.Black
        )
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/components/EmptyState.kt
git commit -m "feat: add EmptyState shared component"
```

---

### Task 4: Create UnifiedSlot.kt

**Files:**
- Create: `android/app/src/main/java/com/xianxia/sect/ui/components/UnifiedSlot.kt`

- [ ] **Step 1: Read existing slot patterns from ProductionComponents.kt and PeakScreenComponents.kt for reference**

Read the slot implementations to understand the common pattern (border, corner, background, click-to-assign).

- [ ] **Step 2: Write UnifiedSlot.kt**

```kotlin
package com.xianxia.sect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xianxia.sect.ui.theme.AppTypography
import com.xianxia.sect.ui.theme.BorderWidth
import com.xianxia.sect.ui.theme.CornerRadius
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.SlotSize
import com.xianxia.sect.ui.theme.Spacing

sealed class SlotState {
    data object Empty : SlotState()
    data class Occupied(
        val name: String,
        val subtitle: String? = null,
        val borderColor: Color = GameColors.Border
    ) : SlotState()
}

@Composable
fun UnifiedSlot(
    state: SlotState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = SlotSize.Medium,
    label: String? = null,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        if (label != null) {
            Text(
                text = label,
                fontSize = AppTypography.Caption,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Box(modifier = Modifier.height(Spacing.XS))
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(CornerRadius.SM))
                .background(GameColors.PageBackground)
                .then(
                    when (state) {
                        is SlotState.Empty ->
                            Modifier.border(BorderWidth.Standard, GameColors.Border, RoundedCornerShape(CornerRadius.SM))
                        is SlotState.Occupied ->
                            Modifier.border(BorderWidth.Standard, state.borderColor, RoundedCornerShape(CornerRadius.SM))
                    }
                )
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                is SlotState.Empty -> {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        textAlign = TextAlign.Center
                    )
                }
                is SlotState.Occupied -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.name,
                            fontSize = AppTypography.Caption,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                        )
                        if (state.subtitle != null) {
                            Text(
                                text = state.subtitle,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SlotRow(
    slots: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(Spacing.SM)
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement
    ) {
        slots.forEach { it() }
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/components/UnifiedSlot.kt
git commit -m "feat: add UnifiedSlot and SlotRow shared components"
```

---

### Task 5: Create DiscipleFilterBar.kt

**Files:**
- Create: `android/app/src/main/java/com/xianxia/sect/ui/components/DiscipleFilterBar.kt`

First, read the existing filter implementations to understand their API:
- `android/app/src/main/java/com/xianxia/sect/ui/game/components/SpiritRootAttributeFilterBar.kt` (referenced by ProductionComponents)
- The `RealmFilterRow` pattern in ProductionComponents.kt and PeakScreenComponents.kt

- [ ] **Step 1: Read existing filter implementations**

Read files:
- `android/app/src/main/java/com/xianxia/sect/ui/game/components/SpiritRootAttributeFilterBar.kt`
- The filter section of `ProductionComponents.kt` (around line 800+)
- The filter section of `PeakScreenComponents.kt` (around line 400+)

- [ ] **Step 2: Write DiscipleFilterBar.kt**

```kotlin
package com.xianxia.sect.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.theme.AppTypography
import com.xianxia.sect.ui.theme.Spacing

data class RealmFilter(
    val realmName: String,
    val realmIndex: Int,
    val count: Int = 0
)

data class SpiritRootFilter(
    val rootName: String,
    val count: Int = 0,
    val color: Color = Color.Black
)

data class AttributeSort(
    val name: String,
    val key: String
)

@Composable
fun DiscipleFilterBar(
    disciples: List<DiscipleAggregate>,
    onFilterChange: (List<DiscipleAggregate>) -> Unit,
    modifier: Modifier = Modifier,
    showSpiritRootFilter: Boolean = true,
    showRealmFilter: Boolean = true,
    showAttributeSort: Boolean = false,
    realmSet: List<Int>? = null,
    attributeSorts: List<AttributeSort> = listOf(
        AttributeSort("悟性", "comprehension"),
        AttributeSort("忠诚", "loyalty"),
        AttributeSort("道德", "morality"),
        AttributeSort("境界", "realm"),
        AttributeSort("战力", "battlePower")
    )
) {
    // Compute counts
    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.spiritRootName }.eachCount()
    }
    val realmCounts = remember(disciples) {
        val targetRealms = realmSet ?: disciples.map { it.realmName }.distinct()
        disciples.filter { it.realmName in targetRealms }
            .groupingBy { it.realmName }.eachCount()
    }

    var selectedSpiritRoot by remember { mutableStateOf<String?>(null) }
    var selectedRealm by remember { mutableStateOf<String?>(null) }
    var selectedSort by remember { mutableStateOf<String?>(null) }

    // Apply filters when any selection changes
    LaunchedEffect(selectedSpiritRoot, selectedRealm, selectedSort) {
        var filtered = disciples
        if (showSpiritRootFilter && selectedSpiritRoot != null) {
            filtered = filtered.filter { it.spiritRootName == selectedSpiritRoot }
        }
        if (showRealmFilter && selectedRealm != null) {
            filtered = filtered.filter { it.realmName == selectedRealm }
        }
        if (showAttributeSort && selectedSort != null) {
            filtered = when (selectedSort) {
                "comprehension" -> filtered.sortedByDescending { it.comprehension }
                "loyalty" -> filtered.sortedByDescending { it.loyalty }
                "morality" -> filtered.sortedByDescending { it.morality }
                "realm" -> filtered.sortedByDescending { it.realmName }
                "battlePower" -> filtered.sortedByDescending { it.battlePower }
                else -> filtered
            }
        }
        onFilterChange(filtered)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        // Spirit Root Filter Row
        if (showSpiritRootFilter && spiritRootCounts.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS)
            ) {
                // "All" button
                FilterChip(
                    text = "全部",
                    isSelected = selectedSpiritRoot == null,
                    onClick = { selectedSpiritRoot = null },
                    color = Color.Black
                )
                spiritRootCounts.entries.take(5).forEach { (root, count) ->
                    FilterChip(
                        text = "$root($count)",
                        isSelected = selectedSpiritRoot == root,
                        onClick = {
                            selectedSpiritRoot = if (selectedSpiritRoot == root) null else root
                        },
                        color = Color.Black
                    )
                }
            }
        }

        // Realm Filter Row
        if (showRealmFilter && realmCounts.isNotEmpty()) {
            val realmEntries = realmCounts.entries.toList()
            val rows = realmEntries.chunked(4)
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.XS)
                ) {
                    rowItems.forEach { (realm, count) ->
                        FilterChip(
                            text = "$realm($count)",
                            isSelected = selectedRealm == realm,
                            onClick = {
                                selectedRealm = if (selectedRealm == realm) null else realm
                            },
                            color = Color.Black
                        )
                    }
                }
            }
        }

        // Attribute Sort Row
        if (showAttributeSort) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS)
            ) {
                attributeSorts.forEach { sort ->
                    FilterChip(
                        text = sort.name,
                        isSelected = selectedSort == sort.key,
                        onClick = {
                            selectedSort = if (selectedSort == sort.key) null else sort.key
                        },
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    val chipModifier = Modifier
        .then(if (isSelected) Modifier else Modifier)
    // Uses existing GameButton pattern for consistency
    GameButton(
        text = text,
        onClick = onClick,
        modifier = chipModifier,
        fontSize = AppTypography.Caption
    )
}
```

- [ ] **Step 3: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL (may have unused warnings initially - OK as it will be used in migration tasks)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/components/DiscipleFilterBar.kt
git commit -m "feat: add DiscipleFilterBar unified filter component"
```

---

### Task 6: Rewrite GameDialog.kt with UnifiedGameDialog

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/components/GameDialog.kt`

- [ ] **Step 1: Rewrite GameDialog.kt**

Replace the entire file content with:

```kotlin
package com.xianxia.sect.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xianxia.sect.R
import com.xianxia.sect.ui.theme.CornerRadius
import com.xianxia.sect.ui.theme.Spacing
import com.xianxia.sect.ui.theme.AppTypography
import com.xianxia.sect.ui.theme.GameColors

enum class DialogMode { Half, Full, Auto }

@Composable
fun UnifiedGameDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    mode: DialogMode = DialogMode.Half,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    headerActions: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (dismissOnBackPress) {
        BackHandler(onBack = onDismissRequest)
    }

    val (widthModifier, heightModifier) = when (mode) {
        DialogMode.Half -> Pair(
            Modifier.fillMaxWidth(0.85f),
            Modifier.fillMaxHeight(0.78f)
        )
        DialogMode.Full -> Pair(
            Modifier.fillMaxSize(),
            Modifier.fillMaxSize()
        )
        DialogMode.Auto -> Pair(
            Modifier.fillMaxWidth(0.85f),
            Modifier
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (dismissOnClickOutside) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = modifier
                .then(widthModifier)
                .then(heightModifier)
                .clip(RoundedCornerShape(CornerRadius.LG))
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.fillMaxSize()) {
                // Unified header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.MD, vertical = Spacing.MD),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = AppTypography.Title,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.SM),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        headerActions?.invoke()
                        CloseButton(onClick = onDismissRequest)
                    }
                }
                // Scrollable content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.MD)
                ) {
                    content()
                }
            }
        }
    }
}

// Keep existing HalfScreenDialog for backward compatibility during migration
@Composable
fun HalfScreenDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    GameFullDialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxWidth(DialogDefaults.HalfScreenWidthFraction)
                .fillMaxHeight(DialogDefaults.HalfScreenHeightFraction)
                .clip(RoundedCornerShape(DialogDefaults.CornerRadius))
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            content()
        }
    }
}

// Keep existing GameFullDialog for Full mode usage
@Composable
fun GameFullDialog(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    content: @Composable () -> Unit
) {
    if (dismissOnBackPress) {
        BackHandler(onBack = onDismissRequest)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (dismissOnClickOutside) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// Keep existing GameAlertDialog unchanged
@Composable
fun GameAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    containerColor: Color = GameColors.PageBackground,
    shape: Shape = androidx.compose.material3.AlertDialogDefaults.shape,
    tonalElevation: Dp = 0.dp
) {
    BackHandler(onBack = onDismissRequest)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = modifier.widthIn(max = 560.dp),
            shape = shape,
            color = containerColor,
            tonalElevation = tonalElevation
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                title()
                if (text != null) {
                    text()
                }
                Row {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

object DialogDefaults {
    const val HalfScreenWidthFraction = 0.85f
    const val HalfScreenHeightFraction = 0.90f
    val CommonMaxHeight: Dp = 280.dp
    val CornerRadius: Dp = 12.dp
}
```

- [ ] **Step 2: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL (existing code uses HalfScreenDialog which is preserved)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/components/GameDialog.kt
git commit -m "feat: add UnifiedGameDialog with Half/Full/Auto modes, preserve backward compat"
```

---

### Task 7: Migrate AlchemyScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/AlchemyScreen.kt`

- [ ] **Step 1: Read current AlchemyScreen.kt fully**

Read the complete file to understand all dialog structures, slot usages, and filter patterns.

- [ ] **Step 2: Replace HalfScreenDialog with UnifiedGameDialog**

In `AlchemyDialog()`, replace:
```kotlin
HalfScreenDialog(onDismissRequest = { viewModel.closeCurrentDialog() }) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("炼丹炉", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            // ... header actions + CloseButton
        }
        // ... scrollable content
    }
}
```

With:
```kotlin
UnifiedGameDialog(
    onDismissRequest = { viewModel.closeCurrentDialog() },
    title = "炼丹炉",
    mode = DialogMode.Half,
    headerActions = {
        // ... existing header action buttons
    }
) {
    // ... existing scrollable content unchanged
}
```

- [ ] **Step 3: Replace slot Box patterns with UnifiedSlot**

For elder slot (currently ~70dp Box):
```kotlin
UnifiedSlot(
    state = if (alchemyElder != null)
        SlotState.Occupied(alchemyElder.name, alchemyElder.realmName, GameColors.getSpiritRootCountColor(alchemyElder.spiritRootCount))
    else SlotState.Empty,
    onClick = { showElderSelection = true },
    size = SlotSize.Large,
    label = "长老"
)
```

For direct disciple slots (currently ~55dp Box):
```kotlin
UnifiedSlot(
    state = if (disciple != null)
        SlotState.Occupied(disciple.name, disciple.realmName, GameColors.getSpiritRootCountColor(disciple.spiritRootCount))
    else SlotState.Empty,
    onClick = { showDirectDiscipleSelection = index },
    size = SlotSize.Small,
    label = "亲传$i"
)
```

For production slots (currently ~60dp Box):
```kotlin
UnifiedSlot(
    state = if (slot.productName != null)
        SlotState.Occupied(slot.productName, "剩余${slot.remainingMonths}月")
    else SlotState.Empty,
    onClick = { selectSlot(index) },
    size = SlotSize.Medium,
    label = "槽位${index + 1}"
)
```

- [ ] **Step 4: Replace padding/spacing values with DesignTokens**

Replace all hardcoded padding/spacing:
- `padding(horizontal = 12.dp, vertical = 12.dp)` → `padding(horizontal = Spacing.MD, vertical = Spacing.MD)`
- `Arrangement.spacedBy(8.dp)` → `Arrangement.spacedBy(Spacing.SM)`
- `padding(8.dp)` → `padding(Spacing.SM)`
- `padding(16.dp)` → `padding(Spacing.LG)`

- [ ] **Step 5: Replace fontSize values with AppTypography**

- `fontSize = 14.sp` → `fontSize = AppTypography.Title`
- `fontSize = 12.sp` → `fontSize = AppTypography.Subtitle`
- `fontSize = 11.sp` → `fontSize = AppTypography.Body`
- `fontSize = 9.sp, 10.sp` → `fontSize = AppTypography.Caption`

- [ ] **Step 6: Add imports**

```kotlin
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedSlot
import com.xianxia.sect.ui.components.SlotState
import com.xianxia.sect.ui.theme.SlotSize
import com.xianxia.sect.ui.theme.Spacing
import com.xianxia.sect.ui.theme.AppTypography
```

- [ ] **Step 7: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/AlchemyScreen.kt
git commit -m "refactor: migrate AlchemyScreen to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 8: Migrate ForgeScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/ForgeScreen.kt`

Follow the same pattern as Task 7 (AlchemyScreen):
1. Replace `HalfScreenDialog` → `UnifiedGameDialog(mode = DialogMode.Half, title = "锻造坊")`
2. Replace slot Boxes → `UnifiedSlot` (elder=Large, direct disciples=Small, forge slots=Medium)
3. Replace hardcoded padding/spacing → DesignTokens
4. Replace hardcoded fontSize → AppTypography
5. Add required imports

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/ForgeScreen.kt
git commit -m "refactor: migrate ForgeScreen to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 9: Migrate HerbGardenScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/HerbGardenScreen.kt`

HerbGarden uses a private `CommonDialog` wrapper (not HalfScreenDialog directly). Replace with `UnifiedGameDialog(mode = DialogMode.Half, title = "灵植阁")`.

Slot mapping:
- Elder → `UnifiedSlot(size = SlotSize.Large)`
- Direct disciples (3) → `UnifiedSlot(size = SlotSize.Small)`
- Planting slots (3) → `UnifiedSlot(size = SlotSize.Medium)`

Also replace hardcoded spacing/fontSize values.

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/HerbGardenScreen.kt
git commit -m "refactor: migrate HerbGardenScreen to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 10: Migrate SpiritMineScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/SpiritMineScreen.kt`

SpiritMine uses a private `CommonDialog` wrapper. Replace with `UnifiedGameDialog(mode = DialogMode.Half, title = "灵矿场")`.

Slot mapping:
- Deacon slots (2) → `UnifiedSlot(size = SlotSize.Medium)` (currently 65dp, Medium=60dp is close match)
- Miner slots (3) → `UnifiedSlot(size = SlotSize.Medium)`

Replace hardcoded spacing/fontSize.

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/SpiritMineScreen.kt
git commit -m "refactor: migrate SpiritMineScreen to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 11: Migrate LibraryScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/LibraryScreen.kt`

Library uses a private `CommonDialog`. Replace with `UnifiedGameDialog(mode = DialogMode.Half, title = "藏经阁")`.

Slot mapping:
- Cultivation slots (3) → `UnifiedSlot(size = SlotSize.Medium)` (currently 60dp)

Replace hardcoded spacing/fontSize.

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/LibraryScreen.kt
git commit -m "refactor: migrate LibraryScreen to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 12: Migrate PeakSystem (WenDaoPeakScreen + QingyunPeakScreen + PeakScreenComponents)

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/WenDaoPeakScreen.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/QingyunPeakScreen.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/PeakScreenComponents.kt`

Both WenDaoPeak and QingyunPeak use `PeakScreenComponents.kt` for shared components (`PeakDialog`, `PeakElderSection`, `PeakPreachingMasterSection`, `PeakDiscipleListSection`, `PeakDiscipleSelectionDialog`).

- [ ] **Step 1: Migrate PeakScreenComponents.kt**

Replace `PeakDialog` (currently a raw `Dialog` + `fillMaxWidth(0.85f)` + `CommonMaxHeight(280dp)`):
```kotlin
@Composable
fun PeakDialog(
    title: String,
    onDismiss: () -> Unit,
    headerActions: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Half,
        headerActions = headerActions
    ) {
        content()
    }
}
```

Replace elder slot items (currently `PeakElderSlotItem` with 60dp):
```kotlin
UnifiedSlot(
    state = if (elder != null)
        SlotState.Occupied(elder.name, elder.realmName, GameColors.getSpiritRootCountColor(elder.spiritRootCount))
    else SlotState.Empty,
    onClick = { onSelect(index) },
    size = SlotSize.Medium,
    label = "长老${index + 1}"
)
```

Replace preaching master slots (currently 50dp Box):
```kotlin
UnifiedSlot(
    state = if (master != null)
        SlotState.Occupied(master.name, master.realmName, GameColors.getSpiritRootCountColor(master.spiritRootCount))
    else SlotState.Empty,
    onClick = { onSelect(index) },
    size = SlotSize.Small,
    label = "传道师${index + 1}"
)
```

Replace hardcoded spacing: `16.dp` between elders → `Spacing.LG`, `8.dp` between masters → `Spacing.SM`.

- [ ] **Step 2: Update WenDaoPeakScreen.kt**

Replace direct usage of `PeakDialog` → ensure it still passes `title = "问道塔"`.

- [ ] **Step 3: Update QingyunPeakScreen.kt**

Replace direct usage of `PeakDialog` → ensure it still passes `title = "青云塔"`.

- [ ] **Step 4: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/PeakScreenComponents.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/WenDaoPeakScreen.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/QingyunPeakScreen.kt
git commit -m "refactor: migrate PeakSystem to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 13: Migrate LawEnforcementHallScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/LawEnforcementHallScreen.kt`

Replace private dialog wrapper with `UnifiedGameDialog(mode = DialogMode.Half, title = "执法堂")`.

Slot mapping:
- Elder slot → `UnifiedSlot(size = SlotSize.Medium)` (currently 60dp)
- Law disciple slots (8) → `UnifiedSlot(size = SlotSize.Tiny)` (currently 48dp, Tiny=44dp is close match)

Replace hardcoded spacing/fontSize.

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/LawEnforcementHallScreen.kt
git commit -m "refactor: migrate LawEnforcementHallScreen to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 14: Migrate TianshuHallScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/TianshuHallScreen.kt`

Replace HalfScreenDialog with `UnifiedGameDialog(mode = DialogMode.Half, title = "天枢殿")`.

Slot mapping:
- Vice sect master slot → `UnifiedSlot(size = SlotSize.Medium)` (currently 60dp)

Replace hardcoded spacing/fontSize.

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/TianshuHallScreen.kt
git commit -m "refactor: migrate TianshuHallScreen to UnifiedGameDialog + UnifiedSlot + DesignTokens"
```

---

### Task 15: Migrate MissionHallScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/MissionHallScreen.kt`

Replace private `CommonDialog` with `UnifiedGameDialog(mode = DialogMode.Half, title = "任务阁")`.

Mission hall has no traditional slots - it uses mission cards. Replace padding/spacing/fontSize with DesignTokens.

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/MissionHallScreen.kt
git commit -m "refactor: migrate MissionHallScreen to UnifiedGameDialog + DesignTokens"
```

---

### Task 16: Migrate ReflectionCliffScreen.kt

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/ReflectionCliffScreen.kt`

Replace private `CommonDialog` with `UnifiedGameDialog(mode = DialogMode.Half, title = "监牢")`.

Replace hardcoded spacing/fontSize with DesignTokens.

- [ ] **Step 1: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/ReflectionCliffScreen.kt
git commit -m "refactor: migrate ReflectionCliffScreen to UnifiedGameDialog + DesignTokens"
```

---

### Task 17: Migrate Feature Screens (Recruit, Merchant, Inventory, SalaryConfig)

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/RecruitScreen.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/MerchantScreen.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/InventoryScreen.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/SalaryConfigScreen.kt`

- [ ] **Step 1: Migrate RecruitScreen.kt**

Replace HalfScreenDialog → `UnifiedGameDialog(mode = DialogMode.Half, title = "招募")`.
Replace hardcoded spacing/fontSize.

- [ ] **Step 2: Migrate MerchantScreen.kt**

Replace HalfScreenDialog → `UnifiedGameDialog(mode = DialogMode.Half, title = "商会")`.
Replace hardcoded spacing/fontSize.

- [ ] **Step 3: Migrate InventoryScreen.kt**

Replace private `CommonDialog` → `UnifiedGameDialog(mode = DialogMode.Half, title = "仓库")`.
Replace hardcoded spacing/fontSize.

- [ ] **Step 4: Migrate SalaryConfigScreen.kt**

Replace private `CommonDialog` → `UnifiedGameDialog(mode = DialogMode.Half, title = "俸禄配置")`.
Replace hardcoded spacing/fontSize.

- [ ] **Step 5: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/RecruitScreen.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/MerchantScreen.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/InventoryScreen.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/SalaryConfigScreen.kt
git commit -m "refactor: migrate feature screens to UnifiedGameDialog + DesignTokens"
```

---

### Task 18: Migrate Tabs (BuildingsTab, DisciplesTab, WarehouseTab, SettingsTab)

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/tabs/BuildingsTab.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/tabs/DisciplesTab.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/tabs/WarehouseTab.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/tabs/SettingsTab.kt`

- [ ] **Step 1: Migrate BuildingsTab.kt**

Replace `HalfScreenDialog` → `UnifiedGameDialog(mode = DialogMode.Half, title = "建造")`.
Replace hardcoded spacing/fontSize in building cards.
Replace building card padding (currently 12dp) with `Spacing.MD`.
Replace card spacing (currently 8dp) with `Spacing.SM`.

- [ ] **Step 2: Migrate DisciplesTab.kt**

Replace `Dialog(usePlatformDefaultWidth = false)` + `Surface(fillMaxSize)` → `UnifiedGameDialog(mode = DialogMode.Full, title = "弟子")`.
Replace hardcoded padding/spacing/fontSize.

- [ ] **Step 3: Migrate WarehouseTab.kt**

Replace `Dialog(usePlatformDefaultWidth = false)` + `Surface(fillMaxSize)` → `UnifiedGameDialog(mode = DialogMode.Full, title = "仓库")`.
Replace hardcoded padding/spacing/fontSize.

- [ ] **Step 4: Migrate SettingsTab.kt**

Replace `Dialog(usePlatformDefaultWidth = false)` + `Surface(fillMaxSize)` → `UnifiedGameDialog(mode = DialogMode.Full, title = "设置")`.
Replace hardcoded padding/spacing/fontSize.

- [ ] **Step 5: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/tabs/BuildingsTab.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/tabs/DisciplesTab.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/tabs/WarehouseTab.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/tabs/SettingsTab.kt
git commit -m "refactor: migrate tabs to UnifiedGameDialog + DesignTokens"
```

---

### Task 19: Cleanup Duplicated Code

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/ProductionComponents.kt`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/PeakScreenComponents.kt`

- [ ] **Step 1: Clean up ProductionComponents.kt**

Remove duplicated filter code that's now in `DiscipleFilterBar`. Keep only Production-specific code:
- Keep: `ProductionTheme`, `ProductionElderSection`, `ProductionDirectDiscipleSection`, `ProductionSlotItem`, `ProductionCommonDialog` (for sub-dialogs that haven't migrated)
- Remove: `SpiritRootAttributeFilterBar` import references (the component file itself stays until all callers migrated)
- Remove: Duplicate `REALM_FILTERS` list if present
- Replace remaining hardcoded sizing with DesignTokens

- [ ] **Step 2: Clean up PeakScreenComponents.kt**

Remove duplicated code now in shared components:
- Keep: `PeakDiscipleItem`, `PeakDiscipleListSection` (Peak-specific list rendering)
- The `PeakDialog`, `PeakElderSlotItem` etc. already migrated to use UnifiedGameDialog/UnifiedSlot
- Remove: Duplicate `realmFilters` list
- Replace remaining hardcoded sizing with DesignTokens

- [ ] **Step 3: Verify compile**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/ProductionComponents.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/PeakScreenComponents.kt
git commit -m "refactor: remove duplicated filter/slot code, use DesignTokens"
```

---

### Task 20: Update Changelog

**Files:**
- Modify: `CHANGELOG.md` (project root)
- Modify: `android/app/src/main/java/com/xianxia/sect/core/ChangelogData.kt`

- [ ] **Step 1: Update project CHANGELOG.md**

Add new version section at top:
```markdown
## v3.0.04 (2026-05-10)

- 统一所有界面布局：间距、插槽尺寸、字体层级标准化
- 重构对话框系统：UnifiedGameDialog 替代四种不同包裹器
- 提取共享组件：UnifiedSlot、DiscipleFilterBar、EmptyState
- 消除 10+ 处筛选器和境界筛选的重复代码
```

- [ ] **Step 2: Update in-game ChangelogData.kt**

Add entry:
```kotlin
ChangelogEntry(
    version = "v3.0.04",
    date = "2026-05-10",
    content = "界面布局全面优化：统一间距与尺寸标准，简化对话框结构，提升整体美观度"
)
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md android/app/src/main/java/com/xianxia/sect/core/ChangelogData.kt
git commit -m "docs: update changelog for v3.0.04 UI layout unification"
```

---

### Task 21: Final Verification

- [ ] **Step 1: Full compile check**

Run: `cd android && ./gradlew.bat compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run tests**

Run: `cd android && ./gradlew.bat test`
Expected: All tests pass

- [ ] **Step 3: Build APK for final check**

Run: `cd android && ./gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL
