# report-G13 · 数值落地（M0 杠杆终局 · 突破补偿 · 回血参数 · 经济复测 · 星级乘区终值）

> 派工真源：[`TASKBOOK-G13.md`](TASKBOOK-G13.md)；开工时点 2026-09-27，G12 已 accepted（收官笔 `616f50912`，核验笔 `866ec165f`）。
> 本报告的每个数字都取自**本会话同轮命令输出**，未跑的一律显式登记为未做（§8）。
> 会话纪律遵守：只实施本批、不自登记 accepted、`git add` 一律明确文件名。

---

## 1. 结论一页速览

| 项 | 结论 |
|---|---|
| 状态 | **五任务（A…E）全部落地 + 本机可得的门禁全绿**；本批**零行为改动**（删的是零消费配置键）⇒ 桌面 ctest / JNI 重建不适用，`.so` 三件套 = G10 交付值逐字一致 |
| 任务 A | ⑨ 突破补偿终局 = **不补**（G09 P-6 拍板兑现）：删 `gachaDefaults.breakthroughCompBonus` + `GameConfig.Gacha.BREAKTHROUGH_COMP_BONUS` + 守卫同值断言行；白皮书 §3 勾「不采纳」并备注「终局=不补，键已移除」 |
| 任务 B | ⑩ 重伤回血终局 = **重复键认定**（D-2 推荐臂①）：删 `gachaDefaults.injuryHealPctPerPhase` + `INJURY_HEAL_PCT_PER_PHASE` + 守卫行；回血单源留既有机制 `Cultivation.PHASE_HP_MP_RECOVERY_RATE = 0.2`（Kotlin `HpMpRecoveryService` / C++ `kPhaseHpMpRecoveryRate` 双端原样）；白皮书 §3 勾「沿用既有机制」 |
| 任务 C | 星级乘区**实现面源码扫描守卫**两条（⑥c/⑥d）：`StarZone.kt` 必须引用 `GameConfig.Gacha.STAR_*` 且禁止 `0.08/0.05` 字面量；`GachaService.take(...)` 必须引用 `HISTORY_RING_SIZE` 且禁止 `.take(50)`；红证闭环（§5） |
| 任务 D | 经济复测进白皮书（D-4，修订版不新建文件）：新 §11 含全部 5 项（月收入曲线 / sink 占比 / 五条已删 sink 影响列 / 第二角色时刻 / 材料注入期望），**蒙特卡洛 200,000 局**按 `gacha_tx.h` 精确语义重算 |
| 任务 E | 白皮书口径修正：§4.1「采纳口径 B」→ 终局**口径 A**（1★ 基线、5★ 战斗 +32% / 修炼 +20%）；§3 杠杆表 ⑨⑩ 补勾（**十项全部有勾**）；§4.1 公式笔误与 A/B 标签互换修正；另在 §1.2/§2.2/§5/§6.2/§7/§9 加 6 处「G13 注」防误读指针（未改表体，见 §8-3） |
| 硬门 | 零 Room 迁移（仍 v59）、零 `@ProtoNumber`、零 ActionId 变更（201/1872 零漂移）、零新 JNI 面（86/86）、**零 C++ 改动**（ctest/JNI 重建不适用）、`gacha_tx.h`/seed/金黄 digest 未动（RNG 基线无从平移，也不需要——零行为改动） |
| 🔴 关键数值 | `game-data.json` sha256 `035066cb…94ef` → **`809375f4…8619`**（改中性源删键导致，**非漂移**；`gen-game-data.mjs --check` 通过） |

---

## 2. 验收逐条对照（任务书 §1）

| 验收 | 判据 | 实测证据 |
|---|---|---|
| ① 杠杆表十项全勾 | 每项指向「已落配置/已删键/已实现」之一 | 白皮书 §3 表：①–⑧ 已落配置且生效（三向守卫在册，本批零改动）；⑨ 勾「不采纳」+ 备注「终局=不补，键已移除（G13）」；⑩ 勾「沿用既有机制」+ 备注「重复键认定，配置键已移除（G13）」 |
| ② ⑨⑩ 二选一定死 | 不留第三种状态；删守卫行并跑绿 | 全仓 grep `breakthroughCompBonus/BREAKTHROUGH_COMP_BONUS/injuryHealPctPerPhase/INJURY_HEAL_PCT_PER_PHASE`（`--exclude-dir=build`，kt/json/h/cpp/mjs）**零命中**；`CharacterTemplateGuardTest` 两行断言已删，同轮全类绿（§5 红证第三步） |
| ③ 白皮书 :143-144 改口径 A | 与 `star_zone.h` / `StarZone.kt` / G09 拍板一致 | §4.1 采纳行改写为「**采纳（终局）：口径 A**（`starBattle=(star−1)×8%`、`starCult=(star−1)×5%`；1★ ×1.00，5★ 战斗 +32% / 修炼 +20%）」；顺带修正 :133 公式笔误（`starIndex×5%` → `(starIndex−1)×5%`）与斜体注内 A/B 标签互换 |
| ④ 经济复测进白皮书 | 修订版不新建文件，5 项全含 | 白皮书新 §11：11.1 月收入曲线、11.2 sink 占比、11.3 五条已删 sink 影响列、11.4 第二角色时刻、11.5 材料注入期望 |
| ⑤ 星级乘区源码扫描守卫 | Kotlin 实现禁字面量；`StarZone.kt` 仍引用 `GameConfig` | ⑥c 断言两条引用存在 + 正则 `(?<![\d.])0\.0[58](?!\d)` 零命中；⑥d 断言 `HISTORY_RING_SIZE` 引用存在 + `\.take\(\s*50\s*\)` 零命中；`StarZone.kt` 生产源码零改动（红证反例已还原，`git status` 不含该文件） |
| ⑥ 行为改动定性 | 动了判定行为才跑 ctest；否则报告写明 | **本批零行为改动**：删除的两个配置键全仓零消费点（Kotlin 无任何类解析 `game-data.json` 的 `gachaDefaults`，C++ 常量为编译期内联、与这两键无关）；回血/突破/抽卡/RNG 判定路径零触碰 ⇒ ctest 不适用 |
| ⑦ 全量门禁 | JUnit + detekt 六模块 0 + lint 36 警告 0 error + `check-agent-instructions` | §7 门禁表（终树同轮实测） |
| ⑧ 交付物 | 双 changelog + 本报告 + 单次提交 | `changelog_entries.json` 4.01.14 追加 1 条玩家文案（`node JSON.parse` ✓）；`CHANGELOG.md` [4.01.16] 段内新增 G13 节；单次 `refactor(gacha)` 提交 |
| 不做面 | 不改 `gacha_tx.h` / 不重录金黄 / 不改版本号 / 不新增字段 | 全部遵守：C++ 零改动、digest 未动、版本号未动、`game-data.json` 只删两键零新增 |

---

## 3. 实施内容（按任务书 §8 切片）

| 片 | 落地 |
|---|---|
| **A-13a** 杠杆终局 | `scripts/data/gacha_config_sample.json`（`gachaDefaults` 删 2 键，剩 6 键）、`GameConfig.kt`（object Gacha 删 2 常量）、`CharacterTemplateGuardTest.kt`（⑥ 删 ⑨⑩ 两行守卫） |
| **A-13b** 守卫加严 | `CharacterTemplateGuardTest.kt` 新增 ⑥c「星级乘区实现面源码扫描」+ ⑥d「历史环截断源码扫描」+ companion 文件定位常量与修复指引 + KDoc 判别力自证表补 2 行 |
| **D-13c** 白皮书 | `m0-economic-whitepaper.md`：§3 ⑨⑩ 补勾、§4.1 三处口径修正（:133 公式 / 斜体注标签 / :143-144 采纳行）、§1.2+§2.2+§5+§6.2+§7+§9 六处「G13 注」指针、新 §11 经济复测（5 项） |
| **主线程** | `assets/data/game-data.json` + `game-data.hash.txt` 生成器重跑（禁手改产物）、双 changelog、本报告、门禁、单次提交 |

**蒙特卡洛方法学（§11 数据来源）**：一次性诊断脚本在**系统临时目录**（不入库，仓库零残留），200,000 局 × 逐抽模拟 `gacha_tx.h` 语义——类别权重 11/10/26/26/27、非保底角色抽在类别内均匀落点（单根 2 人 / 双根 4 人）、每 10 抽保底本体 = 6 人均匀 6 选 1 × 5 碎片（`characterPool` 按声明序拼接）、`addFragment` 满 100 自动升星 / 满星后进度继续累加（无效用）、开局周明 1★ 碎片 0/100 灵石 50,000（=10 抽）。

---

## 4. 上位失真与漏项（本批实测）

| # | 任务书记载 | 实测真值 | 处置 |
|---|---|---|---|
| 1 | §0 表 ⑨⑩「配置键 → 值 ×2」 | 每键实为 **3 处**（中性源 + GameConfig + 守卫断言行） | 任务书 D-5 已预判并授权同批改守卫（A-13a 文件面含 `CharacterTemplateGuardTest.kt`），照做 |
| 2 | §0 表行号锚（⑨→`GameConfig.kt:241`、⑩→`:240`） | 实测 ⑨=`:240`、⑩=`:239`（G12 批改 GameConfig 后 ±1 行漂移） | 按键名定位无歧义，登记不回填 |
| 3 | §4 表「环截断处引用常量而非字面量 50」的缺口表述 | `GachaService.kt:147` **本就引用常量**（零字面量）——缺口是「无人看守」而非「已写字面量」 | 守卫照加（把现状钉死），任务书语义按「加断言」执行 |
| 4 | 验收③只点名 :143-144 | §4.1 :133 公式笔误（`starIndex×5%` 配「star=1 时 +0%」自相矛盾）与斜体注 A/B 标签互换同属该节 | 并入验收③的「§4.1 统一为 A」一并修正（任务 E 第 3 行授权「统一为 A，正文与该表一致」） |

---

## 5. 判别力自证（退回旧态判红，逐条实测）

| # | 构造反例（只改一处） | 实跑结果 |
|---|---|---|
| 1 | `StarZone.battleMult` 临时改写 `1.0 + extraStarLevels * 0.08`（去引用 + 写字面量） | ⑥c **FAILED**（`AssertionError at CharacterTemplateGuardTest.kt:428`） |
| 2 | `GachaService` 历史环临时改 `.take(50)` | ⑥d **FAILED**（`AssertionError at CharacterTemplateGuardTest.kt:447`） |
| 3 | 同轮（两反例同时在位） | `10 tests completed, 2 failed`——**恰好**两条新用例红，既有 8 例全绿；`BUILD FAILED in 44s` |
| 4 | 双反例还原后复跑同任务 | `BUILD SUCCESSFUL in 11s` 全类绿 |

红证注入的两处反例已逐字还原（`git status` 不含 `StarZone.kt` / `GachaService.kt`）。

---

## 6. 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `CharacterTemplateGuardTest.卡池经济常量与配置同值` | **保留，删 ⑨⑩ 两行比对行**（原 6 行 → 4 行） | 断言对象（两键）已删除，行本身失去被比对的两侧 |
| `CharacterTemplateGuardTest` 其余 7 例 | 不动 | 未触碰对应生产面 |
| 新增 ⑥c / ⑥d 两例 | 新增 | A-13b 判据（任务书 §4 / 验收⑤） |
| 其余全仓用例 | 不动 | 本批零行为改动，无期望值变化面 |

---

## 7. 门禁表（终树同轮实测；判据 = 命令输出原文）

| 门 | 实测值 | 对照 G12 基线 |
|---|---|---|
| Kotlin 组合门 | `compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=<G10 .so> detekt lintRelease` ⇒ **BUILD SUCCESSFUL in 22m 42s**（339 tasks 全执行，终树同轮） | 第 1 轮 `BUILD FAILED in 22m 28s`——唯一红 = `:core:engine:detekt` 1 条 `MaxLineLength`（新守卫 KDoc 自证表行 127 字符），真修（缩短反例描述，98 字符）后整门 `--rerun-tasks` 复跑第 2 轮全绿；两轮之间除该一行 KDoc 外零代码改动 |
| JUnit 逐模块 | **7476 / 0 failures / 0 errors / 18 skipped**（694 份 XML；app **1011** / domain **1571** / data **809** / engine **2938** / ui **155** / feature:game **992**） | 7474 → 7476 = **+2**（engine：⑥c/⑥d 两条新源码扫描守卫；skipped 18 = app 2 + data 15 + engine 1 同基线） |
| detekt | 六模块 0 error 0 warning（第 2 轮全绿；两份 baseline 零改动） | 同基线（第 1 轮 1 条为真修，未入 baseline） |
| lint | **36 warnings / 0 errors**（另有 3 warnings 按 baseline lint-baseline.xml 过滤，`lint-results-release.txt` 实测 `: Warning:` 计 36） | 36 与 G10/G11/G12 同值 ⇒ 全预存 |
| 桌面 ctest / JNI 重建 | **不适用**——本批 C++ 零改动、零行为改动（验收⑥口径）；`.so` 三件套开工实值 = **9015808 字节 / mtime 2026-09-26 23:32 / sha256 `b1eb1bec…`** = G10 交付值逐字一致 | 零漂移 |
| ActionId | `gen-action-ids.mjs` = **201 actions (maxId=1872)**，regen 后 `git diff --exit-code` 两产物零漂移 ✓ | 同值 |
| 游戏数据 | 生成器重跑：`sha256 809375f4126ae90e688d5bbf10fb0d0180ff0ab91108e4d8c1d1ed6013938619`；`--check` 通过 ✓ | 旧值 `035066cb…94ef`（**改中性源导致，非漂移**） |
| JNI 计数 | `check-jni-count.mjs` ✓ **86/86 双桥无扩散** | 同值 |
| 规范门禁 | `check-agent-instructions.mjs` ✓（报告落盘后复跑）：路由闭包 42 篇 / 444 条引用零死链 / 预算闸 26763 / 32768 | 不精确引用计数不增长 |
| Room | 零迁移、零 `@ProtoNumber` 变更 | 同值（v59） |
| 双 changelog JSON | `node JSON.parse` ✓（4.01.14 条目 48 → 49） | — |

---

## 8. pending-device / 未完成 / 登记

**真机 pending-device**：本批零行为、零 UI 改动 ⇒ **无新增项**；存量 18 项（G11 §7 十二项 + G12 §9 六项）不变，同台设备同轮验证。

**登记**：

1. 🟠 **若产品恢复 ⑨ 突破补偿**（任务书 §10-1 预案留档）：① `BreakthroughZones` 加第 5 项（`DiscipleStatCalculator突破Ops5.kt`）② `computeBreakthroughZones` 只对跨大境界分支注入 ③ 上限常量单源化（现 `clamp(0, 1.0)` → 0.95）④ C++ `BreakthroughChanceInput` 同名同参 ⑤ 双端守卫扩例；复跑 `-R Breakthrough` 与全部 `Diff*`。
2. 🟠 **经济复测结论（§11.4）**：稀释修正后第二角色为**数百月级**（期望 683 抽 / 月入 5k 档 ≈673 月）——产品「现实周/月计」预期不成立，比 §13 风险登记「角色解锁节奏过慢（高）」更严重。属**数值杠杆**问题（白皮书 §3 ①③④），不动结构；是否拉杠杆留用户拍板。
3. 🟡 **白皮书其余口径冲突的处置方式**：§5 表体（strawman 口径 B 战力表）、§6.2 建议终值行、§7 JSONC 两键与键表两行、§9 勾选表——任务书 §10-3 授权「只修三处，其余扫描并登记」⇒ 本批**未改这些表体**，以 6 处「G13 注 / 终局注」指针钉住防误读；后续文档收口批可再统一表体。
4. 🟠 `docs/knowledge-base.md` 经济基线表增补「仙缘寻访」耗行（白皮书 §8 原登记）仍归 G14；本批知情未动。
5. 🟠 `architecture.md` 乘区表两处失真归 G14（G12 §9-7 原登记有效）。
6. 🟠 本报告落盘后 `check-agent-instructions.mjs` 复跑一次（新增 `report-G13.md` 引用面），结果见 git 提交说明。
7. 🟡 开工树况：`docs/research/`×2 未跟踪（他线性能调研遗留文件，非 G 批在途改动；本批显式 `git add` 不入库，G12 §10 同款处置）。
8. 🟡 蒙特卡洛脚本为一次性诊断代码，置于系统临时目录（`%TEMP%\g13-econ-mc\`），不入库、仓库零残留（AGENTS 公约 13）。
9. 🟠 **批内树况新增项（已定性，不影响本批）**：终树出现 `docs/realtime-settlement-plan-2026-09-27.md` 未跟踪文件（53KB，mtime 2026-09-27 03:13）——他线**纯设计方案文档**（结算改造方案），自述「待用户批准后实施，当前仅产出方案，未改动任何代码」，其基线提交 = 本批开工 HEAD `866ec165f`；与本批 9 个提交文件零相交，本批不动、不入库、留该线自行处置。
10. 🟠 构建副产物 `atlas-rgba-manifest.json`（仅 `generatedAt` 一键差异，本批第 2 轮组合门产生）提交前 `git checkout --` 还原（G10/G12 实证流程沿用）。

---

## 9. 树身份与证据边界

| 项 | 值 |
|---|---|
| 分支 / 开工 HEAD | `feat/gacha-m0-m1` / `866ec165f` |
| 树况 | 开工 = `docs/research/`×2 未跟踪 + 看护台账零改动；批内新增 `docs/realtime-settlement-plan-2026-09-27.md` 未跟踪（他线纯方案文档，§8-9 定性）；本批改动面 = 生产 3（中性源 JSON / GameConfig / 生成产物×2 计 2 文件）+ 测试 1（CharacterTemplateGuardTest）+ 文档 3（白皮书 / CHANGELOG / changelog_entries.json）+ 报告 1 |
| 门禁轮次 | 红证轮（双反例，`BUILD FAILED in 44s`）→ 还原轮（`BUILD SUCCESSFUL in 11s`）→ 终树全量组合门 2 轮：第 1 轮 `22m 28s` FAILED（detekt 1 条 MaxLineLength）→ 真修一行 KDoc → 第 2 轮 `22m 42s` 全绿（§7） |
| 提交 | 单批内逐文件 `git add <file>`；`docs/research/`×2 与 `realtime-settlement-plan` 不入库 |
