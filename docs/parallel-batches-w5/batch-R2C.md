# 批次 B08 — R2.3 第二波（镜像瘦身：GameViewStore 投影态 + replaceAll 退役）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R2 表（R2.3 行第二波）。
> **你只做本批范围。R2.4 及其他条目一律不动。**
> 前置已就绪：B06（proto 信封+解码换轨）与 B07（第一波：消费面 100% 二进制馈送审计 +
> 三臂收敛守卫 + 静态门禁）——先读提交 daa8eeb11/6b3354708（B06）、707cbf2df/cb59bf537/
> 57f1d67ae（B07）、审计报告 `docs/mirror-consumer-audit-2026-09-18.md`、方案 §7.2 B06/B07 行。

## 任务

1. **GameViewStore 投影态新增**：按 UI 消费块拆分的投影 store（替代 GameStateStore
   全量快照的唯一入口地位）；
2. **GameStateStore 全量 replaceAll 退役**（每旬级全量重建路径退场）；
3. **ViewModel 逐块迁移**：按消费块逐个迁移到 GameViewStore 投影（一次一个块，每块独立 commit）；
4. **mirror 段耗时观测**：迁移前后对照，记录向 G2（<10ms@5000 弟子）迈进的实测数字。

## 红线

- **投影缺失字段 fail-fast 而非静默空**（方案 §5 风险条款原文）——投影缺字段时抛错，
  禁止返回默认值掩盖；
- **UI 行为零变更**：迁移是数据来源切换，ViewModel/UI 可见数据语义逐块等价；
  每块迁移独立可回滚（独立 commit）；
- **灰度共存**：投影态与全量路径共存一个版本周期（旗标控制回退）；
- **存档/协议 JSON 面、JNI 签名零变更**；
- 每子项独立 commit，信息沿用仓库惯例（`feat(engine): 重构方案 R2.3 二波 —— …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL；
- 环境隐患：遇 classes.jar 句柄 FileSystemException → 终止 Kotlin 编译守护进程后重跑。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（当前 desktop-test 配置基线 **1453**；纯 Kotlin 批二进制
   无需重建；新增守卫需同步更新基线数字并登记）；
2. 全量 JUnit **实跑非 UP-TO-DATE**：
   `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks
   "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so"`；
   已知抖动 `GameEngineCoreLifecycleInterleavingTest`：单点失败且 Diff 全过 → 单类重跑绿
   记录放行，勿改测试；engine 模块串行最慢（3300+ 用例）属正常，勿误判卡死；
3. `./gradlew.bat detekt` 绿；
4. **G2 趋势证据**：mirror 段耗时迁移前后对照数字（B06 bench 9e1d9c0cc 的
   `DirtyTrackerBench.MirrorTransportJsonVsProtobuf` 可扩展复用）；
5. **投影完整性证据**：每块迁移的等价守卫（投影字段 ↔ 旧全量字段逐值对照，缺字段 fail-fast
   的守卫用例）；
6. 文档三件套：方案 §7.2 追加 B08 行（投影块清单/迁移顺序/G2 数字）；
   `CHANGELOG.md` 4.01.15 段内追加；`docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐块迁移逐 commit）；
- GTest 数字 / JUnit 各模块数与实跑证明 / detekt 结论（贴关键输出行）；
- mirror 段耗时前后对照数字（G2 趋势）；
- 投影块清单与迁移顺序、fail-fast 守卫证据；
- 改动文件清单；与方案 R2.3 第二波验收口径的逐条对照。
