# UI 布局统一方案

## Context

项目有 30+ 个对话框和 11 种建筑/生产界面，布局代码积累了以下问题：
- 间距值从 1dp 到 32dp 随意散布，无统一规范
- 插槽尺寸有 48/50/55/60/65/70dp 六种不同值
- 字体大小有 13 种（7-22sp），Material3 Typography 形同虚设（全部 12sp）
- 4 种对话框包裹器混用，3 种背景 ContentScale 不一致
- 筛选器代码在 10+ 处重复
- 每个建筑文件独立定义相同模式的插槽组件

目标：统一间距/尺寸 + 简化布局结构，全面重构。

## Design Tokens

新建 `android/app/src/main/java/com/xianxia/sect/ui/theme/DesignTokens.kt`。

### 间距 (Spacing)
| Token | 值 | 用途 |
|-------|-----|------|
| XS | 4.dp | 元素内部微间距、标签与内容间距 |
| SM | 8.dp | 紧密元素间距、插槽间距 |
| MD | 12.dp | 默认间距、对话框内边距、头部内边距 |
| LG | 16.dp | 段落间距、区块间距 |
| XL | 20.dp | 大区块间距 |

### 插槽尺寸 (SlotSize)
| Token | 值 | 用途 |
|-------|-----|------|
| Tiny | 44.dp | 紧凑项（执法弟子） |
| Small | 52.dp | 标准项（亲传弟子、传道师） |
| Medium | 60.dp | 生产槽、峰长老、矿工位 |
| Large | 70.dp | 生产建筑长老位 |

### 字体层级 (AppTypography)
| Token | 大小 | 粗细 | 用途 |
|-------|------|------|------|
| Title | 16.sp | Bold | 对话框标题 |
| Subtitle | 13.sp | Bold | 区块标题 |
| Body | 11.sp | Normal | 正文、列表项 |
| Caption | 9.sp | Normal | 辅助信息、标签 |

重写 `Typography.kt`：将 14 个 Material3 样式填充为实际不同值，使 `MaterialTheme.typography` 可用。

### 圆角 (CornerRadius)
| Token | 值 | 用途 |
|-------|-----|------|
| SM | 6.dp | 小元素（插槽、标签） |
| MD | 8.dp | 卡片、区块背景 |
| LG | 12.dp | 对话框外框 |

### 边框
| Token | 值 | 用途 |
|-------|-----|------|
| Standard | 1.dp | 标准边框 |
| Accent | 2.dp | 高亮/选中边框 |

## Shared Components

### 1. UnifiedGameDialog (重写 GameDialog.kt)

替代现有的 4 种对话框包裹器：

| 模式 | 宽度 | 高度 | 场景 |
|------|------|------|------|
| Half | fillMaxWidth(0.85f) | fillMaxHeight(0.78f) | 建筑/生产界面（默认） |
| Full | fillMaxSize | fillMaxSize | 弟子/仓库/设置 |
| Auto | fillMaxWidth(0.85f) | wrapContentHeight | 简短确认/信息弹窗 |

三种模式共享：bg_horizontal 背景 (ContentScale.Crop)、12dp 圆角、统一头部结构。

### 2. UnifiedSlot (新建 UnifiedSlot.kt)

单一 `UnifiedSlot` 组件，通过 `size: SlotSize` 参数控制尺寸：
- `UnifiedSlot(size = Large)` → 70dp（生产建筑长老位）
- `UnifiedSlot(size = Medium)` → 60dp（峰长老、生产槽、矿工位）
- `UnifiedSlot(size = Small)` → 52dp（亲传弟子、传道师）
- `UnifiedSlot(size = Tiny)` → 44dp（执法弟子）

附带 `SlotRow` 和 `SlotGrid` 布局容器，统一间距 8dp。

### 3. DiscipleFilterBar (新建 DiscipleFilterBar.kt)

合并 `SpiritRootAttributeFilterBar` + `RealmFilterRow` + 各处的 `applyFilters` 逻辑。
参数化控制：
- `realmSet: List<Realm>` — 包含哪些境界
- `showSpiritRootFilter: Boolean` — 是否显示灵根筛选
- `showAttributeSort: Boolean` — 是否显示属性排序
- `spiritRootCounts: Map<String, Int>` — 灵根计数
- `realmCounts: Map<String, Int>` — 境界计数

消除 ProductionComponents、PeakScreenComponents、LibraryScreen、SpiritMineScreen、AllianceScreen、MerchantScreen、RecruitScreen 中的重复。

### 4. EmptyState (新建 EmptyState.kt)

```kotlin
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier)
```
替换各处内联的 "暂无XXX" Box 写法：11sp 黑色文字、120dp 高度、居中。

## Migration Steps

### Step 1: 基础设施
新建 DesignTokens.kt、UnifiedSlot.kt、DiscipleFilterBar.kt、EmptyState.kt
重写 Typography.kt、GameDialog.kt
→ 验证: `./gradlew.bat compileReleaseKotlin` 通过

### Step 2: 生产建筑迁移
AlchemyScreen、ForgeScreen、HerbGardenScreen、SpiritMineScreen、LibraryScreen
→ 验证: 编译通过

### Step 3: 宗门建筑迁移
WenDaoPeakScreen、QingyunPeakScreen、LawEnforcementHallScreen、TianshuHallScreen、MissionHallScreen、ReflectionCliffScreen
→ 验证: 编译通过

### Step 4: 功能界面迁移 + 清理
RecruitScreen、MerchantScreen、InventoryScreen、SalaryConfigScreen、BuildingsTab、DisciplesTab、WarehouseTab
清理 ProductionComponents.kt 和 PeakScreenComponents.kt 中的重复代码
→ 验证: 编译通过

### Step 5: 更新 Changelog
更新 CHANGELOG.md 和 ChangelogData.kt

## Art Asset Constraints

游戏使用 `D:\模拟宗门美术素材\` 中的预渲染美术素材，重构时必须保持兼容：

| 素材 | 对应资源 | 约束 |
|------|---------|------|
| 横向背景图.jpg | `R.drawable.bg_horizontal` | 所有对话框背景，统一使用 `ContentScale.Crop` |
| 按钮（长方形）.png | `R.drawable.ui_button` | 按钮背景图，`GameButton` 使用 `ContentScale.FillBounds`，不改变按钮图片 |
| 关闭按钮（圆形）.png | `R.drawable.ui_close_button` | 固定 24dp 圆形，不可改变尺寸 |
| 各建筑 .png | `R.drawable.building_*` | 建筑卡片图标，保持现有比例 |
| 各圆形按钮 .png | `R.drawable.ui_map/team/recruit/...` | 主界面侧边按钮，保持现有 56dp 圆形 |
| 宗门地图.png | `R.drawable.sect_ground_map` | 主界面地图背景，不受此次重构影响 |
| 弟子人物素材.png | `R.drawable.disciple_portrait` | 弟子详情头像，不受影响 |
| 妖兽/洞府素材 | `R.drawable.*_beast`, `R.drawable.cave_*` | 战斗/探索界面，不受影响 |

**核心原则**：只改布局代码（间距、尺寸、组件结构），不改素材资源。`GameButton` 保持使用现有 `ui_button` 图片。

## Verification

每步之后运行 `./gradlew.bat compileReleaseKotlin` 确保无编译错误。
全部完成后运行 `./gradlew.bat test` 确保现有测试通过。
