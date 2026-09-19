# 批次 B13 完成报告（R3.8 — R3 渲染阶段收官批）

> 批次文件：`docs/parallel-batches-w5/batch-R3D.md`
> 完成结论：**accepted**（2026-09-19 20:35）
> 台账：`docs/parallel-batches-w5/dispatch-ledger.md` B13 行
> 性质：**通道交付批** —— 浮字通道建成并自检，**零生产消费面接入**

## 提交号列表（逐子项）

| 子项 | 提交号 | 说明 |
|---|---|---|
| R3.8-① Tier1 文本资产管线 | `6db34bb0a` | `build-atlas.mjs` +331 行（LAYOUT.tier1 + 三函数 + codegenSource 接入）+ `scene_uv_tables.h` 重生成 |
| R3.8-② 浮字对象池 + 动画核心 | `008455266` | 新建 `app/src/main/cpp/scene/float_text.h`（池 + 动画纯函数，零 Android 依赖） |
| R3.8-③ spawn 通道 + ④ 渲染集成 | `3a7d0e3de` | `scene_draw.h::buildFloatTextBatch` + `NativeBridge.kt` 豁免登记 + Kotlin 守卫 5 用例 + CMakeLists |
| R3.8-④ 场景回归集 | `387d60003` | 新建 `scene_pixel_regression_test.cpp` 12 用例 + `golden/scene_regression_golden.txt` 22 键入库 |
| R3.8-②/③ 补遗 | `cc7770376` | `NativeBridge.cpp` 接线（池 + spawn 端口 + drawFrame 尾部 + 纪元复位）+ `scene_floating_text_test.cpp` 35 用例 |
| 文档三件套 + 清单 | `e77d5062e` | 方案 §7.2 / CHANGELOG 4.01.15 / cpp-engine 进展行 / renderer-feature-checklist（顺带补回被误吞的 B12 标题行） |
| detekt 打回修复 | `2ad93d566` | MaxLineLength ×3 折行（零语义改动） |
| 台账 | `d489847c5` | B13 → accepted + 证据列 |

**合并说明**：批次文件允许「按实际耦合合并为 ≥3 笔，但合并须说明理由」。实际情况：
- `NativeBridge.cpp` **单文件同时承载 ② 池接线与 ③ spawn 端口**（池 globals、drawFrame 累加与
  advance、shutdown 纪元复位、spawn JNI 端口），按子项强行拆碎会产生不可编译的中间态，
  故合并为一笔补遗提交并在提交信息内注明。
- ③ 与 ④ 在 `scene_draw.h` 上耦合（`kFloatSpawnStride` 常量 + `buildFloatTextBatch`），
  且同属「通道可用性」验收位，合并为一笔。
- 最终 **6 笔代码/文档 + 1 笔台账**，符合 ≥3 笔下限。

## 验收门逐门实证（看护亲跑）

### 门 1 — 桌面全量 GTest 全绿 ✅

```
1538/1538 Test #1538: AppointmentTxFixture.DispatchEnvelopeHappyAndFailure ... Passed 0.26 sec
100% tests passed, 0 tests failed out of 1538
Total Test time (real) = 447.96 sec
CTEST_EXIT=0
```

- **1538** = B12 基线 **1494** + 本批 **44**（`scene_floating_text_test.cpp` 35 +
  `scene_pixel_regression_test.cpp` 12 − 3 既有夹具复用重叠口径；以 `--gtest_filter` 实测
  **47 用例 6 套件**为准：`'SceneFloatingText*:*FloatText*:ScenePixelRegression*'`）。
- 对拍桥：`android/core/engine/build/desktop-jni/libgamecorejni.so`（9625600 B）；
  **对源即时核验** —— 除两个测试 `.cpp`（不入桥）外无源文件晚于该 `.so`。
- `ctest` 已将 llvm-mingw `bin` 入 PATH（否则 `libc++.dll`/`libunwind.dll` 缺失 ⇒ 全量
  `0xc0000135` 假失败）。
- 分套件实测：`ScenePixelRegressionTest` **12/12**、`*FloatText*:*FloatingText*` **40/40**、
  `*FloatTextPool*:*FloatTextTelemetry*` **18/18**、`SceneOverlayEquivalenceTest` **16/16**
  （含 G4 打印）。

### 门 2 — 全量 JUnit 实跑非 UP-TO-DATE ✅

```
BUILD SUCCESSFUL in 41m 48s
229 actionable tasks: 229 executed
TEST_EXIT=0
```

六模块 XML 汇总（`**/build/test-results/testReleaseUnitTest/TEST-*.xml`，mtime 19:55–20:10 实证）：

| 模块 | XML 文件数 | tests | fail | error | skip |
|---|---:|---:|---:|---:|---:|
| `:core:engine` | 323 | **3363** | 0 | 0 | 0 |
| `:core:domain` | 87 | 1743 | 0 | 0 | 0 |
| `:app` | 88 | 1004 | 0 | 0 | **2**（既有） |
| `:feature:game` | 83 | 886 | 0 | 0 | 0 |
| `:core:data` | 62 | 716 | 0 | 0 | **15**（既有） |
| `:core:ui` | 20 | 146 | 0 | 0 | 0 |
| **TOTAL** | **663** | **7858** | **0** | **0** | **17**（既有） |

- `:core:engine` **3363** = B12 基线 3358 + 本批 5（`SceneUvTablesMirrorGuardTest` 新增 5 用例）。
- **`Diff*` 50 类 273 用例 0 fail 0 skip** —— 对拍桥**真加载**（非跳过）。
- `SceneUvTablesMirrorGuardTest` **11/11** 0 fail 0 skip（含本批 5：Tier1 UV 表 `toRawBits`
  逐位 / 资产计数与索引 / 冻结词表与字形表文本 / 样式档索引 / spawn 步长 ↔ `FLOAT_SPAWN_STRIDE`）。
- `SceneUpdateChannelTest` **9/9**（G4 Kotlin 侧口径，含每帧 JNI 对照打印）。
- `detekt` + `lintRelease` 六模块：`BUILD SUCCESSFUL in 4m 43s`（`LINT_EXIT=0`）；
  `:core:engine:detekt` 曾报 3 条 `MaxLineLength`，折行后转绿（`2ad93d566`，零语义改动）。
- 已知抖动 `GameEngineCoreLifecycleInterleavingTest` **本轮未触发**（12/12 通过）。

### 门 3 — G3/G4 前后对照（守卫实跑打印值，非文档声明）✅

**G4 — 放置模式整帧 vkCmdDraw 预算**（`SceneOverlayEquivalenceTest.PlacementModeFullFrameDrawCallBudget`）：

```
[G4 整帧] 放置模式最坏帧世界内容 draw call = 12（地图 1 + 崖壁最坏 8 段 + 叠加层 3），
          另加天空 1 = 13，目标 < 15
```

| 口径 | B11 基线 | B12 基线 | **B13 本批** | 目标 | 判定 |
|---|---:|---:|---:|---:|---|
| 叠加层 draw call（128×128 整岛最坏帧） | 276 | 3 | **3** | — | 维持 |
| 世界内容 draw call（地图+崖壁+叠加层） | — | 12 | **12** | — | 维持 |
| **整帧 vkCmdDraw（+天空）** | — | 13 | **13** | **< 15** | ✅ 达成 |
| 浮字批占用（空池） | — | — | **0** | 零开销 | ✅ 达成 |
| 浮字批占用（多实例同图集） | — | — | **1** | 1 draw call | ✅ 达成 |

> 浮字层为**条件层**：仅当 `g_floatPool.activeCount() > 0` 且 `g_sceneAtlasTexId != 0`
> 才 `begin/end` 一次；**空池 = 不 begin/不 submit = 0 draw call**，故 G4 整帧预算
> 在浮字功能引入后**逐位不变**（13）。多字形串与多实例按连续资产索引合批至**单纹理段**，
> 20 实例仍 1 draw call（`FloatTextBatchTest.ManyInstancesStillSingleDraw`）。

**G3 — 稳态每帧 JNI 传输字节 < 200B**：

| 口径 | 数值 | 判定 |
|---|---|---|
| `drawFrame` 参数个数 | **8**（`camX, camY, scale, vpW, vpH, overlayFlags, fadeAlpha, frameAlpha`） | **签名零变更** |
| 每帧传输字节 | ≈ **36 B**（8 标量） | ✅ **< 200 B** |
| 浮字活跃期**每帧 JNI 次数** | **0**（动画由 C++ 内部 `g_floatNowSeconds` 自驱动） | ✅ **= 0** |
| spawn 跨线频率 | 仅事件驱动（低频），**禁止**帧循环轮询 | ✅ 纪律成立 |

**ABI 规避决策（关键）**：把「动画时间」做成 `drawFrame` 第 9 参属 **ABI 变更**（须单独
登记豁免 + 双端同步 + 全部既有等价守卫更新并逐条复跑）。本批**规避**——改用 C++ 内部
**固定标称帧步长累加器**：

```cpp
g_floatNowSeconds += kFloatFrameStepSeconds;      // 1/60 s
if (g_floatNowSeconds > kFloatTimeWrapSeconds) {  // 1e6 s 回绕
    g_floatNowSeconds = 0.0f;
}
```

`drawFrame` 保持 8 参数签名不动 ⇒ **既有 JNI 签名零变更**，无需新豁免。

### 门 4 — 场景回归集可复跑 ✅

**实现形态**（`scene_pixel_regression_test.cpp`，测试代码内实现）：

```
顶点流（RecorderRenderer / SoftRasterRenderer::draw）
  → 世界坐标 → NDC → 帧缓冲像素（project() 复刻 cameraProjMatrix）
  → 扫描线软光栅（整数采样 + float 重心插值 + source-over 混合）
  → 像素缓冲（pixels_ + covered_ 双缓冲）
  → FNV-1a64 像素校验和 → golden 比对
```

**覆盖矩阵**（22 键 golden，覆盖六要素场景 + 浮字四场景 × 相机三档位）：

| 场景族 | 相机档位 | 键 |
|---|---|---|
| `terrain_only` | near 2.0 / mid 1.0 / far 0.3 | 3 |
| `full_scene`（六要素） | 三档位 | 3 |
| `full_scene_overlays`（叠加层全开） | 三档位 | 3 |
| `float_birth`（出生帧） | 三档位 | 3 |
| `float_rising`（上浮中） | 三档位 | 3 |
| `float_fading`（淡出） | 三档位 | 3 |
| `float_saturated`（池满覆盖） | 三档位 | 3 |
| `float_layer_top`（层序证明） | mid | 1 |
| **合计** | | **22** |

**golden 关键数据**（入库原文抽样）：

```
terrain_only/near=9f88520b67ad3488:1:230400
full_scene/near=9f88520b67ad3488:1:230400
float_saturated/mid=761d7a5fb8e90e0e:2:112046
float_layer_top=f448415c45459a02:2:106766
```

**复跑命令**（一条命令全绿）：

```bash
cd android/app/src/main/cpp/gamecore/build/desktop-test/test
export PATH="<llvm-mingw>/bin:<llvm-mingw>/x86_64-w64-mingw32/bin:$PATH"
./game-core-tests.exe --gtest_filter='ScenePixelRegressionTest.*'
# => 12 tests from 1 test suite ran. [ PASSED ] 12 tests.
```

**golden 变更重生流程**（显式、绝不自动改写）：

```bash
SCENE_GOLDEN_UPDATE=1 ./game-core-tests.exe --gtest_filter='ScenePixelRegressionTest.*'
# 逐键打印前后校验和，写入 test/golden/scene_regression_golden.txt
# 变更须在提交信息说明原因
```

**反静默失效守卫**（防"绿得没意义"）：
- `CameraTiersProduceDistinctPixels` —— 三档位像素**互不相同**（防相机静默失效）；
- `FloatTextLifecycleFramesDiffer` —— 浮字三帧像素**互不相同**（防动画静默失效）；
- `EmptyFloatPoolEqualsSceneWithoutFloatLayer` —— **空池 = 无浮字层逐位相同**（零影响证明）；
- `FloatLayerDrawsOnTopOfScene` —— 浮字层在叠加层之上；
- `GoldenBaselineIsPresentAndWellFormed` —— 基线存在且格式自洽。

**纹理源**：程序化 **32×32 棋盘渐变图案**（测试环境无 ASTC 解码器，不读真实图集 KTX）；
`-ffp-contract=off` 已钉死 FMA 融合保证 FP 确定性（B11 前置缺陷 B）。

**残余登记（不粉饰）**：真机 GPU 截图回归需**设备农场**（基建未建，延续 B11/B12 残余③）；
Adreno 黑名单补全需真机复现后逐条登记。

### 门 5 — 池语义守卫 ✅

`scene_floating_text_test.cpp` **35 用例 5 套件**（实跑上下文 `*FloatTextPool*:*FloatTextTelemetry*`
= 18/18，全套 40/40）：

| 套件 | 用例 | 覆盖 |
|---|---:|---|
| `FloatTextPoolTest` | **14** | 容量常量显式命名 / 入池回收 / **池满覆盖最旧**（`PoolOverflowOverwritesOldestAndCountsTelemetry` + `RepeatedOverflowKeepsOverwritingOldest`）/ **纪元复位**（`ResetClearsInstancesEpochAndTelemetry`）/ **参数防御五路**（非有限坐标 / 资产索引越界 / 样式+字数越界 / 缩放与 riseScale 非法 / 时间卫生）/ 序号严格单调 / 无事件零活跃 |
| `FloatTextAnimationTest` | 6 | 上浮单调 / 淡出归零 / 暴击弹跳窗内缩 / 非弹跳恒单位缩放 / 负龄钳到出生 / 元数据携带 |
| `FloatTextBatchTest` | 10 | **空池零 draw call** / 全过期零 draw call / 单实例单纹理段 6 顶点 / 多字形连续单 draw / 20 实例仍单 draw / 顶点流合 `SpriteBatcher` 契约 / 样式色逐实例 / 视外剔除 / 缺图集或 UV 整层跳过 / UV 越界回落不越读 |
| `FloatTextTelemetryTest` | 3 | 三计数器精确 / **降级帧恒 0**（本特性不虚构降级行为）/ 溢出旗标单帧作用域 |
| `FloatTextAssetContractTest` | 2 | 生成表自洽 / 样式档覆盖四语义桶 |

**关键用例名（实跑摘录）**：

```
[       OK ] FloatTextPoolTest.CapacityAndConstantsAreExplicitlyNamed
[       OK ] FloatTextPoolTest.PoolOverflowOverwritesOldestAndCountsTelemetry
[       OK ] FloatTextPoolTest.ResetClearsInstancesEpochAndTelemetry
[       OK ] FloatTextPoolTest.SpawnRejectsNonFiniteCoordinatesWithoutResidue
[       OK ] FloatTextPoolTest.SpawnRejectsOutOfRangeAssetIndex
[       OK ] FloatTextPoolTest.SpawnRejectsInvalidStyleAndCharCount
[       OK ] FloatTextPoolTest.SpawnRejectsInvalidScaleAndRiseScale
[       OK ] FloatTextPoolTest.SpawnSequenceIsStrictlyMonotonic
```

### 门 6 — 文档三件套 + 渲染特性清单 ✅

| 文档 | 落点 |
|---|---|
| `docs/native-engine-refactor-plan-2026-09-17.md` | §7.2 新增 B13 块（4 子项行 + 浮字/像素守卫行 + 三后端红线自查行 + 测试口径段）；**顺带补回被上一笔编辑误吞的 B12 标题行** |
| `CHANGELOG.md` | `## [4.01.15] - 2026-09-17` 段内新增 B13 块（五条要点 + 门禁 + 残余登记） |
| `docs/cpp-engine.md` | R3.8/B13 进展行（含软光栅三处关键坑 + 三层提交语义差异） |
| `android/docs/renderer-feature-checklist.md` | 新增 5 行：`floating_text_pool` / `floating_text_tier1_assets` / `floating_text_spawn` / `floating_text_batch` / `scene_pixel_regression` |

## Tier1 词表冻结清单 + 资产生成管线

**冻结词表**（8 条，`kFloatWordCount=8`）：
`会心` / `格挡` / `闪避` / `连击` / `暴击` / `破防` / `吸血` / `免疫`

**冻结字形表**（40 个，`kFloatGlyphCount=40`）：
`0 1 2 3 4 5 6 7 8 9`（10）+ `A..Z`（26）+ `+ - . %`（4）

**样式档**（4 档，`kFloatStyleCount=4`）：`FLOAT_STYLE_NORMAL(0)` 白 / `CRIT(1)` 金 /
`HEAL(2)` 绿 / `WARN(3)` 红

**资产索引**：`kFloatAssetCount=48`（词条 0..7 + 字形 `kFloatGlyphBaseIndex=8` .. 47）

**图集空间**：既有内容最低边界 y=3640；`rowH=224 + gutter 8 + rowH=224 = 456` 恰好填满
y3640..4096（余 264px）。词条行 8 格 × 329px、字形行 40 格 × 88px。

**光栅化**（sharp / libvips / pango，支持 CJK）：固定字号渲染 → 按**共用缩放因子**
`resize contain` → `composite` 居中入格。

**两个踩坑（已固化在代码注释）**：
1. `sharp().trim()` 对极端纵横比字形（`-` 为 7×2）报 `rank: window too large` ⇒ **彻底弃用 trim**；
2. 逐字形 `contain` 拉满格会让 `-` 变成**黑条** ⇒ 改为**全部字形共用同一缩放因子**
   （= 基准全角字形 ink 高 ÷ 格高），视觉相对大小自然正确。

**codegen hash 门证据**：`codegenSource()` 已追加 `tier1Entries` / `tier1SpriteName` /
`tier1GlyphScale` / `rasterizeTier1Cell` / `tier1CppLines` / `tier1KotlinLines` 六个函数源码
入 hash；`node scripts/build-atlas.mjs --codegen` 与 `--atlas-def-only` 均成功；
生成头 `scene_uv_tables.h` **入库**；非法输入由生成器自抓（`tier1Entries(layout)` 内校验）。

## 浮字池设计要点

| 维度 | 设计 |
|---|---|
| 容量 | `kFloatPoolCapacity = 256`（常量显式命名，守卫锁定） |
| 实例字段 | 世界空间锚点 `worldX/worldY` + `assetIndex` + `charCount`（表达多字形串）+ `styleIndex` + `spawnTime` + `spawnSeq` + `scale` / `riseScale` / `bounce` + `alive` |
| **覆盖语义** | **池满覆盖最旧**：`spawnSeq` 单调递增序号选最小者（循环缓冲语义），并计遥测 `droppedTotal++` / `overflowFrames++` / `overflowThisFrame_=true` |
| 生命周期 | `kFloatLifetimeSeconds = 1.1s`；`advance(nowSeconds)` 回收到期，返回活跃数 |
| 动画参数 | 上浮 `kFloatRisePerSecond = 1.0`；淡出 `kFloatFadeStart = 0.45`；暴击弹跳 `kFloatCritBounceScale = 1.45` / `kFloatCritBounceSeconds = 0.18` |
| **时间源选型** | 调用方时间标量（`sampleFloatText()` **纯函数**）—— 不查系统时钟、不跨线、不分配；C++ 侧 `g_floatNowSeconds` 以固定 1/60s 步长自累加，带 1e6s 回绕 |
| **纪元复位** | `shutdownRenderer` → `g_floatPool.reset()` + `g_floatNowSeconds = 0`（实例 + 遥测双清） |
| 遥测 | 接 R0.3 三计数器口径（累计丢弃精灵数 / 累计溢出帧数 / 累计降级生效帧数）；**降级帧数恒 0**（本特性不虚构降级行为，守卫锁定） |

## spawn 端口豁免登记 + 端口总数前后对照

**新端口**：`sceneSpawnFloatingText(spawnData: FloatArray?)`

**豁免理由**：浮字出生是**离散游戏事件**（战斗伤害数字、提示词条），不可由相机/状态标量
推导，且频率远低于帧率（事件驱动）。走**批量扁平数组**（10 标量/条）而非每实例一次调用，
使一次 spawn N 条只跨线 1 次；`kFloatSpawnStride = 10` 由双端镜像守卫锁定（步长错位会导致
spawn 参数静默错读）。保留位非 0 即拒 —— 为后续字段扩展留位而不破 ABI。

**端口总数对照（实测）**：

| 面 | 基线（B12 后，`92e5703fb`） | **本批（HEAD）** | 增量 |
|---|---:|---:|---|
| 渲染面 `external fun` 声明数 | **48** | **49** | **+1**（`sceneSpawnFloatingText`） |
| 生产 JNI 面（`NativeBridge.cpp` `Java_*` 符号） | **48** | **49** | **+1** |
| 生产 JNI 面（含 `gamecore/jni/GameCoreJni.cpp` 57 个） | **89** | **90** | **+1** |

**既有 JNI 签名零变更证据**：`NativeBridge.cpp:1424` `drawFrame` 仍为 8 参数
（`camX, camY, scale, vpW, vpH, overlayFlags, fadeAlpha, frameAlpha`）；`git diff` 未触及
既有端口签名。

## 灰度开关

**本批无新增灰度开关**。理由：浮字通道为**加性条件层**——不在池中无实例时**完全零影响**
（不 begin/不 submit/不绘制，由 `EmptyFloatPoolEqualsSceneWithoutFloatLayer` 逐位证明），
且**零生产消费面接入**（无任何生产调用点），故无需回滚臂。唯一的隐式门控是
`g_sceneAtlasTexId != 0`（图集未就绪时整层跳过，与地图/叠加层同语义）。

## 改动文件清单

**新增（5）**
```
android/app/src/main/cpp/scene/float_text.h
android/app/src/main/cpp/gamecore/test/scene_floating_text_test.cpp
android/app/src/main/cpp/gamecore/test/scene_pixel_regression_test.cpp
android/app/src/main/cpp/gamecore/test/golden/scene_regression_golden.txt
docs/parallel-batches-w5/report-B13-completion.md
```

**修改（12）**
```
android/scripts/build-atlas.mjs
android/app/src/main/cpp/scene/scene_uv_tables.h          （生成物重生成）
android/app/src/main/cpp/scene/scene_draw.h
android/app/src/main/cpp/NativeBridge.cpp
android/app/src/main/cpp/gamecore/test/CMakeLists.txt
android/core/engine/src/main/java/.../nativebridge/NativeBridge.kt
android/core/engine/src/test/java/.../render/SceneUvTablesMirrorGuardTest.kt
android/docs/renderer-feature-checklist.md
CHANGELOG.md
docs/cpp-engine.md
docs/native-engine-refactor-plan-2026-09-17.md
docs/parallel-batches-w5/dispatch-ledger.md
```

## 「零生产消费面接入」声明

本批为**通道交付批**。完成后全仓库**无任何生产代码调用 `sceneSpawnFloatingText`**：

```bash
$ grep -rn "sceneSpawnFloatingText" android/ --include=*.kt | grep -v NativeBridge.kt
（无输出 —— 仅 NativeBridge.kt 声明处与守卫测试）
```

**理由**：既有 Compose 浮字（天劫界面 `DamageNumberState` 族）**仍在 Compose 路径**，其迁移
到本通道需改动消费面（战斗/天劫触发点），**范围纪律要求不在本批顺手做**。本批只负责把
通道建好、建对、可回归、可自检。消费面接入列为后续批次。

该状态对验收的影响：G4 整帧预算**逐位不变**（空池零开销），G3 稳态字节**不变**（浮字活跃期
每帧 JNI = 0，且当前无活跃期）。

## 与方案 R3.8 验收口径的逐条对照

| 方案 R3.8 要求 | 本批落点 | 判定 |
|---|---|---|
| ① 数字/字母/固定词条/符号预烘焙为 sprite | 8 词 + 40 字形 + 4 样式档 = 48 资产，`kFloatUv[48]` | ✅ |
| ① 复用 `build-atlas.mjs`，沿 B10 codegen 先例 | `LAYOUT.tier1` + 6 函数入 `codegenSource()` hash 门 + 生成头入库 + 非法输入自抓 | ✅ |
| ① Kotlin 侧镜像守卫逐位对照 | `SceneUvTablesMirrorGuardTest` +5（UV `toRawBits` 逐位 / 计数 / 文本 / 样式 / 步长） | ✅ |
| ① Tier2 动态字形**不在本批** | 未引入任何 Tier2 机制、未预留 Tier2 代码路径 | ✅ |
| ② 固定容量池（~256，常量显式命名） | `kFloatPoolCapacity=256` + `CapacityAndConstantsAreExplicitlyNamed` | ✅ |
| ② 实例 = 锚点 + 资产索引 + 样式档 + 出生时刻 + 动画参数 | 全部字段在位（含 `charCount` 表达多字形串） | ✅ |
| ② 池满覆盖最旧（循环缓冲语义，守卫锁定） | `findOldestSlot` by `spawnSeq` + 2 用例锁定（含重复溢出） | ✅ |
| ② 动画全 C++ 时间驱动、零每帧 JNI | `sampleFloatText()` 纯函数 + 内部累加器；浮字活跃期每帧 JNI = 0 | ✅ |
| ② `shutdownRenderer` 后池纪元复位 | `g_floatPool.reset()` + `g_floatNowSeconds = 0`（实例+遥测双清），用例锁定 | ✅ |
| ② 池满/溢出遥测接 R0.3 三计数器口径 | `FloatTextStats{droppedTotal, overflowFrames, degradeFrames}`；降级帧恒 0 锁定 | ✅ |
| ③ 新 JNI 端口 `sceneSpawnFloatingText`，逐个登记豁免 | KDoc 豁免块 + 本报告「豁免登记」段（理由/频率/批量/步长守卫） | ✅ |
| ③ 给出端口总数前后对照 | 渲染面 48→49、生产面 89→90（实测） | ✅ |
| ③ 既有 JNI 签名零变更（否则单独登记+双端同步+守卫复跑） | `drawFrame` 保持 8 参数，时间源改内部累加器**规避 ABI 变更**，无需新豁免 | ✅ |
| ④ 浮字批走既有 push-constant 投影 | `buildFloatTextBatch(batcher, projMatrix, params, submit)` 复用 `updateCameraGlobals` 投影 | ✅ |
| ④ 层序 = 浮字在最上层（叠加层之上） | `drawFrame` 尾部提交（地图 → 崖壁 → 叠加层 → 浮字）；`FloatLayerDrawsOnTopOfScene` 锁定 | ✅ |
| ④ 空池 = 0 draw call | `activeCount()==0` 时直接 `return 0`，不 begin/不 submit；两用例 + 像素等价用例锁定 | ✅ |
| ④ Vulkan/GLES 同构，零 GLES 专属分支 | 判定/推进/提交全落共享头与基类；`GlesRenderBackend : VulkanRenderBackend` 构造性同构 | ✅ |
| ④ 场景回归集（软光栅，六要素 + 浮字 × 三档位，golden 入库） | `scene_pixel_regression_test.cpp` 12 用例 + golden 22 键 | ✅ |
| ④ golden 变更须显式重生并说明原因 | `SCENE_GOLDEN_UPDATE=1`（默认比对失败即红，绝不自动改写） | ✅ |
| ④ Canvas 兜底零改动 | `git diff --name-only` 改动面**无** `SoftwareCanvasBackend*` | ✅ |
| ④ **消费面接入不在本批** | 见「零生产消费面接入」声明 | ✅ |
| 红线 G3 < 200B/帧（含新时间标量后重测） | drawFrame 8 参数 ≈36B；**无新时间标量**（内部累加器） | ✅ |
| 红线 G3 浮字活跃期每帧 JNI = 0 | 动画纯函数自驱动 | ✅ |
| 红线 G4 每帧 JNI < 10、vkCmdDraw < 15（B11/B12 → 本批对照，空池零开销） | 每帧 JNI 稳态 4；vkCmdDraw 12(+天空 1)=**13 < 15**；空池 +0 | ✅ |
| 红线 零每帧 JNI 的生成纪律 | spawn 仅事件驱动；无帧循环轮询/逐帧同步浮字状态 | ✅ |
| 红线 Canvas 兜底零改动 | 同上 | ✅ |
| 红线 §3.1 新视觉元素禁止建在 Compose；Tier2 不得提前引入 | 通道本身 native 实现；未建 Compose 元素；未引入 Tier2 | ✅ |
| 红线 协议 JSON 面 / 存档格式 / 既有 JNI 签名零变更 | 改动面不含协议/存档文件；drawFrame 签名实测未变 | ✅ |
| 红线 每子项独立 commit（允许合并 ≥3 笔但说明理由） | 6 笔代码/文档 + 1 笔台账，合并理由见「提交号列表」 | ✅ |
| 红线 提交前 `compileReleaseKotlin lintRelease` BUILD SUCCESSFUL | `testReleaseUnitTest` 依赖链覆盖 `compileReleaseKotlin`；`lintRelease` 单独跑绿（4m43s，含 detekt） | ✅ |

## 残余项登记（不粉饰）

1. **零生产消费面接入** —— 通道交付批的**既定范围**，非缺陷。既有 Compose 浮字
   （天劫 `DamageNumberState` 族）迁移属后续批次。
2. **真机截图回归** —— 本批只建**桌面确定性软光栅**这一层；真机 GPU 截图回归需**设备农场**
   （基建未建，延续 B11/B12 残余③）。
3. **Adreno 黑名单补全** —— 浮字层与远景 REPEAT 同存在真机驱动采样风险，需真机复现后
   逐条登记（同 B12 残余②）。
4. **`arm64` 渲染层 FP 真机门** —— `arm64-fp-determinism.yml` 仅覆盖 gamecore 逻辑面
   （延续 B11/B12 残余①）。

## 看护环境坑登记（供后续批次复现）

1. **`-Dgamecore.jni.path` 必须指向 `.so` 文件本身**，不是目录：
   `C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so`。
   传目录 ⇒ 全部 `Diff*` 测试 `UnsatisfiedLinkError: Can't load library` 而失败
   （**非真失败**，勿据此改测试）。
2. **跑 GTest 二进制须把 UCRT 目录入 PATH**：除 `llvm-mingw-*/bin` 外还需
   `llvm-mingw-*/x86_64-w64-mingw32/bin`，否则报
   `api-ms-win-crt-time-l1-1-0.dll: cannot open shared object file`。
3. **`app:mergeReleaseResources` 反复遇 Windows 文件锁**：daemon 退出瞬间仍持有
   `merged_res/release/mergeReleaseResources/*.xml.flat`。处置 = 确认无 java 进程后
   `rm -rf` 该目录再重跑（`./gradlew.bat --stop` 可能报 "No Gradle daemons are running"
   但仍需手清目录）。
4. 本次组合门曾因坑 1 与坑 3 各失败一轮（前者是**我的参数错误**，后者是**环境锁**），
   两轮均非测试真失败；第三轮 `testReleaseUnitTest` 单独跑得 `BUILD SUCCESSFUL 41m48s`。

## 结论

**B13 = R3.8 验收成立，R3 渲染阶段收官。** 六个验收门全绿、证据可复跑、残余项如实登记。
**零生产消费面接入**为既定范围声明，非缺口。
