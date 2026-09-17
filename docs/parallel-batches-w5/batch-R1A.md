# 批次 B01 — R1.1 + R1.5（map 重建提升到步骤入口）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R1 表（R1.1、R1.5 行）。
> **你只做本批范围。其他批次条目（R1.2/1.3/1.4/1.6 及 R2+）一律不动。**
> 开工前先读仓库 `CLAUDE.md` 全文与方案 §1/§3.R1。

## 任务

1. **R1.1** `isFullHpMp` 的 map 重建从**每实体**提升到**每步骤入口**：
   - 位置：gamecore `phase_settlement.h:159-181` 两个重载（以当前工作区实际行号为准）；
   - **精确改法（不得偏离方案）**：不能无脑提循环外——孕养升级当旬的候选判定依赖最新
     nurtureLevel。正确改法：突破候选筛选（步骤 7）在核心批次（步骤 1-5 含孕养）**之后**
     执行，在步骤 7 入口构建一次 map 传入；既保住"当旬最新"语义又消 D-1 次重建。
     同文件核心批次（约 :1315）已示范该模式，先读懂示范再动手。
2. **R1.5** `getMaxHpMp` 临时 map 消除：`disciple_stats.h:289` 附近，随 R1.1 同一改造模式。

## 红线

- **只改形状，不改结果**：全量 Diff 对拍逐位一致是唯一且充分的正确性标准；
- 每子项**独立 commit**（R1.1 一个、R1.5 一个），提交信息沿用仓库惯例
  （参考：`feat(engine): 重构方案 R1.1 —— …`）；
- 不动 Kotlin 协议面、存档格式、JNI 签名；不改其他文件族；
- 提交前按 CLAUDE.md §13.1：`./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL；
- 确定性迭代序：对拍路径如依赖遍历序，保留保序版本（R1.6 才引入 swap-and-pop，本批不做）。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest 对拍**全绿**（现行基线见方案 §7.1：携旗标全量 1419；构建/运行按
   `docs/cpp-engine.md` 与 `scripts/build-desktop-jni.ps1` 既有流程，llvm-mingw bin 需在 PATH）；
2. `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1` 全绿（必须串行）；
3. `cd android && ./gradlew.bat detekt` 绿；
4. 文档三件套：方案 §7 新增本批登记小节（状态/关键落点/测试口径）；`CHANGELOG.md`
   按仓库版本规则新增段落（当前 4.01.14 → 递增）；`docs/cpp-engine.md` 涉及口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 通过数 / JUnit 通过数 / detekt 结论（贴关键输出行）；
- 改动文件清单；
- 与方案 R1.1/R1.5 验收口径的逐条对照。
