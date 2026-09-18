# 批次 B07 — R2.3 第一波（UI 消费面完成二进制传输切换，镜像仍全量）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R2 表（R2.3 行第一波）。
> **你只做本批范围。R2.3 第二波（镜像瘦身/GameViewStore）与 R2.4 及其他条目一律不动。**
> 前置已就绪：B06（R2.1+R2.2）已落地 GameView proto 信封与 StateSyncService 解码换轨——
> 先读提交 daa8eeb11 / 6b3354708 / 9e1d9c0cc 与方案 §7.2 B06 行（含灰度旗标
> `NativeEngineFlag.mirrorProtobufTransport` 说明）。

## 任务

1. **R2.3 第一波——UI 消费面只换传输（镜像仍全量、二进制）**：
   - 先审计 B06 后的残余缺口：哪些 UI 侧消费点仍走旧 JSON 镜像解码/旧字节路径
     （对照 StateSyncService → GameStateStore 馈送链逐点核查）；
   - 把残余消费点切到 protobuf 解码产物，**GameStateStore 仍全量 replaceAll**（第二波才退役）；
   - **UI 行为零变更**：ViewModel/UI 层无感（数据形状不变，仅来源编码变）；
   - 旧 JSON 镜像回滚臂保留（灰度旗标既有语义不变，新旧共存）；
2. 若 B06 后消费面已全部切换（审计结论为零缺口），则本批交付物为：
   审计报告 + 补齐等价性守卫（覆盖全部残余消费点）+ 镜像链路 e2e 观测固化，
   并在报告中明确"第一波实质已由 B06 完成，本批补齐守卫与证明"。

## 红线

- **UI 行为零变更**：切传输不改任何 UI 可见数据语义；发现数据形状差异即为缺陷（fail-fast）；
- **镜像仍全量**：不做任何瘦身/投影/增量替换（第二波范围）；
- **存档/协议 JSON 面、JNI 签名零变更**（如确需新增引擎控制端口，沿 R0.2/B06 先例登记豁免）；
- Kotlin 侧改动允许；C++ 侧原则上不动（如确需，独立 commit 并说明）；
- 每子项独立 commit，信息沿用仓库惯例；提交前
  `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL；
- 环境隐患：遇 classes.jar 句柄 FileSystemException → 终止 Kotlin 编译守护进程后重跑。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（当前 desktop-test 配置基线 **1453**（B06 后），含 bench 用例；
   本批原则无 C++ 变更则二进制无需重建、ctest 直接跑）；
2. 全量 JUnit **实跑非 UP-TO-DATE**：
   `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks
   "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so"`；
   已知抖动 `GameEngineCoreLifecycleInterleavingTest`：单点失败且 Diff 全过 → 单类重跑绿
   记录放行，勿改测试；遇 jar 锁先杀 Kotlin 编译守护进程；
3. `./gradlew.bat detekt` 绿；
4. **消费面切换证据**：审计清单（消费点 → 切换前后对照）+ 全链路守卫测试
   （proto 信封 → 解码 → GameStateStore 馈送，断言与旧路径同形同值）；
5. 文档三件套：方案 §7.2 追加 B07 行；`CHANGELOG.md` 4.01.15 段内追加；
   `docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字 / JUnit 各模块数与实跑证明 / detekt 结论（贴关键输出行）；
- 审计清单：残余消费点数量与切换对照表（或"零缺口"结论的证据）；
- 灰度开关现状说明；
- 改动文件清单；与方案 R2.3 第一波验收口径的逐条对照。
