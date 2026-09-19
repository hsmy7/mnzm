# 批次 B15 — R6.1（图集离线化：消运行时 Canvas 拼装，运行时只 upload）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R6 表 **R6.1 行**
> （"运行时 Canvas 拼图集（SectAtlasAssembler）→ build-atlas.mjs 直接产出 KTX/ASTC 包，
> 运行时只 upload。消启动 Canvas 依赖与内存尖峰；**iOS 前置**"）。
> 台账批次总表：`B15 = R6.1`（`docs/parallel-batches-w5/dispatch-ledger.md`）。
> **前置 = B14 已验收**（R4 阶段收官；`batch-R4C.md`）。
> 开工前先读仓库 `CLAUDE.md`、方案 §3.R6/§5 风险登记册/§7.2（B10–B14 段）、
> `android/scripts/build-atlas.mjs` 头注（KTX 管线 + codegen hash 门）、
> `AtlasAsyncPipeline.kt`（两段式流水线 KDoc）、`SectAtlasAssembler.kt`（数值权威声明）、
> `SoftwareCanvasBackend.kt` 消费面。

## 现状盘点（开工前先自查核实，与本节不符处以实测为准）

- **离线管线已存在**：`build-atlas.mjs` 已产出 `app/src/main/assets/atlas/atlas_astc.ktx`
  （KTX1 + ASTC 4×4）+ `TextureAtlas.h`/`scene_uv_tables.h` codegen（hash 门）；
- **运行时 ASTC 压缩路径已读离线资产**：`AtlasAsyncPipeline` 的 ASTC 资产读取钩子
  （可注入、可测）→ `NativeBridge.uploadCompressedAtlas`；
- **待消除的 Canvas 依赖**：`assembleAtlasBitmap()` → `SectAtlasAssembler.buildAtlasBitmap()`
  ——**逐精灵解码 + Canvas 画布拼装 2048² 位图**，服务三条支路：RGBA 上传回退臂、
  mip 链编码、**Canvas 软渲染路径**（`softwareBitmap`）。即：ASTC 缺失/上传拒绝/
  软渲染时仍走运行时拼图 ⇒ 启动期 Canvas 依赖 + 数百毫秒拼装 + 2048²（16MB）位图
  与逐精灵解码中间缓冲的内存尖峰依旧在。

## 任务

1. **运行时消费面切换**：RGBA 上传回退臂与 Canvas 软渲染路径的像素来源，从
   `SectAtlasAssembler` 运行时拼装切换为**离线产物**（`build-atlas.mjs` 同源新增输出，
   如无损 PNG / RAW RGBA，实现自定；ASTC 直传路径保持既有资产不变）；
   运行时只做**一次性解码/映射 + upload**，零 Canvas、零逐精灵循环；
2. **`SectAtlasAssembler` 退役**：生产源码零引用（静态门禁锁定）；
   类本体可删除或转测试夹具（等价对照用），二选一并说明理由；
3. **离线产物管线接线**：`build-atlas.mjs` 新输出纳入 codegen hash 门与产物入库口径
   （沿 B10/B13 先例：生成物入库、桌面测试与构建无需先跑 codegen、非法输入自抓）；
   Gradle 资产接线核过（assets 缺失时构建失败，不允许静默跳过——沿 astcenc 先例）；
4. **内存尖峰消除实证**：启动拼装路径的位图/中间缓冲分配前后对照
   （2048² 16MB 位图 + 逐精灵解码缓冲的消除；数字可复跑，诚实登记残余）；
5. **Canvas 软渲等价性守卫**：离线产物解码像素 vs 运行时拼装像素**逐位/像素级对照**
   （一次性迁移对照测试或 golden 校验和，沿 B13 golden 先例；2048 封顶缩放语义若变化
   须显式登记 UV/分辨率契约影响）；
6. **iOS 前置登记**：方案原文点名本项为 iOS 前置——完成报告中登记"消启动 Canvas 依赖"
   的达成面与残余（iOS 立项仍属范围外）。

## 红线（违者验收打回）

- **UV 布局权威零变更**：`build-atlas.mjs` LAYOUT / `SpriteAtlasDef` / `scene_uv_tables.h`
  的布局数值与生成口径不动（本批只换像素来源，不换布局）；
- **Canvas 兜底绘制代码零改动**（R3.6 红线延续）：`SoftwareCanvasBackend*` 的**绘制逻辑**
  不得修改；其**像素来源**切换属本批范围，但须有等价性守卫（任务 5）+ 既有
  `SoftwareCanvasBackend*Test` 族全绿未改语义；
- **ASTC 直传路径行为不变**：压缩路径资产/钩子/上传语义不动（本批不重写已工作路径）；
- **协议 JSON 面 / 存档格式 / 既有 JNI 签名零变更**；本批**不新增** `external fun`
  （`uploadCompressedAtlas` 等既有端口复用）；如确需新端口，逐个登记豁免并给总数对照；
- **生成物入库纪律**：新离线产物入 git（桌面测试/构建无需先跑 codegen）；
  生成器变更纳入 hash 门；
- **每子项独立 commit**（离线管线扩展 / 运行时切换+退役 / 守卫+文档，或按耦合合并
  ≥2 笔但说明理由）；提交信息沿用仓库惯例（`feat(renderer): 重构方案 R6.1/B15 …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑，且**不采信自述**）

1. **桌面全量 GTest 全绿**：当前 desktop-test 基线 **1545**（B14 复核后）。
   含 C++ 改动则先 `pwsh -File scripts/build-desktop-jni.ps1` 重建桥 +
   `cmake . && cmake --build . && ctest`（纯 Kotlin/脚本批说明理由后可免桥重建，
   但 `ctest` 仍须跑）；新增守卫同步登记新基线。
   - **环境注意**：`ctest` 须把 llvm-mingw `bin` **及** `x86_64-w64-mingw32/bin`（UCRT）
     置于 PATH；
2. **全量 JUnit 实跑非 UP-TO-DATE**：
   `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`
   （约 20–35 分钟；**须设 `JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1`**）。
   判绿必须 XML 实证：六模块 totals/0 失败/**Diff\* 类 0 skip**；
   已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按前例处置（勿改测试）；
   **已知环境锁**：`app:mergeReleaseResources` Windows 文件锁（B13/B14 各发生）——
   `gradlew --stop` + 手清 `merged_res/release/mergeReleaseResources` 后重跑，勿改代码；
3. **Canvas 依赖消除证明**：静态门禁（生产源零 `SectAtlasAssembler` 引用）+
   运行时拼装路径代码路径不复存在的守卫；ASTC 直传路径不受影响的对照证明；
4. **内存尖峰前后对照**：拼装路径分配（位图/中间缓冲）前后数字，可复跑口径；
   未完全消除的部分诚实登记与归因；
5. **等价性守卫**：离线产物像素 vs 拼装像素对照测试实跑输出（或 golden 校验和）；
   `SoftwareCanvasBackend*Test` 族全绿数字；
6. **文档三件套**：方案 §7.2 追加 **B15 行**（产物形态选型、切换面、内存对照、
   iOS 前置达成面与残余）；`CHANGELOG.md` 4.01.15 段内追加；
   `docs/cpp-engine.md` 进展行同步；`android/docs/renderer-feature-checklist.md`
   若有新产物/形态登记。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字（含新基线）/ JUnit 六模块各模块数与实跑证明（XML 时间戳 + Diff 0 skip）/ detekt 结论——贴关键输出行；
- **Canvas 依赖消除证明**（静态门禁 + 路径守卫实跑输出）；
- **内存尖峰前后对照表**（可复跑口径）与残余登记；
- **等价性守卫**实跑输出（像素对照或 golden）+ `SoftwareCanvasBackend*Test` 数字；
- 离线产物形态选型理由（PNG/RAW/其他）与 hash 门证据；
- 改动文件清单；iOS 前置达成面与残余；与方案 R6.1 验收口径的逐条对照。
