# Landscape Orientation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the game from portrait to landscape orientation, restructure the main screen layout, and replace backgrounds with landscape-specific assets.

**Architecture:** Change orientation in manifest + code config. Replace `bg_screen` references with `bg_horizontal` across 25 files. Restructure `MainGameScreen` from tab-based vertical stack to full-map canvas with two floating button columns and dialog-based content for disciples/warehouse.

**Tech Stack:** Kotlin/Compose, Android (AGP 8.8.0, compileSdk 35)

---

### Task 1: Orientation Configuration

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml:36,56,69`
- Modify: `android/app/src/main/java/com/xianxia/sect/taptap/TapTapAuthManager.java:43`

- [ ] **Step 1: Change orientation in AndroidManifest.xml**

Change three locations:

Line 36 — `uses-feature`:
```xml
<uses-feature android:name="android.hardware.screen.landscape" android:required="true" />
```

Line 56 — MainActivity:
```xml
android:screenOrientation="landscape"
```

Line 69 — GameActivity:
```xml
android:screenOrientation="landscape"
```

- [ ] **Step 2: Change TapTap SDK orientation**

In `TapTapAuthManager.java:43`:
```java
options.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/xianxia/sect/taptap/TapTapAuthManager.java
git commit -m "feat: switch orientation from portrait to landscape"
```

---

### Task 2: Asset Replacement

**Files:**
- Replace: `android/app/src/main/res/drawable-nodpi/loading_background.png` ← `D:\模拟宗门美术素材\加载界面图（横屏）.png`
- Replace: `android/app/src/main/res/drawable-nodpi/bg_horizontal.jpg` ← `D:\模拟宗门美术素材\横向背景图.jpg`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/components/GameBackground.kt:21`
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/components/GameDialog.kt:144`

- [ ] **Step 1: Copy landscape loading background**

```bash
cp "D:/模拟宗门美术素材/加载界面图（横屏）.png" android/app/src/main/res/drawable-nodpi/loading_background.png
```

- [ ] **Step 2: Copy landscape horizontal background**

```bash
cp "D:/模拟宗门美术素材/横向背景图.jpg" android/app/src/main/res/drawable-nodpi/bg_horizontal.jpg
```

- [ ] **Step 3: Replace bg_screen → bg_horizontal in GameBackground.kt**

In `GameBackground.kt:21`, change:
```kotlin
painter = painterResource(id = R.drawable.bg_screen),
```
to:
```kotlin
painter = painterResource(id = R.drawable.bg_horizontal),
```

- [ ] **Step 4: Replace bg_screen → bg_horizontal in GameDialog.kt**

In `GameDialog.kt:144` (inside `HalfScreenDialog`), change:
```kotlin
painter = painterResource(id = R.drawable.bg_screen),
```
to:
```kotlin
painter = painterResource(id = R.drawable.bg_horizontal),
```

- [ ] **Step 5: Replace bg_screen → bg_horizontal in all other 23 files**

Run a single find-and-replace across all files in `android/app/src/main/java/com/xianxia/sect/ui/`:
Search: `R.drawable.bg_screen`
Replace: `R.drawable.bg_horizontal`

Files affected (from Phase 1 exploration):
- `MainGameScreen.kt` — line 619 (Settings dialog background)
- `DiscipleDetailScreen.kt`
- `InventoryScreen.kt`
- `HerbGardenScreen.kt`
- `LawEnforcementHallScreen.kt`
- `LibraryScreen.kt`
- `ForgeScreen.kt`
- `AlchemyScreen.kt`
- `TianshuHallScreen.kt`
- `MerchantScreen.kt`
- `RecruitScreen.kt`
- `SpiritMineScreen.kt`
- `ReflectionCliffScreen.kt`
- `MissionHallScreen.kt`
- `ProductionComponents.kt`
- `PeakScreenComponents.kt`
- `tabs/SettingsTab.kt`
- `tabs/WarehouseTab.kt`
- `dialogs/WorldMapDialogs.kt`
- `dialogs/BattleLogDialogs.kt`
- `dialogs/LevelDetailDialog.kt`
- `components/AllianceDialog.kt`
- `components/ElderBonusInfoButton.kt`

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/res/drawable-nodpi/loading_background.png android/app/src/main/res/drawable-nodpi/bg_horizontal.jpg
git add android/app/src/main/java/com/xianxia/sect/ui/
git commit -m "feat: replace landscape assets and switch all backgrounds to bg_horizontal"
```

---

### Task 3: Dialog Sizing for Landscape

**File:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/components/GameDialog.kt:118-124`

- [ ] **Step 1: Update DialogDefaults constants**

In `GameDialog.kt`, change:
```kotlin
object DialogDefaults {
    /** Width fraction for half-screen dialogs: leaves ~3.5% margin on each side */
    const val HalfScreenWidthFraction = 0.93f
    /** Height fraction for half-screen dialogs: reduced from 0.85f */
    const val HalfScreenHeightFraction = 0.78f
    /** Standard max height for scrollable CommonDialog-style wrappers */
    val CommonMaxHeight: Dp = 500.dp
    /** Standard corner radius for dialog boxes */
    val CornerRadius: Dp = 12.dp
}
```
to:
```kotlin
object DialogDefaults {
    /** Width fraction for half-screen dialogs: tighter in landscape */
    const val HalfScreenWidthFraction = 0.85f
    /** Height fraction for half-screen dialogs: use more vertical space in landscape */
    const val HalfScreenHeightFraction = 0.90f
    /** Standard max height for scrollable CommonDialog-style wrappers */
    val CommonMaxHeight: Dp = 280.dp
    /** Standard corner radius for dialog boxes */
    val CornerRadius: Dp = 12.dp
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/components/GameDialog.kt
git commit -m "feat: adjust dialog size constants for landscape orientation"
```

---

### Task 4: MainGameScreen Layout Restructure

**File:**
- Modify: `android/app/src/main/java/com/xianxia/sect/ui/game/MainGameScreen.kt`

This is the core change. The file will be restructured as:

```
Box(fillMaxSize)
├── SectMapLayer                    // full-screen map (unchanged)
├── Box(fillMaxSize)                // UI overlay
│   ├── SectInfoCard                // top-start, compact
│   ├── Left button column          // Align.CenterStart, 4 buttons
│   │   └── 世界, 招募, 商人, 外交
│   └── Right button column         // Align.CenterEnd, 5 buttons
│       └── 弟子, 建造, 仓库, 日志, 设置
├── BuildingConstructionBar         // Align.BottomCenter
├── DisciplesTabDialog              // full-screen dialog when 弟子 clicked
└── WarehouseTabDialog             // full-screen dialog when 仓库 clicked
```

- [ ] **Step 1: Delete `MainTab` enum and `selectedTab` state**

Remove line 101-103:
```kotlin
enum class MainTab {
    OVERVIEW, DISCIPLES, BUILDINGS, WAREHOUSE, SETTINGS
}
```

Remove line 192:
```kotlin
var selectedTab by remember { mutableStateOf(MainTab.OVERVIEW) }
```

- [ ] **Step 2: Add state for disciples/warehouse dialog visibility**

Add after the `buildingBarExpanded` state (around line 202):
```kotlin
var showDisciplesDialog by remember { mutableStateOf(false) }
var showWarehouseDialog by remember { mutableStateOf(false) }
var showSettingsDialog by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Replace the UI overlay Box content (lines 477-550)**

Replace the entire `when (selectedTab)` block inside the UI overlay Box with just the SectInfoCard (always visible):

```kotlin
// UI layer
Box(
    modifier = Modifier
        .fillMaxSize()
) {
    SectInfoCard(
        gameData = gameData,
        discipleCount = aliveDisciples.value.size,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp, top = 12.dp)
    )

    // Left column: 4 buttons
    Column(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FloatingActionButton(text = "世界", onClick = { viewModel.openWorldMapDialog() }, drawableRes = R.drawable.ui_map_button)
        FloatingActionButton(text = "招募", onClick = { viewModel.openRecruitDialog() }, drawableRes = R.drawable.ui_recruit_button)
        FloatingActionButton(text = "商人", onClick = { viewModel.openMerchantDialog() }, drawableRes = R.drawable.ui_merchant_button)
        FloatingActionButton(text = "外交", onClick = { viewModel.openDiplomacyDialog() }, drawableRes = R.drawable.ui_diplomacy_button)
    }

    // Right column: 5 buttons
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FloatingActionButton(text = "弟子", onClick = { showDisciplesDialog = true }, drawableRes = R.drawable.ui_team_button)
        FloatingActionButton(text = "建造", onClick = { buildingBarExpanded = !buildingBarExpanded }, drawableRes = R.drawable.ui_start_button)
        FloatingActionButton(text = "仓库", onClick = { showWarehouseDialog = true }, drawableRes = R.drawable.ui_secret_realm_button)
        FloatingActionButton(text = "日志", onClick = { viewModel.openBattleLogDialog() }, drawableRes = R.drawable.ui_log_button)
        FloatingActionButton(text = "设置", onClick = { showSettingsDialog = true }, drawableRes = R.drawable.ui_settings_button)
    }
}
```

- [ ] **Step 4: Delete the old BottomNavigationBar and FloatingActionRow**

Remove the entire `BottomNavigationBar` composable (lines 1536-1584).

Remove the old `FloatingActionRow` composable (lines 1015-1046).

- [ ] **Step 5: Replace old settings dialog with boolean-based full-screen dialog**

Remove the old settings dialog block (lines 609-635, the `if (selectedTab == MainTab.SETTINGS)` block). Add a new block in the full-screen dialogs area (same pattern as disciples/warehouse):

```kotlin
// Full-screen settings dialog
if (showSettingsDialog) {
    Dialog(onDismissRequest = { showSettingsDialog = false }) {
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
                SettingsTab(
                    viewModel = viewModel,
                    saveLoadViewModel = saveLoadViewModel,
                    onLogout = onLogout,
                    onDismiss = { showSettingsDialog = false },
                    limitAdTracking = limitAdTracking,
                    onLimitAdTrackingChanged = onLimitAdTrackingChanged
                )
            }
        }
    }
}
```

- [ ] **Step 6: Update BuildingConstructionBar modifier**

Remove the `BottomCenter` alignment and old padding since the bottom nav is gone. Change at ~line 578-581:
```kotlin
modifier = Modifier
    .align(Alignment.BottomCenter)
    .padding(bottom = 8.dp, start = 8.dp, end = 8.dp)
```

- [ ] **Step 7: Add DisciplesTab and WarehouseTab as full-screen dialogs**

After the `BuildingConstructionBar` block (before the existing dialog conditions), add:

```kotlin
// Full-screen disciples dialog
if (showDisciplesDialog) {
    Dialog(onDismissRequest = { showDisciplesDialog = false }) {
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
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "弟子",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        CloseButton(onClick = { showDisciplesDialog = false })
                    }
                    DisciplesTab(
                        gameData = gameData,
                        disciples = aliveDisciples.value,
                        equipment = equipment,
                        manuals = manuals,
                        manualStacks = manualStacks,
                        equipmentStacks = equipmentStacks,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

// Full-screen warehouse dialog
if (showWarehouseDialog) {
    Dialog(onDismissRequest = { showWarehouseDialog = false }) {
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
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "仓库",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        CloseButton(onClick = { showWarehouseDialog = false })
                    }
                    WarehouseTab(viewModel = viewModel)
                }
            }
        }
    }
}
```

- [ ] **Step 8: Add import for Surface and CloseButton if missing**

Ensure imports exist at top of file:
```kotlin
import androidx.compose.material3.Surface
import com.xianxia.sect.ui.components.CloseButton
```

(CloseButton is already imported at line 61, Surface likely needs to be added if not imported.)

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/xianxia/sect/ui/game/MainGameScreen.kt
git commit -m "feat: restructure main screen for landscape with side button columns"
```

---

### Task 5: Compile Verification

- [ ] **Step 1: Compile check**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

Expected: BUILD SUCCESSFUL. Fix any compile errors.

- [ ] **Step 2: Final commit (if fixes needed)**

```bash
git add -A
git commit -m "fix: compile errors from landscape restructure"
```

---

## Verification Checklist

1. `./gradlew.bat compileReleaseKotlin` passes
2. Launch app — landscape loading screen shows correctly
3. Main screen — full map, SectInfoCard at top, 9 buttons in two columns
4. Click 世界 → opens WorldMap dialog
5. Click 招募 → opens Recruit dialog
6. Click 商人 → opens Merchant dialog
7. Click 外交 → opens Diplomacy dialog
8. Click 弟子 → opens full-screen disciple list dialog
9. Click 建造 → toggles building bar at bottom
10. Click 仓库 → opens full-screen warehouse dialog
11. Click 日志 → opens battle log dialog
12. Click 设置 → opens settings dialog
13. No crash, all features functional
