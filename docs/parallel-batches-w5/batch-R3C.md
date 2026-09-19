# 批次 B12 — R3.5 + R3.6（远景观看容量路径 + GLES 后端同构改造）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R3 表（R3.5、R3.6 行）。
> 台账批次总表：`B12 = R3.5 + R3.6`（`docs/parallel-batches-w5/dispatch-ledger.md`）。
> **前置 = B11 已验收**：`batch-R3B.md` 四笔提交（前置缺陷 B = `fb465f0b1`、R3.3 = `5b3a19dd5`、
> 前置缺陷 A = `574378424`、R3.4 = `e4ccade12`）+ 文档三件套 `474e4bcc3`，
> 以及方案 §7.2 的 **B11 登记块**（overlay 端口清单、脏更新协议语义、G4 实测数字）。
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§2/§3.R3/§3.1 治理规则/§5 风险登记册/§7.2（B10/B11 段）。
> 本批直接消费 B10/B11 成果：`scene/scene_store.h`（场景真相）、`scene/scene_draw.h`（单份绘制核心，
> 含 `buildMapBatch`/`buildCliffLayer`/`buildOverlayLayers`）、`scene/scene_uv_tables.h`（同源生成表）、
> `drawFrame(camX,camY,scale,vpW,vpH,overlayFlags,fadeAlpha,frameAlpha)`、
> `SceneUpdateChannel`（R3.4 脏更新判定器）。

## 任务

1. **R3.5 远景观看容量路径**：整岛可见（相机缩小到整岛）时的绘制容量策略——
   方案原文两条可选路径（**chunk 级合并/烘焙 quad** 或 **复活 GROUND_QUAD REPEAT 路径**）；
   二选一或组合，**必须带黑名单验证**（`NativeBridge.cpp:1032` 现为编译期恒 `false`，
   注释指向 Adreno 驱动采样异常黑屏——启用前须建立可回退的设备判定，禁止无守卫地全局打开）；
   溢出遥测接 R0.3 既有三计数器（`nativeGetSpriteOverflowStats` → `RenderMetrics`），
   降级有序（先跳装饰层 → 再截断）；
2. **R3.6 GLES 后端同构改造**：GLES 路径随 R3.2 消费**同一 SceneStore**（`GlesRenderBackend`
   继承 `VulkanRenderBackend`，Rhi 虚函数面后端无关——本批须给出**GLES 侧真实覆盖证据**，
   而非仅"继承即同构"的声明：枚举 Rhi 面在 GLES 后端下的行为等价面 + 守卫）；
   **Canvas 兜底路径不动**（方案 R3.6 原文——`SoftwareCanvasBackend` 及其 Kotlin 数据流零改动，
   兜底专属技术债保持显式标注）。

## 红线（R3 行为等价性风险最高延续，违者验收打回）

- **灰度共存继续一个版本周期**：R3.5 远景路径必须有**独立灰度开关**（默认值与既有
  `NativeEngineFlag.sceneStoreRender` 口径正交、不覆盖它），旧行为（逐格地面 + 现有溢出截断）
  完整保留可即时回退；旧路径代码不得删除；
- **R3.6 Canvas 兜底零改动**：`SoftwareCanvasBackend`/`SoftwareCanvasBackendOverlays` 不得出现在
  改动面；既有 Canvas 测试族（`SoftwareCanvasBackendTest` 及各专项）必须全绿且未改语义；
- **等价性以顶点流/几何逐位对照护栏锁定**：R3.5 的远景路径若引入新的地面绘制形态，
  必须有守卫证明其在**开关关闭时逐位回到现状**（回归基线），开启时几何正确（覆盖整岛可见域、
  无缝隙/无重叠/UV 连续），并与 Canvas 兜底的对应视觉语义一致；
- **黑名单必须可测**：设备判定（Adreno 采样异常）须为**纯函数 + 表驱动**，守卫覆盖
  "命中即回退逐格路径""不命中即启用"两侧；禁止把判定写成不可测的 `Build.*` 直读散落各处；
- **§3.1 治理规则**：任何新视觉元素禁止建在 Compose（本批若引入新绘制形态须走 native 通道）；
- 协议 JSON 面 / 存档格式 / 既有 JNI 签名 **零变更**；新增 `external fun` **逐个登记豁免理由**
  （沿 R0.2 / B06 / B10 八端口 / B11 三端口先例），并给出端口总数前后对照；
- **每子项独立 commit**：R3.5 一笔、R3.6 一笔（若 R3.6 仅取证零代码，须有独立"证据 + 守卫"笔）；
  提交信息沿用仓库惯例（`feat(renderer): 重构方案 R3.x/B12 …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑，且**不采信自述**）

1. **桌面全量 GTest 全绿**：当前 desktop-test 基线 **1491**（B11 后：1476 + SceneOverlayEquivalenceTest 13
   + SceneStoreTest 2）。本批含 C++ 改动 ⇒ 先 `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥，
   再在 `android/app/src/main/cpp/gamecore/build/desktop-test` 执行 `cmake . && cmake --build . && ctest`；
   新增守卫同步登记**新基线数字**。
   - **环境注意（B11 验收实测）**：`ctest` 须把 llvm-mingw `bin` 置于 PATH
     （`C:\Users\<user>\llvm-mingw\llvm-mingw-*\bin`），否则测试二进制因缺
     `libc++.dll`/`libunwind.dll` 全部 `0xc0000135` 假失败；
2. **全量 JUnit 实跑非 UP-TO-DATE**：
   `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`
   （约 21–28 分钟；**须设 `JAVA_HOME=C:/Users/<user>/.jdks/jdk-21.0.12.1+1`**，
   否则 `JAVA_HOME is not set` 直接失败）。判绿必须 XML 实证：六模块 totals/0 失败/**Diff\* 类 0 skip**。
   已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按 B03/B10/B11 前例处置（单点失败 + Diff 全过 → 重跑，**勿改测试**）；
3. **`detekt` 绿**（JNI ABI 必要的参数面豁免须与既有先例同写法并写明理由）；
4. **R3.5 证据**：整岛可见档（最远 scale）的**精灵数 / draw call / 每帧 JNI 次数**前后对照；
   黑名单判定守卫（命中/不命中两侧）；溢出降级有序性证明（既有 R0.3 计数器口径）；
   未达标即诚实登记残余与归因，不得粉饰；
5. **R3.6 证据**：GLES 侧真实覆盖证据（Rhi 面后端无关性守卫 + 与 Vulkan 共享新路径的
   静态门禁）+ **Canvas 兜底零改动旁证**（`git status` 未触及文件清单 + 既有 Canvas 测试族全绿数字）；
6. **文档三件套**：方案 §7.2 追加 **B12 行**（R3.5 路径选型与黑名单口径、R3.6 取证结论、
   灰度开关名与默认值）；`CHANGELOG.md` 4.01.15 段内追加；
   **`docs/cpp-engine.md`**（注意：该文件在**仓库根 `docs/` 下**，不是 `android/core/engine/docs/`）进展行同步；
   另按既有惯例同步 `android/docs/renderer-feature-checklist.md`（新绘制形态须登记双端状态）。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字（含新基线）/ JUnit 六模块各模块数与实跑证明（XML 时间戳 + Diff 0 skip）/ detekt 结论——贴关键输出行；
- **R3.5 前后对照数字**（整岛档精灵数/draw call/每帧 JNI）与达成判定 + 黑名单口径；
- **R3.6 取证**：GLES 同构证据与 Canvas 零改动旁证；
- 新增 JNI 端口清单与逐个豁免理由 + 端口总数前后对照（如无新增须显式声明）；
- 灰度开关名称与默认值、回退路径说明（含回退臂行为等价证明）；
- 改动文件清单；与方案 R3.5/R3.6 验收口径的逐条对照。
