# 宗门地图瓦片渲染系统 — 下一代架构设计

> 基于行业调研（25+ 来源）和当前代码深度分析，对宗门地图 Vulkan 原生渲染管线进行全量升级。
> 
> **目标：** 修复已知 bug + 采用行业最佳实践 + 为未来 Autotile/Biome 扩展预留架构

---

## 当前架构问题清单

### P0 — 正确性缺陷

| # | 问题 | 文件 | 行 | 描述 |
|---|------|------|-----|------|
| 1 | 悬空指针 | `VulkanBackend.cpp` | 1178 | `draw()` 存储栈上裸指针，`submitFrame()` 读取时已悬空 |
| 2 | 纹理未找到时无回退 | `VulkanBackend.cpp` | 1262-1267 | `textureId>0` 但 `m_textures` 中找不到时，descriptor set 不更新 |

### P1 — 兼容性/健壮性缺陷

| # | 问题 | 文件 | 行 | 描述 |
|---|------|------|-----|------|
| 3 | LINEAR tiling + REPEAT | `VulkanBackend.cpp` | 1003,1144 | 非常规组合，部分驱动行为未定义 |
| 4 | 地面无法独立选纹理 | `NativeBridge.cpp` | 153-165 | UV 平铺方式，每格无法独立控制 |
| 5 | 无可见性剔除 | `NativeBridge.cpp` | 186-212 | 屏幕外装饰/建筑也在 batcher 中排队 |
| 6 | SpriteBatcher 栈过大 | `SpriteBatcher.h` | 17 | 768KB 栈上数组超过 Android 线程默认栈（1MB）的 75% |

### P2 — 架构可扩展性缺陷

| # | 问题 | 文件 | 行 | 描述 |
|---|------|------|-----|------|
| 7 | 地面/装饰/建筑分层 batcher | 架构设计 | — | 三层独立，无法做层间混合/过渡 |
| 8 | 无 Autotile 接口 | 架构设计 | — | 无 neighbor bitmask 计算，无法扩展 Biome 过渡 |
| 9 | 无 LOD 架构 | 架构设计 | — | 宗门扩大后全量渲染不可持续 |
| 10 | 纹理上传靠运行时解码 | `NativeSurfaceView.kt` | 58-70 | WebP→ARGB→RGBA，GPU 上为 RGBA32 未压缩，浪费显存 |

---

## 下一代架构设计

### 总体架构：统一单层渲染 + 分通道提交

```
将当前三层分离（ground / decor / building）改为单层统一渲染：
所有瓦片（地面+装饰+建筑）走同一个 SpriteBatcher 流，
提交时按纹理 ID 分组 → 最多 2 个 draw call（图集纹理 + 预览白色纹理）
```

```
渲染管线（从底到顶）：
  Layer 1: 统一瓦片层 — 所有瓦片类型（地面+装饰+建筑）通过 SpriteBatcher 合并
  Layer 2: 预览层 — 纯色矩形（放置/移动预览）

架构变化：
  ┌─ 改前 ──────────────────────┐     ┌─ 改后 ───────────────────────────────┐
  │ Ground:  UV REPEAT × 1 DC   │     │ 统一层:  SpriteBatcher(atlas) × 1 DC │
  │ Deco:    SpriteBatcher ×C    │  →  │ 预览层:  whiteTex × vertexColor      │
  │ Building: SpriteBatcher ×C   │     │ 总共固定 2 draw calls               │
  │ Preview: whiteTex × 1 DC     │     │   (或由 atlas texId 分组 → ≤2 DC)    │
  │ 总共 3-4 draw calls          │     └──────────────────────────────────────┘
  └──────────────────────────────┘
```

### 数据流

```
┌─ Kotlin 侧 ─────────────────────────────────────────────────────────────┐
│ GameActivity                                                             │
│   └─ SectMapTileGenerator.generateTileData(w,h) → Array<IntArray>        │
│        ※ 扩展：增加 autotile bitmask 通道                                 │
│                                                                          │
│ MainGameScreen                                                           │
│   ├─ effectivePlacedBuildings → tileData(合成建筑占位)                    │
│   ├─ flatTileData: IntArray → JNI (地面+装饰+tile类型+位掩码)             │
│   ├─ buildingData: FloatArray → JNI (gx,gy,gw,gh,nameIdx)               │
│   └─ cameraState → camX,camY,scale → JNI                                │
│                                                                          │
│ NativeSurfaceView (RenderThread, 10fps)                                  │
│   ├─ setCamera → JNI → C++: 更新投影矩阵 + 记录视口范围                   │
│   ├─ beginFrame → JNI → C++: 清空 batcher + 重置 VBO offset             │
│   ├─ drawAllTiles → JNI → C++: 统一 SpriteBatcher 合并                  │
│   ├─ drawRect    → JNI → C++: 预览矩形                                   │
│   └─ submitFrame → JNI → C++: 提交 GPU                                  │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓ JNI
┌─ C++ 侧 ─────────────────────────────────────────────────────────────────┐
│ RenderThread 每帧:                                                        │
│   1. setCamera → 更新 projection matrix + push constant                  │
│   2. beginFrame → pendingDraws.clear, vboOffset=0                        │
│   3. drawAllTiles(tileData, buildingData, rows, cols, camera, atlasTex)  │
│      ├─ 可见性检测: tile 世界坐标在视口内?                                 │
│      ├─ 地面格: batcher.add(atlas, x,y, w,h, ground_uv)                 │
│      ├─ 装饰格: batcher.add(atlas, x,y, w,h, decor_uv)                  │
│      ├─ 建筑格: batcher.add(atlas, x,y, w,h, building_uv)               │
│      └─ batcher.end() → draw(vertices, count, atlasTexId)               │
│   4. drawRect → draw(verts, 6, 0) — 白色纹理                             │
│   5. submitFrame                                                        │
│      ├─ 逐 draw cmd 执行:                                                │
│      │  - 纹理切换时 bindDescriptorSet                                   │
│      │  - 直接使用 VBO 已有数据，不 memcpy                                │
│      ├─ vkCmdDraw(cmd.vertexOffset, cmd.count)                          │
│      └─ VkQueueSubmit + VkQueuePresentKHR                               │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 分模块改动设计

### 模块 1：VulkanBackend — 核心渲染后端

#### 1.1 修复悬空指针：DBO（Direct Buffer Output）模式

**改前：**
```cpp
// draw() 存裸指针
void draw(const SpriteVertex* vertices, int count, uint32_t textureId) {
    m_pendingDraws.push_back({ vertices, count, textureId });
}
// submitFrame() 从野指针 memcpy
memcpy(vboPtr + offset, draw.vertices, copySize);
vkCmdDraw(cmd, draw.count, 1, offset, 0);
```

**改后：**
```cpp
// 1. DrawCommand 改为 VBO 偏移量
struct DrawCommand {
    uint32_t vertexOffset;  // VBO 中顶点偏移（单位：顶点数）
    int count;              // 顶点数
    uint32_t textureId;     // 纹理 ID
};

// 2. draw() 立即写入持久映射 VBO
int m_vboOffset = 0;  // 当前 VBO 写入位置（字节偏移）

void draw(const SpriteVertex* vertices, int count, uint32_t textureId) {
    if (!m_ready || count == 0) return;
    size_t copySize = count * sizeof(SpriteVertex);
    if (m_vboOffset + copySize > m_vertexBufferSize) return; // VBO 满保护
    memcpy((char*)m_vertexMapped + m_vboOffset, vertices, copySize);
    m_pendingDraws.push_back({
        .vertexOffset = m_vboOffset / sizeof(SpriteVertex),
        .count = count,
        .textureId = textureId
    });
    m_vboOffset += copySize;
}

// 3. beginFrame() 重置 VBO offset
void beginFrame() {
    m_pendingDraws.clear();
    m_vboOffset = 0;
}

// 4. submitFrame() 直接提交已有 VBO 数据
for (auto& draw : m_pendingDraws) {
    // 纹理切换逻辑不变
    vkCmdDraw(cmd, draw.count, 1, draw.vertexOffset, 0);
}
```

#### 1.2 OPTIMAL tiling + Staging Buffer 纹理上传

**改前：** `VK_IMAGE_TILING_LINEAR` + 直接 memcpy + `vkCmdPipelineBarrier`

**改后：** 标准三步：
```
1. 创建 VK_IMAGE_TILING_OPTIMAL 图像
   → 使用 VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
   
2. 创建 staging buffer (HOST_VISIBLE | HOST_COHERENT)
   → 像素数据写入 staging buffer
   
3. 单次 command buffer:
   a) vkCmdCopyBufferToImage(stagingBuffer, image)
   b) Pipeline barrier: TRANSFER_DST → SHADER_READ_ONLY_OPTIMAL
```

**新增成员变量（VulkanBackend.h）：**
```cpp
VkBuffer m_stagingBuffer = VK_NULL_HANDLE;
VkDeviceMemory m_stagingMemory = VK_NULL_HANDLE;
size_t m_stagingBufferSize = 8 * 1024 * 1024; // 8MB pool
```

**注意：** staging buffer 可复用（上传后不销毁），只在大纹理超过当前大小时重建。

#### 1.3 纹理回退保护

在 `submitFrame()` 的纹理绑定循环中，增加未找到时的回退：
```cpp
if (draw.textureId != currentBoundTexId) {
    currentBoundTexId = draw.textureId;
    if (draw.textureId == 0) {
        bindTextureToDescriptor(m_whiteTexture);
    } else {
        bool found = false;
        for (const auto& tex : m_textures) {
            if (tex.id == draw.textureId) {
                bindTextureToDescriptor(tex);
                found = true;
                break;
            }
        }
        if (!found) {
            bindTextureToDescriptor(m_whiteTexture); // 回退白色
            currentBoundTexId = 0; // 下次重新绑定
        }
    }
    vkCmdBindDescriptorSets(...);
}
```

#### 1.4 双缓冲 VBO 防冲突

当前单 VBO + 每帧覆写 + 三重缓冲交换链中，可能出现 GPU 仍在使用上一帧 VBO 数据时 CPU 开始写入新帧。改为 **双 VBO 交替**：

```cpp
// 交替缓冲
VkBuffer m_vertexBuffers[2];
VkDeviceMemory m_vertexMemories[2];
void* m_vertexMapped[2];
int m_activeBuffer = 0;

// 每帧在 beginFrame 中切换
void beginFrame() {
    m_pendingDraws.clear();
    m_vboOffset = 0;
    m_activeBuffer = (m_activeBuffer + 1) % 2;
}

// submitFrame 中绑定当前 buffer
vkCmdBindVertexBuffers(cmd, 0, 1, &m_vertexBuffers[m_activeBuffer], offsets);
```

---

### 模块 2：NativeBridge — JNI 桥接重构

#### 2.1 合并绘制接口

**改前：** 三个独立的 JNI 函数 + 各一套参数
- `drawGround(worldW, worldH, textureId, tilesX, tilesY)`
- `drawDecor(tileData, cols, rows, ..., atlasTexId, uvMap)`
- `drawBuildings(buildingData, count, ..., atlasTexId, buildingUVMap)`

**改后：** 统一为 `drawAllTiles` + `drawRect` 两个函数

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawAllTiles(
    JNIEnv* env, jobject /*thiz*/,
    jintArray tileData,     // 展平的瓦片类型数组 [0..N]
    jfloatArray buildingData, // [gx,gy,gw,gh,nameIdx] × 每个建筑
    jint buildingCount,
    jint cols, jint rows,   // 地图尺寸
    jint firstCol, jint lastCol, jint firstRow, jint lastRow, // 可见范围
    jint tileSize,
    jint atlasTexId,        // 图集纹理 ID
    jfloatArray uvMap       // UV 映射表 [u0,v0,u1,v1] × tileTypeCount
)
```

**`drawAllTiles` 实现逻辑：**
```
1. 获取 tileData/buildingData/uvMap 的 JNI 数组指针
2. 创建 SpriteBatcher
3. 遍历可见范围的行列:
   a. 获取 tile = tiles[row * cols + col]
   b. 跳过 tile == TILE_BUILDING(6) (由 buildingData 处理)
   c. 计算世界坐标: wx = col * tileSize, wy = row * tileSize
   d. 视锥可见性检测: 矩形 (wx, wy, tileSize, tileSize) 与视口相交?
   e. 不可见则跳过
   f. 从 uvMap 获取 tile 对应的 UV
   g. batcher.add(atlasTexId, wx, wy, tileSize, tileSize, u0,v0,u1,v1)
4. 遍历 buildingData:
   a. 每个建筑 5 个 float: gx,gy,gw,gh,nameIdx
   b. 视锥可见性检测
   c. 从 buildingUVMap 获取 UV
   d. batcher.add(...)
5. batcher.end() → g_renderer->draw(batcher.vertices, count, atlasTexId)
6. 释放 JNI 数组指针
```

#### 2.2 相机视口信息传递

在 `setCamera` 中额外计算并存储当前视口的世界坐标范围：
```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setCamera(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat camX, jfloat camY, jfloat scale,
    jint vpW, jint vpH) {

    cameraProjMatrix(g_projMatrix, camX, camY, scale, (float)vpW, (float)vpH);
    if (g_renderer) g_renderer->setProjection(g_projMatrix);
    
    // 新增：存储视口世界范围（用于可见性检测）
    g_viewLeft   = camX;
    g_viewTop    = camY;
    g_viewRight  = camX + vpW / scale;
    g_viewBottom = camY + vpH / scale;
}
```

---

### 模块 3：SpriteBatcher — 栈大小优化

#### 3.1 将顶点数组改为堆分配

**改前（栈上 768KB）：**
```cpp
struct SpriteBatcher {
    SpriteVertex vertices[MAX_VERTICES]; // 24576个 × 32B = 768KB 栈！
};
```

**改后（堆上 + 小栈缓冲）：**
```cpp
struct SpriteBatcher {
    // 小栈预分配（~16KB），超出时堆分配
    static constexpr int STACK_THRESHOLD = 512; // 512 顶点 = 16KB 栈
    SpriteVertex* vertices;                     // 堆指针或栈指针
    SpriteVertex stackBuffer[STACK_THRESHOLD];  // 栈缓冲
    int vertexCount = 0;
    int capacity;
    
    void begin(const float projection[16]) {
        clear();
        vertices = stackBuffer;  // 默认用栈缓冲
        capacity = STACK_THRESHOLD;
    }
    
    void add(...) {
        if (vertexCount + 6 > capacity) grow();  // 超限时切换到堆
        ...
    }
    
    void grow() {
        int newCap = capacity * 2;
        auto* newBuf = new SpriteVertex[newCap];
        memcpy(newBuf, vertices, vertexCount * sizeof(SpriteVertex));
        if (vertices != stackBuffer) delete[] vertices;
        vertices = newBuf;
        capacity = newCap;
    }
    
    ~SpriteBatcher() {
        if (vertices != stackBuffer) delete[] vertices;
    }
};
```

**进一步优化：** 结合 VBO 直写模式，SpriteBatcher 的 `add()` 实际上可以直接写入 VBO，而不是先收集再提交。但保持现有批处理架构更清晰，改动最小。

---

### 模块 4：SectMapTileGenerator — 扩展 Autotile 接口

#### 4.1 增加 Autotile bitmask 计算层

```kotlin
object SectMapTileGenerator {
    // 现有瓦片类型（扩展范围）
    const val TILE_GROUND       = 0
    const val TILE_GRASS_SMALL  = 1
    const val TILE_GRASS_MEDIUM = 2
    const val TILE_GRASS_LARGE  = 3
    const val TILE_TREE1        = 4
    const val TILE_TREE2        = 5
    const val TILE_BUILDING     = 6
    
    // Autotile 扩展（使用高字节存储 bitmask）
    // tile类型: 低4位 = 基础类型 (0-6), 高4位 = biome/变体
    // 另外生成一个 parallel bitmask mask 数组
    
    /**
     * 生成带 Autotile bitmask 的瓦片数据。
     * 
     * 输出:
     * - tileData: Array<IntArray> — 瓦片类型（不变）
     * - bitmaskData: Array<IntArray> — 8-bit neighbor bitmask（新增）
     */
    fun generateTileData(
        worldWidthCells: Int,
        worldHeightCells: Int,
        decorationDensity: Float = 0.30f
    ): TileGenerationResult {
        val tileData = generateBaseTiles(worldWidthCells, worldHeightCells, decorationDensity)
        val bitmaskData = computeAutotileBitmask(tileData)
        return TileGenerationResult(tileData, bitmaskData)
    }
    
    /**
     * 计算 8-bit blob tile bitmask。
     * 角邻居规则: 对角线仅在相邻两卡也匹配时计入。
     */
    fun computeAutotileBitmask(tileData: Array<IntArray>): Array<ByteArray> {
        val h = tileData.size
        val w = tileData[0].size
        val mask = Array(h) { ByteArray(w) { 0 } }
        
        for (y in 1 until h-1) {
            for (x in 1 until w-1) {
                val here = tileData[y][x]
                if (here == TILE_BUILDING || here == TILE_TREE1 || here == TILE_TREE2) continue
                
                val n  = if (tileData[y-1][x]   == here) 1 else 0
                val s  = if (tileData[y+1][x]   == here) 1 else 0
                val w  = if (tileData[y][x-1]   == here) 1 else 0
                val e  = if (tileData[y][x+1]   == here) 1 else 0
                val nw = if (tileData[y-1][x-1] == here && n == 1 && w == 1) 1 else 0
                val ne = if (tileData[y-1][x+1] == here && n == 1 && e == 1) 1 else 0
                val sw = if (tileData[y+1][x-1] == here && s == 1 && w == 1) 1 else 0
                val se = if (tileData[y+1][x+1] == here && s == 1 && e == 1) 1 else 0
                
                mask[y][x] = (n | (ne<<1) | (e<<2) | (se<<3) | 
                              (s<<4) | (sw<<5) | (w<<6) | (nw<<7)).toByte()
            }
        }
        return mask
    }
}

data class TileGenerationResult(
    val tileData: Array<IntArray>,
    val bitmaskData: Array<ByteArray>
)
```

**注意：** 此阶段只添加 bitmask 计算能力，**不要求图集中立刻有 47 张过渡瓦片**。架构预留，实际 autotile 瓦片的艺术资源可以后续补充。

---

### 模块 5：NativeSurfaceView — 简化

#### 5.1 移除独立地面纹理

**改后 `onRendererReady`：**
```kotlin
view.onRendererReady = {
    // 只构建图集（地面/装饰/建筑全部在图集中）
    view.atlasTextureId = view.buildAtlas(ctx)
}
```

#### 5.2 移除 groundTextureId 相关字段

```kotlin
// 删除
@Volatile var groundTextureId: Int = 0
// 删除 uploadBitmap 中地面纹理上传的相关逻辑
```

#### 5.3 简化 RenderThread

```kotlin
// 改后每帧渲染序列
NativeBridge.setCamera(camX, camY, scale, width, height)
NativeBridge.beginFrame()

// 统一瓦片绘制
NativeBridge.drawAllTiles(
    tileData = td, buildingData = bd, 
    buildingCount = bc,
    cols = worldWidthCells, rows = worldHeightCells,
    firstCol = 0, lastCol = worldWidthCells - 1,
    firstRow = 0, lastRow = worldHeightCells - 1,
    tileSize = config.tileSize,
    atlasTexId = atlasTextureId,
    uvMap = decorUvMap + buildingUVMap  // 合并 UV 映射表
)

// 预览
if (showPlacementPreview) {
    NativeBridge.drawRect(previewX, previewY, previewW, previewH,
                          previewR, previewG, previewB, previewA)
}

NativeBridge.submitFrame()
```

---

### 模块 6：纹理图集布局重构

#### 6.1 新图集布局

```
2048×2048 图集布局（重构）：
行0 (y=0):      地面(64×64) × 8  =  512px   ← 8 个地面变体（支持 autotile 过渡）
行0-后段:       草装饰(64×64) × 3 =  192px   ← 移到行0后段
行1 (y=128):    树(128×128) × 2  +  建筑(128×128) × 2
行2-4 (y=256):  建筑(128×128) × 4/行  ← 不变
行5:            作物/其他
```

**地面变体预留 8 个槽位（64×64）：**
| 偏移 | 名称 | 用途 |
|------|------|------|
| 0 | ground_tile_base | 基础地砖 |
| 1-7 | ground_tile_var1-7 | 变体/过渡保留 |

#### 6.2 UV 映射表重构

当前 UV 映射表是 Kotlin 中硬编码的 `decorUvMap` + `BUILDING_UV_MAP` 两个独立数组。

**改后：** 合并为单张 UV 查找表，tileType 直接索引（或通过 JNI 在 C++ TextureAtlas 中查询）：
```kotlin
// Kotlin 侧只传 tileTypeCount + atlasTexId
// C++ TextureAtlas 持有 UV 数据，根据 tileType 查询
```

但更简单的过渡方案是保持 `uvMap: FloatArray` 从 Kotlin 传入，因为 tileType 编码由 Kotlin 侧控制。

---

## GLSL 着色器

当前使用了单 Pipeline + 单 DescriptorSet 模式，着色器无需修改。关键特性：
- `sprite.vert`：Push Constant 投影矩阵 × 顶点位置 → NDC
- `sprite.frag`：`texture(sampler2D, inUV) × inColor`

**无需改动。** 当前的着色器设计（纹理采样 × 顶点颜色）已经是 2D 渲染的最简高效方案。

---

## 验证方案

### 编译验证
```bash
cd android
./gradlew.bat compileReleaseKotlin     # Kotlin 编译
# NDK 编译在 Kotlin 编译中自动触发（CMakeLists.txt）
./gradlew.bat assembleDebug            # 完整 APK 构建
```

### 运行时验证列表

| 验证项 | 预期结果 | 验证方法 |
|--------|---------|---------|
| 宗门地图显示 | 地面显示地砖纹理（非纯色） | 目测 + 截屏对比 |
| 装饰物渲染 | 草/树正确显示在对应格位置 | 目测 |
| 建筑渲染 | 所有建筑正确显示在对应网格 | 点击建筑应触发对应对话框 |
| 相机拖动 | 拖动时地图平滑移动，无闪烁 | 手势测试 |
| 相机缩放 | 缩放时所有图层正确缩放 | 双指缩放测试 |
| 放置预览 | 绿色半透明矩形显示在放置位置 | 进入放置模式 |
| 移动建筑 | 建筑跟随手指移动 | 长按建筑进入移动 |
| 金手指功能 | 框选区域正确 | 进入金手指模式 |
| 内存 | 无渐进式增长（纹理/Buffer 无泄漏） | Android Studio Profiler |
| 单位测试 | 通过 | `./gradlew.bat test --max-workers=1` |
| 旋转屏幕 | Surface 重建后渲染恢复正常 | 旋转手机 |

### 回归测试
```bash
./gradlew.bat testReleaseUnitTest --max-workers=1   # 单元测试（必须串行）
```

---

## 关键文件变更清单

| 文件 | 改动类型 | 概要 |
|------|---------|------|
| `cpp/VulkanBackend.h` | 修改 | DrawCommand 改偏移量；新增 m_vboOffset、双缓冲数组、staging buffer 成员 |
| `cpp/VulkanBackend.cpp` | 重写 | draw()→VBO 直写；beginFrame()→reset offset；submitFrame()→直接提交；uploadTexture()→OPTIMAL+staging |
| `cpp/NativeBridge.cpp` | 重写 | drawGround/drawDecor/drawBuildings → drawAllTiles 合并；setCamera 加视口范围 |
| `cpp/SpriteBatcher.h` | 修改 | 栈数组改为小栈+堆扩展 |
| `cpp/SpriteBatcher.cpp` | 修改 | add() 增加 grow() 调用 |
| `cpp/TextureAtlas.h` | 不改 | UV 定义兼容 |
| `nativebridge/NativeBridge.kt` | 修改 | 同步 JNI 接口定义 |
| `sect/NativeSurfaceView.kt` | 修改 | 删 groundTextureId；RenderThread 用 drawAllTiles |
| `sect/NativeRenderConfig.kt` | 不改 | （已内联在 NativeSurfaceView.kt） |
| `ui/MainGameScreen.kt` | 微调 | 更新 FrameRenderState 传递 |
| `util/SectMapTileGenerator.kt` | 扩展 | 增加 `computeAutotileBitmask()` + `TileGenerationResult` |
| `util/SectMapTileGeneratorTest.kt` | 扩展 | 增加 bitmask 计算测试 |
| `docs/map-rendering-architecture.md` | 更新 | 同步架构文档 |

---

## 实施路线

```
Phase 1：核心修复（P0）
  ├─ 修复悬空指针：DrawCommand 改偏移 + draw() 直写 VBO
  ├─ 添加纹理回退保护
  └─ 验证：地面和装饰正常工作

Phase 2：地面统一到图集
  ├─ 合并 JNI 接口为 drawAllTiles
  ├─ 删除 groundTextureId 和独立地面渲染
  ├─ 地面格在图集中取 UV 逐格渲染
  └─ 验证：地面显示地砖纹理，装饰/建筑正常

Phase 3：架构加固
  ├─ OPTIMAL tiling + staging buffer 纹理上传
  ├─ SpriteBatcher 栈优化
  ├─ 双缓冲 VBO
  └─ 验证：全量运行无内存泄漏

Phase 4：可见性剔除
  ├─ setCamera 增加视口范围计算
  ├─ drawAllTiles 中增加视锥检测
  └─ 验证：屏幕外 tile 被跳过

Phase 5：Autotile 扩展（可选）
  ├─ SectMapTileGenerator 增加 bitmask 计算
  ├─ 图集增加过渡瓦片槽位
  └─ 验证：biome 边界自然过渡
```
