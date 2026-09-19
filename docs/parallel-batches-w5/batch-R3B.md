# 批次 B11 — R3.3 + R3.4（overlay 几何 C++ 生成消 258 drawRect + 脏更新协议）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R3 表（R3.3、R3.4 行）。
> **前置 = B10 已验收（accepted）**：续接提交 `ba0901c89`（R3.1 SceneStore）、`242440778`（R3.2 JNI 面重构）、
> `cd5df439b`（文档三件套），以及方案 §7.2 的 **B10 登记块**（含 8 端口清单与 `sceneStoreRender` 灰度旗标口径）。
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§2/§3.R3/§3.1 治理规则/§5 风险登记册/§7.2（B10 段）。
> 本批直接消费 B10 成果：`scene/scene_store.h`（场景真相）、`scene/scene_draw.h`（单份绘制核心）、
> `scene/scene_uv_tables.h`（同源生成表）、`drawFrame(camX,camY,scale,vpW,vpH,overlayFlags,fadeAlpha,frameAlpha)`。

## 任务

1. **R3.3 overlay 几何全部改由 C++ 生成**：把当前 Kotlin 侧逐 rect 跨线的四类叠加层收进
   `scene_draw.h` 单份绘制核心，由 `drawFrame` 的 **overlayFlags 模式位**驱动（B10 已预留：bit0 =
   `buildingVisible`，其余位本批启用）：
   - **网格线**（`VulkanRenderBackend.drawGridOverlay`，放置模式最坏 ~258 次 `drawRect` 的主体来源：
     逐列/逐行循环各一次跨线）；
   - **放置预览框**（`drawPlacementPreview` 一带，5 次 `drawRect`：填充 + 四边）；
   - **选中高亮**（`drawSelectionHighlight`，`VulkanRenderBackend.kt:384/412-416`，5 次 `drawRect`）；
   - **拆除高亮**（`drawDemolishHighlight`，`VulkanRenderBackend.kt:433/476-495`，6 次 `drawRect`）。
     选中索引与合法性（可放置/阻挡）数据**随 building diff 同步**推送（低频、变化驱动），
     不得每帧逐 rect 传几何；几何（格→世界矩形、线宽、透明度常量、相机投影、Y 轴压缩）全部在 C++ 侧算。
2. **R3.4 脏更新协议**：游戏状态变化才推 diff——把 `RenderCommandBus` 的"变化才跨线"语义平移到
   **C++ 场景更新侧**（Kotlin 只负责判定"变了什么"并推最小增量；相机变化仅传标量），
   并**保留渲染线程脏帧跳过机制**（现有 `frameDirty`/早退语义不得退化；稳态相机静止帧应零跨线）。

## 红线（R3 行为等价性风险最高延续，违者验收打回）

- **灰度共存继续一个版本周期**：overlay 新路径必须仍受 `NativeEngineFlag.sceneStoreRender`
  （如需细粒度可另加子旗标，但默认值与 B10 口径一致）控制；**旧 Kotlin `drawRect` 逐条路径完整保留**
  且行为 = 本批开工前现状，可即时回退。旧路径代码不得删除。
- **等价性以顶点流逐位对照守卫锁定**：扩展 `android/app/src/main/cpp/gamecore/test/scene_equivalence_test.cpp`
  （或新建 overlay 等价测试文件）覆盖 overlay 四要素 × 相机多档位 × overlayFlags 组合，
  新旧路径 **draw call 纹理序 + 每顶点 px/py/u/v/r/g/b/a 全等**。这是本批验收门 5 的硬证据。
- **前置缺陷 A（必须显式二选一，禁止"顺手改"混进等价 commit）**：
  `VulkanRenderBackend.kt:552/555` 网格线视口钳制的 **Y 轴漏乘 `TOPDOWN_Y_SCALE(0.75f)`**
  （`lastRow = (cachedCamY + viewportH / scale) / tileSize`，而 C++ 投影与 Canvas
  `SoftwareCanvasBackend` 侧均按 Y 压缩处理）⇒ 现状 Vulkan 与 Canvas 在放置模式横线根数本就不同。
  下沉时二选一并在 §7.2 登记：
  ① **顺带修复** ⇒ 必须是**独立 commit**（这是行为变更，不是等价重构），并给出修复前后的
  守卫/截图证据与差异说明；② **保持现状** ⇒ C++ 侧必须**逐位复刻现状行为**（包括与 Canvas 不一致的那部分），
  并把该缺陷单列登记留待后续批次。
- **前置缺陷 B（overlay 几何进 C++ 之前必须先闭合）**：`-ffp-contract=off` 目前**只覆盖 game-core**
  （`android/app/src/main/cpp/gamecore/CMakeLists.txt:61-68`），**native-renderer 未覆盖**
  （`android/app/src/main/cpp/CMakeLists.txt` 无该旗标）⇒ 桌面顶点流逐位对照绿**不等于 arm64 位一致**
  （G6 在渲染层未闭合）。须为 native-renderer 补同源旗标（沿用 gamecore 的 MSVC 豁免写法
  `$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>`），**独立 commit**，并在报告中说明闭合后的口径。
- **Canvas 兜底路径不动**（R3.6 红线）：`SoftwareCanvasBackend` 及其 Kotlin 数据流零改动，
  兜底专属技术债保持显式标注。
- **JNI 面纪律**：既有 `drawRect` 端口**保留**（Canvas 与其他消费者仍用）；`drawAllTiles` 继续 deprecated 保留；
  新增 `external fun` **逐个登记豁免理由**（沿 R0.2 / B06 / B10 八端口先例），并给出端口总数前后对照。
- 协议 JSON 面 / 存档格式 / 既有 JNI 签名 **零变更**；§3.1 治理规则：任何新视觉元素禁止建在 Compose。
- **每子项独立 commit**：R3.3 一笔、R3.4 一笔，前置缺陷 A/B 各自独立成笔（如适用），文档另计；
  提交信息沿用仓库惯例（`feat(renderer): 重构方案 R3.x/B11 …`）。
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑，且**不采信自述**）

1. **桌面全量 GTest 全绿**：当前 desktop-test 基线 **1476**（B10 后：1456 + SceneStoreTest 10 +
   SceneEquivalenceTest 10）。本批含 C++ 改动 ⇒ 先 `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥，
   再在 `android/app/src/main/cpp/gamecore/build/desktop-test` 执行 `cmake . && cmake --build . && ctest`；
   新增守卫同步登记**新基线数字**。
2. **全量 JUnit 实跑非 UP-TO-DATE**：
   `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`
   （约 21–26 分钟）。判绿必须 XML 实证：六模块 totals/0 失败/**Diff\* 类 0 skip**（0 skip = 桥真加载）。
   已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按 B03/B10 前例处置（单点失败 + Diff 全过 → 重跑，**勿改测试**）。
3. **`detekt` 绿**（JNI ABI 必要的参数面豁免须与既有先例同写法并写明理由）。
4. **G4 实证（本批核心量化目标）**：放置模式**每帧 JNI 次数**与 **vkCmdDraw 计数**前后对照，
   目标 **< 10 次 JNI / < 15 draw call**（基线：最坏 ~300 次）。给出可复跑口径（bench 或遥测计数）
   与真实数字；未达标即诚实登记残余与归因，不得粉饰。
5. **overlay 等价性证据**：门 4 所述顶点流逐位对照守卫全绿（四要素 × 相机档位 × overlayFlags 矩阵），
   并证明回退臂可用（旗标 false 时旧 `drawRect` 路径仍产出与开工前一致）。三后端口径：
   Vulkan/GLES 共享同一新路径（`VulkanRenderBackend` 单实现，GLES 继承）；Canvas 兜底零改动。
6. **文档三件套**：方案 §7.2 追加 **B11 行**（overlay 端口清单与豁免、脏更新协议语义、
   前置缺陷 A/B 处置结论、G4 实测数字）；`CHANGELOG.md` 4.01.15 段内追加；
   **`docs/cpp-engine.md`**（注意：该文件在**仓库根 `docs/` 下**，不是 `android/core/engine/docs/`）进展行同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项，含前置缺陷 A/B 的独立 commit 或其"保持现状"结论）；
- GTest 数字（含新基线）/ JUnit 六模块各模块数与实跑证明（XML 时间戳 + Diff 0 skip）/ detekt 结论——贴关键输出行；
- **G4 前后对照数字**（每帧 JNI 次数、vkCmdDraw 数）与达成判定；
- overlay 等价守卫覆盖矩阵（要素 × 相机档位 × 标志位）与结果；
- 新增 JNI 端口清单与逐个豁免理由 + 端口总数前后对照；
- 灰度开关名称与默认值、回退路径说明（含回退臂行为等价证明）；
- 脏更新协议语义（哪些变化推什么、稳态帧跨线次数）；
- 改动文件清单；与方案 R3.3/R3.4 验收口径的逐条对照。
