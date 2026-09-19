# 批次 B12 完成报告（R3.5 + R3.6）

> 批次文件：`docs/parallel-batches-w5/batch-R3C.md`
> 完成结论：**accepted**（2026-09-19 15:05）
> 台账：`docs/parallel-batches-w5/dispatch-ledger.md` B12 行

## 提交号列表（逐子项）

| 子项 | 提交号 | 说明 |
|---|---|---|
| R3.5 C++ 核心 | `21345d413` | 修复 `buildMapBatch` 整图地面分支丢弃 batcher 的真实缺陷 + `submitGround` 回调 + `nativeSetFarViewGroundQuad` + 灰度门控 |
| R3.5 Kotlin 策略层 | `e42088a3c` | `FarViewGroundPolicy` 纯函数四门 + `RenderDeviceKey` API31+ 守卫 + `farViewGroundQuad` 正交开关 + 两端接线 |
| 守卫（R3.5+R3.6） | `d6d7b3b8b` | C++ +3 远景几何对照 + Kotlin 8+5 |
| 文档三件套 + 清单 + 台账 | `66f94d081` | 方案 §7.2 B12 块 / CHANGELOG 4.01.15 / cpp-engine 进展行 / renderer-feature-checklist / batch-R3C.md |
| 台账验收状态 | `cbb5a68d8` | B11 → accepted、B12 → accepted |

## 验收门逐门实证

### 门 1 — 桌面全量 GTest 全绿 ✅

```
100% tests passed, 0 tests failed out of 1494
Total Test time (real) = 398.74 sec
```

- **1494** = B11 基线 **1491** + 本批 3（`FarViewGroundQuadReplacesPerTileGround` /
  `FarViewGroundQuadWithoutTextureFallsBack` / `FarViewGroundQuadLeavesOtherLayersUntouched`）。
- 对拍桥重建携入本批；`ctest` 已把 llvm-mingw `bin` 入 PATH。
- **无回归**：`buildMapBatch` 签名改为模板 + `submitGround` 回调后，既有 4 参调用点仍走
  no-op 重载，1491 条既有用例逐条通过。

### 门 2/3 — 组合门（compileReleaseKotlin + lintRelease + detekt）✅

```
BUILD SUCCESSFUL in 18m 57s
252 actionable tasks: 48 executed, 204 up-to-date
```

- `:core:engine:detekt` / `:feature:game:detekt` / `:app:detekt` 等六模块全绿。
- 前序全量 `testReleaseUnitTest` 六模块 **7853/0/0/17**，`:core:engine` **3358**
  = B11 基线 3345 + 本批 13（`FarViewGroundPolicyTest` 8 + `RenderBackendIsomorphismTest` 5），0 skip。

### 门 4 — R3.5 证据 ✅

**整岛可见档地面层容量路径**（最远 scale）：

| 项 | 逐格路径（现状/回退臂） | 整图 quad 路径（本批） |
|---|---:|---:|
| 整岛档地面精灵数（128×128 最坏） | ≤ **16384**（=128×128 瓦片，逼近 `MAX_SPRITES_PER_FRAME`=20480 悬崖） | **1**（单 quad = 6 顶点） |
| 地面层 draw call | 合批后仍按纹理分段 | **1** |
| 每帧 JNI 次数 | 不受本批影响（地面几何全在 C++ 内生成，不跨线） | 同左 |

- 门控条件（`FarViewGroundPolicy.groundQuadEnabled`，纯函数四门合取）：
  ① `userEnabled`（`NativeEngineFlag.farViewGroundQuad`，默认 false）
  ② `groundTextureReady`（`groundTextureId != 0`）
  ③ `scale <= RenderLodPolicy.DECOR_ZOOM_THRESHOLD`（整岛档；与 `RenderLodPolicy` 的 `>=` 互补）
  ④ `ALLOWED_DEVICES.contains(deviceKey)`
- **黑名单口径**：`FarViewGroundPolicy.ALLOWED_DEVICES` **默认为空集 = 安全默认**
  （Adreno REPEAT 采样异常真机复现前，本路径**暂无设备启用**，禁止无守卫全局打开）。
  设备键 = `Build.SOC_MANUFACTURER`/`SOC_MODEL`（API 31+ 守卫，`RenderDeviceKey`），
  `buildKey` 为可测纯函数。
- **守卫两侧**：
  - 命中侧：`FarViewGroundPolicyTest` 覆盖"四门齐备才启用"；`RenderBackendIsomorphismTest`
    覆盖"Vulkan 仍需白名单命中"。
  - 不命中侧：空白名单 / 无纹理 / 超出 scale 阈值 / 用户关闭 —— 逐条断言拒绝。
- **溢出降级有序性**：沿用 R0.3 既有三计数器（`nativeGetSpriteOverflowStats` →
  `RenderMetrics`），降级序 = 先跳装饰层 → 再截断；本批不新增降级分支。
- **互斥性 + 其余层不动**：`FarViewGroundQuadLeavesOtherLayersUntouched` 证明
  逐格臂主批 − quad 臂主批 = 被移除的地面顶点，为 `VERTICES_PER_SPRITE`(6) 的整数倍且 > 6；
  quad 臂的 submitGround 恰收 **6** 顶点且带自有 texId。

### 门 5 — R3.6 取证 ✅

**GLES 同构证据（构造性 + 行为级）**：

- `GlesRenderBackend : VulkanRenderBackend` —— `pushFarViewGroundDecision()` 定义在**基类**，
  由 `renderSceneStorePath` 与 `renderLegacyDrawAllTilesPath` **两路共用** ⇒ GLES 子类
  无专属分支、构造性同构。
- **行为级自动降级**：GLES 后端不支持 `uploadRepeatTexture`（`dynamic_cast<VulkanBackend*>`
  失败返回 0）⇒ `g_groundTexId == 0` ⇒ `groundTextureReady == false` ⇒ 策略层自动拒绝。
  **无需任何 GLES 专属代码即安全**——这是同构设计带来的核心收益。
- 守卫：`RenderBackendIsomorphismTest` 5 用例（同一输入 Vega/GLES 判定恒等、GLES 自动降级、
  Vulkan 仍白名单、`RenderFrame` 后端中立、降级链相机语义）。

**Canvas 兜底零改动旁证**：

```
$ git status --short | grep -iE "canvas|software"
(空)
```

- `SoftwareCanvasBackend` / `SoftwareCanvasBackendOverlays` 未出现在改动面。
- 既有 Canvas 测试族（`SoftwareCanvasBackendGridTest` 5 / Demolish 7 / Highlight 8）全绿。

### 门 6 — 文档三件套 ✅

1. `docs/native-engine-refactor-plan-2026-09-17.md` §7.2 — B12 块（R3.5/R3.6/守卫/三后端红线自查四行 + 测试口径 1494 + 残余）。
2. `CHANGELOG.md` 4.01.15 段 — `### R3.5 + R3.6 批 B12（2026-09-19）`（8 条）。
3. `docs/cpp-engine.md` — B12 进展行（置于 B11 行之上）。
4. 另按惯例：`android/docs/renderer-feature-checklist.md` 新增 `far_view_ground_quad`、`gles_backend_isomorphic` 两行。

## 新增 JNI 端口清单与豁免理由

| 端口 | 豁免理由 |
|---|---|
| `nativeSetFarViewGroundQuad(on: Boolean)` | 远景地面路径的**灰度使能位**，纯控制面标量（无业务语义）。与既有 `nativeSetDirtyExportProtobuf` / `nativeSetAiThermalBatchSize` 同族：跨线同步的渲染策略开关，既有 ActionId 业务事务面 / 镜像导出面均非此形状；不参与协议 JSON 面与存档格式。 |

**端口总数前后对照**：渲染面 `external fun` **47 → 48**（+1）；生产 JNI 面总数 **88 → 89**。
（R3.5 的几何生成完全在 C++ 内，**零新增几何导入端口** —— 这正是 R3 收敛目标。）

## 灰度开关与回退路径

| 项 | 值 |
|---|---|
| 开关名 | `NativeEngineFlag.farViewGroundQuad`（Kotlin）↔ `g_farViewGroundQuad`（C++ 原子） |
| 默认值 | **false**（关闭 = 逐格地面，即现状） |
| 与既有开关关系 | **正交**于 `sceneStoreRender`（互不覆盖；`sceneStoreRender=false` 的旧路径同样受 `farViewGroundQuad` 门控） |
| 真机强制关闭条件 | 上述四门任一不满足（尤其白名单为空 / GLES 无 REPEAT 纹理 / 未上传地面纹理 / scale 超出整岛档） |
| 回退臂行为等价证明 | `FarViewGroundQuadWithoutTextureFallsBack`：无纹理时主批与逐格臂**逐位一致**；开关关闭时 `groundQuadEnabled=false` ⇒ 地面恒走逐格绘制，几何与 B11 现状完全相同 |
| 旧路径代码 | **完整保留**（`buildMapBatch` 逐格分支在位，未删一行） |

## 改动文件清单

**C++**
- `android/app/src/main/cpp/scene/scene_draw.h`（+33/−11，含缺陷修复）
- `android/app/src/main/cpp/NativeBridge.cpp`（+54/−？，原子 + JNI 端口 + 两处调用点 + shutdown 复位）
- `android/app/src/main/cpp/gamecore/test/scene_equivalence_test.cpp`（+178，3 用例 + 辅助）

**Kotlin 生产面**
- `android/core/engine/.../render/FarViewGroundPolicy.kt`（新 +68）
- `android/feature/game/.../sect/RenderDeviceKey.kt`（新 +64）
- `android/core/engine/.../nativebridge/NativeBridge.kt`（+21）
- `android/core/engine/.../nativebridge/NativeEngineFlag.kt`（+24）
- `android/feature/game/.../sect/VulkanRenderBackend.kt`（+31）
- `android/feature/game/.../sect/NativeSurfaceView.kt`（+47/−28，含 FileLength 压缩）
- `android/feature/game/.../sect/AtlasAsyncPipeline.kt`（+22）

**Kotlin 测试面**
- `android/core/engine/src/test/.../render/FarViewGroundPolicyTest.kt`（新 +152）
- `android/core/engine/src/test/.../render/RenderBackendIsomorphismTest.kt`（新 +141）

**文档**
- `docs/native-engine-refactor-plan-2026-09-17.md`（+20）
- `CHANGELOG.md`（+13）
- `docs/cpp-engine.md`（+1）
- `android/docs/renderer-feature-checklist.md`（+2）
- `docs/parallel-batches-w5/batch-R3C.md`（新 +80）
- `docs/parallel-batches-w5/dispatch-ledger.md`（B11/B12 行）

## 与方案 R3.5/R3.6 验收口径的逐条对照

| 方案要求 | 本批 | 判定 |
|---|---|---|
| R3.5：整岛档绘制容量策略（chunk 合并 / 复活 GROUND_QUAD REPEAT，二选一或组合） | 复活 GROUND_QUAD REPEAT 路径：整图单 quad（6 顶点）替代 ≤16384 逐格精灵 | ✅ |
| R3.5：**必须带黑名单验证**，禁止无守卫全局打开 | `FarViewGroundPolicy.ALLOWED_DEVICES` 纯函数 + 表驱动，**默认空集**；命中/不命中两侧守卫齐 | ✅ |
| R3.5：溢出遥测接 R0.3 三计数器，降级有序 | 沿用既有 `nativeGetSpriteOverflowStats`；本批不新增分支 | ✅ |
| R3.6：GLES 消费同一 SceneStore，**真实覆盖证据**（非"继承即同构"声明） | 基类共用判定 + GLES 无 REPEAT 纹理 ⇒ `groundTextureReady=false` 自动拒绝；`RenderBackendIsomorphismTest` 5 用例行为级证据 | ✅ |
| R3.6：**Canvas 兜底零改动** | `git status` 未触及 + 三层 Canvas 测试全绿 | ✅ |
| 灰度共存一个版本周期，旧路径不删 | `farViewGroundQuad` 默认 false；逐格分支完整保留 | ✅ |
| 等价性：开关关闭时逐位回到现状 | `FarViewGroundQuadWithoutTextureFallsBack` 主批逐位一致 | ✅ |
| 每子项独立 commit | 4 笔 + 台账 1 笔 | ✅ |
| 提交前 `compileReleaseKotlin lintRelease` BUILD SUCCESSFUL | 已跑（含 detekt）**18m57s 绿** | ✅ |

## 残余（登记）

1. **`ALLOWED_DEVICES` 为空集 ⇒ 本路径暂无设备启用**：R3.5 要求"带黑名单地验证"，
   真机验证前不得全局打开。待 Adreno REPEAT 采样异常真机复现后逐条登记白名单。
2. **arm64 渲染层 FP 真机门仍缺**（`arm64-fp-determinism.yml` 只覆盖 gamecore 逻辑面）。
3. **真机像素级回归属 R3 验收面**（截图回归基建未建，同 B11 残余③）。
