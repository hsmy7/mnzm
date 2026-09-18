# 批次 B06 — R2.1 + R2.2（GameView proto 定义 + 镜像通道换 protobuf）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R2 表（R2.1、R2.2 行）。
> **你只做本批范围。R2.3/R2.4 及其他条目一律不动。**
> 前置已就绪：R1.4 列级写屏障（B03，`column_dirty.h`）是本批的技术前提，先读
> `eb9812c0a` 与方案 §7.2 B03 行。
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§2/§3.R2/§7.2。

## 任务

1. **R2.1 定义 `GameView` proto**：
   - 四块结构：resourcesHeader / discipleListDelta（行级增量）/ eventFeed（月年结算、
     突破、死亡、购买、秘境关闭）/ configEcho；
   - **选型 protobuf**（基建已在：app 插件 + core/engine/src/main/proto），不引 flatbuffers；
   - **schema 演进纪律：proto 字段只增不改**（方案 §5 风险条款），文件头注释声明该约束；
2. **R2.2 镜像通道换 protobuf**：
   - `nativeExportDirty` 产出 protobuf 信封；`StateSyncService` 解码换 protobuf；
   - **存档格式不动**（仍 JSON，低频路径，兼容优先）——`nativeExportState` 全量 JSON
     仅保留给存档/rebaseline；
   - 优先沿用既有 JNI 通道扩展输出格式；如确实无法避免新增 external fun，
     须在 §7.2 与 CHANGELOG 登记豁免理由（沿 R0.2 探针先例）。

## 红线

- **对拍零漂移**：R1.4 列级导出为 opt-in 能力，本批接入热路径时同样保持
  Diff 对拍路径的既有全量导出行为（全量模式开关保留）；
- **UI 消费面本批不动**（R2.3 才迁移）——镜像仍全量、仅换传输编码为二进制 protobuf，
  旧 JSON 镜像路径保留灰度开关（新旧共存一个版本周期）；
- 每子项独立 commit（R2.1 一个、R2.2 一个），信息沿用仓库惯例；
- Kotlin 侧 StateSyncService 改动允许，但**存档/协议 JSON 面、JNI 签名（除非登记豁免）零变更**；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL；
- 环境隐患（B04 实证）：遇 classes.jar 句柄 FileSystemException → 终止 Kotlin 编译
  守护进程后重跑，勿反复重试。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（当前 desktop-test 配置基线 **1443**，含 bench 3 用例；
   llvm-mingw + SDK cmake 3.22.1 入 PATH；新增守卫需同步更新基线数字并登记）；
2. 全量 JUnit **实跑非 UP-TO-DATE**：先 `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥
   （本批含 C++ 改动则必须重建），再 `./gradlew.bat testReleaseUnitTest --max-workers=1
   --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so"`；
   已知抖动 `GameEngineCoreLifecycleInterleavingTest`：单点失败且 Diff 全过 → 单类重跑绿
   记录放行，勿改测试；
3. `./gradlew.bat detekt` 绿；
4. **镜像通道等价性证据**：protobuf 信封与旧 JSON 镜像在相同 state 输入下语义等价
   （守卫测试对照），并证明 PhaseSegmentTimer mirror 段未劣化（G2 趋势向 <10ms 观测）；
5. 文档三件套：方案 §7.2 追加 B06 行（含 proto schema 摘要、灰度开关位置）；
   `CHANGELOG.md` 4.01.15 段内追加；`docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字 / JUnit 各模块数与实跑证明 / detekt 结论（贴关键输出行）；
- mirror 段耗时观测（新旧对比，如有）；
- proto 字段清单摘要 + 灰度开关名称与默认值；
- 改动文件清单；与方案 R2.1/R2.2 验收口径的逐条对照。
