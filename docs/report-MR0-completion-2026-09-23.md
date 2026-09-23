# MR0 完成报告——内存管理根治 · Phase 0（开关与基线批）

> **批次**：MR0（P0.0 线程契约登记 / P0.1 `memorySubsystem` 旗标 / P0.2 基线采集清单）
> **施工面**：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）
> **日期**：2026-09-23 · **性质**：架构级重构（选项 2）Phase 0 编排批，**零 C++ 生产码**
> **验收状态**：实施完成、门禁全绿；**未经看护/用户验收，不自登记 accepted**

---

## 一、任务逐条交付

### P0.0 线程契约登记 ✅

`docs/threading-contract.md` 更新（文档头登记日期 2026-09-23，注明**登记先于实现**）：

| 表 | 条目（供后续批次引用的登记名） |
|----|------|
| 表一（线程职责） | **UI 主线程**：trim 回调接收（`onTrimMemory`/`onLowMemory` → Bridge 投递，只投递命令）+ 纹理上传编排 acquire/release 命令投递；**GameEngine-Thread**：tick 边界 gamecore 容器收缩钩子（trim 水位驱动，禁入 JobSystem 并行段/结算中途）；**RenderThread**：内存子系统 GPU 面独占——`TextureCache` 表、`GpuAllocator`、`nativeMemoryTrim`/`textureAcquire`/`textureRelease` 命令消费执行（帧边界） |
| 表二（白名单） | ① **内存子系统命令投递**（`textureAcquire`/`textureRelease`/`nativeMemoryTrim` JNI）——任意 Kotlin 线程投递合法，GPU/表操作仅渲染线程帧边界执行；② **内存子系统 MemoryStats 快照读**——渲染线程发布不可变快照，任意线程只读 |
| 表三（禁止区） | ① Kotlin 直触 `TextureCache` 表/`GpuAllocator`/直调 GL·VK；② trim 回调线程做 GPU 操作或纹理重传；③ 容器收缩进入 JobSystem 并行段或结算中途 |
| 表四（通道） | `nativeMemoryTrim(level)` / `textureAcquire(key, payload)` / `textureRelease(key)` / **MemoryStats 读通道**——各条含方向、语义、实现随属批次（MR1/MR2/MR3/MR4）标注 |

登记事实经代码核实：上传编排在主线程（`AtlasAsyncPipeline` post 段），trim 回调在主线程，与表一现状描述一致。

### P0.1 `NativeEngineFlag.memorySubsystem` ✅

- **旗标定义落点**：`android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/NativeEngineFlag.kt`——`@Volatile var memorySubsystem: Boolean = BuildConfig.MEMORY_SUBSYSTEM_DEFAULT`（字段本体即运行时读取入口；P2–P4 新路径以此为唯一分支依据，OFF = no-op；**P1.1–P1.5 止血不受控**）。
- **编译期默认注入**：`android/core/engine/build.gradle` `defaultConfig`——`buildConfigField "boolean", "MEMORY_SUBSYSTEM_DEFAULT", apiProperties.getProperty('MEMORY_SUBSYSTEM_DEFAULT', 'false')`。**入库缺省 `false`（预发期）**；`api.properties`（gitignored 本地件）可本地覆盖为 true 而不改入库面。
- **并入既有旗标族**：与既有 `Mode.OFF/AUTHORITATIVE` + `authoritative` kill-switch 同居 `NativeEngineFlag` object，未另造开关体系。
- **守卫测试**：`android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/NativeEngineFlagMemorySubsystemTest.kt`（2 用例：①运行时入口由编译期注入初始化——防硬编码字面量绕开覆盖通道；②build.gradle 入库默认字面量锁死 `'false'`——翻 true 前置 = 根治验收，翻默认须同步断言）。
- **翻 true 前置**（写入 KDoc 与测试消息）：实施方案附录 A 真机清单 + P4.5 门禁全绿；RemoteConfig 绑定前无热关断（债表既有登记，事故预案 = 紧急版本切默认 false）。

### P0.2 附录 A 基线采集清单 ✅

`docs/memory-refactor-implementation-plan-2026-09-23.md` 附录 A 重写为**可复制执行**清单：

- **A.0** 环境准备（包名/机型/版本/PID；输出落仓库外 `~/memrefactor-baseline/`，不入库）
- **A.1** `dumpsys meminfo` 总水位（三采样时机：冷启动 / 进宗门稳态 / CRITICAL trim 后）
- **A.2** Native Heap 分项（`-d` 详表、`VmRSS|VmHWM`、heapprofd 抽样）
- **A.3** 图形内存分项（Graphics/GL mtrack、KGSL 可选项）
- **A.4** VMA/GpuAllocator stats **将来对照位**（P2 落地后启用，P0.2 阶段仅占位、禁凭空填数）
- **A.5** 触发场景（`am send-trim-memory $PKG COMPLETE`、前后台 ×20 循环、切宗门 ×50 / 5000 弟子档手动项）
- **A.6** 基线记录表（6 指标 × 基线/复测双栏，MR2–MR4 验收对照同一张表）

**核实更正**：原骨架的 `python -m perfetto.heapprofd` 模块**不存在**，更正为 perfetto 仓库 `tools/heap_profile`；启动 Activity 核实为 `com.xianxia.sect.ui.MainActivity`；manifest **未声明 `profileable`**——heapprofd 需 userdebug/eng 设备（清单内如实注明，user 版跳过并登记）。

---

## 二、门禁实证（本批零 C++ 生产码）

| 门禁 | 命令 | 结果 |
|------|------|------|
| 编译+静态分析 | `./gradlew.bat compileReleaseKotlin detekt` | **BUILD SUCCESSFUL in 3m 21s**（129 tasks：63 executed / 59 from cache） |
| 旗标守卫单测 | `./gradlew.bat :core:engine:testReleaseUnitTest --tests "com.xianxia.sect.core.nativebridge.NativeEngineFlagMemorySubsystemTest" --max-workers=1` | **BUILD SUCCESSFUL**；`tests="2" failures="0" skipped="0"` |
| 规范分发架构门禁 | `node scripts/check-agent-instructions.mjs` | **全部通过**（路由闭包 41 篇 / 409 引用无死链） |
| detekt baseline | `git status` 复核 | **未触碰**（只缩不增纪律遵守） |
| C++ 生产码 | `git status` 复核 | **零改动**（cpp/ 无任何文件入提交） |
| 工作区 | `git status` 复核 | **本批恰好 5 文件 + 1 新增测试**，无构建副产物残留 |

---

## 三、关键实施事实（供后续批次派发引用）

1. **旗标读取入口**：`NativeEngineFlag.memorySubsystem`（`core/engine` 模块，`com.xianxia.sect.core.nativebridge` 包）。MR2+ 实现分支一律读它，不得自建开关。
2. **契约登记名**：跨线程实现引用 `docs/threading-contract.md` 表四四通道（`nativeMemoryTrim(level)` / `textureAcquire(key, payload)` / `textureRelease(key)` / MemoryStats 读通道）；线程独占语义以表一 RenderThread「内存子系统 GPU 面独占」为准。
3. **API 边界确认**：`AtlasAsyncPipeline` 上传段现处**主线程**（`mainHandler.post`），故 textureAcquire 投递线程现状为主线程——MR3 JNI 设计须保持「任意 Kotlin 线程可投递」而不锁定主线程。
4. **翻默认 true 操作点**：`core/engine/build.gradle` 的 `getProperty('MEMORY_SUBSYSTEM_DEFAULT', 'false')` 回退字面量 + `NativeEngineFlagMemorySubsystemTest` 第二用例断言，两处必须同步改。

## 四、途中发现（预存问题，未处置、不在本批范围）

1. **方案全局约束 8 引用的三旗标已退役**：`mirrorProtobufTransport` / `gameViewProjection` / `dirtyColumnExport` 已随 **B18 回滚臂删除批**全部删除（守卫 `MirrorConsumerSurfaceGuardTest` 反向断言禁止回流）。「既有旗标族」现 = `NativeEngineFlag` object 本体（Mode 双态 + `authoritative` kill-switch）。`memorySubsystem` 与该守卫无冲突（已核对：守卫仅禁三旗标名回流）。**建议**：方案约束 8/交叉表的表述在下次方案修订时更新（本批不改方案正文语义，仅附录 A 获授权重写）。
2. **check-agent-instructions 预存告警 3 条**（非本批引入，本批未触碰 AGENTS.md/rules/）：预算余量仅 401 字节；`rules/static-resources.md:277` 引用仅 basename 精确；`feature/game/AGENTS.md` 子目录启动链 37,784 字节超 Codex 预算。
3. **图集 codegen 构建副作用**：任何 Gradle 构建会重写 `atlas-rgba-manifest.json`（仅 `generatedAt` 时间戳）并触碰 `scene_uv_tables.h` / `sprite-uid-map.json`（行尾幻影脏）。本批已还原，未入库；后续批次构建后须同样甄别剔除。

## 五、诚实残余与 pending 项

| 项 | 状态 |
|----|------|
| 附录 A 真机/模拟器**实际采集**与 A.6 表填数 | **pending-device**——本会话无设备接入；清单已可复制执行，待用户/看护安排真机后按 A.0–A.6 执行 |
| `memorySubsystem` 翻 `true` | 未到时点——前置 = 根治验收（P4.5 + 附录 A），本批按评审维持 `false` 预发 |
| heapprofd 在 user 版设备不可用 | manifest 未声明 `profileable`；如需 user 版采集，须另行评估加 `profileable` 的合规面（未立项，不擅动） |
| `lintRelease` 未跑 | 派发门禁清单为 `compileReleaseKotlin detekt`（本批零 C++ 生产码），未含 lint；如看护要求补跑请指示 |
| 环境修复登记 | 工作树缺 `api.properties`（看护仅手拷 local.properties/keystore.properties）→ `app/build.gradle` 配置期失败；已**从主树只读拷入**（gitignored，不入库），构建恢复 |
| MR1–MR4（P1 止血 / P2 GPU / P3 纹理 / P4 基线+GLES+收口） | 未开工——本会话只实施本批（Phase 0），无任何越批代码 |

## 六、提交

单笔提交（明确文件名 add，未用 `git add -A`）：

```
docs/threading-contract.md
docs/memory-refactor-implementation-plan-2026-09-23.md
docs/report-MR0-completion-2026-09-23.md
android/core/engine/build.gradle
android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/NativeEngineFlag.kt
android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/NativeEngineFlagMemorySubsystemTest.kt
```
