# 世界地图 & 宗门地图模块解耦方案

> **生成日期**: 2026-06-09 | **版本**: v1.0.0 | **参考来源**: 20 条
> **状态**: 待审核 | **工时**: ~3 人天

---

## 1. 问题诊断

### 1.1 根因

`CameraState` 类同时被世界地图和宗门地图使用。任何对 `CameraState` 的修改——加 `scale` 参数、改 `clamp` 逻辑、改 `pan`/`centerOn` 公式——都会同时影响两张地图。

```
改前架构（耦合）:

WorldMapScreen ──┐
                 ├── CameraState (同一个类，共享所有字段/方法)
SectMapCanvas  ──┘

问题链:
  世界地图加 scale → 宗门地图的 coordinate math 全变了
  世界地图改 hard clamp → 宗门地图的行为同步变化
  Canvas 改 renderScale → CameraState 不知情，错位
```

### 1.2 已发生的交叉感染

| 改动 | 目标地图 | 波及地图 | 后果 |
|------|---------|---------|------|
| `CameraState` 加 `scale` 字段 | 世界地图 | 宗门地图 | `pan`/`centerOn` 公式变了，宗门地图视角偏移 |
| `clamp()` 改硬钳制 | 世界地图白边 | 宗门地图 | 钳制行为变化 |
| `SectMapCanvas` 新增 `sectBgBmp` 层 | 宗门地图解耦 | 自身 | `sectBgBmp` 尺寸不匹配世界坐标，引入白边 |
| Canvas `scale(renderScale)` | GPU 降频 | 自身 | 和 CameraState.scale=1.0 形成双层变换 |

### 1.3 当前修复后仍存的问题

- 宗门地图初始视角不在中心
- 世界地图玩家宗门位置不在中心
- 两张地图共用 `CameraState` 类，未来改动风险仍在

---

## 2. 行业对标

### 2.1 游戏行业解耦模式

参照 Game Programming Patterns (Nystrom, 2014)、Unity Cinemachine、Unreal Engine 的多相机架构：

| 产品/引擎 | 多视图解耦方式 | 核心原则 |
|-----------|--------------|---------|
| **Unity Cinemachine** | 虚拟相机（Virtual Camera）+ 通道（Channel）隔离 | 每个视图独立相机，不共享状态 |
| **Unreal Engine** | 多个 `UCameraComponent` + `SceneViewport` | 每个视图独立渲染管线 |
| **Clash of Clans** | 场景切换而非叠加 | 每次只渲染一张地图 |
| **率土之滨** | 无极缩放在单相机内完成，多视图通过层级切换 | 单相机多层级，而非多相机共享 |
| **Robert Nystrom 组件模式** | 每个子系统独立组件，通过接口通信 | 组合优于继承，隔离优于共享 |

### 2.2 结论

行业标准是**每个地图视图拥有独立的相机/坐标系统**，不共享可变状态。共享的只能是不可变配置（世界尺寸常量），不能是运行时可变对象。

---

## 3. 方案设计

### 3.1 目标

- 世界地图和宗门地图各自拥有独立的相机/坐标类
- 修改一张地图的相机行为不影响另一张
- 共享的只有常量配置（世界尺寸、网格参数）
- 回退到 3.2.24 的功能正确性

### 3.2 新文件结构

```
ui/game/map/
├── infrastructure/
│   ├── MapCoordTransformer.kt    # 纯函数坐标转换，两个地图共享（无状态）
│   └── CameraState.kt            # [删除] 拆分为下面两个
│
├── world/
│   ├── WorldCameraState.kt       # [新建] 世界地图专用相机
│   │   - scale 支持（未来缩放用，目前=1.0）
│   │   - hard clamp 硬钳制
│   │   - worldToScreen / screenToWorld
│   │   - pan / centerOn / tryCenterOn
│   │   - isVisible
│   │   - viewport 管理
│   │
│   ├── WorldMapScreen.kt        # [修改] 使用 WorldCameraState
│   ├── WorldMapBackground.kt    # 不变
│   ├── WorldMapConnections.kt   # 不变
│   └── ...
│
├── sect/
│   ├── SectCameraState.kt        # [新建] 宗门地图专用相机
│   │   - 无 scale 字段（宗门地图不需要缩放）
│   │   - hard clamp 硬钳制
│   │   - worldToScreen / screenToWorld（公式内联，不除 scale）
│   │   - pan / centerOn / tryCenterOn（公式内联）
│   │   - isVisible
│   │   - viewport 管理
│   │
│   ├── SectMapCanvas.kt          # [修改] 使用 SectCameraState
│   ├── SectMapState.kt           # 不变
│   └── ...
│
└── MapCoordTransformer.kt        # [新建] 无状态纯函数
    - worldToScreen(wx, wy, cx, cy, scale)
    - screenToWorld(sx, sy, cx, cy, scale)
    - worldToNormalized(wx, wy, ww, wh)
    - isVisible(wx, wy, cx, cy, scale, vw, vh)
```

### 3.3 WorldCameraState

```kotlin
// world/WorldCameraState.kt

@Stable
class WorldCameraState(
    val worldWidth: Float,   // 6000
    val worldHeight: Float,  // 5000
    val scale: Float = 1.0f  // 世界地图可缩放（预留）
) {
    var cameraX by mutableFloatStateOf(0f); private set
    var cameraY by mutableFloatStateOf(0f); private set
    var viewportWidth by mutableIntStateOf(0); private set
    var viewportHeight by mutableIntStateOf(0); private set

    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    fun worldToScreenX(wx: Float) = (wx - cameraX) * scale
    fun worldToScreenY(wy: Float) = (wy - cameraY) * scale
    fun screenToWorldX(sx: Float) = sx / scale + cameraX
    fun screenToWorldY(sy: Float) = sy / scale + cameraY

    fun updateViewport(w: Int, h: Int) { viewportWidth = w; viewportHeight = h }

    fun pan(dx: Float, dy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX -= dx / scale
        cameraY -= dy / scale
        clamp()
    }

    fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / (2f * scale)
        cameraY = wy - viewportHeight / (2f * scale)
        clamp()
    }

    fun tryCenterOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!hasInitialized || abs(wx - lastCenterX) > 100f || abs(wy - lastCenterY) > 100f) {
            centerOn(wx, wy)
            lastCenterX = wx; lastCenterY = wy; hasInitialized = true
        }
    }

    fun isVisible(wx: Float, wy: Float, margin: Float = 0f): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx); val sy = worldToScreenY(wy)
        val m = margin * scale
        return sx >= -m && sx <= viewportWidth + m && sy >= -m && sy <= viewportHeight + m
    }

    fun reset() { hasInitialized = false; cameraX = 0f; cameraY = 0f }

    private fun clamp() {
        if (scale <= 0f) return
        val ew = viewportWidth / scale; val eh = viewportHeight / scale
        cameraX = cameraX.coerceIn(0f, (worldWidth - ew).coerceAtLeast(0f))
        cameraY = cameraY.coerceIn(0f, (worldHeight - eh).coerceAtLeast(0f))
    }
}
```

### 3.4 SectCameraState

```kotlin
// sect/SectCameraState.kt

@Stable
class SectCameraState(
    val worldWidth: Float,   // 3072
    val worldHeight: Float   // 3072
) {
    var cameraX by mutableFloatStateOf(0f); private set
    var cameraY by mutableFloatStateOf(0f); private set
    var viewportWidth by mutableIntStateOf(0); private set
    var viewportHeight by mutableIntStateOf(0); private set

    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    // 宗门地图不需要 scale，公式直接内联
    fun worldToScreenX(wx: Float) = wx - cameraX
    fun worldToScreenY(wy: Float) = wy - cameraY
    fun screenToWorldX(sx: Float) = sx + cameraX
    fun screenToWorldY(sy: Float) = sy + cameraY

    fun updateViewport(w: Int, h: Int) { viewportWidth = w; viewportHeight = h }

    fun pan(dx: Float, dy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX -= dx; cameraY -= dy
        clamp()
    }

    fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / 2f
        cameraY = wy - viewportHeight / 2f
        clamp()
    }

    fun tryCenterOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!hasInitialized || abs(wx - lastCenterX) > 100f || abs(wy - lastCenterY) > 100f) {
            centerOn(wx, wy)
            lastCenterX = wx; lastCenterY = wy; hasInitialized = true
        }
    }

    fun isVisible(wx: Float, wy: Float, margin: Float = 0f): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx); val sy = worldToScreenY(wy)
        return sx >= -margin && sx <= viewportWidth + margin &&
               sy >= -margin && sy <= viewportHeight + margin
    }

    fun reset() { hasInitialized = false; cameraX = 0f; cameraY = 0f }

    private fun clamp() {
        val ew = viewportWidth.toFloat(); val eh = viewportHeight.toFloat()
        cameraX = cameraX.coerceIn(0f, (worldWidth - ew).coerceAtLeast(0f))
        cameraY = cameraY.coerceIn(0f, (worldHeight - eh).coerceAtLeast(0f))
    }
}
```

### 3.5 MapCoordTransformer（无状态工具函数）

```kotlin
// infrastructure/MapCoordTransformer.kt

object MapCoordTransformer {
    /** 世界坐标 → 归一化 [0,1] */
    fun worldToNormalized(wx: Float, wy: Float, ww: Float, wh: Float): Pair<Float, Float> =
        (wx / ww).coerceIn(0f, 1f) to (wy / wh).coerceIn(0f, 1f)

    /** 归一化 → 世界坐标 */
    fun normalizedToWorld(nx: Float, ny: Float, ww: Float, wh: Float): Pair<Float, Float> =
        nx * ww to ny * wh
}
```

---

## 4. 变更文件清单

| 操作 | 文件 | 说明 |
|------|------|------|
| **新建** | `map/world/WorldCameraState.kt` | 世界地图专用相机（~60行） |
| **新建** | `map/sect/SectCameraState.kt` | 宗门地图专用相机（~55行） |
| **新建** | `map/infrastructure/MapCoordTransformer.kt` | 纯函数工具（~10行） |
| **删除** | `map/MapCameraState.kt` | 旧 CameraState（含 `rememberCameraState`） |
| **修改** | `map/WorldMapScreen.kt` | `CameraState` → `WorldCameraState`，`rememberCameraState` → `rememberWorldCamera` |
| **修改** | `map/WorldMapBackground.kt` | `CameraState` 类型改为 `WorldCameraState` |
| **修改** | `map/WorldMapConnections.kt` | `CameraState` 类型改为 `WorldCameraState` |
| **修改** | `map/markers/SectMarker.kt` | `CameraState` 类型改为 `WorldCameraState` |
| **修改** | `map/markers/LevelMarker.kt` | `CameraState` 类型改为 `WorldCameraState` |
| **修改** | `sect/SectMapCanvas.kt` | `CameraState` → `SectCameraState`，删掉 `renderScale` 引用 |
| **修改** | `sect/SectMapState.kt` | `SectMapRenderConfig.cameraState` 类型改为 `SectCameraState` |
| **修改** | `MainGameScreen.kt` | `rememberCameraState` → `rememberSectCamera`；所有 `cameraState` 引用调整 |
| **修改** | `dialogs/WorldMapDialog.kt` | 从构造 `WorldCameraState` 的引用调整 |
| **删除** | `map/MapCoordinateSystem.kt` 中已废弃方法 | `worldToScreen`/`isWorldPositionVisible`（如果之前未删） |

---

## 5. 实施步骤

| 步骤 | 内容 | 验证 |
|------|------|------|
| 1 | 新建 `WorldCameraState`、`SectCameraState`、`MapCoordTransformer` | 编译通过 |
| 2 | 替换世界地图侧所有 `CameraState` → `WorldCameraState` | 世界地图编译通过，功能不变 |
| 3 | 替换宗门地图侧所有 `CameraState` → `SectCameraState` | 宗门地图编译通过，功能不变 |
| 4 | 删除旧 `MapCameraState.kt` | 编译通过，无残留引用 |
| 5 | 删 `MapCoordinateSystem` 废弃方法 | 编译通过 |
| 6 | `compileReleaseKotlin` + `test` | 全绿 |
| 7 | 人工验收：两张地图初始视角均在中心 | 世界地图玩家宗门居中，宗门地图中心居中 |

---

## 6. 后续规则

解耦后强制遵守：

1. **世界地图的相机改动只在 `WorldCameraState` 中**，不碰 `SectCameraState`
2. **宗门地图的相机改动只在 `SectCameraState` 中**，不碰 `WorldCameraState`
3. **`MapCoordTransformer` 只放无状态纯函数**，不放任何可变字段
4. **新增地图类型**需要新建专属 CameraState，不准复用现有类

---

## 7. 参考来源

| # | 来源 | 等级 | 核心摘要 |
|---|------|------|---------|
| 1 | [Game Programming Patterns — Component](https://gameprogrammingpatterns.com/component.html) | S | 多子系统解耦：组合优于继承，每个子系统独立组件 |
| 2 | [Game Programming Patterns — Decoupling Patterns](https://deepwiki.com/munificent/game-programming-patterns/2.4-decoupling-patterns) | S | 事件队列、服务定位器，最小化模块间知识 |
| 3 | [Unity Cinemachine — Virtual Camera Architecture](https://docs.unity3d.com/Packages/com.unity.cinemachine@3.1/manual/concept-essential-elements.html) | S | 多虚拟相机+优先级+通道隔离，单 Brain 切换 |
| 4 | [Unity Cinemachine — Multiple Brains & Channels](https://docs.unity3d.com/Packages/com.unity.cinemachine@3.1/manual/concept-camera-control-transitions.html) | S | 多 Brain 多通道，每视图独立相机队列 |
| 5 | [Unreal Engine — Independent Camera Setup](https://forums.unrealengine.com/t/independent-camera-setup/2019476) | A | 多 UCameraComponent 独立渲染目标 |
| 6 | [Android GameActivity Immersive Mode](https://developer.android.com/games/agdk/game-activity/get-started) | S | SYSTEM_UI_FLAG_IMMERSIVE_STICKY 全屏配置 |
| 7 | [率土之滨 SLG 战略地图设计](http://www.gamelook.com.cn/2022/04/480356/) | A | 无极缩放 M1-M4 单相机层级切换，非多相机共享 |
| 8 | [Game Development Patterns — Decoupled Systems](https://www.oreilly.com/library/view/game-development-patterns/9781803243252/B18297_07.xhtml) | A | 完美解耦：通过接口通信，禁止共享可变状态 |
| 9 | [Game Module Isolation — Memory Safety](https://www.gamedev.net/forums/topic/646090-dll-question/) | A | 模块隔离首要目标：内存安全，每个模块管理自身分配 |
| 10 | [Layered Game Rendering Architecture (CN119345689B)](https://www.baiten.cn-www.patenthub.cn/content/CN119345689B) | A | 四层隔离：窗口抽象层→渲染实现→语言绑定→游戏代码 |
| 11 | [Jetpack Compose Performance](https://developer.android.com/develop/ui/compose/performance) | S | Compose 三阶段渲染模型 |
| 12 | [Game Development Best Practices](https://www.oreilly.com/library/view/game-development-patterns/9781787127838/227e25cf-2268-4680-a0e2-1953b48db9df.xhtml) | A | 依赖反转：高层不依赖低层，都依赖抽象 |
| 13 | [Flame Game Engine — Isolate Pattern](https://pub.dev/documentation/flame_isolate/0.6.2+21/flame_isolate/FlameIsolate-mixin.html) | B | 多 isolate 独立内存堆，零共享状态 |
| 14 | [2D Camera Pan/Zoom Architecture](https://dev.to/rexthony/how-panning-and-zooming-work-in-a-2d-top-down-game-1afj) | B | 世界→相机→屏幕变换矩阵 |
| 15 | [Google Maps Android Marker Clustering](https://developers.google.com/maps/documentation/android-sdk/utility/marker-clustering) | S | 标记信息密度管理 |
| 16 | [Honkai Star Rail UI/UX Case Study](https://www.artstation.com/blogs/eyween/WBeAB/what-you-can-learn-from-hoyoverse-honkai-star-rail-and-mobile-gaming) | B | 米哈游地图 UI 设计模式 |
| 17 | [Open World Game Boundaries Design](https://gamedev.stackexchange.com/questions/112272) | A | 8 种地图边界设计模式 |
| 18 | [Unity 2D Camera Clamping](https://discussions.unity.com/t/camera-screen-view-staying-inside-the-bounds-of-a-larger-level-sprite/922145) | A | 相机钳制最佳实践：LateUpdate + 视口半宽 |
| 19 | [Game Camera World Limits](https://love2d.org/forums/viewtopic.php?t=79649) | B | 旋转相机边界检测数学 |
| 20 | [Compose Dialog Full Screen](https://stackoverflow.com/questions/79191984/jetpack-compose-dialog-fullscreen) | B | DialogProperties 全屏配置 |

---

> **方案状态**: 待审核。批准后按 §5 步骤执行，每步验证编译+功能。
