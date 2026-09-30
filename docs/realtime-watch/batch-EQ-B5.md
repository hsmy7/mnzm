# 派发件 · EQ-B5 文档与发布收口（装备线末批）

> 派发真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§4.6 B5 / §2 门禁 / §8）+ `docs/design/equipment-set-system-refactor-plan.md` HEAD 版。

## 0. 工作区与纪律

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set`（HEAD = EQ-B4 收官笔 `762b83def` 之后，开工先 `git status` 核干净）。**禁止在主树改任何文件。**
- 本会话只实施 **EQ-B5 一批**（装备线末批），完成即收官提交；不开下一批。
- 实施前先读：方案 HEAD（§九 债表 I1–I10 / R12–R17 / D1–D10 最终状态）+ IMPLEMENTATION-BATCHES（§4.6 / §8）+ B0–B4 五份报告（各批移交面）+ 看护台账（只读；异议写其 §9）。
- 报告：`docs/design/equipment-batches/reports/report-B5.md`（门禁实测原数字+债表全景对照；禁「应该通过」/占位符），随收官笔入库。
- 收官：单笔提交，格式 `chore(equip): B5 文档与发布收口——<要点>`；**版本号不自增**（用户拍板）。
- 构建副产物（`atlas-rgba-manifest.json` 等）提交前 `git checkout --` 还原。`accepted` 由看护亲验后设置。
- 🔴 **E12 开工前置**：`git status` 干净 + 无他线在途——命中即停手报告。

## 1. 批次任务（IMPLEMENTATION-BATCHES §4.6 原文）

| 项 | 内容 |
|---|---|
| **目标** | 收口：把 R1–R12、D1–D10、I1–I10、R12–R17 的最终状态写进活文档与两个更新日志，并交付 ADR。 |
| **前置** | B4 完成（✓ `762b83def` accepted）。 |
| **写入面** | ① `CHANGELOG.md` + `android/app/src/main/assets/changelog_entries.json`（**两个一起更新**，玩家版文案通俗无术语、不泄数值）；② `docs/knowledge-base.md`（经济基线表：升级/分解/两笔补偿；子系统索引）；③ `docs/architecture.md`（属性与装备体系描述 + 债 D/I 登记）；④ `docs/cpp-engine.md`；⑤ **新增** `docs/adr/equipment-set-system.md`（含"单列属性"与"删堆叠"两项决策）；⑥ `CODE_WIKI.md`；⑦ `docs/ui-read-surface.md` §2（最终镜像面）；⑧ `docs/threading-contract.md`（若新增跨线程交互则登记，否则声明不涉及） |
| **影响面** | 纯文档 + 玩家可见文案。 |
| **兼容性** | 必须写明"**存档版本不可回退**"（高于旧客户端）与补偿公告。 |
| **验收判据** | ① `node scripts/check-agent-instructions.mjs` 全绿（引用无死链）；② 两个更新日志同批更新（漏一个即未完成）；③ ADR 落 `docs/adr/`；④ 债表 I1–I10 全景登记；⑤ `git status` 只含本批改动。 |
| **旧用例处置** | 无。 |
| **回滚** | 文档 revert。 |
| **规模** | 小（~12 文件）。 |

## 2. 本批追加义务（看护汇编——四批累计移交面，逐条收口或显式入债表）

1. **双 changelog 补登记 B0–B4 全量**（B2/B3/B4 三份报告均登记「归 B5」）：外部 `CHANGELOG.md` 增「装备系统重构线」小节（B0 冻结编号/B1 属性单列/B2 孕养丹退役+补偿/B3 六部位实例轨+Room v64/B4 数值校准）；游戏内 `changelog_entries.json` 玩家文案同批（通俗、不泄数值；孕养丹退役与补偿公告、存档版本不可回退提示必写）。
2. **对拍两缺口桥端口**（B3 报告 §5 移交+轮#105 钉入）：`DiffEquipmentGeneration`/`DiffEquipmentSetBonus` 至今无测试桥端口——本批在债表显式登记（缺口内容/风险面/建议触发条件），若实施成本低可顺手补桥，但**不得扩面停留**（文档批纪律：登记优先于实施）。
3. **暴击面板 uncapped 专项候选**（B4 报告 §7.3：暴击率 203%/暴伤 882%）：债表登记为数值评审专项候选（修在战斗 cap 层，不动基数表）。
4. **月产出锚 940 万落经济基线表**（B4 §3 指定）：`STANDARD_MONTH_OUTPUT_AT_T6_STAGE` 注记进 knowledge-base 经济基线表（标注=拍板口径反推定锚、非测量值，上线后运营数据回标）。
5. **SecretRealmBackpack.equipment 旧堆叠轨**白名单项（B3 §7.3→B4 §7.6）：债表登记收口批次建议。
6. **ForgeRecipeDatabase.TIER_DURATION 单位歧义**（B3 §7.5→B4 §7.4）：债表登记（旬/月语义 + 88 炉产能复核触发条件）。
7. **AI 装备加成产品决策**（B4 §6.5）：债表登记（当前双端占位 0 加成语义一致）。
8. **结构性数值登记两条**（B4 §7.1/7.2）：T1@炼气越带、深化期衰减——随方案 §8 未来场景注记。

## 3. 门禁命令（收官前全跑；报告记实测原数字）

worktree 根/`android/` 下执行：

1. `node scripts/check-agent-instructions.mjs`（**本批主门**：引用无死链——新增 ADR/债表引用全可解析）
2. 双 changelog 完整性自查：`changelog_entries.json` JSON 解析合法（`python -c "import json;json.load(open(...))"` 或等价）+ 外部/内部两日志同批更新对照表入报告
3. `./gradlew.bat compileReleaseKotlin`（快速增量，验证资源与文档引用面不破编译）
4. `git status` 只含本批改动（验收判据 ⑤）；构建副产物已还原

本批不触 C++/Room/JVM 测试面（.so 复用 EQ-B4 产物 10:01，无需重编；ctest/JVM 不跑，报告如实注明复用 B4 基线）。

## 4. 环境教训（必读）

- 纯文档批：门禁轻（agent-instructions+JSON 解析+compile 增量），但**引用死链是本批最大风险**（新增 ADR 与债表引用面大）——每加一处引用跑一遍 agent-instructions。
- `atlas-rgba-manifest.json` 等构建副作用 checkout 还原勿混入提交。
- 5 小时用量上限触顶会暂停——重置后续跑，现场勿动勿删。
- 诚实纪律：门禁失败须归因入报告。

## 5. 前批交付事实附录（EQ-B4，看护填）

1. EQ-B4 收官笔 **`762b83def`**（**13 文件 +837/−16**）；**看护已亲验 accepted**（定向 PowerParity 5/0/0+EconomyCalibration 4/0/0 亲跑打新 .so、ctest 1521/1521 亲跑 50.72s、jni-count 87/87、agent-instructions 绿）；worktree 树净，HEAD 即该笔。
2. **门禁基线（B4 后）**：六模块 JVM **7652/0/0 22skip**（domain 1599、data 881/15skip、engine 2997/5skip、ui 155、feature:game 992、app 1028/2skip）；ctest **1521/1521**；jni-count **87/87**；detekt 零违规 baseline 零动；**.so**：worktree 重编 10:01（259 源同源指纹）——本批不触 C++ 直接复用。
3. **数值终态**：S9 五入口阶段（金丹/元婴/化神/炼虚/大乘）中位占比全入 [35,45] 带、**大乘锚 38.25%**（仅 T6 档 flat 主词条基数 ×1.8：ATTACK/DEFENSE 780→1404、HP 7800→14040；CRIT_RATE 行与 T1–T5 不动）；S16 满级一套 9,396,000 ÷ 月产出锚 **9,400,000** = **0.9996**；S18 热点基准 0.282–0.603（门 ≤1.10）；**速度/灵力不补偿**（拍板维持，S14 零贡献回归锁在）。
4. **热点缓存新决策（勿翻案）**：`EquipStatResolver` 恒等键整解析缓存（方案 §13-6 字面「值语义缓存」经实测否定——深哈希 6.3× 劣化；恒等键 124–161 ns，4096 清空护栏；版本安全=全 val+copy 纪律）。
5. **期望成本表已入方案 §九 I9**：掉落链理想套 11,389 件（精确容斥）；锻造链 88 次理想套（12 配方天然定向）——I9 触发时优先评估「锻造指定主词条/洗练」而非新建定向链。
6. **B4 新测试面**：PowerParityTest(5)+EconomyCalibrationTest(4)+HotPathBenchmark(1)+RarityGateTest(+2→11)——B5 文档引用这些类名时按此拼写。
7. **装备线累计收官笔**：B0=`5280d1b46` / B1=`9068049a1` / B2=`fa36109fc` / B3=`45bf909cd` / B4=`762b83def`——双 changelog 补登记时按此追账；Room 现值 **v64**（B3 占），B5 零迁移。
8. **实时线遗留三项勿顺手处置**；版本号不自增（`version.properties` 4.01.16 原值，发版号由用户拍板）。
