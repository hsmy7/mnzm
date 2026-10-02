# report-SS-final · 单存档改造全案终验收报告（SS10 收官批）

> **批次**：SS10 · 文档与发布收口（收官批，零生产代码）
> **协议**：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §3/§7 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)（铁律 1–14 照用）
> **派工真源**：[`TASKBOOK-SS10.md`](TASKBOOK-SS10.md)（验收①–⑤ / 决策 D-1~D-4 / 切片 SS10-a~d）
> **报告命名说明**：按任务书验收③与 D-3 定名 `report-SS-final.md`（派工词交付条款中的 `report-SS10.md` 系笔误，以任务书为准）。

---

## 1. 全案概览

**目标**（上位方案 [`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) v2 前提）：
删档重置 + 去槽位维度 + 旧存档兼容代码清零 + 账号数据空间分库 + 持久化面收口 + 增量落盘 +
事件触发自动存档 + 云灾备/换设备续玩 + 登录门槛 + 玉符账本（C++ 真源）+ 文档与发布收口。

**终态一句话**：单存档、按账号分库、删档重置语义；写入走增量落盘 + 事件触发关键落盘；
云端灾备 + 换设备续玩（W>C 只读降级绝不静默覆盖）；未登录强制登录（离线宽限按 B1）；
玉符余额真源在 C++ append-only 账本；文档、发布面、验收记录一次收口（本批）。

**批次构成**：11 批（SS0–SS10），其中代码批 9（SS0/SS1/SS9/SS2/SS3/SS4/SS5/SS6/SS7/SS8）+
文档批 1（本批 SS10）。**全部并网 main**，入口判据「SS0–SS9 全部已合入（`e7ab3d4e5` 时刻核验 ✓）」满足。

---

## 2. 11 批次 commit / 并网 sha 对照表

> sha 以本仓 git 实测为准；规模为 `git show --shortstat` 实测（与台账 §8 个别行有 1–2 文件级偏差，勘误见
> [`DISPATCH-ledger.md`](DISPATCH-ledger.md) §9 末注——不回改历史行）。

| 批 | 名称 | 实施 commit | 并网 merge | 规模（git 实测） | 并网时点 |
|---|---|---|---|---|---|
| SS0 | 删档重置 + 旧存档兼容代码清零 | `f79afcd84` + 邮件清理 `c5903948a` | `3a89a9e7a`（已推送 `a473a22b6..3a89a9e7a`） | 211 文件 +1326/−323317 | 2026-10-01 05:57 |
| SS1 | 去槽位维度（Kotlin + C++ 同批） | `c92e70133` | `580fab8fb` | 207 文件 +7955/−8863 | 2026-10-01 17:52 |
| SS9 | 玉符账本（C++ 真源） | `01f3ec931` | `7efe2fce0` | 25 文件 +1618/−871 | 2026-10-01 20:39 |
| SS2 | 账号数据空间分库 + 登出五件套 | `6eff39677` | `a4c17acc2` | 19 文件 +1044/−223 | 2026-10-01 22:45 |
| SS3 | 持久化面收口四组件 | `773f26f81` + 看护补笔 `b874b24e5`（文档面） | `d1d5940d1` | 35+2 文件 +1582/−1662 | 2026-10-01 23:5x |
| SS4 | 单档 UI 终态 + 元数据表清除 | `6b1c928b2` | `8b2e5c035` | 40 文件 +1305/−1832 | 2026-10-02 03:0x |
| SS5 | 增量落盘（真增量写） | `a4a3ce2a8` | `f13b14ad6` | 34 文件 +2231/−337 | 2026-10-02 04:2x |
| SS6 | 事件触发自动存档 | `a9637891e` | `4c9d98ec5` | 24 文件 +687/−43 | 2026-10-02 08:0x |
| SS7 | 云：灾备 + 换设备续玩 | `b0c76b42e` | `405e06449` | 25 文件 +652/−910 | 2026-10-02 08:4x |
| SS8 | 登录门槛 + 隐私政策双入口 | `2ff65a537` | `e7ab3d4e5` | 10 文件 +413/−105 | 2026-10-02 10:1x |
| **SS10** | **文档与发布收口（本批）** | 本批 commit（见 §9 汇报） | 待看护验收后并网 | 见 §4.3 | — |

**并网方式**：全部 `merge --no-ff` 零冲突；SS0 并网同时完成了 2026-09-30 以来代理中断期滞留笔的补推
（`a473a22b6..3a89a9e7a`）；此后各批因推送通道（本地代理 7897）持续断开而本地滞留，属基建状态非本批范围。

---

## 3. 验收①–⑤ 逐条对照（本批自验收）

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| ① | 双 changelog 收口（V4） | ✅ | 游戏内 `changelog_entries.json`：**唯一 `4.2.00` 条目**（同版本无第二条目），骨架 3 行保留（删档重置居首两位显著 + 云端旧档清理提醒），收口并入 SS2/SS3/SS4/SS5/SS6/SS7/SS8/SS9 及 SS0 邮件清理共 10 行，合计 13 行；通俗/无术语/零数值（逐行过 `rules/version-release.md` 玩家规范）；JSON 可解析（`node -e JSON.parse` 实测，entries=1/changes=13）；「归一化对比其余条目零变化」**平凡满足**（V3 清空后全文件仅此一条目，无其余条目）。外部 `CHANGELOG.md`：`[4.2.00]` 段补全（SS0–SS9 十批技术 bullet + 门禁基线演进 + 报告链接），段头日期维持首次发布日 2026-10-01 未动 |
| ② | 活文档同步 | ✅ | `CODE_WIKI.md`（目录树 data/ 包结构重写 + 域接口清单剔孤儿 + 📌 持久化体系注记改写 + Room v70）、`docs/architecture.md`（「存档入口」节整体改写为「持久化与存档体系（单存档终态）」八段）、`docs/ui-read-surface.md` §2（§2.1 删 `currentSlot` + 玉符账本 `jadeLedger` 登记面 + §3.2 存档链路行）；grep 过期符号复核见 §5 |
| ③ | 验收报告出具 | ✅ | 即本报告（11 批对照 §2 / 门禁演进 §3 / 真机清单 §6 / 过期引用 §7 / 遗留 §8） |
| ④ | 本册与各 report 回填 | ✅ | [`DISPATCH-ledger.md`](DISPATCH-ledger.md) 新增 §9 过账表 18 项逐条处置（只新增不改历史原文）；各 `report-SSxx.md`/`TASKBOOK-SSxx.md` 历史档案零改动（V5 先例） |
| ⑤ | 全门禁绿（硬门） | ✅ | 见 §9 门禁实跑：`check-agent-instructions` EXIT=0 + `compileReleaseKotlin` 绿 + `:app:detekt` 绿 + diff 自证零生产代码 |

**决策 D-1~D-4 遵守**：D-1 唯一条目未新建 ✓；D-2 外部技术段面向开发者 ✓；D-3 本报告出具 ✓；
D-4 真机清单汇总移交（§6），本批零真机执行 ✓。

---

## 4. 本批（SS10）交付明细

### 4.1 切片完成度

| 片 | 内容 | 状态 |
|---|---|---|
| SS10-a 双 changelog | 游戏内 json 唯一条目补全 + 外部 CHANGELOG 技术段 | ✅（§3 验收①） |
| SS10-b 活文档 | CODE_WIKI / architecture / ui-read-surface §2 + 过期符号复核 | ✅（§3 验收② + §5） |
| SS10-c 验收报告 | 本报告 | ✅ |
| SS10-d 册面回填 | 派工册 §9 过账表 18 项 | ✅ |
| 主线程门禁 | compile / detekt / agent-instructions / diff 自证 | ✅（§9） |

### 4.2 允许清单之外的修正（越界登记，均 .md 零代码）

活文档同步复核中发现以下**规范面/知识面过期事实**（存档语义与 SS 终态直接矛盾），按「文档与发布收口」批
使命与用户公约 9/12（不留尾巴、报告发现）随批修正，**均在 .md 面零生产代码**：

| 文件 | 修正 | 理由 |
|---|---|---|
| 根 `AGENTS.md` §3 | 「存档入口纪律」硬不变式改写（5 槽位→单档 + 事件触发 + 增量 + 分库 + 三禁令） | 唯一规范真源不得与已并网代码矛盾；「5 槽位」「有效槽位门控」在 SS1/SS4 后已是错误指令 |
| `android/core/data/AGENTS.md` | 「存档」节改写（5 槽位行删除；`(id, slot_id)` 复合主键/`resetForSlot` 槽位隔离行替换为账号分库/删档重置/云单键终态） | 数据层模块规范同样过期；与 rules/database-migration.md 现行纪律对齐 |
| `docs/knowledge-base.md` | ① 目录锚点「存档槽位隔离」→「增量落盘（真增量写）」（SS5 已改写节体、漏改目录）；② `GameStateStore` 条目 `resetForSlot(slotId)`→`resetForSlot()`；③ `GameData` 主键 `(id, slot_id)`→`id`；④ 「存档」手段行 5 槽位/slot 0 → 单档终态 | SS1/SS4/SS5 文档同步遗漏（SS5 报告自述改写了节体，此三处为其漏网面，本批补全） |

不修的边界：`rules/database-migration.md` 的迁移纪律表述与 destructive 语义并存系 SS0 期已定稿的现行规范
（SS1 D-1 为批次级豁免），本批不重释；历史/过程档案一律不回改（§7）。

### 4.3 改动文件清单

| 文件 | 改动 |
|---|---|
| `android/app/src/main/assets/changelog_entries.json` | 唯一 4.2.00 条目补全（3→13 行） |
| `CHANGELOG.md` | [4.2.00] 段补全（技术段 + 批次终态 + 门禁演进） |
| `CODE_WIKI.md` | 目录树 / 域接口清单 / 📌 注记 / Room v70 |
| `docs/architecture.md` | 持久化与存档体系节改写 |
| `docs/ui-read-surface.md` | §2.1 / §2.1 玉符账本 / §3.2 |
| `docs/knowledge-base.md` | 4 处过期点（§4.2 表） |
| `AGENTS.md` / `android/core/data/AGENTS.md` | 存档规范过期点（§4.2 表） |
| `docs/design/single-save-batches/DISPATCH-ledger.md` | 追加 §9（不改历史） |
| `docs/design/single-save-batches/report-SS-final.md` | 新建（本报告） |

**零生产代码自证**：`git diff --stat` 全量核对无 `.kt`/`.java`/`.cpp`（§9 门禁实跑）。

---

## 5. grep 过期符号复核（SS10-b 自检）

对规范面 + 活文档全集（根/模块 `AGENTS.md`、`CODE_WIKI.md`、`docs/architecture.md`、`docs/knowledge-base.md`、
`docs/ui-read-surface.md`、`docs/threading-contract.md`、`docs/cpp-engine.md`）执行：

- **符号面**：`save_slot_metadata` / `SaveSlotMetadata` / `FunctionalWAL` / `SavMigrator` / `RecoveryManager` /
  `SaveStorageImpl` → **零残留**（唯一命中 = 本批新写的 architecture「已退役」说明句）。
- **语义面**：`5 槽位` / `slot 0 入口` / `三前置门控` / `选槽` / 槽位隔离 → **零残留**
  （`SlotRefRule`、`elderSlots`、生产/锻造/灵矿槽位等伪同形名族按铁律 15 属生产槽语义，正确保留）。
- **协议面**：`currentSlot` 生产源码零残留（`GameData.kt` 仅剩 `reserved 3` 注释）；`jadeLedger` 协议面
  三点齐备（`json_codec.cpp` GC_TO/GC_FROM、`GameDataFieldPatch.kt:178` coveredFields、`GameData.kt` proto 240）。
- **孤儿接口发现**：`core/repository/SaveStorage.kt` 接口零实现零消费（SS0 删 `SaveStorageImpl` 后遗留）——
  登记 §8.1，不在本批删（零代码红线）。

---

## 6. 真机 pending-device 清单汇总（D-4：移交发布流程）

> 各批 report §5/§6 累积汇总；本批不执行任何真机验证。**建议发布前一轮真机冒烟按本清单顺序执行。**

| # | 来源批 | 验证项 | 预期 |
|---|---|---|---|
| 1 | SS0 | 装旧版→建进度→覆盖安装新包 | 首启自动清档，全新档开始 |
| 2 | SS0 | 清 App 数据重装 | 全新档；无残留文件/孤儿库 |
| 3 | SS0 | 换设备同账号登录 | **不得**恢复旧档（云端旧档已删） |
| 4 | SS2 | 账号 A/B 交替登录建进度 | 两空间互不串档（`filesDir/accounts/` 分库） |
| 5 | SS2 | 登出 → 进程重启 → 登录页 | 重启观感可接受；重登数据完整 |
| 6 | SS3 | TapDB 后台 `#storage_metrics_report` 出报 | 事件可见 + 属性白名单录入生效（后台配置项） |
| 7 | SS5 | 增量落盘真机耗时 | 与 JVM p50=14.7ms 同量级或更低；无卡顿感知 |
| 8 | SS6 | 涉钱操作（购买/发放）返回时序 | 无可感知等待；杀进程后该笔流水不丢 |
| 9 | SS7 | 新设备登录 → 本地无档 | AutoEntry 云分支自动恢复 + 落盘 boot 成功 |
| 10 | SS7 | 双设备制造 `W > C` 场景 | 本地只读降级 + 提示；云端新档不被覆盖 |
| 11 | SS8 | 已登录账号 → 飞行模式 → 杀进程重进 | B1①：离线照常进游戏 |
| 12 | SS8 | 首次安装 + 飞行模式 | B1②：停留登录页，明确需联网 |
| 13 | SS8 | 首次登录强制联网链路 | TapTap 登录成功后进入游戏 |
| 14 | SS8 | `enterGameAuto` 兜底路径（会话残缺边界） | 进程重启到登录页，重登可正常进入（风险表登记的预期观感） |
| 15 | SS9 | 玉符购买/发放/时长累积真机行为 | 六事务 native 臂参数形状变更后行为正常；账本余额一致 |

**渠道侧配合项**（任务书登记项 2）：① 隐私政策更新合规审核（SS8 双入口已同步，提交渠道审核）；
② 强制更新/产品公告（W2/W10——SS0 删档重置前提，用户侧已确认就绪，发布时执行）。

---

## 7. 已知过期引用清单（SS0 A8-7 基础上补全）

> 口径：**历史/过程档案中的过期表述不回改**（上位方案 §0.5 作废语义 + V5 先例）。
> 本清单是「已知且接受」的登记，防后续会话误判为新缺陷；活文档面已随 SS 批与本批清零（§5）。

| 组 | 位置 | 过期内容 | 处置 |
|---|---|---|---|
| A8-7 基础集（SS0 登记全量枚举） | `docs/parallel-batches-w4/`、`docs/parallel-batches-w5/`、`docs/save-system-refactor-plan-2026-09-21.md`、`docs/save-system-survey-2026-10-01.md`、`docs/save-system-audit-2026-09-21.md`、`docs/realtime-settlement-plan-2026-09-27.md`、`docs/realtime-watch/`、`docs/gacha-watch/`、`docs/design/equipment-set-system-refactor-plan.md`、`docs/design/equipment-batches/`、`docs/design/texture-minification-pipeline-overhaul.md`、`docs/design/remove-2x-speed-implementation-plan.md`、`docs/design/remove-law-enforcement-and-prison-implementation-plan.md`、`docs/design/elemental-damage-system-plan.md`、`docs/design/acceptance-review-equipment-and-elemental.md`、`docs/memory-audit-2026-09-22.md`、`docs/longrun-stability-audit-report.md`、`docs/longrun-stability-remediation-plan.md`、`docs/sr0-recon-report-2026-09-21.md`、`docs/report-移除自动存档-接入云存档.md`、`docs/build-perf/`、`docs/adr/sqlite-sqldelight-evaluation.md`、`docs/design/single-save-batches/`、`docs/design/gacha-batches/` | 历史方案/派工/验收档案中引用已退役符号（槽位、迁移链、旧存档格式等） | 不回改（作废语义已在档） |
| V5 集合 | `docs/gacha-watch/BATCH-PLAN-ALL.md:1028,1038`；`docs/design/gacha-batches/TASKBOOK-G14.md:63,73` | 版本号格式仍写 `X.XX.XX`（旧格式） | 不回改（G 批已收官过程档案） |
| SS1–SS9 补充：已发现已修复 | `docs/knowledge-base.md`「存档槽位隔离」节（SS5 改写为增量落盘；目录锚点/`resetForSlot(slotId)`/`GameData` 主键/存档手段行四处漏网面**本批补全**） | —— | 已闭环（本批 §4.2） |
| SS1–SS9 补充：已发现已修复（随批） | `rules/sdk-init-lifecycle.md`（SS2 消费 `:50` 退役行 + SS8 修正两处主界面退役后表述）；`CloudSaveCacheWriter` 等 KDoc（SS4/SS7 随批清） | —— | 已闭环 |
| 本批复核新增（接受现状） | 模块级 `AGENTS.md`/活文档中提及「Room v64」的历史章节（如装备线 EQ 期章节行） | 版本号表述滞后于 v70 | 仅 CODE_WIKI 装备线一行随批更正为 v70；其余历史章节沿用 V5 先例不回改 |

---

## 8. 途中发现（不在本批处置，移交看护）

### 8.1 无用代码
- **`core/domain/.../repository/SaveStorage.kt` 孤儿接口**：零实现零消费（SS0 删 `SaveStorageImpl` 后接口未随删）。
  CODE_WIKI 域接口清单已随批剔行；接口本体删除属 .kt 改动，登记后续清理批（可与其它 .kt 收尾合批）。

### 8.2 行为评估结论（report-SS7 §6.4(3) 指定本批评估项）
- **`CloudSaveDialog` 云下载 / `SettingsTab` 云下载卡仍走 `TapCloudSaveManager` 内存加载不落盘**：
  评估结论 = **保留现状，不改**。理由：① 该入口语义是「显式预览」，与换设备续玩正规链路（AutoEntry 自动 +
  SS7 落盘链 + `adoptCloudState` 收敛）已分离；② 改走落盘链 = 行为变更，需代码批 + 守卫测试，
  与本批零代码红线冲突；③ 残余风险面 = 玩家预览云端档后不触发本地保存即退出，进度不落盘——
  建议后续代码批统一改走 `CloudSaveCacheWriter` 落盘链（低风险，SS7 已建全部挂点），登记为候选。

### 8.3 文案候选（report-SS8 §6.2(2) 指定本批评估项）
- **TapTap SDK 未就绪时登录按钮 Toast 沿用「正在初始化」**，未明确告知「需联网」：评估 = 属代码/资源内文案
  改动，本批不夹带；建议后续批改为明确引导文案（B1②体验优化，非缺陷）。

### 8.4 其他
- 派工词交付条款中报告名 `report-SS10.md` 与任务书 `report-SS-final.md` 不一致——按派工真源（任务书 D-3）
  定名 `report-SS-final.md`，本报告头部已注明。

---

## 9. 遗留与后续建议 / 门禁实跑

### 9.1 遗留
1. **推送滞留**：SS1 并网 `580fab8fb` 起（自 `3bc2f6be1` 起 ~35 笔）本地滞留，origin 停 `3a89a9e7a`——
   推送通道（本地代理）恢复后由看护统一补推（非本批范围）。
2. **真机清单**（§6）整体移交发布流程。
3. **§8 三项登记**移交看护排批。
4. **`android/core/data/AGENTS.md` 字节预算告急**：本批修正过期点后 = 32124/32768（余量 644 字节，
   规则⑤预算闸 98% 占用）——下次向该文件增补规范前需先瘦身或拆分，登记给看护。

### 9.2 后续建议
- 4.2.00 发布后首个代码批建议内容：SaveStorage 孤儿接口删除 + 云下载落盘链统一 + 登录 Toast 文案
  （三件均低风险小改，可合一批）。
- `docs/design/single-save-batches/` 全案档案（方案/派工册/任务书×11/report×11）建议保留在库作审计链
  （A8-7 口径），不做归档清理。

### 9.3 本批门禁实跑（工作目录 `C:\Mnzm\XianxiaSectNative-SS10`）

| 门禁 | 结果 |
|---|---|
| `node scripts/check-agent-instructions.mjs`（本批核心硬门） | **EXIT=0**（预算闸 AGENTS.md 25703/32768；引用闭包 42 篇 489 条全可解析；路由表 7 个 AGENTS.md 全登记；⚠️ 子目录启动链最坏件 `android/core/data/AGENTS.md` = **32124/32768（余量 644 字节），见 §9.1 登记**） |
| `.\gradlew.bat compileReleaseKotlin --console=plain` | **BUILD SUCCESSFUL**（119 tasks，44s） |
| `.\gradlew.bat :app:detekt --console=plain` | **BUILD SUCCESSFUL**（baseline 零新增） |
| `.\gradlew.bat :app:testReleaseUnitTest --max-workers=1`（worktree 桥 `-Dgamecore.jni.path=…`） | **BUILD SUCCESSFUL**（docs-only 零影响预期成立；桥为 worktree 重编，259 文件同源指纹） |
| `git diff --stat` 零生产代码自证 | 9 改 + 1 新增全为 `.md`/`.json`，`.kt`/`.java`/`.cpp`/`.h` 命中 = **0** |
| 游戏内 json 可解析 | `node -e JSON.parse` OK（entries=1 / changes=13） |
| 构建副产物还原 | `atlas-rgba-manifest.json` / `scene_uv_tables.h` / `sprite-uid-map.json` 三件 compile 触发再生，提交前 `git checkout --` 还原，工作树只留本批改动 |
