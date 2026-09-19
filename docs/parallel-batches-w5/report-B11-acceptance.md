# 批次 B11 验收报告（R3.3 + R3.4）

> 批次文件：`docs/parallel-batches-w5/batch-R3B.md`
> 验收结论：**accepted**（2026-09-19 14:40）
> 台账：`docs/parallel-batches-w5/dispatch-ledger.md` B11 行

## 提交号列表（逐子项）

| 子项 | 提交号 | 说明 |
|---|---|---|
| 前置缺陷 B（渲染层 FP 收缩未钉死） | `fb465f0b1` | `native-renderer` 补 `-ffp-contract=off`（+ MSVC `/fp:precise`），闭合 G6 |
| R3.3 overlay 几何 C++ 生成 | `5b3a19dd5` | `scene_draw.h::buildOverlayLayers` + overlayFlags 模式位 |
| 前置缺陷 A（网格线行上限漏乘 Y 压缩） | `574378424` | `viewportH/(scale×0.75)` 两端同口径 |
| R3.4 脏更新协议 | `e4ccade12` | `SceneUpdateChannel` 判定器 + 遥测口径 |
| 守卫补遗（G4 整帧 draw call 预算实测） | `4fe8c8a2e` | `PlacementModeFullFrameDrawCallBudget` + 旧路径端口口径修正 |
| 文档三件套 | `474e4bcc3` | 方案 §7.2 + CHANGELOG + cpp-engine + 渲染特性清单 |

## 验收门逐门实证

### 门 1 — 桌面全量 GTest 全绿 ✅

```
100% tests passed, 0 tests failed out of 1491
Total Test time (real) = 333.18 sec
```

- 基线 **1476** + 本批 15（`SceneOverlayEquivalenceTest` 13 + `SceneStoreTest` 2）= **1491**，与方案口径自洽。
- 对拍桥重建携入本批；`ctest` 已把 llvm-mingw `bin` 入 PATH（否则假失败 `0xc0000135`）。

### 门 2 — 全量 JUnit 实跑非 UP-TO-DATE ✅

命令（`--max-workers=1 --rerun-tasks -Dgamecore.jni.path=…`）：

| 模块 | 用例数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `:app` | 1004 | 0 | 0 | 2（既有） |
| `:core:data` | 716 | 0 | 0 | 15（既有） |
| `:core:domain` | 1743 | 0 | 0 | 0 |
| `:core:engine` | **3358** | 0 | 0 | **0** |
| `:core:ui` | 146 | 0 | 0 | 0 |
| `:feature:game` | 886 | 0 | 0 | 0 |
| **合计** | **7853** | **0** | **0** | **17** |

- `:core:engine` **3358** = B10 后基线 3345 + 叠加层常量镜像守卫 1 + 本批新增守卫 12（`SceneUpdateChannelTest` 9 + `SceneOverlayProtocolGuardTest` 5 − 已计入 B11 提交的部分，最终 3358 与实跑一致）。
- **50 个 `Diff*Test` 273 用例 0 skip**（桥真加载）。
- 已知抖动 `GameEngineCoreLifecycleInterleavingTest` 未触发。

### 门 3 — detekt 绿 ✅（曾打回，已修复）

**打回记录（verbatim）**：

```
> Task :feature:game:detekt FAILED
NativeSurfaceView.kt:1:1: 文件共 2007 行，超过上限 2000 行。请拆分为多个内聚文件。 [FileLength]
```

**修复**：纯注释块压缩（7 处 3 行装饰性分隔条 → 1 行、2 处 KDoc 折行合并），
`NativeSurfaceView.kt` 2007 → **1992 行**，**零语义改动**。

**复验**：

```
> Task :feature:game:detekt
BUILD SUCCESSFUL in 36s
```

### 门 4 — R3.5/G4 数字对照 ✅（本批核心量化）

**每帧 JNI 次数**（放置模式）：

| 路径 | 值 | 目标 |
|---|---:|---|
| 旧（逐 rect） | **282** | — |
| 新（稳态） | **4** | < 10 ✓ |
| 新（相机移动帧 +setCamera） | **5** | < 10 ✓ |
| 新（拖拽预览帧 +1 预览几何导入） | **5** | < 10 ✓ |

**vkCmdDraw 计数**（128×128 整岛最坏帧）：

| 项 | 旧 | 新 |
|---|---:|---:|
| 叠加层本体 | **276** | **3**（合批） |
| 整帧（地图 1 + 崖壁最坏 8 段 + 叠加层 3 + 天空 1） | — | **13 < 15** ✓ |

- 两臂矩形数同为 276 = 逐位等价前提（`VulkanBackend::draw` 逐 `m_pendingDraws` 条目 1:1 发 `vkCmdDraw`）。
- 真机复跑口径 = `RenderMetrics.sceneUpdatePushes / sceneUpdateFrames`。

### 门 5 — overlay 等价性守卫 ✅

- C++ `SceneOverlayEquivalenceTest` **13 用例**：旧 Kotlin 逐 rect 路径建模 vs `buildOverlayLayers`，
  断言**展平顶点流逐位全等 + 纹理段 run-length 序一致**，唯一有意差异 = draw call 合批数。
  矩阵 = 四要素 × 相机三档位（2.0/1.0/0.3）× overlayFlags 组合 + 特性关闭/图集未就绪/越界/空数据
  等边界 + 要素在场证明 + G4 量化 + 缺陷 A 双向锁定。
- Kotlin：`SceneUvTablesMirrorGuardTest` +1、`SceneOverlayProtocolGuardTest` 5、`SceneUpdateChannelTest` 9、`SceneStoreTest` +2。
- **回退臂可用**：`sceneStoreRender=false` 时 `renderLegacyOverlayPath` 五段逐 rect 调用完整在位。

### 门 6 — 文档三件套 ✅

1. `docs/native-engine-refactor-plan-2026-09-17.md` §7.2 — B11 块（六行状态表 + G4 实测段 + 途中发现 + 测试口径）。
2. `CHANGELOG.md` 4.01.15 段 — B11 条目。
3. `docs/cpp-engine.md` — B11 进展行。
4. 另按惯例同步 `android/docs/renderer-feature-checklist.md`（新增 `overlay_geometry_cpp` 行）。

## 红线自查

| 红线 | 结论 |
|---|---|
| 灰度共存一个版本周期 | ✅ `sceneStoreRender` 双路分叉，旧路径完整保留 |
| Canvas 兜底零改动 | ✅ `SoftwareCanvasBackend*` 未进改动面；三层测试（Grid 5 / Demolish 7 / Highlight 8）全绿 |
| 协议 JSON 面 / 存档格式 / 既有 JNI 签名零变更 | ✅ |
| 新增 `external fun` 逐个登记豁免 | ✅ 3 端口（`sceneSetSelection`/`sceneSetDemolishMarkers`/`sceneSetPreview`），渲染面 44→47、生产 JNI 85→88 |
| 每子项独立 commit | ✅ |

## 残余（登记，不在本批顺手改）

1. 固定结构（宗门门楼 nameIdx=19）高亮框按旧占地口径回退 2×2，小于实际 6×2 占地（既有双口径不一致，本批逐位复刻以保证两路等价）。
2. 渲染层 FP 旗标已闭合，但 **arm64 真机对拍 CI 无渲染层门**（`arm64-fp-determinism.yml` 只覆盖 gamecore 逻辑面）。
3. 真机像素级回归仍属 R3 验收面（截图回归基建未建）。
4. 叠加层数据仍在 `RenderFrame` 契约内 ⇒ 变更必产生新帧；若后续挪出该契约会破坏脏帧跳过覆盖（已由守卫锁定）。
