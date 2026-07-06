# 宗门地图渲染架构

## 概览

宗门地图自 **v4.0.43+** 起采用 **Vulkan 原生渲染管线**，替代了旧版 Compose Canvas。
**v4.0.45+** 改为 **统一图集逐格渲染**，修复了悬空指针 bug、LINEAR+REPEAT 兼容性隐患。

新架构：**固定 2 draw calls / 帧** + 独立渲染线程 + 双缓冲 VBO + OPTIMAL tiling 纹理 + 视锥剔除。

```
渲染顺序（由 C++ VulkanBackend::submitFrame 提交）：
  Layer 1: 统一瓦片层 — 地面+装饰+建筑全部从图集取 UV，SpriteBatcher 合并为 1 次 draw call
  Layer 2: Preview    — 纯色矩形（放置/移动预览）→ 白色纹理乘以顶点颜色
```

## 架构对比

| 维度 | 4.0.43-4.0.44 (Vulkan 初版) | **4.0.45+ (Vulkan v2)** |
|------|----------------------------|------------------------|
| Draw calls | 3-4 (地面+装饰+建筑+预览) | **固定 2** (统一层+预览) |
| 地面渲染 | UV=REPEAT 平铺（LINER+REPEAT隐患） | **图集逐格批处理** |
| VBO 更新 | 裸指针 → 悬空 bug | **直写+偏移** |
| 纹理 tiling | LINEAR (兼容性差) | **OPTIMAL** (标准做法) |
| VBO 管理 | 单缓冲 | **双缓冲交替** |
| 可见性 | 无剔除 | **视锥剔除** |
| Autotile | 无 | 预留 bitmask 接口 |
| SpriteBatcher | 栈 768KB (溢出风险) | **小栈16KB+堆扩展** |

## 数据流

```
美术资源 (WebP in drawable-nodpi)
  ├─ decoration_grass_*.webp       — 3 种草装饰变体 → 图集
  ├─ decoration_tree*.webp         — 2 种树装饰变体 → 图集
  └─ 建筑精灵 → 图集（所有建筑统一为 128×128）
        ↓
GameActivity.kt (启动时 LaunchedEffect)
  └─ SectMapTileGenerator.generateTileData() → rawTileData + bitmaskData
        ↓
MapPreloadData (Compose State)
  ├─ rawTileData: Array<IntArray>
  └─ worldWidthCells / worldHeightCells / tileSize / worldPixelWidth / worldPixelHeight
        ↓
MainGameScreen.kt
  ├─ rawTileData + effectivePlacedBuildings → tileData (含 TILE_BUILDING)
  ├─ flatTileData: IntArray → JNI 传递
  ├─ buildingData: FloatArray → JNI 传递 (gridX, gridY, width, height, nameIndex)
  └─ NativeRenderConfig / FrameRenderState → 每帧更新
        ↓
NativeSurfaceView (SurfaceView + RenderThread)
  ├─ surfaceCreated → NativeBridge.initAtlas()
  ├─ surfaceChanged → NativeBridge.initRenderer() → 上传图集纹理 → 启动 RenderThread
  └─ RenderThread 每 100ms:
       1. setCamera (投影矩阵 → Push Constant) [同时更新视口范围]
       2. beginFrame (清空 pending draws + 切换双缓冲 VBO)
       3. drawAllTiles (SpriteBatcher 合并 → 1 draw call, 含视锥剔除)
       4. drawRect(s) (放置预览纯色矩形)
       5. submitFrame (VkQueueSubmit + VkQueuePresentKHR)
        ↓
C++ VulkanBackend (Vulkan 1.1+)
  ├─ 单 Pipeline (固定功能)
  ├─ 单 DescriptorSet (单纹理图集 sampler)
  ├─ 双缓冲 VBO (持久映射，draw() 时直写)
  ├─ Staging buffer (OPTIMAL tiling 纹理上传)
  ├─ 三重缓冲交换链 (3× semaphore + fence)
  ├─ 视锥剔除 (在 drawAllTiles 中基于视口范围跳过不可见格)
  └─ 纹理 0 = 1×1 白色纹理 (供纯色矩形用)
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

定义在 `SectMapTileGenerator`（`core/engine/.../util/`），消费端在 `MainGameScreen.kt` 和 `NativeBridge.cpp::drawDecor`。

## 纹理图集布局

所有地面/装饰/建筑精灵合并到单张 **2048×2048 RGBA8** 纹理：

```
行0 (y=0):     地面(64×64) + 草装饰(64×64×3) + 树(128×128×2)
行1 (y=128):   建筑 A-E  (128×128×4)
行2 (y=256):   建筑 F-J  (128×128×4)
行3 (y=384):   建筑 K-O  (128×128×4)
行4 (y=512):   建筑 P-R  (128×128×3 + 空位)
```

UV 坐标通过 `BUILDING_UV_MAP`（Kotlin）和 `MAP_SPRITES`（C++ TextureAtlas.h）双重定义，必须保持同步。

## 精灵图集构建

`NativeSurfaceView.buildAtlas()` 在渲染器就绪后调用：
1. 创建 2048×2048 `Bitmap`
2. 逐精灵调用 `BitmapFactory.decodeResource` 解码 → `Canvas.drawBitmap` 绘制到图集
3. `uploadBitmap` 将 ARGB 像素转为 RGBA ByteArray 上传到 GPU 纹理
4. 返回纹理 ID 存入 `atlasTextureId`

资源 ID 运行时通过 `context.resources.getIdentifier(name, "drawable", pkg)` 查找。

## 装饰物生成算法

`SectMapTileGenerator.generateTileData()` 使用**平滑噪声地块**方案：

| 装饰类型 | 斑块尺度 | 斑块覆盖 | 斑块内密度 | 算法 |
|---------|---------|---------|-----------|------|
| 草 (3 变体) | 8×8 | ~14%（0.18 密度时） | ~80% | `smoothNoise(scale=8)` 确定草地斑块区域，斑块内密集分布 |
| 树 (TREE1/TREE2) | 12×12 | ~6%（0.18 密度时） | ~35% | `smoothNoise(scale=12)` 确定树丛区域，区域内稀疏分布 |

- **原理**：`smoothNoise()` 在粗网格上采样 `cellHash`，经双线性插值 + smoothstep 产生连续平滑值，相邻格值变化平缓 → 自然地块而非噪点
- **地面变体**：两个地面纹理（地面1/地面2）使用同一方案以 6×6 尺度混合，地面1≈70%、地面2≈30%
- **确定性**：相同输入永远产生相同输出（全部基于 `cellHash` + 固定种子，无 `java.util.Random`）
- **密度控制**：`decorationDensity` 参数 (0.0~1.0)，默认 0.18
- **定义位置**：`core/engine/src/main/java/com/xianxia/sect/core/util/SectMapTileGenerator.kt`

## Vulkan 渲染管线结构

### 着色器

| 着色器 | 作用 | Push Constant | 输入 |
|--------|------|---------------|------|
| `sprite.vert` | 顶点变换 | mat4 投影矩阵 | inPos(vec2), inUV(vec2), inColor(vec4) |
| `sprite.frag` | 纹理采样 | — | sampler2D(纹理图集) × inColor |

### Pipeline 状态

- 顶点输入: 2×float32 (位置) + 2×float32 (UV) + 4×float32 (颜色) = 32 字节/顶点
- 图元: TRIANGLE_LIST
- 混合: 无 (不透明渲染)
- 深度: 关闭
- 面剔除: 关闭 (背面可见)

### 纹理切换

在 `submitFrame()` 中，按 `draw.textureId` 分组：
- **textureId = 0** → 1×1 白色纹理（纯色矩形输出 `white × vertexColor = vertexColor`）
- **textureId > 0** → 对应上传纹理，更新 DescriptorSet 后绘制

## 设备兼容性

`VulkanPolicy` 检测设备 GPU 兼容性：

| 等级 | 行为 |
|------|------|
| SAFE (高通 Adreno) | 正常使用硬件加速 |
| WARNING (国产厂商 + Android 15+) | 日志警告，继续运行 |
| PROBLEMATIC (MTK/已知问题机型) | 禁用硬件加速 |

已知问题：国产 ROM（MIUI/OriginOS/ColorOS）的 Mali GPU Vulkan 驱动兼容性差。
详见 `android-renderthread-crash-research.md`。

## 关键文件索引

| 文件 | 职责 |
|------|------|
| **C++ 渲染引擎** | |
| `app/src/main/cpp/VulkanBackend.cpp/h` | Vulkan 1.1+ 渲染后端（双缓冲 VBO + Staging Buffer） |
| `app/src/main/cpp/Renderer2D.h` | 渲染抽象接口 + 正交投影数学 |
| `app/src/main/cpp/SpriteBatcher.h/cpp` | 精灵批处理构建器（小栈+堆扩展） |
| `app/src/main/cpp/TextureAtlas.h/cpp` | 纹理图集定义 + UV 坐标 |
| `app/src/main/cpp/NativeBridge.cpp` | JNI 桥接（drawAllTiles 统一绘制） |
| `app/src/main/cpp/shaders/sprite.vert/frag` | GLSL 着色器 + SPIR-V 预编译 |
| `app/src/main/cpp/CMakeLists.txt` | NDK CMake 构建配置 |
| **Kotlin 桥接** | |
| `core/engine/.../nativebridge/NativeBridge.kt` | JNI 声明 |
| `feature/game/.../sect/NativeSurfaceView.kt` | SurfaceView + 渲染线程 + 纹理上传/图集构建 |
| `feature/game/.../sect/SectUIState.kt` | 放置/移动/金手指状态 |
| `feature/game/.../sect/NativeRenderConfig.kt` | 渲染配置 data class (内联于 NativeSurfaceView.kt) |
| **游戏逻辑** | |
| `core/engine/.../util/SectMapTileGenerator.kt` | 瓦片数据生成算法 |
| `core/engine/.../util/SectMapTileGeneratorTest.kt` | 生成算法测试（10 用例） |
| `core/domain/.../model/MapPreloadData.kt` | 预加载数据模型（无纹理字段） |
| `app/.../ui/game/GameActivity.kt` | 资源加载 + MapPreloadData 构建 |
| `feature/game/.../MainGameScreen.kt` | tileData 计算 + AndroidView 嵌入 + 手势处理 |
| **兼容性** | |
| `app/.../core/VulkanPolicy.kt` | 设备兼容性检测 |
| `AndroidManifest.xml` | `<uses-feature android:name="android.hardware.vulkan" android:required="false">` |

## 历史版本

| 版本 | 架构 | 问题 |
|------|------|------|
| ≤4.0.40 | 双缓冲 Bitmap 烘焙（frontBuffer/backBuffer） | 残影 bug、复杂增量追踪 |
| 4.0.41 | 统一 Canvas 直接绘制 + `fullMapBmp` 单层位图 | 地面+装饰合并无法独立控制 |
| 4.0.42 | 三层按格实时绘制（Compose Canvas） | Compose 重组开销、主线程渲染 |
| **4.0.43-4.0.44** | **Vulkan 原生渲染管线 v1** | 3 draw calls、独立渲染线程、低功耗 |
| **4.0.45+** | **Vulkan v2 统一图集逐格渲染** | 2 draw calls、无双空指针/LINEAR+REPEAT/栈溢出 bug、视锥剔除 |

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

这些资源不走 `SpriteResRegistry` 注册，而是通过 `NativeSurfaceView.buildAtlas()` 直接解码后上传到 GPU 纹理图集。
