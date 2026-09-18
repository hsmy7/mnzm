# 批次 B09 — R2.4（eventFeed 并入 + 执行器退化平台适配）+ R2 收官核对（G2/WS-1）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R2 表（R2.4 行）+ R2 验收行
> （"mirror 段 < 10ms@5000 弟子（G2）；WS-1 关闭"）+ §7.2 B08 行的诚实登记
> （"G2 <10ms 未达成；残余成本中心 = R1.4 列级导出未接生产"）。
> **你只做本批范围。R3 及其他条目一律不动。**
> 前置：B06/B07/B08（GameView proto、消费面二进制化、GameViewStore 投影态）。

## 任务

1. **R2.4 月/年 JSON 信封并入 eventFeed**：proto 块 3 `eventFeed` 正式产出
   （B08 登记的 `PROTO_EVENT_FEED_BLOCK = "reserved-not-produced"` 预留位转正），
   月/年结算事件（月年结算、突破、死亡、购买、秘境关闭）入流；
2. **残留执行器退化为纯平台效应适配器**：发邮件/写 Room/通知，**不再解析 JSON**
   （静态守卫固化"执行器零 JSON 解析"）；
3. **G2 缺口处理（R2 收官判定）**：
   - 接入 R1.4 列级写屏障的**生产路径**（B03 交付的 opt-in 能力：热路径写点标脏 +
     导出仅序列化脏列），保留全量导出开关供对拍；
   - 重测 mirror 段 @5000：达标（<10ms）→ 登记 WS-1 关闭；未达标 → 逐项登记残余
     成本中心与后续计划，**不得虚报达标**；
4. **R2 收官核对**：§3 R2 验收口径逐条对照（G2/WS-1、Kotlin 每旬 GC 分配显著下降）。

## 红线

- **对拍零漂移**：列级导出仅序列化脏列必须与全量导出在相同初态+相同写集下语义等价
  （守卫测试对照），全量模式开关保留；
- **残留执行器行为等价**：平台效应（邮件内容/Room 写入/通知）逐字段等价，仅剥离 JSON 解析；
- 存档格式、协议 JSON 面、JNI 签名零变更（新增引擎控制端口须登记豁免）；
- 每子项独立 commit，信息沿用仓库惯例；提交前
  `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL；
- 环境隐患：遇 classes.jar 句柄 FileSystemException → 终止 Kotlin 编译守护进程后重跑；
  engine 模块串行最慢属正常勿误判卡死。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（本批含 C++ 改动则先 `pwsh scripts/build-desktop-jni.ps1`
   重建对拍桥 + `cmake --build` 重建 GTest 二进制；当前基线 **1453**，新增守卫同步登记）；
2. 全量 JUnit **实跑非 UP-TO-DATE**（`--rerun-tasks` + `-Dgamecore.jni.path`）；
   已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按前例处置；
3. `./gradlew.bat detekt` 绿；
4. **G2 证据**：mirror 段 @5000 重测数字（接入列级导出后），与 B08 的 132.68ms 对照；
   达标则登记 WS-1 关闭，未达标则逐项登记残余成本；
5. **执行器退化证据**：静态守卫（执行器源码零 JSON 解析命中）+ 平台效应等价测试；
6. 文档三件套：方案 §7.2 追加 B09 行 + R2 收官小结；`CHANGELOG.md` 4.01.15 段内追加；
   `docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字 / JUnit 各模块数与实跑证明 / detekt 结论（贴关键输出行）；
- G2 重测数字与 WS-1 判定结论（达标/未达标+残余成本清单）；
- 执行器退化对照（JSON 解析命中 0 的守卫证据）；
- 改动文件清单；与方案 R2.4 + R2 验收口径的逐条对照。
