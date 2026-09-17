# 批次 B05 — R4.3 探索/巡逻生产下沉

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R4 表（R4.3 行）+ §3.R4 统一流程。
> **你只做本批范围。其他条目（R4.4 及 R2/R3）一律不动。**
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§3.R4/§7.2、`docs/adr/reverse-channel-elimination.md`，
> 并研究 R4.1/R4.2 已落地的路由接线模式（`BattleExecutionRouter`，fa8fa5833 为最近范例）——
> 复用既有机制，不新发明。

## 任务

1. **R4.3 探索/巡逻生产**：`ExplorationService` 的结果生产评估下沉 C++；
   - 参照系：AI 兽战处理器已是 native 优先模式（先调研该模式再动手）；
   - **仅切结果生产评估**——探索会话管理/UI/通知等平台域留 Kotlin；
2. 遵循 R4 统一流程：**迁移 → 对拍 → flag 灰度**：
   - native 优先、异常回退 Kotlin；灰度 flag 接既有旗标体系；
   - **Kotlin 回退臂保留一个版本周期**（本批不删臂、不转 golden）。

## 红线

- **只切生产通道，不改行为**：探索/巡逻结果（产出物/数量/RNG 消耗序）逐位一致；
  RNG 走既有 NativeBackedRng 委托式约定，分区独立属 R4.4 不在本批；
- Kotlin 服务代码允许改动，但**协议面/存档格式/JNI 签名零变更**；
- 每子项独立 commit，信息沿用仓库惯例（`feat(engine): 重构方案 R4.3 —— …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL；
- **环境隐患处置**（B04 登记，看护台账经验教训同步）：若遇
  `FileSystemException: 另一个程序正在使用此文件`（bundleLibCompileToJarRelease/classes.jar），
  直接终止 Kotlin 编译守护进程后重跑，勿反复重试。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（当前 desktop-test 配置基线 **1443**，含 bench 3 用例；
   llvm-mingw + SDK cmake 3.22.1 入 PATH；新增守卫需同步更新基线数字并登记）；
2. 全量 JUnit **实跑非 UP-TO-DATE**：先 `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥，
   再 `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks
   "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so"`；
   **已知抖动**：`GameEngineCoreLifecycleInterleavingTest` 单点失败且 Diff 全过时，
   单类重跑绿后记录即可，勿改动该测试；
3. `./gradlew.bat detekt` 绿；
4. 文档三件套：方案 §7.2 追加 B05 行（R4.3 状态/关键落点/测试口径/灰度 flag 位置）；
   `CHANGELOG.md` 4.01.15 段内追加；`docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字 / JUnit 各模块数与实跑证明 / detekt 结论（贴关键输出行）；
- 探索生产 Diff 对拍证据（哪 Diff 类覆盖了该路径）；
- 灰度 flag 名称与默认值、回退臂位置说明；
- 改动文件清单；与方案 R4.3 验收口径的逐条对照。
