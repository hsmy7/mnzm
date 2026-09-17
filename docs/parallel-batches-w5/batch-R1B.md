# 批次 B02 — R1.2 + R1.3（去物化 + dense 索引）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R1 表（R1.2、R1.3 行）。
> **你只做本批范围。其他批次条目（R1.4/1.5/1.6 及 R2+）一律不动。**
> 开工前先读仓库 `CLAUDE.md` 全文与方案 §1/§3.R1；B01（R1.1/R1.5）已落地，先读其
> 提交（`01849d8b4`、`ad5ca62ee`）与方案 §7.2 登记，续接同一改造模式。

## 任务

1. **R1.2** `committedDisciples` 去物化：
   - 突破只需"长老悟性 committed 视图"少量字段；
   - 改法：候选筛选先在 SoA 列上判定（R1.1 后已便宜），**仅对命中候选的弟子物化**；
   - 效果目标：每旬 D 次深拷贝 → 候选数次。
2. **R1.3** 字符串键 → dense 索引：
   - DiscipleStore 增 numeric id 列（ids 本是数字串）；
   - equipment/manual 映射改 owner 行索引桶式存储；
   - **分两步走（各自独立 commit）**：先建索引逐点替换；`idToRow` string 键仅保留在协议边界。

## 红线（与 B01 相同，违者验收打回）

- **只改形状，不改结果**：全量 Diff 对拍逐位一致是唯一且充分的正确性标准；
- **每子项独立 commit**（R1.2 一个、R1.3 一个；R1.3 内部分两步可再各一笔），提交信息
  沿用仓库惯例（参考：`feat(engine): 重构方案 R1.2 —— …`）；
- 不动 Kotlin 协议面、存档格式、JNI 签名；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL；
- 确定性迭代序：对拍路径如依赖遍历序，保持保序（R1.6 的 swap-and-pop 不在本批）。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest 对拍**全绿**（基线 1419，`ctest` 于
   `android/app/src/main/cpp/gamecore/build/desktop-test`；工具链 llvm-mingw + SDK cmake 3.22.1 入 PATH）；
2. 全量 JUnit 必须**实跑非 UP-TO-DATE**：先 `pwsh -File scripts/build-desktop-jni.ps1`
   重建对拍桥（嵌入你的 C++ 改动），再
   `./gradlew.bat testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so"`；
   如遇 UP-TO-DATE 需 `--rerun-tasks` 强制实跑并在报告注明核查方法（结果 XML 时间戳 + Diff 类 0 skip）；
3. `./gradlew.bat detekt` 绿；
4. 文档三件套：方案 §7.2 登记表追加本批行（状态/关键落点/测试口径）；`CHANGELOG.md`
   沿用 4.01.15 段内追加或按仓库规则递增（纯内部批不增 version.properties，与 B01 同口径）；
   `docs/cpp-engine.md` 涉及口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 通过数 / JUnit 各模块通过数与实跑证明 / detekt 结论（贴关键输出行）；
- 改动文件清单；
- 与方案 R1.2/R1.3 验收口径的逐条对照（含"每旬深拷贝次数 → 候选数次"的达成说明）。
