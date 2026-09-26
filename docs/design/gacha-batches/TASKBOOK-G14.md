# TASKBOOK-G14 · 文档与发布收口（双 changelog · CODE_WIKI · architecture · 验收报告 · 死代码死文案终稿）

> **本文件是 G14 的派工真源**，取代 `docs/design/character-gacha-implementation.md` §G14（`:216-224`）的粗口径。
> **时点**：2026-09-26；**依赖 G11–G13 全部合入**（本批是 M2 末批，也是整个 G 批的**文档硬门**）。
> **侦察方式**：1 个只读子代理穷举文档现状（逐行 file:line）＋ 主线程回核（版本号三方不一致、Facade/Delegate 计数均**已实测确认**）。
> **纪律**：引用写全路径（`docs/AGENTS.md` 四条规范）；**新增引用不得顶高规则③的"写法不精确"计数**（`HANDOVER-3` 坑 14）；
> 本批**禁止擅自更新版本号**（`rules/version-release.md:8`：由用户判断和指令）。

---

## 0. 与 G10 的边界（**先看，避免重复劳动**）

| 批次 | 负责的文档改动 |
|---|---|
| **G10**（已派） | **「仍描述已删玩法」的表述**：`docs/cpp-engine.md` / `docs/architecture.md` / `docs/knowledge-base.md` / `CODE_WIKI.md` / `docs/ui-read-surface.md` 的删除面改写 ＋ `docs/rng-source-inventory.md` §2/§3 重跑盘点 |
| **G14**（本批） | **「结构与计数」的表述**：版本号三方归一、双 changelog 终稿、`CODE_WIKI.md` 的 Facade/Delegate/ActionId **计数与列表**、`architecture.md` 的**乘区表字段数与扩展点钩子**、`report-G14-completion.md`、死代码/死文案清零表**终稿** |

> 边界判据：**"某个玩法已删 → 段落该删/改" 归 G10；"设施数量/版本/列表口径不对 → 该数字该改" 归 G14。**

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 目标 | 让「文档 = 代码现状」在**结构、计数、版本、列表**四个维度成立，并给出可审计的批次收官报告 |
| 验收① | 🔴 **版本号三方归一为 `4.01.16`**（✅ 用户 2026-09-26 已拍板，§3.1）：`version.properties`（现 `4.01.14`/`4114` → `4.01.16`/`4116`）、`CHANGELOG.md` 段头（现已是 `4.01.16`，不动）、`android/app/src/main/assets/changelog_entries.json`（现重复 4 条 `4.01.14` → 合并为唯一 `4.01.16` 条）三者一致 |
| 验收② | 双 changelog **终稿齐备且同版本**：玩家向条目符合「无专业术语/不泄露数值/只粗略描述」，技术向条目覆盖 G12/G13；**禁止按日期拆多个同版本条目** |
| 验收③ | `CODE_WIKI.md` 六处计数/列表修正（§4）逐条完成：Facade **7 → 12**、Delegate **9 → 27**、ActionId **166/1712 → 201/1872**、退役 **21 → 24**、目录树补 `gacha/` 等、`DiscipleDelegate` 的已删玩法说明 |
| 验收④ | `docs/architecture.md` 五处修正（§5）逐条完成：`CultivationSpeedZones` **4 → 5 乘区**、`BreakthroughZones` 字段名对齐代码、补「星级进战力」一行、扩展点补寻访钩子、结算列表声明「寻访不属于第五层结算」 |
| 验收⑤ | `docs/report-G14-completion.md` 落盘，按 §6 的七节结构（含门禁实证表 + 关键实施事实 + 诚实残余 + pending-device） |
| 验收⑥ | 死代码 + **死文案** grep 清零表**终稿**（含 G12 的 14 词 × 4 区计数：清零前后对照；豁免项写理由） |
| 验收⑦ | `node scripts/check-agent-instructions.mjs` = ✓ 全部通过，且**规则③「引用路径不精确」计数不高于改前（改前 0 处）** |
| 验收⑧ | 提交前过 `rules/pr-review-checklist.md` 与 `rules/version-release.md`；单次提交（或本批内多笔且报告写明） |
| 不做 | 不改代码逻辑（纯文档批）；不改版本号（除非用户明确指令）；不回改过程档案（`docs/report-*`、`docs/parallel-batches*`）；不动 `docs/design/gacha-batches/` 的历史报告 |

---

## 2. 🔴 实测硬伤（本批必须解决，逐条已核）

| # | 硬伤 | 实测证据 | 性质 |
|---|---|---|---|
| 1 | **版本号三方不一致** | `version.properties`：`versionName=4.01.14` / `versionCode=4114`；`CHANGELOG.md:1` = `## [4.01.16] - 2026-09-22`；`changelog_entries.json` 首条 `"version":"4.01.14"` | 🔴 发布口径断裂 |
| 2 | **`changelog_entries.json` 同版本条目重复 4 条** | `"version":"4.01.14"` 在该文件出现 **4 次** | 🔴 违反 `rules/version-release.md:53`「同日同版本一律并入同一条目」 |
| 3 | `CODE_WIKI.md` 计数全面过期 | `:228`/`:236` 写「**7 个**领域 Facade 接口」⇒ 实存 **12**（漏 Cultivation/Economy/Exploration/**Gacha**/Road）；`:295` 写「**9 个** Delegate」⇒ `feature/game/.../ui/game/delegate/` 实存 **27 个 `.kt`**；`:102-106` 旁注写「198 动作 / maxId 1861 / 退役 21」⇒ 实为 **201 / 1872 / 24**；`:75-76` 写「166 动作 / maxId 1712 / 31 handler」 | 🔴 `docs/AGENTS.md` 文档同步义务 |
| 4 | `architecture.md` 乘区表与代码不符 | `:213`「`CultivationSpeedZones`（**4 乘区**：资源/社交/状态/临时）」⇒ 实为 **5**（多 `starBonus`）；`:215`「`BreakthroughZones`（长老指导/自身加成/**状态惩罚**）」⇒ 代码字段为 `{baseZone, elderGuidance, selfBonus, adFlatBonus}` | 🔴 同上 |
| 5 | `CODE_WIKI.md:299` `DiscipleDelegate.kt` 说明仍写「弟子管理（**招募/驱逐**/装备/**道侣**）」 | 三者均已删除（G05/G06/G03） | 归 G10 边界内（"已删玩法"），本批只需**交叉确认** G10 已改 |

---

## 3. 任务 A：双 changelog 与版本号归一

### 3.1 ✅ 目标版本号（**用户 2026-09-26 已拍板 = `4.01.16`**）

三方归一目标 = **`4.01.16`**（对齐 `CHANGELOG.md` 顶部段头）：

| 文件 | 改前 | 改后 |
|---|---|---|
| `CHANGELOG.md:1` | `## [4.01.16] - 2026-09-22` | **不动**（G12/G13 的小节并入该段内） |
| `version.properties` | `versionName=4.01.14` / `versionCode=4114` | `versionName=4.01.16` / `versionCode=4116` |
| `android/app/src/main/assets/changelog_entries.json` | 首条 `"version":"4.01.14"`（**重复 4 条**） | 合并为**唯一条** `"version":"4.01.16"`，`date` 保持首次发布日 `2026-09-23` |

🔴 判据：三者版本号**完全相同**且 `versionName` 形如 `X.XX.XX`（禁 `4.0.16`）；
`rules/version-release.md:8` 的「禁止擅自更新版本号」已由本次用户拍板满足 ⇒ 本批按上表执行即可。

### 3.2 条目合并（不依赖 §3.1）
1. `android/app/src/main/assets/changelog_entries.json`：把 **4 条同版本 `4.01.14` 条目合并为 1 条**，
   `changes` 数组按时间顺序保留全部内容（**只增不减**），`date` 取首次发布日（`2026-09-23`）不改；
   追加 G12/G13 的**玩家向**条目（通俗、无数值细节）。
2. `CHANGELOG.md`：在**当前版本段内**追加 G12/G13 小节（技术向，可写实现细节）；若 §3.1 定为 (a)/(c)，同步段头版本号。
3. 改完校验：`node -e "JSON.parse(require('fs').readFileSync('android/app/src/main/assets/changelog_entries.json','utf8'))"`；
   **用编辑工具改，禁脚本重排整文件**（`HANDOVER-3` 坑 3：G04 曾把 2,474 行全文件重写导致审查不可读）。
4. 判据：同版本条目**唯一**；`version.properties` / `CHANGELOG.md` 段头 / `changelog_entries.json` 版本号**三者相同**且 `versionName` 形如 `X.XX.XX`。

---

## 4. 任务 B：`CODE_WIKI.md` 六处

| # | 行 | 现状 | 改成 |
|---|---|---|---|
| 1 | `:228`、`:236` | 「**7 个**领域 Facade 接口」 | **12 个**，并列全 12 个域（补 `Cultivation`/`Economy`/`Exploration`/`Gacha`/`Road`） |
| 2 | `:236-243` | 七行 Facade 清单 | 补 5 行；`GachaFacade` 的说明写「寻访域：抽卡/保底/碎片/升星/入库（C++ AUTHORITATIVE，`gacha_tx.h`）」 |
| 3 | `:249-258` | 目录树缺 `gacha/`、`cultivation/`、`economy/`、`road/` | 补四行 |
| 4 | `:294-307` | 「**9 个** Delegate」 | 按实存 **27** 个 `.kt` 重写（至少补齐 `GachaDelegate` 与其余遗漏项；若数量断言不可维护，改为「按域分组列出」并注明"以 `ui/game/delegate/` 实际文件为准"） |
| 5 | `:75-76`、`:102-106` | ActionId 计数 `166/1712`、`198/1861`、退役 `21` | **201 / maxId 1872 / 退役 24**（口径见 `HANDOVER-m1-remaining-3.md` §2.2 的 ActionId 行）；台账表补 `1870–1872` 抽卡段 |
| 6 | `:81-100` 台账表 | 缺抽卡段 | 补 `1870 GACHA_FRAGMENT_GRANT_TX` / `1871 GACHA_PULL_ONCE` / `1872 GACHA_PULL_TEN` |

**判据**：每个数字都能用一条命令复现（`node scripts/gen-action-ids.mjs` 的输出、`ls` 计数）——报告里贴命令与输出。

---

## 5. 任务 C：`docs/architecture.md` 五处

| # | 行 | 改成 |
|---|---|---|
| 1 | `:213` | `CultivationSpeedZones` **5 乘区**（资源/社交/状态/临时/**星级 `starBonus`**），注明口径 A（`1★` 基线 ×1.00）与单源 `GameConfig.Gacha.STAR_CULT_PCT_PER_STAR` |
| 2 | `:215` | `BreakthroughZones` 字段名与代码一致（`baseZone`/`elderGuidance`/`selfBonus`/`adFlatBonus`）；若 G13 已改 clamp 上限则同步 |
| 3 | 乘区表 | 补一行「**星级进战力**」：`SectCombatPower`（Kotlin）与 `sect_power`（C++）同乘区、`star_zone.h` 单源、纳入对拍 |
| 4 | `:309-333` 扩展点段 | 补寻访域钩子：轮换池/UP（`poolId` 级 pity、池开关）、埋点 `gacha_pull`/`gacha_unlock`、付费抽入口位（依据 `docs/character-gacha-redesign-2026-09-23.md:721-732`） |
| 5 | `:188-199` GameSystem 段 | 声明「**寻访不属于第五层结算**，不注册月/年回调；保底不进年变 T1/T2」（依据同文件 §15.1/§15.4） |

**判据**：表内每个 `Zones` 名与代码 `grep "data class .*Zones"` 的**字段数**一致；报告贴 grep 输出。

---

## 6. 任务 D：`docs/report-G14-completion.md`（新建，七节结构）

参照 `docs/report-MR4-completion-2026-09-23.md` 的结构：

| 节 | 内容 |
|---|---|
| 头部 | `> 批次 · 施工面（主树/分支）· 日期 · 性质（文档批）· 验收状态（**不得自登记 accepted**）· 方案指针 · 范围纪律` |
| 一 | **任务逐条交付**：本任务书 §1 的八条判据逐条 `✅` + 落点 + 证据 |
| 二 | **门禁实证**（表格：门禁 ‖ 口径 ‖ 结果），含 `check-agent-instructions` 五条规则原文 |
| 三 | **关键实施事实**（供总收官引用）：版本归一结论、CODE_WIKI/architecture 逐处改动、死文案清零表终稿 |
| 四 | **诚实残余与过程记录**（表：项 ‖ 状态）——含"未经用户/看护验收""未做项" |
| 五 | **pending-device 清单**（汇总 M1 的 G11 12 项 + M2 新增项） |
| 六 | **收官笔材料清单**（checkbox） |
| 七 | **提交前建议复跑**（代码块） |

---

## 7. 任务 E：死代码 + 死文案清零表终稿

- 素材来源：`TASKBOOK-G10.md` §5（死代码清零）＋ `TASKBOOK-G12.md` §7（死文案 14 词 × 4 区）＋ 各批报告的登记项。
- 格式：`类别 | 符号/关键词 | 定义位置 | 清零前 | 清零后 | 处置（清零 / 豁免+理由）`。
- 🔴 必须写清**豁免理由**（历史迁移注释、`ActionIds.kt` 退役 desc、历史 changelog 条目、`GameDatabaseMigrations*`）。
- 判据：清零表内**没有"待定"**；每条要么归零、要么有书面豁免。

---

## 8. 决策（D-1…D-5）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | ✅ **版本号目标 = `4.01.16`**（用户 2026-09-26 拍板）⇒ 三个文件按 §3.1 表归一 | `rules/version-release.md:8` 的"由用户判断指令"已满足 |
| **D-2** | `changelog_entries.json` 的 4 条同版本**合并为 1 条**，内容只增不减 | 同上 `:53` |
| **D-3** | `CODE_WIKI.md` 的 Delegate 计数改为**按域分组 + 以实际文件为准**的表述（避免"27"这类易腐计数） | 计数型表述注定漂移 |
| **D-4** | 与 G10 的边界按 §0 判据执行；G10 已改的段本批**不重复改**，只在报告里交叉确认 | 避免双改冲突 |
| **D-5** | 本批**不新建**"总验收报告"以外的新文档；`report-G14-completion.md` 是唯一新增文件 | 文档收敛 |

---

## 9. 文件面与切片（全路径）

| 片 | 允许改 |
|---|---|
| **D-14a** changelog 与版本 | `CHANGELOG.md`、`android/app/src/main/assets/changelog_entries.json`、`version.properties`（**仅当用户已拍板**） |
| **D-14b** CODE_WIKI | `CODE_WIKI.md` |
| **D-14c** architecture | `docs/architecture.md` |
| **D-14d** 收官报告 | `docs/report-G14-completion.md`（新建）、`docs/design/character-gacha-implementation.md` 的 Report 回填（若该文件有 Report 节） |
| **主线程** | 死代码/死文案清零表终稿（并入 `report-G14-completion.md`）、文档门禁、提交 |

---

## 10. 门禁清单

```powershell
# 文档门禁（本批唯一硬门；判据 = 五条规则全 ✓ 且规则③"写法不精确"计数不增长）
node scripts/check-agent-instructions.mjs
# JSON 校验（changelog_entries.json 改后必跑）
node -e "JSON.parse(require('fs').readFileSync('android/app/src/main/assets/changelog_entries.json','utf8'));console.log('JSON OK')"
# 计数复现（CODE_WIKI 里每个数字都要有命令支撑）
node scripts/gen-action-ids.mjs
Get-ChildItem android/feature/game/src/main/java/com/xianxia/sect/ui/game/delegate -Filter *.kt | Measure-Object
Get-ChildItem android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain -Recurse -Filter *Facade.kt | Where-Object { $_.Name -notmatch 'Impl' } | Measure-Object
```

**提交前**：`git status` 只留本批改动；`docs/research/`×2 不入库；报告里**不得**写「accepted」（留用户/看护）。

---

## 11. 登记 / 待拍板

1. ✅ **版本号目标已拍板 = `4.01.16`**（用户 2026-09-26）——本批**已无用户阻塞项**。
2. 🟠 M1 的「G11 真机通」与 M2 的「全链真机走通」**同一台设备同一轮**（见 `report-G11.md` §7 的 D-1…D-12）。
3. 🟠 若 `docs/design/character-gacha-implementation.md` 的批次表需要标注「15 批全部完成」，由本批在 Report 节回填（但不改历史批次描述）。

---

## 12. 一句话给执行者

**G14 是文档硬门，只做四件事：把版本号三方归一为 `4.01.16` 并把双 changelog 合并成"同版本唯一条目"（玩家向/技术向各一）、
把 `CODE_WIKI.md` 的 Facade 7→12 / Delegate 9→27 / ActionId 166·1712·198·1861·退役21 → 201·1872·退役24 六处数字改成现状、
把 `architecture.md` 的乘区表（4→5 乘区、`BreakthroughZones` 字段名、补星级进战力行、补寻访扩展钩子、声明寻访不属第五层结算）改准、
产出 `report-G14-completion.md` 并把死代码/死文案清零表收成终稿；
🔴 版本号不许自选、引用必须写全路径（否则规则③计数被顶高而门禁仍显示"通过"）。**
