# 宗门地图渲染架构

## 概览

宗门地图采用**三层按格实时绘制**架构，自 v4.0.42 起废除 `fullMapBmp` 单层位图烘焙模式。

```
渲染顺序（从底到顶）：
  Layer 1: Ground  — map_tile.webp 平铺合成 → groundTileBmp 拉伸铺满
  Layer 2: Deco    — 逐可见格绘制装饰精灵，建筑占用的格子跳过
  Layer 3: Building — placedBuildings 列表逐建筑绘制
```

## 数据流

```
美术资源 (WebP)
  ├─ map_tile.webp          — 单格地面纹理
  ├─ decoration_grass_*.webp — 3 种草装饰变体
  └─ decoration_tree*.webp   — 2 种树装饰变体
        ↓
GameActivity.kt (启动时 LaunchedEffect)
  ├─ map_tile 平铺 → groundTileBmp (全图地面位图)
  ├─ 加载 3+2 装饰精灵图 → grassDecBitmaps[3] / treeDecBitmaps[2]
  └─ SectMapTileGenerator.generateTileData() → rawTileData
        ↓
MapPreloadData (Compose State)
  ├─ groundTileBmp: ImageBitmap
  ├─ grassDecBitmaps: List<ImageBitmap>  [小, 中, 大]
  ├─ treeDecBitmaps: List<ImageBitmap>   [树木1, 树木2]
  └─ rawTileData: Array<IntArray>
        ↓
MainGameScreen.kt
  └─ rawTileData + effectivePlacedBuildings → tileData (含 TILE_BUILDING)
        ↓
SectMapCanvas.kt (每帧 Canvas 实时绘制)
  ├─ drawImage(groundTileBmp)          → 地面层
  ├─ for each visible cell:            → 装饰层
  │   switch tileData[row][col]:
  │     TILE_GRASS_SMALL/MEDIUM/LARGE → draw grass variant
  │     TILE_TREE1/TREE2              → draw tree variant
  │     TILE_BUILDING/TILE_GROUND     → skip
  └─ for each placedBuilding:          → 建筑层
      drawImage(buildingBitmap)
```

## 瓦片类型编码

```kotlin
const val TILE_GROUND       = 0   // 空地（无装饰）
const val TILE_GRASS_SMALL  = 1   // 小草丛装饰
const val TILE_GRASS_MEDIUM = 2   // 中草丛装饰
const val TILE_GRASS_LARGE  = 3   // 大草丛装饰
const val TILE_TREE1        = 4   // 树变体1
const val TILE_TREE2        = 5   // 树变体2
const val TILE_BUILDING     = 6   // 建筑占位（由 placedBuildings 计算）
```

定义在 `SectMapTileGenerator`（`core/engine/.../util/`），消费端在 `SectMapCanvas.kt` 和 `MainGameScreen.kt`。

## 装饰物生成算法

`SectMapTileGenerator.generateTileData()` 使用两种策略的混合：

| 装饰类型 | 算法 | 分布效果 |
|---------|------|---------|
| 树 (TREE1/TREE2) | 5×5 网格簇 + `java.util.Random` 偏移 | 团状聚集 |
| 草 (3 变体) | 逐格位置哈希 + 噪声阈值 | 自然散布 |

- **确定性**：相同输入永远产生相同输出（种子 42 + 位置哈希）
- **密度控制**：`decorationDensity` 参数 (0.0~1.0)，默认 0.30
- **定义位置**：`core/engine/src/main/java/com/xianxia/sect/core/util/SectMapTileGenerator.kt`

## 性能指标

| 指标 | 值 | 说明 |
|------|-----|------|
| 地面层 draw calls | 1 | `drawImage(groundTileBmp)` 一次调用 |
| 装饰层 draw calls | ~10-30 | 视口内可见装饰格数，每格 1 次 |
| 建筑层 draw calls | ~5-15 | 视口内可见建筑数 |
| 视口裁剪 | ✅ | 装饰和建筑均只渲染可见区域 |
| 总帧开销 | <0.1ms | 28×28 网格，约 60 可见格 |

## 架构演进

| 版本 | 架构 | 问题 |
|------|------|------|
| ≤4.0.40 | 双缓冲 Bitmap 烘焙（frontBuffer/backBuffer） | 残影 bug、复杂增量追踪 |
| 4.0.41 | 统一 Canvas 直接绘制 + `fullMapBmp` 单层位图 | 地面+装饰合并无法独立控制 |
| **4.0.42** | **三层按格实时绘制** ✅ | 分离地面/装饰/建筑，无后处理 |

## 美术资源清单

所有地图资源放在 `drawable-nodpi`（需同时放入 `:app` 和 `:feature:game` 模块）：

| 文件名 | 用途 | 原始素材 |
|--------|------|---------|
| `map_tile.webp` | 单格地面纹理 | `地图格.png` |
| `decoration_grass_small.webp` | 小草丛装饰 | `小草丛.png` |
| `decoration_grass_medium.webp` | 中草丛装饰 | `中草丛.png` |
| `decoration_grass_large.webp` | 大草丛装饰 | `大草丛.png` |
| `decoration_tree1.webp` | 树变体 1 | `树木1.png` |
| `decoration_tree2.webp` | 树变体 2 | `树木2.png` |

这些资源不走 `SpriteResRegistry` 注册，而是通过 `GameActivity.kt` 的 `MapPreloadData` 构建逻辑直接预加载（地图资源类别的正交设计）。

## 关键文件索引

| 文件 | 职责 |
|------|------|
| `core/engine/.../util/SectMapTileGenerator.kt` | 瓦片数据生成算法 |
| `core/engine/.../util/SectMapTileGeneratorTest.kt` | 生成算法测试（10 用例） |
| `core/domain/.../model/MapPreloadData.kt` | 预加载数据模型 |
| `app/.../ui/game/GameActivity.kt` | 资源加载 + MapPreloadData 构建 |
| `feature/game/.../sect/SectMapCanvas.kt` | 三层按格渲染 |
| `feature/game/.../MainGameScreen.kt` | tileData 计算 + 参数传递 |
| `feature/game/.../sect/SectMapState.kt` | 放置/移动/金手指状态 |
