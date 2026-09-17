# 批次 B03 — R1.4 + R1.6 + R1 收官 bench（写屏障 + 去 O(D²) + G1 证明）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R1 表（R1.4、R1.6 行）
> + R1 验收行（"新增 bench（malloc 计数 + 耗时）证明 G1"）+ §"CI 与度量执法"第 1 条。
> **你只做本批范围。R2 及其他条目一律不动。**
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§3.R1/§CI、§7.2 既有登记，续接 B01/B02 提交
> （`01849d8b4`/`ad5ca62ee`/`96636ec95`/`d4e25dac1`/`dd2b4e0e9`/`f7e9b3251`）同一改造模式。

## 任务

1. **R1.4** DirtyTracker 列级写屏障：
   - 各 SoA 列 dirty 位图 + 集合 tombstone，导出仅序列化脏列；
   - **本批只做 C++ 侧写屏障与导出能力**（R2 的前置），不改 Kotlin 消费面；
   - 注意导出语义：对拍路径所依赖的全量导出行为必须可显式选择（保留全量模式开关），
     确保既有 Diff 对拍零漂移。
2. **R1.6** `destroyDiscipleEntities` 去 O(D²)：
   - `eraseEntity` 增加 **swap-and-pop 变体**供重建场景使用；
   - 红线：确定性迭代序仅在需要对拍的路径保留保序版本（方案原文）——swap-and-pop
     只用于重建/批量删除等顺序无观察点的场景；
   - 若 R1.3 已建 `numericIdToRow`/桶结构，注意 swap 后行号漂移的索引同步（同点维护惯例）。
3. **R1 收官 bench（G1 证明）**：
   - 新增 bench：每旬结算（5000 弟子）**malloc 计数 + 耗时**，证明 G1（堆分配 < 1 万次/旬，
     基线 ~15 万）；
   - 纳入门禁：沿用 kover 开关模式（本地可关、CI 必跑），脚本/CI 接线按仓库既有惯例；
   - bench 结果数字登记进方案 §7.2 与 CHANGELOG（G1 达成证据）。

## 红线（与 B01/B02 相同，违者验收打回）

- **只改形状，不改结果**：全量 Diff 对拍逐位一致；R1.4 写屏障不得改变对拍导出内容；
- **每子项独立 commit**（R1.4 一个、R1.6 一个、bench 一个），信息沿用仓库惯例；
- 不动 Kotlin 协议面、存档格式、JNI 签名；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（当前基线 **1425**；`ctest` 于
   `android/app/src/main/cpp/gamecore/build/desktop-test`；llvm-mingw + SDK cmake 3.22.1 入 PATH；
   新增守卫需同步更新基线数字并登记）；
2. 全量 JUnit **实跑非 UP-TO-DATE**：先 `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥，
   再 `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks
   "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so"`，
   报告注明实跑证明（任务全 executed + Diff 类 0 skip）；
3. `./gradlew.bat detekt` 绿；
4. **bench 证据**：malloc 计数/耗时数字（5000 弟子）贴进完成报告，证明 G1 < 1 万次/旬；
5. 文档三件套：方案 §7.2 追加 B03 行（R1 收官宣告 + G1 数字）；`CHANGELOG.md` 4.01.15
   段内追加（口径同 B02）；`docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字（含新增守卫）/ JUnit 各模块数与实跑证明 / detekt 结论；
- bench 关键输出（malloc 次数、耗时、对比基线）；
- 改动文件清单；
- 与方案 R1.4/R1.6/R1 验收口径的逐条对照。
