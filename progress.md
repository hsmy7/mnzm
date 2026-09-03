# Progress Log：C++ 迁移整改

## Session: 2026-09-04

### Phase 0: 资料理解与规划文件建立
- **Status:** complete
- **Started:** 2026-09-04
- Actions taken:
  - 通读 docs/cpp-migration-audit-report.md（275行）
  - 通读 docs/cpp-migration-implementation-plan.md（183行）
  - 确认：这是"真实但未完成的迁移"，8 决策已裁决
  - 建立 task_plan.md / findings.md / progress.md
- Files created/modified:
  - task_plan.md（created）
  - findings.md（created）
  - progress.md（created）

## Test Results
（迁移批次验证时填写；当前为规划阶段无测试。）

| Test | Input | Expected | Actual | Status |
|------|-------|----------|--------|--------|
| :core:data RoomMigrationTest (62用例) | --max-workers=1 串行 | 全绿 | 62/62 通过 | ✓ |
| :app assembleRelease | 完整 release 构建 | BUILD SUCCESSFUL | 通过（native/R8/lint 无错） | ✓ |
| :core:data compileReleaseKotlin | 实体+迁移 | 编译通过 | 通过 | ✓ |
| :app externalNativeBuildRelease | arm64-v8a native 编译 | BUILD SUCCESSFUL | 通过（删除JNI导出+测试断言后） | ✓ |
| :core:engine testReleaseUnitTest | 引擎单测（含 Diff 对拍） | 全绿 | 通过（含 onPhaseTick 基准 Diff 测试） | ✓ |
| :app compileReleaseKotlin | 死代码链清理 | 编译通过 | 通过 | ✓ |
| :app testReleaseUnitTest (state/repository.\*) | 状态/仓库测试 | 全绿 | 通过 | ✓ |
| :app assembleRelease（含 P1-7） | 完整 release 构建 | BUILD SUCCESSFUL | 通过（native/符号表提取/R8/lint 无错） | ✓ |
| detekt-baseline-count.guard 逻辑 | 6 模块 '<ID>' 计数比对 | 全部 ≤ 守卫值 | PASS（254/464/23/639/509/1150） | ✓ |

### WS-0.d 已改（本批）
- P1-7：app/build.gradle release `ndk { debugSymbolLevel "SYMBOL_TABLE" }`（assembleRelease 已验证触发 extractReleaseNativeSymbolTables）
- CI ②：generateAstcAtlas astcenc 缺失改为 GradleException **fail**（此前静默跳过）；任务未接入 assemble/test，仅显式重生成时生效
- CI ③：detekt-baseline-count.guard（6 模块计数守卫文件）+ ci.yml "Detekt baseline must not grow" 步骤（只缩不增，决策8 守卫）

### WS-0.d 未动（复杂渲染重构，需专项）
- P0-3：图集拼装移出主线程 + RGBA 2048 封顶 + toRgbaByteArray DirectByteBuffer——涉及 NativeSurfaceView 三线程契约/生命周期时序，非单一改动，需专项隔离验证
- P0-3(VulkanBackend 清屏色)——**已核实**：VulkanBackend.cpp:2352 clearColor 已是 {0,0,0,1} 纯黑（审计"提交未提交修复"已兑现）
- P1-4：JNI debug 线程断言（GameCoreBridge.cpp 无锁、三线程约定——需专项）
- CI ①：桌面 libgamecorejni.so 对拍 fail 而非 skip——已由 ci.yml `cpp-diff-jni-test` job 注入路径覆盖（无需改动）

## Error Log
| Timestamp | Error | Attempt | Resolution |
|-----------|-------|---------|------------|
|           |       | 1       |            |

## M0 WS-0 已落地（2026-09-04，本批）
### WS-0.a 已改（安全项 + 高危迁移）
- SettingsTab.kt:356 退出文案 → "确定要退出游戏吗？未保存的进度将会丢失。"
- game_config.json:6-7 删除 autoSaveIntervalSeconds / autoSaveDebounceMs（零消费者）
- GameTimeClock.kt:97 注释 "自动保存已累积" → "保留已累积"
- Room 迁移 V50（决策2 残留彻底清理）：GameData/SectPolicyState 的 autoSaveIntervalMonths
  改 @Ignore+@Transient（不再持久化、不再写入新档，旧档 lenient 解码可读）；
  DATABASE_VERSION 49→50；新建 GameDatabaseMigrationsV50.kt（PRAGMA 动态重建两表删列，
  保留全部约束/默认值/UNIQUE索引——⚠️不能用 GAME_DATA_CREATE_SQL 它是 v29 历史基线，
  会丢 21 列，已实证并纠正）；GameDatabaseMigrationSupport 仅注释纠偏（GAME_DATA_CREATE_SQL
  恢复 v29 原样）；RoomMigrationTest 补 M49_50 注册 + 5 个真实校验链追加 M49_50 +
  专用迁移测试（全绿 62/62）；已生成 core/data/schemas/.../50.json，autoSaveIntervalMonths=0

### WS-0.c 已改（build.gradle）
- abiFilters 'armeabi-v7a','arm64-v8a' → 'arm64-v8a'（决策7），注释重写
- 删除 bundle { texture { enableSplit = true } } 块（决策7）

### WS-0.a 验收 grep 结论（autoSave|自动保存|自动存档）
- schema/*.json（1..49）+ 历史迁移文件 = 历史 schema / 历史迁移，保留正确
- OldSerializableSaveData.kt:34 proto9 = 旧档兼容 DTO，正确保留（WS-0.a item4）
- GameData.kt/SectPolicyStateEntity.kt = 已改 @Ignore+@Transient 的字段，保留（注释说明）
- 文档护栏 = docs/architecture.md + CODE_WIKI.md（本批新增）
- 剩余生产引用（未动，见"途中发现"）

### 途中发现（需用户拍板，规则12）
1. `GameDataMerchant.kt:33` `GameSettingsData.autoSave:Boolean` —— 序列化设置字段，非自动存档机制；仅 GameDataTest:521 单测消费。删除需改设置序列化 schema + 迁移，风险>收益，本次未动。
2. `SaveLoadSaveDelegate.kt:11` 头注释"管理存档持久化、自动存档、保存状态"——已按 WS-0.b 纠偏为"纯手动存档"。
3. `GameEngineAdminOps.kt:19` "触发自动存档"注释——已核实实现仅 insertMail，无自动存档，已纠偏为"注入运营补偿邮件"。

### WS-0.b 已改（本批，2026-09-04）
- `isRendererReady`：删除 NativeBridge.kt 声明（零 C++ 导出、零调用）
- `nativeAdvance`：删除 GameCoreBridge.kt 声明 + GameCoreBridge.cpp JNI 导出（零 Kotlin 调用；benchmark 走 DiffRngBridge.nativeCoreAdvancePhases；C++ `GameCore::advance` 保留——time_system_test.cpp 使用）
- `nativePollEvents`+`pollEventsJson` 链：删除 Kotlin 声明 + GameCoreBridge.cpp JNI 导出 + game_core.cpp impl + game_core.h 声明 + game_core_test.cpp 断言
- 注释纠偏：GameEngineNativeOps.kt:15（46个ActionId→如实）、NativeSurfaceView.kt:35（模拟器必走软渲→API≥31走Vulkan）、gamecore/CMakeLists.txt:7（禁异常/RTTI→如实）、game_core.h:26-29+151（未实现→已实现；删pollEvents行）、SaveLoadSaveDelegate.kt:11、GameEngineAdminOps.kt:19
- 验证：`externalNativeBuildRelease`（arm64-v8a）BUILD SUCCESSFUL；compileReleaseKotlin 验证中

### WS-0.a 死代码链已删（本批）
- GameStateStoreImpl: 删除 _stateDirty/_discipleDirty 字段声明 + markDirty()/consumeDirty() 两方法 + 全部 ~12 处 '= true/false' 赋值（保留每个 _updateVersion.value++——那是活逻辑）。已核实零外部读者（grep 全库仅 CHANGELOG 历史与 store 自身）。
- 验证：:app compileReleaseKotlin（通过）+ :app 测试 state/repository.*（通过）

### WS-0.b 保留项（重要，需用户判断）
- `TimeSystem.onPhaseTick`：审计标"生产死代码"，但被 6 个 Diff 测试文件（DiffTimeTest/DiffAuthoritativeTickTest/DiffMonthSettlementFixture/DiffYearSettlementTest/DiffPhaseSettlementTest/SettlementTransactionMergeTest）用作 **Kotlin 跨语言对拍基准**（C-15 已切真实 TimeSystem 防"复刻漂移"）。删除会破坏跨语言时间验证基线。**建议保留**；如需按审计字面删除，须先重写 6 测试为纯 C++ 断言（失去独立 Kotlin 基准），请拍板。

### 待办（高危/耦合，未动）
- WS-0.d 渲染/CI/性能止血（图集出主线程/P0-3/P1-4/P1-7/CI加固/detekt守卫）

## 5-Question Reboot Check
| Question | Answer |
|----------|--------|
| Where am I? | M0 止血清残：WS-0.a/b/c 完成，WS-0.d 部分完成（P1-7/CI②/CI③），剩余 P0-3/P1-4 大项 |
| Where am I going? | WS-0.d P0-3（图集出主线程）→ P1-4（JNI线程断言）→ WS-6（iOS文案）→ M1 |
| What's the goal? | 按四里程碑完成迁移整改，C++收敛唯一真相源+全面ECS |
| What have I learned? | 见 findings.md |
| What have I done? | 见上方 WS-0 各节；全部 Compile/Test/Native/Assemble 验证通过 |
