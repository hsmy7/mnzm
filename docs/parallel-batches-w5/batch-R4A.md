# 批次 B04 — R4.2 秘境战斗切 native

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R4 表（R4.2 行）+ §3.R4 统一流程。
> **你只做本批范围。其他条目（R4.3/R4.4 及 R2/R3）一律不动。**
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§2/§3.R4/§7.2、`docs/adr/reverse-channel-elimination.md`
>（w3-13 Kotlin 只读原则），并研究 R4.1 遭遇战已落地的接线模式
>（`BattleExecutionRouter` → `nativeBattleExecute`/`nativeAiBattleExecute`，提交 b80dc20ad）——
> **本批复用同一路由与灰度模式，不新发明机制。**

## 任务

1. **R4.2 秘境战斗**：`SecretRealmService.executeBattleWithTimeout`（1291 行服务）拆出战斗执行，
   切 `nativeBattleExecute`（native 优先、异常回退 Kotlin）；
   **会话管理 / UI / 邮件 / 暂停租约留 Kotlin（平台域）**——只切战斗执行段。
2. 遵循 R4 统一流程：**迁移 → 对拍 → flag 灰度**：
   - 灰度 flag 接 R4.1 既有旗标体系（沿 BattleExecutionRouter 既有模式）；
   - **Kotlin 回退臂保留一个版本周期**（本批不删臂、不转 golden）。

## 红线

- **只切执行通道，不改行为**：秘境战斗结果（胜负/伤亡/掉落/RNG 消耗序）逐位一致；
  RNG 走既有 NativeBackedRng 委托式约定，分区调整属 R4.4 不在本批；
- Kotlin 服务代码本批**允许改动**（这是 R4 域接管的性质），但**协议面/存档格式/JNI 签名零变更**；
- 每子项独立 commit，信息沿用仓库惯例（`feat(engine): 重构方案 R4.2 —— …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（当前 desktop-test 配置基线 **1443**，含 bench 3 用例；
   llvm-mingw + SDK cmake 3.22.1 入 PATH；新增守卫需同步更新基线数字并登记）；
2. 全量 JUnit **实跑非 UP-TO-DATE**：先 `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥，
   再 `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks
   "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so"`；
   **已知抖动**：`GameEngineCoreLifecycleInterleavingTest` 为 W2 期并发时序用例（progress.md:912
   有前例），若其单点失败且 Diff 全过，单类重跑绿后记录即可，勿改动该测试；
3. `./gradlew.bat detekt` 绿；
4. 文档三件套：方案 §7.2 追加 B04 行（R4.2 状态/关键落点/测试口径/灰度 flag 位置）；
   `CHANGELOG.md` 4.01.15 段内追加；`docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字 / JUnit 各模块数与实跑证明 / detekt 结论（贴关键输出行）；
- 秘境战斗 Diff 对拍证据（哪 Diff 类覆盖了该路径）；
- 灰度 flag 名称与默认值、回退臂位置说明；
- 改动文件清单；与方案 R4.2 验收口径的逐条对照。
