# GPU 分级不应改变游戏状态：公平性修复方案

> **日期**: 2026-06-09 | **版本**: v1.0.0
> **状态**: 待审核 | **工时**: ~1 人天

---

## 1. 问题

`gpuRenderConfig.mapResolution`（LOW=24, MEDIUM=32, HIGH=48）被用作宗门地图的格数，导致不同设备的游戏世界**物理尺寸不同**：

| 设备 | 格数 | 世界像素 | 灵矿场初始位置 |
|------|------|---------|--------------|
| 高配 | 48×48 | 3072×3072 | grid(23,23) = 中心 |
| 中配 | 32×32 | 2048×2048 | grid(23,23) **已出界** |
| 低配 | 24×24 | 1536×1536 | grid(23,23) **已出界** |

这违反了游戏公平性的基本原则：**不同设备的玩家不应该拥有不同的游戏世界**。

---

## 2. 行业原则

游戏行业有一条铁律：**Simulation / Game State is Authoritative, Rendering is Cosmetic**。

| 原则 | 来源 |
|------|------|
| 游戏状态（网格尺寸、建筑位置、资源分布）必须在所有设备上一致 | [GameDev.SE: Why separate objects from rendering](https://gamedev.stackexchange.com/questions/66874) |
| GPU 层级影响渲染质量（纹理分辨率、阴影、特效），不影响游戏逻辑 | [Innovecs Games: Mobile Rendering Best Practices 2024](https://www.innovecsgames.com/blog/optimizing-art-rendering-for-mobile-gaming-best-practices/) |
| Arm NanoMesh: LOD 选择在 GPU 上完成，游戏状态不变 | [Arm Developer Blog 2024](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/nanomesh-on-mobile) |
| 确定性多人 RTS 必须保证所有客户端模拟一致 | [80.lv: Deterministic Multiplayer RTS](https://80.lv/articles/check-out-this-fully-deterministic-multiplayer-rts-prototype-with-cossacks-vibe) |
| 性能配置档仅在渲染层生效 | [Arm ASR Fortnite Integration 2024](https://newsroom.arm.com/blog/arm-asr-epic-games-fortnite) |

**结论**：`mapResolution` 只能控制渲染分辨率（位图像素），不能控制世界尺寸（格数）。

---

## 3. 方案

### 当前架构

```
gpuRenderConfig.mapResolution
  ├── 控制世界格数 (worldWidthCells)  ← ❌ 污染游戏状态
  ├── 控制世界像素 (worldPixelWidth)  ← ❌ 污染游戏状态
  └── 控制位图采样 (inSampleSize)    ← ✅ 正确的用途
```

### 修复后架构

```
GameConfig.SectMap.WORLD_WIDTH_CELLS (48, 固定)
  ├── 控制世界格数     ← 所有设备一致
  ├── 控制世界像素     ← 所有设备一致
  └── 控制初始建筑位置  ← 公式: gridCells/2 - 1

gpuRenderConfig.mapResolution
  └── 只控制内部渲染位图分辨率 ← 低配用小位图，拉伸到世界尺寸
```

### 具体改动

**1. GameActivity.kt** — 世界尺寸固定为 GameConfig

```kotlin
// 改前
val worldWidthCells = gpuRenderConfig.mapResolution  // ← 动态，低配 24
val worldHeightCells = gpuRenderConfig.mapResolution

// 改后
val worldWidthCells = GameConfig.SectMap.WORLD_WIDTH_CELLS   // 固定 48
val worldHeightCells = GameConfig.SectMap.WORLD_HEIGHT_CELLS
```

**2. GameActivity.kt** — 内部渲染位图按 GPU 层级降分辨率

```kotlin
// 新增：渲染分辨率低于世界分辨率，画的时候拉伸
val renderScale = gpuRenderConfig.mapResolution.toFloat() / GameConfig.SectMap.WORLD_WIDTH_CELLS.toFloat()
val renderWidth = (worldPixelWidth * renderScale).toInt()   // 低配 1536
val renderHeight = (worldPixelHeight * renderScale).toInt()

// groundBmp 创建时用 renderWidth/renderHeight，不用 worldPixelWidth/Height
val groundBmp = Bitmap.createScaledBitmap(src, renderWidth, renderHeight, false)
val fullBmp = Bitmap.createBitmap(renderWidth, renderHeight, bmpConfig)
```

**3. MapPreloadData** — 新增 `renderWidth`/`renderHeight` 字段，Canvas 渲染时拉伸到世界尺寸

```kotlin
// MapPreloadData 新增
val renderWidth: Int,   // 内部位图实际像素宽度
val renderHeight: Int   // 内部位图实际像素高度
```

**4. SectMapCanvas** — 渲染 `fullMapBmp` 时用 `dstSize` 拉伸到世界尺寸

```kotlin
// 改后：始终用 dstSize 拉伸到世界尺寸（而非 topLeft 原生分辨率）
drawImage(
    staticData.fullMapBmp,
    dstOffset = IntOffset.Zero,
    dstSize = IntSize(worldPixelWidth, worldPixelHeight)
)
```

**5. GameEngineCoordination.kt** — 初始灵矿场公式自适应

```kotlin
// 已是公式，无需再改
val centerGrid = gridCells / 2 - 1  // 48 → 23，32 → 15，24 → 11
```

### 变更文件清单

| 文件 | 改动 |
|------|------|
| `GameActivity.kt` | 世界尺寸用 `GameConfig` 固定 48；内部位图用 `mapResolution` 降分辨 |
| `MapPreloadData.kt` | 新增 `renderWidth`/`renderHeight` 字段 |
| `SectMapCanvas.kt` | `fullMapBmp` 始终拉伸到世界尺寸 |
| `MainGameScreen.kt` | 传入 `renderWidth`/`renderHeight`（如有需要） |

### 效果

| 设备 | 世界格数 | 世界像素 | 内部位图 | 内存 |
|------|---------|---------|---------|------|
| 高配 | 48 | 3072 | 3072×3072 RGB_565 ≈ 19MB | 不变 |
| 中配 | 48 | 3072 | 2048×2048 RGB_565 ≈ 8MB | **降低** |
| 低配 | 48 | 3072 | 1536×1536 RGB_565 ≈ 4.5MB | **大幅降低** |

所有设备玩家看到的游戏世界完全一致，低配设备位图更模糊但位置正确。

---

## 4. 验证

- [ ] 高配设备：世界 3072×3072，灵矿场在中心
- [ ] 中配设备：世界 3072×3072，灵矿场在中心，位图略模糊
- [ ] 低配设备：世界 3072×3072，灵矿场在中心，位图更模糊
- [ ] `compileReleaseKotlin` + `test` 通过
- [ ] 所有设备初始视角均居中

---

## 5. 参考来源

| # | 来源 | 等级 | 核心摘要 |
|---|------|------|---------|
| 1 | [GameDev.SE: Why separate objects from rendering](https://gamedev.stackexchange.com/questions/66874) | A | 渲染和游戏逻辑分离：游戏对象不持有渲染状态 |
| 2 | [Innovecs: Mobile Rendering Best Practices](https://www.innovecsgames.com/blog/optimizing-art-rendering-for-mobile-gaming-best-practices/) | A | 性能配置档仅在渲染层生效，不动游戏状态 |
| 3 | [Arm NanoMesh GDC 2024](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/nanomesh-on-mobile) | A | GPU 驱动 LOD，游戏状态不变 |
| 4 | [Arm ASR Fortnite 2024](https://newsroom.arm.com/blog/arm-asr-epic-games-fortnite) | A | 超分辨率上采样，不动渲染管线架构 |
| 5 | [80.lv: Deterministic Multiplayer RTS](https://80.lv/articles/check-out-this-fully-deterministic-multiplayer-rts-prototype-with-cossacks-vibe) | A | 确定性锁步网络模型，所有客户端模拟一致 |
| 6 | [Google Maps Android Marker Clustering](https://developers.google.com/maps/documentation/android-sdk/utility/marker-clustering) | S | 标记信息密度随缩放动态调整，底层数据不变 |
| 7 | [Jetpack Compose Image Optimization](https://developer.android.com/develop/ui/compose/graphics/images/optimization) | S | prepareToDraw、RGB_565、inSampleSize |
| 8 | [Unity Tilemap Performance Guide](https://unity.com/how-to/optimize-performance-2d-games-unity-tilemap) | S | 瓦片地图性能对比，Sprite Atlas 批处理 |
| 9 | [Android GameActivity](https://developer.android.com/games/agdk/game-activity/get-started) | S | GameActivity 沉浸式全屏配置 |
| 10 | [Rate of the World Map Generation Algorithm](https://www.gameres.com/874957.html) | A | 率土之滨 Perlin 噪声+泊松圆盘世界生成 |
| 11 | [Game Camera Viewport Clamping](https://discussions.unity.com/t/camera-screen-view-staying-inside-the-bounds-of-a-larger-level-sprite/922145) | A | 相机钳制最佳实践 |
| 12 | [Game Programming Patterns – Component](https://gameprogrammingpatterns.com/component.html) | S | 组件模式：子系统独立，通过接口通信 |
| 13 | [SLG Fog of War Storage Design](https://hsroot.cn/news/content/79.html) | A | 区块化三态迷雾，差分同步 |
| 14 | [NanoMesh SIGGRAPH 2024 PDF](https://advances.realtimerendering.com/s2024/content/Cao-NanoMesh/AdavanceRealtimeRendering_NanoMesh0810.pdf) | S | GPU 驱动 LOD，device_factor 自适应 |
| 15 | [Stack Overflow: Canvas Tile Performance](https://stackoverflow.com/questions/13911587) | B | 分块预渲染，脏边刷新 |
| 16 | [2D Camera Pan/Zoom Architecture](https://dev.to/rexthony/how-panning-and-zooming-work-in-a-2d-top-down-game-1afj) | B | 世界→相机→屏幕变换矩阵 |
| 17 | [Game Map Boundaries Design](https://gamedev.stackexchange.com/questions/112272) | A | 8 种开放世界边界设计模式 |
| 18 | [了不起的修仙模拟器宗门布局](https://game.zol.com.cn/1155/11553319.html) | A | 宗门建造网格化设计 |
| 19 | [Rise of Kingdoms Grid System](https://www.gamelook.com.cn/2022/04/480356/) | A | SLG 游戏网格世界设计 |
| 20 | [Game Architecture Course Materials](https://www.sci.brooklyn.cuny.edu/~meyer/CISC3600/Materials/3_4GameArchitecture.pdf) | A | 游戏架构分层：渲染层独立于逻辑层 |

---

> **方案状态**: 待审核。核心原则：GPU 分级只影响渲染质量，不改变游戏世界尺寸。
