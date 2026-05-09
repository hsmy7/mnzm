# All Screens Landscape Adaptation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert all 23 remaining portrait-layout game dialogs to landscape using the HalfScreenDialog + standard header pattern from RecruitScreen/MerchantScreen, plus redesign DiscipleDetailScreen with left-right split layout.

**Architecture:** Replace `CommonDialog`/`ProductionCommonDialog`/`PeakDialog`/raw `Dialog` wrappers with `HalfScreenDialog`. Standardize headers. DiscipleDetailScreen gets full-screen 40/60 split with tab navigation. Internal content logic unchanged.

**Tech Stack:** Kotlin/Compose, HalfScreenDialog from GameDialog.kt

---

### Task 1: CommonDialog → HalfScreenDialog (7 files)

**Files:**
- Modify: `HerbGardenScreen.kt`
- Modify: `SpiritMineScreen.kt`
- Modify: `LibraryScreen.kt`
- Modify: `MissionHallScreen.kt`
- Modify: `ReflectionCliffScreen.kt`
- Modify: `InventoryScreen.kt`
- Modify: `SalaryConfigScreen.kt`

**Pattern:** Each file calls `CommonDialog(title, onDismiss) { content }` defined in MainGameScreen.kt. Replace with `HalfScreenDialog(onDismiss) { Column(fillMaxSize) { StandardHeader + content } }`.

- [ ] **Step 1: Replace CommonDialog calls**

For each file, the current pattern is:
```kotlin
CommonDialog(title = "界面名", onDismiss = { viewModel.closeCurrentDialog() }) {
    // existing content
}
```

Replace with:
```kotlin
HalfScreenDialog(onDismissRequest = { viewModel.closeCurrentDialog() }) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("界面名", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            CloseButton(onClick = { viewModel.closeCurrentDialog() })
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            // existing content
        }
    }
}
```

**Files and their titles:**

1. `HerbGardenScreen.kt:HerbGardenDialog` — title "灵植阁"
2. `SpiritMineScreen.kt:SpiritMineDialog` — title "灵矿场"
3. `LibraryScreen.kt:LibraryDialog` — title "藏经阁"
4. `MissionHallScreen.kt:MissionHallDialog` — title "任务阁"
5. `ReflectionCliffScreen.kt:ReflectionCliffDialog` — title "监牢"
6. `InventoryScreen.kt:InventoryDialog` — already uses HalfScreenDialog, just adjust header
7. `SalaryConfigScreen.kt:SalaryConfigDialog` — already uses HalfScreenDialog, just adjust header

For files 6-7 (already HalfScreenDialog), only adjust the header row to match the standard pattern.

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/HerbGardenScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/SpiritMineScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/LibraryScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/MissionHallScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/ReflectionCliffScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/InventoryScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/SalaryConfigScreen.kt
git commit -m "feat: convert CommonDialog screens to HalfScreenDialog landscape layout"
```

---

### Task 2: ProductionCommonDialog → HalfScreenDialog (2 files)

**Files:**
- Modify: `AlchemyScreen.kt`
- Modify: `ForgeScreen.kt`

- [ ] **Step 1: Replace ProductionCommonDialog**

Current pattern:
```kotlin
ProductionCommonDialog(title = "炼丹炉", theme = ALCHEMY_THEME, onDismiss = { ... }, titleActions = { ... }) {
    // elder section + disciple section + slots
}
```

Replace with HalfScreenDialog + standard header that includes the titleActions buttons:
```kotlin
HalfScreenDialog(onDismissRequest = { viewModel.closeCurrentDialog() }) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("炼丹炉", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // titleActions content (elder bonus button etc.)
                CloseButton(onClick = { viewModel.closeCurrentDialog() })
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            // existing content
        }
    }
}
```

Do the same for ForgeScreen.kt (title "锻造坊", theme FORGE_THEME).

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/AlchemyScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/ForgeScreen.kt
git commit -m "feat: convert ProductionCommonDialog screens to HalfScreenDialog"
```

---

### Task 3: PeakDialog → HalfScreenDialog (2 files)

**Files:**
- Modify: `WenDaoPeakScreen.kt` (imported as `WenDaoPeakDialog.kt` in reality)
- Modify: `QingyunPeakScreen.kt` (imported as `QingyunPeakDialog.kt`)

**Note:** These files may have different actual filenames. Verify with glob.

- [ ] **Step 1: Replace PeakDialog calls**

Current pattern:
```kotlin
PeakDialog(title = "问道塔", subtitle = "...", onDismiss = { ... }) {
    // elder slot + preaching section
}
```

Replace with HalfScreenDialog:
```kotlin
HalfScreenDialog(onDismissRequest = { viewModel.closeCurrentDialog() }) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("问道塔", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(subtitle, fontSize = 11.sp, color = GameColors.TextSecondary)
            }
            CloseButton(onClick = { viewModel.closeCurrentDialog() })
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            // existing content
        }
    }
}
```

Do the same for QingyunPeakScreen (title "青云塔").

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/WenDaoPeakScreen.kt android/app/src/main/java/com/xianxia/sect/ui/game/QingyunPeakScreen.kt
git commit -m "feat: convert PeakDialog screens to HalfScreenDialog"
```

---

### Task 4: Raw Dialog → HalfScreenDialog (10 files)

**Files:**
- `TianshuHallScreen.kt`
- `LawEnforcementHallScreen.kt`
- `dialogs/WorldMapDialogs.kt` — `DiplomacyDialog` + `SectTradeDialog` + `WorldMapSectDetailDialog`
- `dialogs/BattleTeamDialogs.kt` — `BattleTeamDialog`
- `dialogs/LevelDetailDialog.kt`
- `RewardDialog.kt`
- `components/AllianceDialog.kt`
- `components/GiftDialog.kt`
- `OuterTournamentResultDialog.kt` — already HalfScreenDialog, adjust header

- [ ] **Step 1: TianshuHallScreen.kt**

Replace `Dialog(onDismissRequest = ...) { Box { Image(bg_horizontal) + Column { ... } } }` with:
```kotlin
HalfScreenDialog(onDismissRequest = { viewModel.closeCurrentDialog() }) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("天枢殿", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            CloseButton(onClick = { viewModel.closeCurrentDialog() })
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            // existing content (vice sect leader, disciple list)
        }
    }
}
```

- [ ] **Step 2: LawEnforcementHallScreen.kt**

Same pattern as TianshuHallScreen, title "执法堂".

- [ ] **Step 3: WorldMapDialogs.kt — DiplomacyDialog**

Replace outer `Dialog` + `Surface` with `HalfScreenDialog`, title "外交".

- [ ] **Step 4: WorldMapDialogs.kt — SectTradeDialog**

Replace outer `Dialog` + `Surface` with `HalfScreenDialog`, title "宗门交易".

- [ ] **Step 5: WorldMapDialogs.kt — WorldMapSectDetailDialog**

Replace `AlertDialog` with `HalfScreenDialog`, title = sect name.

- [ ] **Step 6: BattleTeamDialogs.kt — BattleTeamDialog**

Replace `AlertDialog` with `HalfScreenDialog`, title "战斗队伍".

- [ ] **Step 7: LevelDetailDialog.kt**

Replace `Dialog` with `HalfScreenDialog`, title = level name.

- [ ] **Step 8: RewardDialog.kt**

Replace `Dialog` with `HalfScreenDialog`, title "奖励".

- [ ] **Step 9: AllianceDialog.kt**

Replace `Dialog` with `HalfScreenDialog`, title "联盟".

- [ ] **Step 10: GiftDialog.kt**

Replace `Dialog` with `HalfScreenDialog`, title "赠礼".

- [ ] **Step 11: OuterTournamentResultDialog.kt**

Already uses HalfScreenDialog, just adjust header to standard pattern.

- [ ] **Step 12: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/
git commit -m "feat: convert remaining raw Dialog screens to HalfScreenDialog landscape layout"
```

---

### Task 5: BuildingsTab → HalfScreenDialog

**File:**
- Modify: `tabs/BuildingsTab.kt`

- [ ] **Step 1: Convert BuildingsTab**

Currently uses `GameBackground` wrapper. Replace with `HalfScreenDialog` + standard header "建筑".

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/tabs/BuildingsTab.kt
git commit -m "feat: convert BuildingsTab to HalfScreenDialog"
```

---

### Task 6: DiscipleDetailScreen Redesign

**File:**
- Modify: `DiscipleDetailScreen.kt`
- Copy: `D:\模拟宗门美术素材\人物素材图` → `drawable-nodpi/disciple_portrait.png` (check actual filename in assets)

- [ ] **Step 1: Copy disciple portrait asset**

Check the exact filename in `D:\模拟宗门美术素材` for the character image and copy:
```bash
cp "D:/模拟宗门美术素材/<exact-filename>" android/app/src/main/res/drawable-nodpi/disciple_portrait.png
```

- [ ] **Step 2: Add import for HalfScreenDialog if not present**

- [ ] **Step 3: Redesign DiscipleDetailDialog**

New layout structure:
```kotlin
@Composable
fun DiscipleDetailDialog(
    disciple: DiscipleAggregate,
    gameData: GameData?,
    equipment: List<Equipment>,
    manuals: List<Manual>,
    equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>,
    manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>,
    pills: List<Pill>,
    allDisciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("信息", "属性", "装备", "功法")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = GameColors.PageBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.bg_horizontal),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left 40% — disciple portrait + basic info
                    Column(
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.4f).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Portrait image
                        Image(
                            painter = painterResource(id = R.drawable.disciple_portrait),
                            contentDescription = null,
                            modifier = Modifier.weight(0.7f).fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // Basic info
                        Text(disciple.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text(disciple.realmName, fontSize = 14.sp, color = Color.Black)
                        Text("${disciple.spiritRoot.displayName}", fontSize = 12.sp, color = Color(0xFF00695C))
                        CloseButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp))
                    }
                    // Divider
                    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color(0xFFBDBDBD)))
                    // Right 60% — tabbed content
                    Row(modifier = Modifier.fillMaxHeight().weight(1f)) {
                        // Content area
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                            when (selectedTab) {
                                0 -> InfoTab(disciple, gameData, allDisciples)
                                1 -> AttrTab(disciple)
                                2 -> EquipmentTab(disciple, equipment, equipmentStacks, equipmentInstances)
                                3 -> ManualTab(disciple, manuals, manualStacks, manualInstances)
                            }
                        }
                        // Tab buttons on right edge
                        Column(
                            modifier = Modifier.fillMaxHeight().width(48.dp).background(Color(0xFFE0E0E0)),
                            verticalArrangement = Arrangement.Center
                        ) {
                            tabs.forEachIndexed { index, label ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(if (selectedTab == index) Color(0xFFBDBDBD) else Color.Transparent)
                                        .clickable { selectedTab = index },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontSize = 11.sp, color = Color.Black, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

**Extract existing content into tab functions:**
- `InfoTab` — existing info display (cultivation progress, status, position, etc.)
- `AttrTab` — existing attributes (comprehension, intelligence, etc.)
- `EquipmentTab` — existing equipment grid
- `ManualTab` — existing manual list

These functions should be `@Composable` with `Modifier.verticalScroll(rememberScrollState())` for scrollable content.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/drawable-nodpi/disciple_portrait.png android/app/src/main/java/com/xianxia/sect/ui/game/DiscipleDetailScreen.kt
git commit -m "feat: redesign DiscipleDetailScreen with 40/60 left-right split and tab navigation"
```

---

### Task 7: Compile Verification & Changelog

- [ ] **Step 1: Compile check**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update ChangelogData.kt**

Add entry to `ChangelogData.kt`:
```kotlin
ChangelogEntry(
    version = "3.0.07",
    date = "2026-05-09",
    changes = listOf(
        "全部游戏界面适配横屏布局，统一弹窗尺寸和标题栏样式",
        "弟子详情界面重新设计：左右分栏+标签页切换(信息/属性/装备/功法)",
        "建筑材料界面迁移至横屏弹窗"
    )
),
```

- [ ] **Step 3: Update CHANGELOG.md**

Add version section at top:
```markdown
## [3.0.07] - 2026-05-09

### 全界面横屏适配
- 所有弹窗统一为横屏尺寸(85%宽×90%高)和标准化标题栏
- 弟子详情界面重新设计：左侧人物立绘+右侧标签页切换
- 建筑材料界面迁移至横屏弹窗
```

- [ ] **Step 4: Final commit**

```bash
git add CHANGELOG.md android/app/src/main/java/com/xianxia/sect/core/ChangelogData.kt
git commit -m "chore: update changelog for v3.0.07 all-screen landscape adaptation"
```

---

## Verification Checklist

1. `./gradlew.bat compileReleaseKotlin` passes
2. All 23 dialogs open with correct HalfScreenDialog dimensions
3. All headers follow standard pattern (title+CloseButton)
4. DiscipleDetailScreen shows 40/60 split with working tab navigation
5. All text uses `Color.Black`
6. No crashes
