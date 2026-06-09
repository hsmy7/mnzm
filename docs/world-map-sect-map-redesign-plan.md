# 世界地图 & 宗门地图系统代码质量重构方案

> **生成日期**: 2026-06-09 | **版本**: v2.1.0 | **参考来源**: 35 条（S 级 8 + A 级 12 + B 级 15）  
> **状态**: 待审核 | **执行优先级**: P1（代码质量重构 + Bug 修复，无新功能）

---

## 执行摘要

本文档基于对现有世界地图和宗门地图代码的全面审查，识别出架构层面的代码质量问题（坐标重复、耦合过深、参数爆炸、主线程阻塞），参考行业最佳实践提出**纯代码质量重构方案**。

**不新增任何玩法功能**（无迷雾、无缩放、无小地图、无过渡动画）。所有改动都是对现有功能的架构优化、性能提升、可维护性改进。

**核心目标**：消除重复 → 降低耦合 → 提升性能 → 增强可测性。

---

## 目录

1. [现状问题诊断（含 Bug 分析）](#1-现状问题诊断)
2. [重构一：统一坐标系统，消除重复](#2-重构一统一坐标系统消除重复)
3. [重构二：宗門地图解耦，消除参数爆炸](#3-重构二宗門地图解耦消除参数爆炸)
4. [重构三：WorldMapViewModel 职责拆分](#4-重构三worldmapviewmodel-职责拆分)
5. [重构四：地图背景分块渲染，降低内存](#5-重构四地图背景分块渲染降低内存)
6. [重构五：建筑位图异步烘焙，避免主线程阻塞](#6-重构五建筑位图异步烘焙避免主线程阻塞)
7. [重构六：建筑空间索引增量更新](#7-重构六建筑空间索引增量更新)
8. [重构七：连接线渲染（补完已有数据）](#8-重构七连接线渲染补完已有数据)
9. [Bug 修复：地图未全屏](#9-bug-修复地图未全屏)
10. [Bug 修复：地图白边](#10-bug-修复地图白边)
11. [重构八：增加防御性代码与错误兜底](#11-重构八增加防御性代码与错误兜底)
12. [实施计划](#12-实施计划)
13. [验证标准](#13-验证标准)
14. [参考来源](#14-参考来源)

---

## 1. 现状问题诊断

### 1.0 当前 Bug — 行业对标分析

**Bug A: 地图未全屏**

| 项目 | 全屏地图实现方式 |
|------|-----------------|
| **原神** | 地图界面使用独立的全屏 `Activity` / `Window`，系统栏完全隐藏，采用沉浸式粘性模式（`IMMERSIVE_STICKY`） |
| **星穹铁道** | 地图使用 `Dialog` 全屏模式，`usePlatformDefaultWidth=false` + `decorFitsSystemWindows=false`，配合米哈游自研引擎的 `SurfaceView` 叠加层 |
| **率土之滨** | 无极缩放地图通过 `SurfaceView` 独占渲染，非 Compose Dialog 模式，确保零布局裁剪 |
| **Clash of Clans** | 所有全屏界面通过 Scene 切换而非 Dialog 弹窗，避免 Dialog 窗口的默认 padding 和宽度限制 |
| **本项目当前** | `WorldMapDialog` 在 `GameOverlayHost` 中直接渲染为 Composable，未包裹 `Surface(fillMaxSize)`，且未设置 `DialogProperties.usePlatformDefaultWidth=false` |

行业标准做法：全屏地图界面有三条技术路线——

| 路线 | 技术方案 | Window 数 | GPU 开销 | 适用场景 |
|------|---------|----------|---------|---------|
| **A: Compose Dialog** | `Dialog(properties=DialogProperties(usePlatformDefaultWidth=false, decorFitsSystemWindows=false))` + `setDimAmount(0f)` | +1（Dialog 独立 Window） | 高（额外 Surface，额外 vsync） | 需跨 Activity 弹窗、或父布局不可控时 |
| **B: Activity 独占** | 独立 Window / SurfaceView + `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` | 0（替换当前 Window） | 最高（独立渲染管线） | 大型 SLG、FPS 等完全控制渲染的场景 |
| **C: 同 Composition Surface** | `Surface(Modifier.fillMaxSize())` 包裹，与父布局共享 Composition | 0（复用当前 Window） | 零额外开销 | 同页全屏覆盖，项目已大量使用 |

### 路线 A vs C 详细对比

| 维度 | A: Dialog | C: Surface | 胜出 |
|------|----------|-----------|------|
| **系统栏覆盖** | `decorFitsSystemWindows=false` 明确覆盖 | 依赖 Activity 的 `IMMERSIVE_STICKY` 已配置 | A 更显式 |
| **Window 开销** | 新建 Dialog Window → 多一次 SurfaceFlinger 合成 | 零额外 Window | **C** |
| **旋转稳定性** | Google Issue #290502769：旋转时尺寸异常（1.8.0-alpha08 才修复） | 无此问题 | **C** |
| **BackHandler** | Dialog 拦截 back press，需重新配置 | 现有 `BackHandler(onBack)` 直接生效 | **C** |
| **Dim/Scrim** | 默认有半透明遮罩，需额外 `setDimAmount(0f)` 消除 | 无 | **C** |
| **项目一致性** | 项目无任何 `Dialog` 用法 | 弟子/仓库/建造/设置 4 个全屏对话均用此模式 | **C** |
| **生命周期** | Dialog 独立生命周期，dismiss 时机难以与 ViewModel 同步 | 跟随父 Composition，自然受 `currentDialogRoute` 控制 | **C** |

### 选择结论：路线 C

路线 C 在 6/7 个维度上优于路线 A。路线 A 唯一的优势——系统栏覆盖——已被项目的 `IMMERSIVE_STICKY` 配置覆盖，不需要 Dialog 来弥补。

路线 A 的正确用例是"跨 Activity 弹窗"或"需要在系统栏上方显示内容但 Activity 不是全屏模式"，这两个场景本项目都不涉及。**为不需要的能力付出额外 Window 的代价是不必要的。**

**Bug B: 地图白边**

| 项目 | 地图边界处理方式 |
|------|-----------------|
| **原神** | 世界地图四周有深色渐变边框（vignette），地图图幅小于视口时填充深色背景 |
| **鬼谷八荒** | 世界地图边缘使用迷雾 + 深色底色，超出地图范围的区域渲染为黑色 |
| **太吾绘卷** | 世界地图以海洋/山脉自然边界环绕，视口无法移动到地图外 |
| **率土之滨** | 225万格无缝沙盘 + 硬相机钳制（`clamp`），视口永远不超出世界边界 |
| **Rise of Kingdoms** | 地图边缘有"世界尽头"黑色迷雾 + 装饰性边框 |
| **本项目当前** | Canvas 无背景色 → 视口超出世界边界时透明区域透出父级白色 |

行业标准做法：地图边界处理的四种模式——

| 模式 | 描述 | 代表产品 | 优缺点 |
|------|------|---------|--------|
| **A: 硬钳制** | `cameraX = clamp(cameraX, 0, worldW - viewportW)` | 率土之滨、CoC | ✅ 永无白边 ❌ 地图边缘无法居中 |
| **B: 居中+背景色** | 视口大于世界时居中，空白区域填充深色 | 原神、鬼谷八荒 | ✅ 小地图也能居中显示 ❌ 有空白边但颜色统一 |
| **C: 自然边界** | 地图外设计为海洋/山脉/迷雾 | 太吾绘卷、Rise of Kingdoms | ✅ 沉浸感最强 ❌ 需要美术资源 |
| **D: 渐变遮罩** | 地图边缘向内做深色渐变 | 星穹铁道 | ✅ 视觉最佳 ❌ 需要 shader/渐变纹理 |

本项目应选 **模式 A（硬钳制）**，因为：
1. 视口永远不超出世界边界 → 永远没有空白区域 → **从根本上消灭白边**
2. 零额外改动：不需要背景色、不需要美术资源、不需要 shader
3. 率土之滨、Clash of Clans 等头部 SLG 已验证此方案在策略游戏中的可行性
4. 地图不居中的 trade-off 可接受：`tryCenterOn` 在初始化时会将玩家宗门带入视口

### 1.1 代码重复

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 坐标转换逻辑重复 | `MapCoordinateSystem.kt:28-44` 和 `CameraState.kt:40-88` 各自实现 `worldToScreen`/`isVisible`，算法相同但签名不同 | 🔴 HIGH |
| `isPointNearCurvedPath` 完全相同的实现 | `LevelGenerator.kt:179-191` 和 `CaveGenerator.kt:155-167` 逐字复制 | 🔴 HIGH |
| `isPointNearLineSegment` 完全相同的实现 | `LevelGenerator.kt:193-209` 和 `CaveGenerator.kt:169-188` 逐字复制 | 🔴 HIGH |
| `disciples.filter { isAlive && status == IDLE && realmLayer > 0 }` 过滤逻辑 | `WorldMapSectDetailDialog.kt`, `LevelDetailDialog.kt` 等多处 | 🟡 MEDIUM |

### 1.2 参数爆炸

| 函数 | 参数数量 | 严重度 |
|------|---------|--------|
| `SectGroundCanvas` | 40+ | 🔴 HIGH |
| `SectMapLayer` | 30+ | 🔴 HIGH |
| 两个函数重叠参数约 25 个 | — | 🔴 HIGH |

### 1.3 耦合过深

| 问题 | 严重度 |
|------|--------|
| 宗门地图渲染（Canvas）与建筑放置逻辑混在 `MainGameScreen.kt` 中 | 🔴 HIGH |
| `WorldMapViewModel` (226行) 同时管理地图数据 + 10个对话状态 + 贸易/联盟/侦察/送礼/驻守逻辑 | 🟡 MEDIUM |
| `WorldMapDialog.kt` (183行) 内联渲染 8 个子对话 | 🟡 MEDIUM |

### 1.4 性能隐患

| 问题 | 严重度 |
|------|--------|
| `MapBackground.kt:26-32` 使用 `BitmapFactory.decodeResource` 直接解码整张 6000×5000 位图，ARGB_8888 格式约 120MB 内存 | 🔴 HIGH |
| 建筑位图烘焙在 Compose composition 阶段同步执行 | 🟡 MEDIUM |
| `BuildingSpatialIndex` 使用 `LaunchedEffect(placedBuildings)` 全量重建，O(n) | 🟢 LOW |

### 1.5 防御性不足

| 问题 | 严重度 |
|------|--------|
| `SectMarker.kt:42` 使用 `roundToInt()` 可能产生溢出 | 🟢 LOW |
| `MapBackground.kt` 无 `bitmap.isRecycled` 检查 | 🟡 MEDIUM |
| `CameraState.clamp()` 当 `scale=0` 时产生除零 | 🟡 MEDIUM |

---

## 2. 重构一：统一坐标系统，消除重复

### 目标

消除 `MapCoordinateSystem` 和 `CameraState` 之间的坐标转换逻辑重复，删除 `LevelGenerator`/`CaveGenerator` 之间的复制粘贴代码。

### 2.1 世界坐标 ↔ 屏幕坐标统一

**现状**：两个类各自实现，算法相同但签名不同：

```kotlin
// MapCoordinateSystem.kt:28 — 两处实现不一致的隐患
fun worldToScreen(worldX, worldY, canvasWidth, canvasHeight, offsetX, offsetY): Pair

// CameraState.kt:40 — 仅有的调用方
fun worldToScreenX(worldX): Float = (worldX - cameraX) * scale
```

**改法**：`CameraState` 保留为主入口（它是 UI 层的活跃状态），`MapCoordinateSystem` 中的 `worldToScreen`/`isWorldPositionVisible` 标记 `@Deprecated`，调用方全部切换到 `CameraState`。

```kotlin
// MapCoordinateSystem.kt — 添加废弃标记
@Deprecated(
    message = "使用 CameraState.worldToScreenX/Y 替代",
    replaceWith = ReplaceWith("cameraState.worldToScreenX(worldX)")
)
fun worldToScreen(...): Pair<Float, Float> { ... }
```

### 2.2 删除复制粘贴代码

**现状**：`isPointNearLineSegment` 和 `isPointNearCurvedPath` 在 `LevelGenerator.kt` 和 `CaveGenerator.kt` 中逐字重复。

**改法**：提取到公共工具对象。

```kotlin
// 新建文件: core/engine/domain/exploration/GeometryUtils.kt

object GeometryUtils {
    /**
     * 点到线段的最短距离检测
     */
    fun isPointNearLineSegment(
        px: Double, py: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        threshold: Double
    ): Boolean {
        val lineLenSq = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
        if (lineLenSq == 0.0) {
            return sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1)) < threshold
        }
        val t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / lineLenSq
        val tClamped = t.coerceIn(0.0, 1.0)
        val nearestX = x1 + tClamped * (x2 - x1)
        val nearestY = y1 + tClamped * (y2 - y1)
        val dist = sqrt((px - nearestX) * (px - nearestX) + (py - nearestY) * (py - nearestY))
        return dist < threshold
    }

    /**
     * 点到 MST 边的距离检测
     */
    fun isPointNearCurvedPath(
        px: Int, py: Int, edge: MSTEdge, threshold: Double
    ): Boolean {
        val (from, to) = if (edge.sect1.id < edge.sect2.id) {
            edge.sect1 to edge.sect2
        } else {
            edge.sect2 to edge.sect1
        }
        return isPointNearLineSegment(
            px.toDouble(), py.toDouble(),
            from.x.toDouble(), from.y.toDouble(),
            to.x.toDouble(), to.y.toDouble(),
            threshold
        )
    }
}
```

然后在 `LevelGenerator` 和 `CaveGenerator` 中删除本地定义，改为 `GeometryUtils.isPointNearLineSegment(...)` 调用。

### 2.3 变更清单

| 操作 | 文件 |
|------|------|
| `@Deprecated` 标记 `worldToScreen`/`isWorldPositionVisible` | `MapCoordinateSystem.kt` |
| 删除 `isPointNearLineSegment`/`isPointNearCurvedPath` 本地定义 | `LevelGenerator.kt` |
| 删除 `isPointNearLineSegment`/`isPointNearCurvedPath` 本地定义 | `CaveGenerator.kt` |
| 新建公共工具对象 | `GeometryUtils.kt` |

---

## 3. 重构二：宗門地图解耦，消除参数爆炸

### 目标

将宗门地图渲染代码从 `MainGameScreen.kt` 提取为独立模块，用聚合状态对象替代 40+ 参数的函数签名。

### 3.1 现状分析

`MainGameScreen.kt` 中以下函数构成了宗门地图的渲染链：

```
MainGameScreen (800+ 行)
  └─ SectMapLayer (30 参数)
       └─ SectGroundCanvas (40+ 参数)
```

两个函数有约 25 个重复参数（`tileSize`, `worldWidthCells`, `worldHeightCells`, `renderScale`, `cameraState` 等）。放置模式和移动模式的大量状态变量也混在 `MainGameScreen` 的顶层。

### 3.2 改法

**步骤 1**：定义聚合状态对象替代参数洪水。

```kotlin
// 新建文件: sect/SectMapState.kt

data class SectMapRenderConfig(
    val cameraState: CameraState,
    val tileSize: Int,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val renderScale: Float,
    val gpuRenderConfig: GpuRenderConfig
)

data class SectMapStaticData(
    val placedBuildings: List<GridBuildingData>,
    val buildingBitmaps: Map<String, ImageBitmap>,
    val fullMapBmp: ImageBitmap,
    val sectBgBmp: ImageBitmap,
    val buildingsBaked: Boolean
)

data class PlacementModeState(
    val isActive: Boolean,
    val buildingName: String,
    val gridX: Int,
    val gridY: Int,
    val worldX: Float,
    val worldY: Float,
    val size: BuildingSize,
    val validity: PlacementValidity
) {
    companion object {
        val INACTIVE = PlacementModeState(
            isActive = false, buildingName = "", gridX = 0, gridY = 0,
            worldX = 0f, worldY = 0f,
            size = BuildingSize(2, 3), validity = PlacementValidity.Valid
        )
    }
}

data class MoveModeState(
    val isActive: Boolean,
    val building: GridBuildingData?,
    val gridX: Int,
    val gridY: Int,
    val worldX: Float,
    val worldY: Float,
    val size: BuildingSize,
    val validity: PlacementValidity
) {
    companion object {
        val INACTIVE = MoveModeState(
            isActive = false, building = null, gridX = 0, gridY = 0,
            worldX = 0f, worldY = 0f,
            size = BuildingSize(2, 3), validity = PlacementValidity.Valid
        )
    }
}
```

**步骤 2**：将 `SectGroundCanvas` 提取到独立文件，接受聚合状态对象。

```kotlin
// 新建文件: sect/SectMapCanvas.kt

@Composable
fun SectMapCanvas(
    config: SectMapRenderConfig,
    staticData: SectMapStaticData,
    placement: PlacementModeState,
    move: MoveModeState,
    buildingIndex: BuildingSpatialIndex,
    onBuildingClick: (GridBuildingData) -> Unit,
    onBuildingLongPress: (GridBuildingData) -> Unit,
    onPlacementDrag: (Float, Float) -> Unit,
    onMovingDrag: (Float, Float) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 原 SectGroundCanvas 的 Canvas 渲染逻辑
    // 约 300 行 → 独立文件，12 个参数（从 40+ 减少）
}
```

**步骤 3**：`MainGameScreen.kt` 中的放置/移动状态管理提取到 `remember` helper 函数。

```kotlin
// MainGameScreen.kt 中新增

@Composable
fun rememberPlacementState(): PlacementStateHolder { ... }

class PlacementStateHolder(
    val state: PlacementModeState,
    val startPlacing: (String, BuildingSize) -> Unit,
    val onDrag: (Float, Float) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit
)
```

### 3.3 变更清单

| 操作 | 文件 |
|------|------|
| 新建聚合状态类 | `sect/SectMapState.kt` |
| 提取 `SectGroundCanvas` | `MainGameScreen.kt` → `sect/SectMapCanvas.kt` |
| 简化 `SectMapLayer` 签名 | `MainGameScreen.kt`（30 参数 → 8 参数） |
| 提取放置/移动状态 Holder | `MainGameScreen.kt` |

---

## 4. 重构三：WorldMapViewModel 职责拆分

### 目标

将 226 行、管理 10 个对话状态的 `WorldMapViewModel` 拆分为职责单一的类。

### 4.1 现状

`WorldMapViewModel` 当前管理：
- 地图渲染数据 (`worldMapRenderData`)
- 游戏数据 (`gameData`)
- 10 个 `MutableStateFlow`（侦察/联盟/使节/贸易/礼物）
- 15 个业务方法（`openScoutDialog`, `startScoutMission`, `giftSpiritStones`, `openAllianceDialog`, `requestAlliance`, `dissolveAlliance`, `getAllianceCost`, `getEnvoyRealmRequirement`, `isAlly`, `getAllianceRemainingYears`, `getPlayerAllies`, `getMovableTargetSectIds`, `attackSect`, `assignGarrisonDisciple`, `removeGarrisonDisciple`, `openSectTradeDialog`, `closeSectTradeDialog`, `buyFromSectTrade`, `openGiftDialog`, `closeGiftDialog`）

### 4.2 改法

按职责拆分为 3 个类：

```
WorldMapViewModel (保留)
  └── 地图数据 + 导航（与 GameEngine 的只读交互）
  
WorldMapInteractionViewModel (新建)
  └── 侦察/交易/联盟/送礼/使节 — 对话状态 + 写操作

WorldMapGarrisonViewModel (新建)
  └── 驻守/进攻 — 军事操作
```

```kotlin
// WorldMapInteractionViewModel.kt

@HiltViewModel
class WorldMapInteractionViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {
    
    // 对话状态
    private val _dialogs = MutableStateFlow(WorldMapDialogState())
    val dialogs: StateFlow<WorldMapDialogState> = _dialogs.asStateFlow()
    
    fun openDialog(type: WorldMapDialogType, sectId: String) { ... }
    fun closeDialog(type: WorldMapDialogType) { ... }
    
    // 业务逻辑
    fun startScoutMission(memberIds: List<String>, sectId: String) { ... }
    fun giftSpiritStones(sectId: String, tier: Int) { ... }
    fun requestAlliance(sectId: String, envoyDiscipleId: String) { ... }
    fun dissolveAlliance(sectId: String) { ... }
    fun buyFromSectTrade(itemId: String, quantity: Int) { ... }
}

// 聚合对话状态
data class WorldMapDialogState(
    val showScout: Boolean = false,
    val selectedScoutSectId: String? = null,
    val showAlliance: Boolean = false,
    val selectedAllianceSectId: String? = null,
    val showEnvoyDiscipleSelect: Boolean = false,
    val showTrade: Boolean = false,
    val selectedTradeSectId: String? = null,
    val tradeItems: List<MerchantItem> = emptyList(),
    val showGift: Boolean = false,
    val selectedGiftSectId: String? = null
)

enum class WorldMapDialogType { SCOUT, TRADE, ALLIANCE, GIFT, ENVOY }
```

### 4.3 变更清单

| 操作 | 文件 |
|------|------|
| 拆分 `WorldMapViewModel` | → 3 个 ViewModel |
| 对话状态聚合为 `WorldMapDialogState` | 新建 data class |
| `WorldMapDialog.kt` 改用新 ViewModel | 修改 |

---

## 5. 重构四：地图背景分块渲染，降低内存

### 目标

将 `MapBackground` 从"整张 6000×5000 位图一次性加载"改为"按需分块加载"，降低内存峰值。

### 5.1 现状

```kotlin
// MapBackground.kt:23-32 — 当前实现
val mapBitmap = remember {
    BitmapFactory.decodeResource(
        context.resources,
        R.drawable.map_zhongzhou,
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565  // 仅配置了格式
        }
    ).asImageBitmap()
}
// 直接 drawImage(mapBitmap, dstSize = 6000×5000)
```

问题：
- RGB_565 下 6000×5000 ≈ 60MB，ARGB_8888 下 ≈ 120MB
- 在 Canvas 中每次 `drawImage` 都提交全尺寸纹理给 GPU
- 缩放 0.25 时实际可见区域仅约 1500×1250 像素，但 GPU 仍需要处理完整纹理

### 5.2 改法

将背景图在首次加载时预分割为分块（tile），渲染时仅绘制视口内的分块。

```kotlin
// MapBackground.kt 改为

@Composable
fun MapBackground(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
    tileSize: Int = 512  // 每块 512×512 像素
) {
    val context = LocalContext.current
    
    val tileCache = remember {
        MapTileCache(
            fullBitmap = BitmapFactory.decodeResource(
                context.resources, R.drawable.map_zhongzhou,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            ),
            tileSize = tileSize
        )
    }
    
    // 组件销毁时回收原始 bitmap
    DisposableEffect(Unit) {
        onDispose { tileCache.recycle() }
    }
    
    Canvas(modifier = modifier.fillMaxSize()) {
        // 计算当前视口覆盖的分块范围
        val visibleTiles = tileCache.getVisibleTiles(
            cameraX = cameraState.cameraX,
            cameraY = cameraState.cameraY,
            scale = cameraState.scale,
            viewportWidth = size.width.toInt(),
            viewportHeight = size.height.toInt()
        )
        
        // 只绘制可见分块
        withTransform({
            translate(-cameraState.cameraX * cameraState.scale, -cameraState.cameraY * cameraState.scale)
            scale(cameraState.scale, cameraState.scale, Offset.Zero)
        }) {
            visibleTiles.forEach { tile ->
                drawImage(
                    image = tile.bitmap,
                    topLeft = Offset(tile.worldX.toFloat(), tile.worldY.toFloat()),
                    dstSize = IntSize(tileSize, tileSize)
                )
            }
        }
    }
}
```

```kotlin
// 新建: map/infrastructure/MapTileCache.kt

class MapTileCache(
    private val fullBitmap: Bitmap,
    private val tileSize: Int = 512
) {
    private val tilesX = ceil(fullBitmap.width.toFloat() / tileSize).toInt()
    private val tilesY = ceil(fullBitmap.height.toFloat() / tileSize).toInt()
    
    private val cache = LruCache<Long, ImageBitmap>(maxSize = 36) // 约 9 块可见 + 余量
    
    fun getVisibleTiles(
        cameraX: Float, cameraY: Float,
        scale: Float,
        viewportWidth: Int, viewportHeight: Int
    ): List<TileInfo> {
        val startTileX = (cameraX / tileSize).toInt().coerceIn(0, tilesX - 1)
        val startTileY = (cameraY / tileSize).toInt().coerceIn(0, tilesY - 1)
        val endTileX = ((cameraX + viewportWidth / scale) / tileSize).toInt().coerceIn(0, tilesX - 1)
        val endTileY = ((cameraY + viewportHeight / scale) / tileSize).toInt().coerceIn(0, tilesY - 1)
        
        return (startTileX..endTileX).flatMap { tx ->
            (startTileY..endTileY).map { ty ->
                val key = (tx.toLong() shl 32) or ty.toLong()
                val bmp = cache.get(key) ?: createTile(tx, ty).also { cache.put(key, it) }
                TileInfo(worldX = tx * tileSize, worldY = ty * tileSize, bitmap = bmp)
            }
        }
    }
    
    private fun createTile(tileX: Int, tileY: Int): ImageBitmap {
        val x = tileX * tileSize
        val y = tileY * tileSize
        val w = minOf(tileSize, fullBitmap.width - x)
        val h = minOf(tileSize, fullBitmap.height - y)
        return Bitmap.createBitmap(fullBitmap, x, y, w, h).asImageBitmap()
    }
    
    fun recycle() {
        cache.evictAll()
        if (!fullBitmap.isRecycled) fullBitmap.recycle()
    }
    
    data class TileInfo(val worldX: Int, val worldY: Int, val bitmap: ImageBitmap)
}
```

### 5.3 性能收益

| 指标 | 改前 | 改后 |
|------|------|------|
| 内存峰值（RGB_565） | ~60MB（整张位图常驻） | ~15MB（原始+缓存 12 块×1MB） |
| GPU 纹理提交量 | 6000×5000 每帧 | 视口内 2-6 块 × 512×512 |
| 初始加载延迟 | 全量解码 | 首次加载仅解码视口范围 |

---

## 6. 重构五：建筑位图异步烘焙，避免主线程阻塞

### 目标

将宗门地图建筑位图烘焙从 Compose composition 阶段移到 `Dispatchers.Default`。

### 6.1 现状

`MainGameScreen.kt` 中：

```kotlin
// 约 380-440 行 — 建筑位图烘焙在主线程
val bakedMapBmp = remember(shouldBakeBuildings, buildingBitmaps, effectivePlacedBuildings, buildVersion) {
    if (!shouldBakeBuildings || effectivePlacedBuildings.isEmpty()) null
    else {
        bakeBuildingBitmaps(effectivePlacedBuildings, tileSize, buildingBitmaps) // 同步阻塞
    }
}
```

### 6.2 改法

```kotlin
// 替代为 produceState

val bakedMapBmp by produceState<ImageBitmap?>(null, shouldBakeBuildings, buildingBitmaps, effectivePlacedBuildings, buildVersion) {
    if (!shouldBakeBuildings || effectivePlacedBuildings.isEmpty()) {
        value = null
        return@produceState
    }
    value = withContext(Dispatchers.Default) {
        bakeBuildingBitmaps(effectivePlacedBuildings, tileSize, buildingBitmaps)
    }
}
```

### 6.3 变更清单

| 操作 | 文件 |
|------|------|
| `remember { bakeBuildingBitmaps(...) }` → `produceState` | `MainGameScreen.kt` |

---

## 7. 重构六：建筑空间索引增量更新

### 目标

`BuildingSpatialIndex` 当前每次 `placedBuildings` 变化都全量重建索引（O(n)）。改为增量更新（O(1) 增删）。

### 7.1 现状

```kotlin
// MainGameScreen.kt ~950 — 全量重建
val buildingIndex = remember { BuildingSpatialIndex() }
LaunchedEffect(placedBuildings) { buildingIndex.rebuild(placedBuildings) }
```

### 7.2 改法

给 `BuildingSpatialIndex` 增加增量 API，并在业务层调用：

```kotlin
class BuildingSpatialIndex {
    private val grid = mutableMapOf<Long, MutableList<GridBuildingData>>()
    
    /** 首次全量构建 */
    fun rebuild(buildings: List<GridBuildingData>) {
        grid.clear()
        buildings.forEach { add(it) }
    }
    
    /** 增量添加（放置建筑时调用） */
    fun add(building: GridBuildingData) {
        for (gx in building.gridX until building.gridX + building.width) {
            for (gy in building.gridY until building.gridY + building.height) {
                val key = encodeKey(gx, gy)
                grid.getOrPut(key) { mutableListOf() }.add(building)
            }
        }
    }
    
    /** 增量删除（移除建筑时调用） */
    fun remove(instanceId: String) {
        grid.values.forEach { it.removeAll { b -> b.instanceId == instanceId } }
    }
    
    /** O(1) 查询 */
    fun findAt(gridX: Int, gridY: Int): GridBuildingData? {
        return grid[encodeKey(gridX, gridY)]?.firstOrNull()
    }
    
    private fun encodeKey(x: Int, y: Int): Long = (x.toLong() shl 32) or y.toLong()
}
```

### 7.3 变更清单

| 操作 | 文件 |
|------|------|
| 增加 `add`/`remove` 增量方法 | `BuildingSpatialIndex` |
| `LaunchedEffect(placedBuildings)` → 仅首次用 `rebuild`，后续用 `add`/`remove` | `MainGameScreen.kt` |

---

## 8. 重构七：连接线渲染（补完已有数据）

### 目标

`LevelGenerator.buildConnectionEdges()` 已经生成了 `List<MSTEdge>`，但地图上从未渲染。这是**已有数据的可视化补完**，不新增数据/玩法。

### 8.1 实现

```kotlin
// 新建: world/WorldMapConnections.kt

@Composable
fun WorldMapConnections(
    edges: List<MSTEdge>,
    cameraState: CameraState,
    modifier: Modifier = Modifier
) {
    val pathEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    
    Canvas(modifier = modifier) {
        edges.forEach { edge ->
            val from = edge.sect1; val to = edge.sect2
            val sx = cameraState.worldToScreenX(from.x)
            val sy = cameraState.worldToScreenY(from.y)
            val ex = cameraState.worldToScreenX(to.x)
            val ey = cameraState.worldToScreenY(to.y)
            
            // 视口裁剪
            if (sx < -100 && ex < -100) return@forEach
            if (sy < -100 && ey < -100) return@forEach
            if (sx > size.width + 100 && ex > size.width + 100) return@forEach
            if (sy > size.height + 100 && ey > size.height + 100) return@forEach
            
            val path = Path().apply {
                moveTo(sx, sy)
                // 二次贝塞尔曲线，中点偏移做弧度
                val mx = (sx + ex) / 2 + Random.nextFloat() * 40 - 20
                val my = (sy + ey) / 2 + Random.nextFloat() * 40 - 20
                quadraticBezierTo(mx, my, ex, ey)
            }
            
            drawPath(
                path = path,
                color = Color(0x608B7355),
                style = Stroke(width = 1.5f, pathEffect = pathEffect)
            )
        }
    }
}
```

在 `WorldMapScreen` 的 Layer 1 和 Layer 3 之间插入：

```kotlin
// Layer 2: 宗门连接线（新增）
WorldMapConnections(
    edges = connectionEdges,
    cameraState = cameraState,
    modifier = Modifier.fillMaxSize()
)
```

### 8.2 数据源

`connectionEdges` 从 `WorldMapRenderData` 传入（需要在该 data class 中添加字段）：

```kotlin
data class WorldMapRenderData(
    val worldMapSects: List<WorldSect> = emptyList(),
    val worldLevels: List<WorldLevel> = emptyList(),
    val connectionEdges: List<MSTEdge> = emptyList()  // [新增]
)
```

---

## 9. Bug 修复：地图未全屏（研究驱动方案）

### 9.1 行业对标

全屏地图覆盖层在行业中有三条主流技术路线：

| 路线 | 技术方案 | 代表产品 | 性能 | 开发复杂度 |
|------|---------|---------|------|-----------|
| **A: Dialog + 全屏配置** | `DialogProperties(usePlatformDefaultWidth=false, decorFitsSystemWindows=false)` + `Surface(fillMaxSize)` + `setDimAmount(0f)` | 星穹铁道、多数 Unity 手游 | 中（Dialog 创建新 Window） | 低 |
| **B: 同层 Composable 覆盖** | 在同一个 Composition 中用 `Box(fillMaxSize)` 覆盖，无 Dialog 窗口 | Clash of Clans、本项目其他全屏对话 | 高（无额外 Window 开销） | 低 |
| **C: Activity/Window 独占** | 独立 `Activity` 或 `SurfaceView`，`SYSTEM_UI_FLAG_IMMERSIVE_STICKY` | 率土之滨、大型 SLG | 最高（独立渲染管线） | 高 |

### 9.2 本项目推荐：路线 B

理由：
1. **一致性问题**：项目已在 `GameOverlayHost` 中使用路线 B 处理其他全屏对话（弟子/仓库/建造/设置均用 `FullScreenOverlay` → `Surface(fillMaxSize)` + `BackHandler`）。路线 A 会引入第二套架构。
2. **性能**：路线 A 创建新 `Window` → 新 `DialogWindowProvider` → 额外 Surface 分配，每帧多一次 vsync 回调。路线 B 无额外开销。
3. **系统栏管理**：项目已在 `GameActivity` 中配置 `IMMERSIVE_STICKY`，路线 B 天然继承，无需在 Dialog 上重新应用。

### 9.3 具体实施方案

**改前** (`GameOverlayHost.kt:229-241`)：
```kotlin
is DialogRoute.WorldMap -> {
    val mapRenderData by viewModel.worldMapRenderData.collectAsStateWithLifecycle()
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    WorldMapDialog(  // 直接渲染，无 Surface 包裹
        worldSects = mapRenderData.worldMapSects,
        ...
    )
}
```

**改后**：
```kotlin
is DialogRoute.WorldMap -> {
    val mapRenderData by viewModel.worldMapRenderData.collectAsStateWithLifecycle()
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    Surface(  // 包裹 Surface 确保全屏，无默认 padding
        modifier = Modifier.fillMaxSize()
    ) {
        WorldMapDialog(
            worldSects = mapRenderData.worldMapSects,
            mapRenderData = mapRenderData,
            gameData = gameData,
            disciples = disciples,
            viewModel = viewModel,
            worldMapViewModel = worldMapViewModel,
            onDismiss = onDismiss
        )
    }
}
```

**变更清单**：
| 文件 | 变更 | 行数影响 |
|------|------|---------|
| `GameOverlayHost.kt` | `DialogRoute.WorldMap` 分支包裹 `Surface(fillMaxSize)` | +3 行 |
| `WorldMapDialog.kt` | 无需修改（已有 `Box(fillMaxSize)` + `BackHandler`） | 0 |

---

## 10. Bug 修复：地图白边（研究驱动方案）

### 10.1 行业对标

游戏行业处理"地图视口超出世界边界"有四种模式：

| 模式 | 核心机制 | 代表产品 | 视觉表现 | 实现成本 |
|------|---------|---------|---------|---------|
| **A: 硬相机钳制** | `cameraX = clamp(cameraX, 0, worldW - viewportW)`，视口永不出界 | 率土之滨、CoC | 地图始终填满屏幕 | 低（纯数学） |
| **B: 深色背景填充** | 视口超界时居中地图，空白填深色 | 原神、鬼谷八荒 | 地图居中，四边深色 | 极低（1 行代码） |
| **C: 渐变遮罩** | 地图边缘向内做 vignette 渐变 | 星穹铁道 | 边缘自然暗化 | 高（需 shader） |
| **D: 自然边界美术** | 地图外渲染为海洋/山脉/迷雾 | 太吾绘卷、RoK | 沉浸感最强 | 极高（需美术资源） |

### 10.2 本项目推荐：模式 A（硬钳制）

理由：
1. **从根源消灭白边**：视口永不超出世界边界 = 永远没有空白区域，不需要任何背景色或美术资源
2. **零代码增加**：只需要修改 `CameraState.clamp()` 移除居中逻辑，改为统一硬钳制
3. **行业验证**：率土之滨（225万格沙盘）、Clash of Clans 等头部产品均使用硬钳制，地图从不漂移出界
4. **初始位置保证**：`tryCenterOn` 在初始化时将玩家宗门带入视口，硬钳制不会导致开局看不到宗门

### 10.3 具体实施方案

**唯一改动**：`CameraState.clamp()` — 移除居中逻辑，统一硬钳制

```kotlin
// 改前 — 视口大于世界时居中
private fun clamp() {
    val effectiveW = viewportWidth / scale
    val effectiveH = viewportHeight / scale

    if (effectiveW >= worldWidth) {
        cameraX = -(effectiveW - worldWidth) / 2f  // 居中 → 产生空白区
    } else {
        cameraX = cameraX.coerceIn(0f, worldWidth - effectiveW)
    }

    if (effectiveH >= worldHeight) {
        cameraY = -(effectiveH - worldHeight) / 2f  // 居中 → 产生空白区
    } else {
        cameraY = cameraY.coerceIn(0f, worldHeight - effectiveH)
    }
}

// 改后 — 始终硬钳制，视口永不出界
private fun clamp() {
    if (scale <= 0f) return  // 防御：除零保护

    val effectiveW = viewportWidth / scale
    val effectiveH = viewportHeight / scale

    // 统一硬钳制：视口永远不超出世界边界
    // 当 effectiveW >= worldWidth 时，coerceIn(0f, 负数) → 0f
    cameraX = cameraX.coerceIn(0f, (worldWidth - effectiveW).coerceAtLeast(0f))
    cameraY = cameraY.coerceIn(0f, (worldHeight - effectiveH).coerceAtLeast(0f))
}
```

**关键点**：`.coerceAtLeast(0f)` 确保当视口大于世界时 max 为 0，`coerceIn(0f, 0f)` → 0f，视口锁定在世界原点。地图始终填满视口的一侧，不留空白。

**变更清单**：
| 文件 | 变更 | 行数影响 |
|------|------|---------|
| `CameraState.kt` | `clamp()` 由居中+钳制混合 → 统一硬钳制 | ~6 行 |
| `WorldMapScreen.kt` | **不需修改**（无需背景色） | 0 |

## 11. 重构八：增加防御性代码与错误兜底

### 11.1 CameraState 除零保护

```kotlin
// CameraState.kt — clamp() 增加保护
private fun clamp() {
    if (scale <= 0f) return  // 防止除零
    
    val effectiveW = viewportWidth / scale
    val effectiveH = viewportHeight / scale
    // ... 原有逻辑
}
```

### 9.2 MapBackground 位图回收检查

```kotlin
// 当前无检查，bitmap 回收后 drawImage 会崩溃
// 在 Canvas 中添加检查：
if (mapBitmap.width <= 0 || mapBitmap.height <= 0) return@Canvas
```

### 9.3 SectMarker 溢出保护

```kotlin
// SectMarker.kt:44 — 当前
(x - placeable.width / 2f).roundToInt()

// 改为安全转换
(x - placeable.width / 2f).toIntCoerced()

// 工具函数
private fun Float.toIntCoerced(): Int = 
    this.coerceIn(Int.MIN_VALUE.toFloat(), Int.MAX_VALUE.toFloat()).toInt()
```

### 11.4 过滤器提取（消除重复）

```kotlin
// 新建: core/model/DiscipleFilters.kt

object DiscipleFilters {
    /** 可用于派遣的空闲弟子（含最低年龄要求） */
    fun DiscipleAggregate.isDeployable(minAge: Int = 5): Boolean =
        isAlive && status == DiscipleStatus.IDLE && realmLayer > 0 && age >= minAge
}
```

替代多处重复的 `disciples.filter { it.isAlive && it.status == IDLE && ... }`。

---

## 10. 实施计划

### 10.1 分 6 个 Phase

| Phase | 重构内容 | 工时 | 依赖 |
|-------|---------|------|------|
| **P0** | Bug 修复（地图未全屏 + 白边）+ 防御性代码 + 除零保护 + 过滤器提取 | 1d | 无 |
| **P1** | 坐标系统统一 + 删除复制粘贴代码 | 1d | P0 |
| **P2** | 宗门地图解耦 + 参数聚合 | 2d | P0 |
| **P3** | WorldMapViewModel 拆分 | 1.5d | P0 |
| **P4** | 地图背景分块渲染 | 1.5d | P0 |
| **P5** | 建筑位图异步烘焙 + 空间索引增量更新 + 连接线渲染 | 1.5d | P0 |

**总计**：~8.5 人天

### 10.2 执行顺序

```
P0 (0.5d) ── 防御性修复，无风险
  │
  ├── P1 (1d) ── 坐标统一
  ├── P2 (2d) ── 宗门地图解耦  
  ├── P3 (1.5d) ── ViewModel 拆分
  ├── P4 (1.5d) ── 分块渲染
  └── P5 (1.5d) ── 异步烘焙 + 索引增量 + 连接线
```

P1-P5 相互独立，可并行执行。

### 10.3 每个 Phase 的标准流程

```
1. git checkout -b refactor/<phase-name>
2. 实现改动
3. cd android && ./gradlew.bat compileReleaseKotlin  （编译检查）
4. cd android && ./gradlew.bat test                    （全量测试）
5. 人工验收：打开世界地图和宗门地图，确认功能正常
6. git commit
7. 更新 CODE_WIKI.md（如有架构变更）
8. 更新 CHANGELOG.md
```

---

## 11. 验证标准

### 11.1 编译与测试

```bash
cd android && ./gradlew.bat compileReleaseKotlin   # 零编译错误
cd android && ./gradlew.bat test                    # 现有测试全部通过
cd android && ./gradlew.bat lintRelease             # 无新增 lint 警告
```

### 11.2 功能回归

- [ ] **地图全屏**：世界地图填满整个屏幕，无裁剪、无间距
- [ ] **无白边**：Camera 硬钳制后视口永不超界，地图四周零空白
- [ ] 世界地图：宗门标记正常显示，点击弹出详情
- [ ] 世界地图：关卡标记正常显示，点击弹出详情
- [ ] 世界地图：拖动平移正常
- [ ] 世界地图：连接线可见（Phase 5 后）
- [ ] 宗门地图：建筑正常显示
- [ ] 宗门地图：建筑放置/移动正常
- [ ] 宗门地图：网格吸附正常
- [ ] 宗门地图：建筑点击弹出对应对话框
- [ ] 侦察/交易/联盟/送礼/驻守/进攻功能正常

### 11.3 代码质量指标

| 指标 | 目标 |
|------|------|
| `SectGroundCanvas` 参数数 | ≤ 15（从 40+ 降至） |
| `WorldMapViewModel` 行数 | ≤ 80（从 226 降至） |
| 复制粘贴代码（isPointNearLineSegment） | 0 处（从 2 处降至） |
| 坐标转换实现入口 | 1 个（从 2 个降至） |
| 地图背景内存峰值 | ≤ 20MB（从 ~60MB 降至） |
| 建筑烘焙线程 | `Dispatchers.Default`（从主线程迁移） |

---

## 12. 参考来源

| # | 来源 | 等级 | 发布日期 | 核心摘要 |
|---|------|------|---------|---------|
| 1 | [Jetpack Compose Image Optimization](https://developer.android.com/develop/ui/compose/graphics/images/optimization) | S | 2024 | prepareToDraw、RGB_565、分块加载官方指南 |
| 2 | [Jetpack Compose Performance](https://developer.android.com/develop/ui/compose/performance) | S | 2024 | Compose 三阶段渲染模型，derivedStateOf 限制重组范围 |
| 3 | [Google Maps Android Marker Clustering](https://developers.google.com/maps/documentation/android-sdk/utility/marker-clustering) | S | 2024 | 标记信息密度管理算法 |
| 4 | [Unity Tilemap Performance Guide](https://unity.com/how-to/optimize-performance-2d-games-unity-tilemap) | S | 2024 | 瓦片地图性能对比（13ms vs 244ms），Sprite Atlas 批处理 |
| 5 | [HarmonyOS geometryTransition](https://developer.huawei.com/consumer/en/doc/harmonyos-references/ts-transition-animation-geometrytransition) | S | 2024 | 共享元素过渡 API 规范 |
| 6 | [率土之滨 SLG 战略地图设计](http://www.gamelook.com.cn/2022/04/480356/) | A | 2022-04 | 网易无极缩放四层信息架构（M1-M4），信息层级服从层级目标 |
| 7 | [率土之滨世界地图算法](https://www.gameres.com/874957.html) | A | 2022 | Perlin 噪声+泊松圆盘+BFS连通性检测 |
| 8 | [SLG 战争迷雾存储设计](https://hsroot.cn/news/content/79.html) | A | 2025-10 | 区块化三态迷雾，差分同步，位运算编码 |
| 9 | [Bevy Fog of War Library](https://docs.rs/bevy_fog_of_war/latest/bevy_fog_of_war/prelude/struct.FogChunk.html) | A | 2024 | 区块式迷雾开源实现 |
| 10 | [了不起的修仙模拟器宗门布局](https://game.zol.com.cn/1155/11553319.html) | A | — | 网格化宗门建造心得（192×192 网格，49 分区） |
| 11 | [Android RPG Map Rendering (TMX)](https://blog.csdn.net/weixin_35762258/article/details/151870492) | B | — | "解析→模型→缓存→渲染"管线，RGB_565+视口剔除+LRU 缓存 |
| 12 | [Stack Overflow: Canvas Tile Performance](https://stackoverflow.com/questions/13911587) | B | — | 分块预渲染替代逐瓦片绘制，7×7 块网格+脏边刷新 |
| 13 | [2D Camera Pan/Zoom Architecture](https://dev.to/rexthony/how-panning-and-zooming-work-in-a-2d-top-down-game-1afj) | B | — | 世界→相机→屏幕变换矩阵 |
| 14 | [Unity Minimap Implementation](https://gamedev.stackexchange.com/questions/212273/) | B | 2024 | 双摄像机+RenderTexture+CullingMask 分层渲染 |
| 15 | [JAHAN Procedural Map Framework](https://www.sciencedirect.com/science/article/abs/pii/S1875952124000120) | B | 2024-05 | 多目标评估生成，隔离随机性+断言检查 |
| 16 | [Game Design: Map Zoom Levels Patent](https://data.epo.org/publication-server/rest/v1.2/patents/EP2444134NWA1/document.pdf) | A | — | 固定客户端元素数+服务器预渲染+缩放级别独立缓存 |
| 17 | [Kevin Lynch Cognitive Map](https://www.routledge.com/rsc/downloads/SB3_Practices_of_Game_Design__Indie_Game_Marketing_FreeBook.pdf) | A | — | 五要素空间组织（Landmarks/Paths/Nodes/Edges/Districts） |
| 18 | [RTS Fog of War Architecture](https://github.com/rluders/rts-framework/issues/31) | B | 2024 | 多楼层独立迷雾+视口内渲染优化 |
| 19 | [Compose Performance Practical Problems](https://speakerdeck.com/gdglahore/o-extended-lahore-2024) | B | 2024 | Google I/O Extended — Compose 实际问题调优 |
| 20 | [Game Map Gestalt Principles](https://www.theseus.fi/bitstream/handle/10024/858127/) | B | — | Gestalt 定律在地图 UI 中的应用 |
| 21 | [Android Edge-to-Edge Display](https://developer.android.com/develop/ui/compose/system/insets) | S | 2024 | enableEdgeToEdge() + WindowInsets 官方指南，Compose 全屏处理 |
| 22 | [Compose Dialog Full Screen](https://stackoverflow.com/questions/79191984/jetpack-compose-dialog-fullscreen) | B | 2024 | `DialogProperties(usePlatformDefaultWidth=false, decorFitsSystemWindows=false)` 实现全屏 Dialog |
| 23 | [Compose Dialog True Fullscreen](https://stackoverflow.com/questions/78052487/unable-to-make-the-dialog-display-full-screen-in-android-compose) | B | 2024 | `setDimAmount(0F)` + `decorFitsSystemWindows=false` 实现无边框全屏 |
| 24 | [Edge-to-Edge Compose Dialogs](https://proandroiddev.com/camouflage-the-status-bar-with-edge-to-edge-jetpack-compose-screens-and-dialogs-bea553dd97ff) | B | 2024 | 对 Dialog Window 独立应用 edge-to-edge 标志的技术详解 |
| 25 | [GameActivity Immersive Sticky](https://developer.android.com/games/agdk/game-activity/get-started) | S | 2024 | Android GameActivity + `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 全屏方案 |
| 26 | [Open World Game Map Boundaries Design](https://gamedev.stackexchange.com/questions/112272/how-can-i-create-borders-in-an-open-world-game-that-dont-feel-artificial) | A | — | 8 种开放世界地图边界设计模式（岛屿/地形/叙事/敌对/疲劳/程序化等），75% AAA 游戏用叙事解释边界 |
| 27 | [Design of Area Boundaries in Video Games](https://download.atlantis-press.com/proceedings/icobest-hss-25/126013343) | A | — | 三类边界（物理/非物理/交互式）的形式化分类，一致性视觉语言原则 |
| 28 | [Unity Camera Clamping](https://discussions.unity.com/t/camera-screen-view-staying-inside-the-bounds-of-a-larger-level-sprite/922145) | A | 2024 | 相机钳制最佳实践：边界标记对象 + `LateUpdate` + 视口半宽计算 |
| 29 | [2D Game Camera Edge Clamping](https://discussions.unity.com/t/2d-top-down-camera-edge/531301) | B | — | 2D 俯视相机世界边界钳制 + 三种处理模式对比 |
| 30 | [Game Camera Viewport Clamp Math](https://love2d.org/forums/viewtopic.php?t=79649) | B | — | 旋转相机视口四角边界检测，`effectiveWidth = viewportW / zoom` 计算公式 |
| 31 | [Honkai Star Rail UI/UX Case Study](https://www.artstation.com/blogs/eyween/WBeAB/what-you-can-learn-from-hoyoverse-honkai-star-rail-and-mobile-gaming) | B | 2024 | 米哈游星穹铁道全屏地图叠加层 UI 设计案例分析 |
| 32 | [Illusion of Open Space in Games](http://virt10.itu.chalmers.se/index.php?title=Illusion_of_Open_Space) | B | — | 开放世界"开阔感幻觉"设计模式：地标/路径/节点/边缘/区域五要素 |
| 33 | [Compose DialogProperties usePlatformDefaultWidth](https://issuetracker.google.com/issues/290502769) | S | 2024 | Google Issue Tracker — Compose `usePlatformDefaultWidth=false` 旋转时 Dialog 尺寸不更新的已知 bug（已在 1.8.0-alpha08 修复） |
| 34 | [LibGDX OrthographicCamera](https://libgdx.com/wiki/graphics/2d/orthographic-camera) | A | — | 正交相机缩放+平移+钳制的标准实现参考 |
| 35 | [MonoGame Full Screen Android](https://community.monogame.net/t/full-screen-on-android/19136) | B | — | Android 游戏全屏的 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 实践 |

---

> **方案状态**: 待用户审核批准。  
> **下一步**: 用户确认后按 Phase P0-P5 顺序执行，每阶段独立验证。
