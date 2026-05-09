# Landscape Orientation Redesign

## Context

Convert the entire game from portrait to landscape. All UIs must adapt. Replace loading background and global UI background with landscape-specific assets from `D:\模拟宗门美术素材`.

## Design Decisions

- **No more tabs.** Main screen shows the full-screen sect map with a top info card and floating buttons on both sides. Clicking disciple/warehouse opens a full-screen dialog; clicking build toggles the building bar.
- **Nav bar removed.** Its 3 remaining items (disciple, build, warehouse) become floating buttons alongside the 6 existing ones = 9 total.
- **Unified horizontal background.** All UI backgrounds use `bg_horizontal.jpg` instead of `bg_screen.png`.
- **All text stays `Color.Black`** per CLAUDE.md rule.

## File Changes

### 1. Orientation & Assets

| File | Change |
|------|--------|
| `AndroidManifest.xml` | `screenOrientation="portrait"` → `"landscape"` on both activities; update `uses-feature` |
| `TapTapAuthManager.java:43` | `SCREEN_ORIENTATION_PORTRAIT` → `SCREEN_ORIENTATION_LANDSCAPE` |
| `drawable-nodpi/loading_background.png` | Replace with `D:\模拟宗门美术素材\加载界面图（横屏）.png` |
| All `R.drawable.bg_screen` references | Replace with `R.drawable.bg_horizontal` |
| `drawable-nodpi/bg_navbar.jpg` | Remove reference (nav bar deleted) |

### 2. MainGameScreen.kt — Layout Restructure

```
Box(fillMaxSize)
├── SectMapLayer                    // full-screen map (unchanged)
├── SectInfoCard                    // top-center, compact horizontal bar
├── Left button column (4)          // Align.CenterStart
│   └── 世界, 招募, 商人, 外交
├── Right button column (5)         // Align.CenterEnd
│   └── 弟子, 建造, 仓库, 日志, 设置
└── BuildingConstructionBar         // Align.BottomCenter (toggled by build button)
```

**Delete:**
- `BottomNavigationBar` composable entirely
- `selectedTab` state variable
- `MainTab` enum-based tab switching
- Old `FloatingActionRow` layout

**Button behavior:**
- 弟子 → full-screen `DisciplesTab` dialog
- 仓库 → full-screen `WarehouseTab` dialog
- 建造 → toggle `BuildingConstructionBar`
- Other 6 → keep existing dialog behavior

### 3. Dialog Sizing (GameDialog.kt)

| Constant | Old | New |
|----------|-----|-----|
| `HalfScreenWidthFraction` | 0.93f | 0.85f |
| `HalfScreenHeightFraction` | 0.78f | 0.90f |
| `CommonMaxHeight` | 500.dp | 280.dp |

### 4. LoadingScreen.kt

Background switches from `R.drawable.loading_background` to the new landscape image (same resource ID, replaced file). ContentScale.Crop remains appropriate. No code change needed.

### 5. All Screens Using bg_screen

Replace `R.drawable.bg_screen` → `R.drawable.bg_horizontal` in:
- `GameBackground.kt`
- `GameDialog.kt`
- `SaveSelectScreen.kt`
- `PrivacyConsentScreen.kt`
- `DiscipleDetailScreen.kt`
- `InventoryScreen.kt`
- `HerbGardenScreen.kt`
- `LawEnforcementHallScreen.kt`
- `LibraryScreen.kt`
- All other dialog/screen composables using `bg_screen`

## Verification

1. `./gradlew.bat compileReleaseKotlin` — no compile errors
2. Launch game — landscape loading screen shows correctly
3. Main screen — full map, top info card, 9 buttons in two columns
4. Click each of 9 buttons — correct dialog/action triggers
5. Build button — building bar appears/disappears at bottom
6. All dialogs — content fully visible, not clipped
7. Game runs without crashes, all existing features work
