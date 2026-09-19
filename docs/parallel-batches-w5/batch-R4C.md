# 批次 B14 — R4.4（RNG 分区独立：残留执行器本地 PCG，消 per-roll JNI）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R4 表 **R4.4 行** + §5 风险表
> （"RNG 分区重定基线改变老档行为 | 与 kAiSectMirror 同模式（无键重播）；对拍测试同步调整；
> 变更写入存档版本说明"）。
> 台账批次总表：`B14 = R4.4`（`docs/parallel-batches-w5/dispatch-ledger.md`）。
> **前置 = B13 已验收**（R3 收官，`batch-R3D.md` 七笔提交 + 看护复核 1541/1541 + 组合门绿）。
> **前置条件已满足**：R4.4 标注"权威翻转后允许"——B04/B05 起 AUTHORITATIVE 已为生产默认，
> 残留执行器已按 B09 退化为平台效应适配器，本批是其随机域的最终独立化。
> 开工前先读仓库 `CLAUDE.md`、方案 §3.R4/§5 风险登记册/§7.2（B09/B04-B05 段）、
> `RngPartition.kt` 头部 KDoc（**id 持久化键纪律**）、`RngSourceGuardTest`、
> `GameRngManager`、`NativeBackedRng`、`GameCoreRngChannel`。

## 任务

1. **定位"残留执行器"的随机域**（方案原文指认 + B09 语义 = 平台效应适配器；
   以 `NativeBackedRng` 逐 roll 委托的实际消费面为准：
   `GameEngineCoreAuthoritativeOps` 月/年变编排、`BattleExecutionRouter` 回退臂、
   `AISectDiscipleManager` 等——先枚举每个消费点的分区、频率、存档依赖，列出清单再动手）；
2. **为新域分配独立 `RngPartition`**（追加下一个空闲 id；**既有 id 0–10 禁止改动/复用**）：
   - Kotlin 侧改用**本地 PCG 实例**（`DeterministicRng` 本地实现，state 字段真实使用），
     不再经 `NativeBackedRng`/`NativeRngChannel` 逐 roll 跨线
     （消 `NativeBackedRng.kt:46` 的 per-roll JNI）；
   - seed 随分区 init（沿 `systemSeed + id` 既有播种语义）；快照/恢复走本地 state
     （`rngStates` 新键持久化，`inSnapshot = true`）；
   - **同步 C++ 侧**：`gamecore/rng/rng_manager.h` 枚举追加 + `initSystemSeed` 播种 +
     登记（`RngPartition.kt` KDoc 明文纪律；`RngSourceGuardTest.registeredPartitionIds`
     会拦截未登记追加——本批须让它绿且覆盖新 id）；
3. **老档兼容（无键重播语义）**：旧存档 `rngStates` 无新键 → 按 `systemSeed + id`
   确定性重种（MISSION(8)/CHAT(10) 同款恢复语义），不崩溃、不漂移；
   变更写入**存档版本说明**；
4. **对拍重定基线**：受影响的 `Diff*` 对拍测试逐一调整并**显式说明每个改动的原因**
   （序列基线变化 = 设计内行为变更，禁止静默放宽断言）；
5. **消费面接线**：残留执行器随机域切新分区；**其余分区（BATTLE/BREAKTHROUGH/…）
   的既有委托关系与序列不动**。

## 红线（违者验收打回）

- **既有分区 id 与序列零扰动**：id 0–10 的持久化键、播种公式、既有抽取序逐位不变
  （本批只追加新分区，不改旧分区——守卫锁定）；`AI_SECT_MIRROR(9)` 的通道型语义
  （`inSnapshot = false`、C++ 保管流态）不得被波及；
- **存档 schema 零变更**：`rngStates` 仍是 `Map<Int, Long>`，仅新增键值；
  协议 JSON 面/其他存档字段零变更；**行为变更（新分区序列）须写入存档版本说明**；
- **确定性**：同 seed + 同操作序 ⇒ 同序列（本地 PCG 与 `DeterministicRng` 契约一致，
  bound/double/gaussian 上层公式经继承消费原始流，消耗次数与产出逐位可复现——守卫锁定）；
- **snapshot/restore 语义保持**：事务回滚面（`TransactionRngRollbackTest` 族）全绿未改语义；
- **JNI 纪律**：本批**不新增** `external fun`；`NativeRngChannel` 既有三入口签名零变更
  （消的是调用频率，不是通道——通道保留供其余分区）；
- **逐子项独立 commit**（枚举清单一笔 / 分区+播种+接线一笔 / 对拍重定基线一笔，
  或按实际耦合合并 ≥2 笔但说明理由）；提交信息沿用仓库惯例
  （`feat(engine): 重构方案 R4.4/B14 …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑，且**不采信自述**）

1. **桌面全量 GTest 全绿**：当前 desktop-test 基线 **1541**（B13 复核后实测，
   含浮字 47 守卫）。本批含 C++ 改动（rng_manager.h 枚举/播种）⇒ 先
   `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥，再在
   `android/app/src/main/cpp/gamecore/build/desktop-test` 执行
   `cmake . && cmake --build . && ctest`；新增守卫同步登记**新基线数字**。
   - **环境注意（B11–B13 实测）**：`ctest` 须把 llvm-mingw `bin` **及
     `x86_64-w64-mingw32/bin`**（UCRT）置于 PATH，否则缺 dll 全量假失败；
2. **全量 JUnit 实跑非 UP-TO-DATE**：
   `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`
   （约 20–30 分钟；**须设 `JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1`**）。
   判绿必须 XML 实证：六模块 totals/0 失败/**Diff\* 类 0 skip**；
   已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按前例处置（勿改测试）；
3. **per-roll JNI 消除证明**：行为级守卫（计数替身或静态门禁）证明残留执行器随机域
   全路径**零跨线**；其余分区委托通道不受影响的对照证明；
4. **确定性守卫**：同 seed 同操作序 ⇒ 同序列（含 bound/double/gaussian 上层公式）；
   事务回滚 snapshot/restore 语义测试族全绿；
5. **老档兼容守卫**：构造无新键的旧档 `rngStates` → 加载后按 `systemSeed + id`
   确定性重种（可复跑断言，非仅日志）；`RngSourceGuardTest` 覆盖新 id 登记；
6. **对拍重定基线清单**：受影响 `Diff*` 测试逐个列出改动 + 原因（完成报告表格）；
7. **文档三件套**：方案 §7.2 追加 **B14 行**（消费面清单、新分区 id/播种公式、
   存档版本说明、对拍重定基线理由）；`CHANGELOG.md` 4.01.15 段内追加；
   `docs/cpp-engine.md` 进展行同步；`RngPartition.kt` KDoc 同步新分区条目。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字（含新基线）/ JUnit 六模块各模块数与实跑证明（XML 时间戳 + Diff 0 skip）/ detekt 结论——贴关键输出行；
- **per-roll JNI 消除证明**（守卫实跑输出）与其余分区不受影响的对照；
- 消费面枚举清单（分区/调用点/迁移前后对照）；
- 新分区 id、播种公式、老档重种语义与存档版本说明落点；
- **对拍重定基线清单**（逐测试：改动 + 原因）；
- 改动文件清单；与方案 R4.4 验收口径的逐条对照。
