# C++ 迁移整改对接文档（M0 止血清残）

| 项 | 内容 |
|---|---|
| 文档性质 | 面向后续开发者/接手者的**对接文档**：本轮已完成哪些、如何验证、遗留哪些待办（需专项或拍板） |
| 依据文档 | [cpp-migration-audit-report.md](cpp-migration-audit-report.md)（独立审计）+ [cpp-migration-implementation-plan.md](cpp-migration-implementation-plan.md)（总方案，2026-09-04） |
| 里程碑 | M0 止血清残（WS-0 主体已落地，WS-0.d 剩余 P0-3/P1-4 大项待专项） |
| 实施日期 | 2026-09-04 |

---

## 1. 背景一句话

Kotlin→C++ 游戏引擎迁移被审计定性为"**真实但未完成的迁移**"（总评 5.9/10）：模拟核心与地图渲染已真迁 C++ 且 C++ 为真相源；但残留自动存档痕迹、若干只有声明无实现/无调用的死导出、双 ABI 包体、以及掩盖在 detekt baseline 下的债务。本批为 M0 止血清残。

## 2. 本轮已完成并验证

### WS-0.a 自动存档残留清理（决策2，全项）
| 改动 | 文件 | 说明 |
|---|---|---|
| 退出弹窗文案如实 | `android/feature/game/.../tabs/SettingsTab.kt` | "游戏进度会自动保存"→"未保存的进度将会丢失" |
| 删零消费配置键 | `android/app/src/main/assets/config/game_config.json` | 删除 `autoSaveIntervalSeconds` / `autoSaveDebounceMs` |
| 注释纠偏 | `android/core/engine/.../system/GameTimeClock.kt` | "自动保存"→"保留"（指内存时钟累积） |
| Room 迁移 V50 | `GameDatabase.kt` / `GameDatabaseMigrationsV50.kt` / `GameDatabaseMigrationSupport.kt` / `GameData.kt` / `SectPolicyStateEntity.kt` | `autoSaveIntervalMonths` 从 `game_data`+`sect_policy_state` 删除；实体字段改 `@Ignore`+`@Transient`（旧档 lenient 解码可读，新档不写）；`DATABASE_VERSION` 49→50；PRAGMA 动态重建两表（**不能**复用 `GAME_DATA_CREATE_SQL`——那是 v29 历史基线，会丢 21 个后续新增列） |
| 死代码链 | `GameStateStoreImpl.kt` | 删除 `markDirty()`/`consumeDirty()` + `_stateDirty`/`_discipleDirty` 字段及 ~12 处赋值；保留每个活的 `_updateVersion.value++` |
| 防复发护栏 | `docs/architecture.md` + `CODE_WIKI.md` | 加入"存档为纯手动（产品决策 2026-09-04）"权威记录 + 禁止重新实现自动保存 |

### WS-0.b 死导出与注释纠偏
| 项 | 处置 |
|---|---|
| `NativeBridge.isRendererReady` | 删除声明（无 C++ 导出、零调用，调用即崩的"地雷"） |
| `GameCoreBridge.nativeAdvance` | 删除 Kotlin 声明 + C++ JNI 导出（零 Kotlin 调用；benchmark 走 `DiffRngBridge.nativeCoreAdvancePhases`；**保留** C++ `GameCore::advance`——`time_system_test.cpp` 用） |
| `nativePollEvents` + `pollEventsJson` | 删除 Kotlin 声明 + C++ JNI 导出 + `game_core.cpp` 实现 + `game_core.h` 声明 + `game_core_test.cpp` 断言 |
| 注释纠偏 6 处 | `GameEngineNativeOps`（46个ActionId→如实）、`NativeSurfaceView`（模拟器必走软渲→API≥31走Vulkan）、`gamecore/CMakeLists.txt`（禁异常/RTTI→如实）、`game_core.h`（未实现→已实现；删pollEvents行）、`SaveLoadSaveDelegate`（自动存档→纯手动）、`GameEngineAdminOps`（触发自动存档→注入运营邮件） |

### WS-0.c 包体两项（决策7）
- `android/app/build.gradle`：`abiFilters` 仅留 `arm64-v8a`（移除 armeabi-v7a，APK native 约 -40%）；删除 `bundle { texture { enableSplit = true } }`（仅一种 KTX 格式，从未生效）。

### WS-0.d 部分（渲染/CI/性能止血）
| 项 | 处置 |
|---|---|
| P1-7 release 原生符号 | `build.gradle` release `ndk { debugSymbolLevel "SYMBOL_TABLE" }`（已验证触发 `extractReleaseNativeSymbolTables`） |
| CI ② astcenc | `generateAstcAtlas` 任务 astcenc 缺失由"静默跳过"改为 `GradleException` fail |
| CI ③ detekt 守卫（决策8） | 新增 `android/detekt-baseline-count.guard` + ci.yml `Detekt baseline must not grow` 步骤（baseline `<ID>` 计数只缩不增） |
| P0-3 清屏色 | **已核实**无需改：`VulkanBackend.cpp:2352` clearColor 已是纯黑 `{0,0,0,1}` |

## 3. 验证结果（全部通过）

| 验证 | 命令 | 结果 |
|---|---|---|
| Room 迁移测试（62 用例） | `:core:data:testReleaseUnitTest --tests RoomMigrationTest --max-workers=1` | 62/62 通过 |
| 实体+迁移编译 | `:core:data:compileReleaseKotlin` | 通过 |
| 引擎单测（含 Diff 对拍） | `:core:engine:testReleaseUnitTest --max-workers=1` | 通过 |
| app 编译 | `:app:compileReleaseKotlin` | 通过 |
| 状态/仓库测试 | `:app:testReleaseUnitTest --max-workers=1 --tests "...state.*"` `--tests "...repository.*"` | 通过 |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |
| 完整 release 打包 | `assembleRelease` | 通过（含 native/符号表提取/R8/lint 无错） |

> 注：`detekt-baseline-count.guard` 的比对逻辑已本地验证（6 模块计数 254/464/23/639/509/1150，全部 ≤ 守卫值）。

## 4. 遗留待办（明确未完成，勿误判为"已完成"）

### 4.1 需要专项的高危大项（本次未动）
| 项 | 说明 | 风险 |
|---|---|---|
| WS-0.d P0-3 图集拼装移出主线程 | `buildAtlas`(onRendererReady 主线程) → `SectAtlasAssembler.buildAtlasBitmap`(4096² 逐精灵解码+canvas 绘制) 在主线程执行，GLES 低端路径 OOM/ANR 高危 | 渲染器三线程契约/生命周期时序，改动大且真机验证；**建议专项隔离交付**，勿在其它批次内顺带改 |
| WS-0.d P0-3 RGBA 2048 封顶 + `toRgbaByteArray` DirectByteBuffer | 属 P0-3 同一重构范畴 | 同上 |
| WS-0.d P1-4 JNI 三线程无锁 + debug 线程断言 | `GameCoreBridge.cpp` 全文件无 mutex，安全仅靠线程约定；加断言需包全部入口，写错会生产误 abort | C++ 桥层安全模型，需专项 |
| WS-0.d CI ① 桌面对拍 fail 而非 skip | 已由 ci.yml `cpp-diff-jni-test` job 注入 desktop-JNI 路径覆盖（≥`isAvailable()` 为 true），**实际已满足**，无需改动 | —— |
| WS-6 iOS 暂缓落地 | 清理 docs/注释中"iOS 可复用/双端平台"表述（clock.h:12 等），降级为"Android 为主，核心可移植" | 纯文档，低风险；**本次未完成**（已识别目标文件，未实施） |

### 4.2 需要用户拍板
| 项 | 结论 |
|---|---|
| `TimeSystem.onPhaseTick` | 审计标"生产死代码"，但它是 **6 个 Diff 测试文件的 Kotlin 跨语言对拍基准**（C-15 特意切真实 TimeSystem 防"复刻漂移"）。**已保留**（本次未删）。若要按审计字面删除，须先重写 6 个测试为纯 C++ 断言——会失去独立 Kotlin 基准，请用户决定 |
| `GameDataMerchant.kt GameSettingsData.autoSave` | 是**序列化设置字段**，非自动存档机制；删除需改设置序列化 schema + 迁移，风险>收益。**保留** |

### 4.3 规划文档说明
- `task_plan.md` / `findings.md` / `progress.md`：本任务的长期规划/进度记忆（planning-with-files 规范产物），非一次性调试代码。已保留并入库，供下轮接续 M1 时使用。

## 5. 下轮建议（M1）

按总方案，M0 剩余项（P0-3/P1-4）若能接受专项隔离，建议优先 WS-0.d P0-3 + P1-4，再补 WS-6 文案；随后进入 M1（WS-1 同步通道降本 + WS-2 S1-S3 突破/丹药/自动装备下沉 + WS-3 E1 保序验证）。每批遵循"C++实现/启用→ActionId接线→桌面对拍验证→删Kotlin路径→CI绿"流水线。
