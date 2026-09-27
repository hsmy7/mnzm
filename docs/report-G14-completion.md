# 角色卡池重构 G 批 · G14 文档与发布收口报告

> **批次**：G14（M2 末批 · 整个 G 批的文档硬门）　**施工面**：主树（开工 HEAD `432c22537`，收官时树被看护台账笔推进至 `8a4c735ac`）　**分支**：开工于 `feat/gacha-m0-m1`；实施期间他线把主树切到 `refactor/remove-2x-speed`，经用户 2026-09-27 03:39 拍板「任其落错，事后手术」，本批在该分支交付，收官笔由看护 cherry-pick 回 feat（见 §四-1）
> **日期**：2026-09-27　**性质**：纯文档批（零代码逻辑改动）
> **验收状态**：**待用户/看护验收（本报告不得自登记 accepted）**
> **方案指针**：派工真源 `docs/design/gacha-batches/TASKBOOK-G14.md`；权威需求 `docs/character-gacha-redesign-2026-09-23.md`（v1.5）；数值口径 `docs/design/gacha-batches/m0-economic-whitepaper.md`（§3/§4.1/§11 为终局口径）
> **范围纪律**：本批改动面 = 版本三件 + `CODE_WIKI.md` + `docs/architecture.md` + `docs/knowledge-base.md`（经济行）+ 白皮书表体（G13 §8-3 移交）+ 本报告 + `docs/design/gacha-batches/report-G14.md`；不回改任何历史报告/过程档案；`docs/research/`×2 与他线方案文档不入库

---

## 一、任务逐条交付（任务书 §1 八条判据）

| # | 判据 | 结果 | 落点与证据 |
|---|---|---|---|
| ① | 版本号三方归一 `4.01.16` | ✅ | `version.properties`：`versionName=4.01.16` / `versionCode=4116`（改前 4.01.14/4114）；`CHANGELOG.md:1` 段头 `[4.01.16] - 2026-09-22` **未动**；`android/app/src/main/assets/changelog_entries.json` 首条（唯一条）`"version":"4.01.16"`。三者版本号一致且形如 `X.XX.XX` |
| ② | 双 changelog 终稿齐备且同版本、同版本唯一条目 | ✅ | `changelog_entries.json`：原 4 条 `4.01.14`（49+3+1+42=95 条 changes）合并为唯一 `4.01.16` 条目，`date` 保持 `2026-09-23`，`changes` **99 条只增不减、按时间升序**拼接（95 条原内容 + 他线批内新增 4 条执法堂下线公告，逐条集合等价比对通过，见 §二）；总条目 65 → 62。玩家向条目均为通俗文案、无数值细节；技术向 `CHANGELOG.md` [4.01.16] 段内含 G10/G11/G12/G13（前批已写）+ G14（本批新增）五节，无按日期拆分的同版本条目 |
| ③ | `CODE_WIKI.md` 六处修正 | ✅ | 六处中三处（Facade 7→12、目录树补 `gacha/` `cultivation/` `economy/` `road/`、`DiscipleDelegate` 已删玩法说明）**G10 批已先行交付**，本批逐处交叉确认零残留；本批新改三处：`:75` 计数（166/1712/31 handler → **201 / maxId 1872**，handler 数无文档化复现命令，按 D-3 精神删除该易腐字段）；`:102-106` 旁注（198/1861/退役21 → **201/1872/退役 24**，并删「原写 114 动作/20 handler 已过期」的历史性表述）；迁移台账补 `1870–1872` 寻访段；`:304` Delegate 节按 D-3 改**按域分组 + 以 `ui/game/delegate/` 实际文件为准**（现 27 个 `.kt`，分组清单见该文件） |
| ④ | `docs/architecture.md` 五处修正 | ✅ | 乘区表：`CultivationSpeedZones` 4→**5 乘区**（补星级 `starBonus`，注明口径 A 1★ 基线 ×1.00 与单源 `GameConfig.Gacha.STAR_CULT_PCT_PER_STAR`）；`BreakthroughZones` 字段名对齐代码（`baseZone`/`elderGuidance`/`selfBonus`/`adFlatBonus`，G13 未改 clamp，无需同步上限）；补「宗门战力（星级进战力）」行（Kotlin `SectCombatPowerCalculator` 与 C++ `sect_power.h` 的 `discipleCombatPowerWithStar` 同式、`gamecore/system/star_zone.h` 的 `starMultiplier` 单点、纳入 Diff 对拍）；GameSystem 节声明**寻访不注册月/年结算回调、保底不进年变 T1/T2**（依据 `docs/character-gacha-redesign-2026-09-23.md` §15.4）；扩展点新增 **§7 寻访域运营钩子**（轮换池/UP `poolId` 级 pity 与池开关、埋点 `gacha_pull`/`gacha_unlock`、付费抽入口参数位；依据同文件 §15.6） |
| ⑤ | 本报告落盘（七节结构） | ✅ | 即本文件（参照 `docs/report-MR4-completion-2026-09-23.md` 结构） |
| ⑥ | 死代码 + 死文案 grep 清零表终稿 | ✅ | 见 §三.3（表内无「待定」，每条归零或书面豁免；含 14 词 × 4 区清零前后对照） |
| ⑦ | 规范门禁全过、规则③「写法不精确」计数不高于改前 | ✅ | 改前基线：42 篇 / 444 条引用全可解析、0 处不精确；改后（本报告落盘后）复跑：五条规则全 ✓，结果见 §二 |
| ⑧ | 提交前过 `rules/pr-review-checklist.md` 与 `rules/version-release.md`；单次提交 | ✅ | 见 §六；单次提交，`git add` 全部明确文件名 |

**不做面核对**：不改代码逻辑（零 `.kt/.cpp/.h` 改动）✅；版本号仅按用户拍板值 4.01.16 执行 ✅；不回改过程档案（`docs/report-G10/G11/G12/G13.md` 等零触碰）✅；不动 `docs/design/gacha-batches/` 历史报告 ✅；`docs/research/`×2、`docs/realtime-settlement-plan-2026-09-27.md`、`docs/design/remove-*.md` 等他线文档不碰不入库 ✅。

---

## 二、门禁实证（命令输出原文；判据 = 实跑值）

> 本批纯文档，任务书 §10 门禁清单 = 规范门禁 + JSON 校验 + 计数复现三类，未含 Gradle 门。树上存在他线在途代码面（125 项多线并行，看护台账 `8a4c735ac` 登记），Gradle 编译/测试结果无法按文件面归因到本批，按看护「干扰红归因」规则留给验收轮实跑。

| 门禁 | 口径 | 结果 |
|---|---|---|
| `node scripts/check-agent-instructions.mjs` | 五条规则原文：① 预算闸 AGENTS.md ≤32768 字节；② 单一真源无 CLAUDE.md；③ 引用无死链（路由闭包全可解析）；④ 路由表完整；⑤ 子目录启动链路预算 | 改前基线：**26763/32768、无 CLAUDE.md、42 篇 444 条引用零死链、7 个 AGENTS.md、32180/32768，全部 ✓**；改后复跑（本报告落盘后）：**全部 ✓、规则③不精确引用计数 0（不高于改前）** |
| `changelog_entries.json` JSON 校验 | `node JSON.parse` | ✅ 合法；首条 `4.01.16 / 2026-09-23 / 99 条`，总条目 62 |
| 99 条内容等价比对 | 合并结果 逐条 == HEAD 原 95 条 + 批内他线新增 4 条（升序重排） | ✅ 集合与计数双比对零差异（排序比较脚本实跑输出「99 条内容逐条相等」） |
| ActionId 计数复现 | `node scripts/gen-action-ids.mjs` | 输出 **`gen-action-ids: 201 actions (maxId=1872)`**；regen 后 `git diff --exit-code` 两产物（`android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h` + `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt`）**零漂移** ✅ |
| 退役数复现 | `grep -c "已退役" ActionIds.kt` | **24**（与文档新值一致） |
| Facade 计数复现 | `find android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain -name "*Facade.kt" ! -name "*Impl*"` | **12 个**（battle/building/cultivation/diplomacy/disciple/economy/exploration/gacha/inventory/production/road/save），与 `CODE_WIKI.md` 新值一致 |
| Delegate 计数复现 | `ls android/feature/game/src/main/java/com/xianxia/sect/ui/game/delegate/*.kt \| wc -l` | **27**，与 `CODE_WIKI.md` 新值一致 |
| Zones 字段复现 | `grep -A10 "data class CultivationSpeedZones\|data class BreakthroughZones"` | `CultivationSpeedZones` = resourceBonus/socialBonus/statusBonus/temporaryBonus/**starBonus**（5 字段）；`BreakthroughZones` = baseZone/elderGuidance/selfBonus/adFlatBonus（4 字段）——与 `docs/architecture.md` 新表逐字段一致 |
| game-data 生成器 | `node scripts/gen-game-data.mjs --check` 语义（sha256 对照） | 本批零触碰 `android/app/src/main/assets/data/game-data.json`；树上 sha256 = `809375f4…8619` = G13 交付值（G13 附录事实 2 的新值口径沿用） |

---

## 三、关键实施事实（供总收官引用）

### 3.1 版本归一结论

- 三方归一值 = **`4.01.16`（versionCode 4116）**，用户 2026-09-26 拍板（任务书 D-1）。
- `changelog_entries.json` 合并口径：`changes` 按时间**升序**拼接（原 09-11 块 42 条 → 09-15 块 1 条 → 09-15 块 3 条 → 09-23 块 53 条）；`date` 按任务书钉死值保持 `2026-09-23`（登记：若按 `rules/version-release.md`「首条发布日」字面口径应为 09-11，本批按任务书执行，见 §四-3）。
- `CHANGELOG.md` [4.01.16] 段头日期 `2026-09-22` 未动（任务书明令不改）；段内按批次小节组织，与本 JSON 一一对应的玩家文案均已入库。

### 3.2 逐处改动清单

| 文件 | 处 | 改动 |
|---|---|---|
| `version.properties` | 1 | versionName/versionCode → 4.01.16/4116 |
| `android/app/src/main/assets/changelog_entries.json` | 1 | 4 条 4.01.14 → 1 条 4.01.16（99 条 changes 升序只增不减） |
| `CHANGELOG.md` | 1 | [4.01.16] 段顶新增 G14 批技术节 |
| `CODE_WIKI.md` | 4 | ActionId 协议行计数 + 旁注去历史表述；台账补 1870–1872 行；旁注 201/1872/退役 24；Delegate 节按域分组重写（27 个 .kt 以实际文件为准） |
| `docs/architecture.md` | 4 | 乘区表 3 处（修炼 5 乘区 / 突破四字段名 / 新增星级进战力行）；GameSystem 节寻访结算声明；扩展点 §7 寻访域运营钩子 |
| `docs/knowledge-base.md` | 1 | 经济基线表补「仙缘寻访」耗行（`PRICE_PER_PULL`=5000、保底 10 抽×5 碎片、结算 `gacha_tx.h`） |
| `docs/design/gacha-batches/m0-economic-whitepaper.md` | 7 | §5 战力表口径 B→A 全表重写；§6.1 回血行改既有机制单源（兜底键已删）；§6.2 突破补偿表改终局「不补」并删 G13 尾注；§7 JSONC 删两悬空键 + 键表删两行 + 删 G13 防误读注；§8 移交行改「已增补」；§9 勾选表十项回填终值并改尾注 |
| `docs/report-G14-completion.md` | 新建 | 本文件 |
| `docs/design/gacha-batches/report-G14.md` | 新建 | 批次执行报告（看护核验入口） |

### 3.3 死代码 + 死文案清零表（终稿）

**批内 14 词清单（本批复测所用，4 区 = Kotlin src/main / C++ gamecore / assets 配置 / changelog）**：
`招募、驱逐、改名、道侣、子嗣、拜师、师徒、资质、洗炼、血炼、偷盗、寿元、俘虏、思过`
（G12 侦察原件未随档保留全名单，本表以本批显式清单为准复测；清零前档值引自 `docs/design/gacha-batches/TASKBOOK-G12.md` 上位失真表：Kotlin src/main 244 / C++ 51 / assets 0 / changelog 101。）

| 关键词 | Kotlin 主面 | C++ | assets 配置 | changelog 当前条 | changelog 历史条 |
|---|---|---|---|---|---|
| 招募 | 39 | 10 | 0 | 5 | 30 |
| 驱逐 | 7 | 1 | 0 | 1 | 0 |
| 改名 | 12 | 3 | 0 | 2 | 7 |
| 道侣 | 4 | 1 | 0 | 3 | 3 |
| 子嗣 | 0 | 0 | 0 | 1 | 0 |
| 拜师 | 4 | 2 | 0 | 1 | 0 |
| 师徒 | 5 | 2 | 0 | 2 | 1 |
| 资质 | 5 | 2 | 0 | 1 | 2 |
| 洗炼 | 5 | 2 | 0 | 3 | 6 |
| 血炼 | 20 | 7 | 0 | 3 | 15 |
| 偷盗 | 6 | 0 | 0 | 1 | 13 |
| 寿元 | 3 | 2 | 0 | 1 | 3 |
| 俘虏 | 6 | 3 | 0 | 2 | 3 |
| 思过 | 17 | 6 | 0 | 2 | 6 |
| **合计** | **133** | **41** | **0** | **28** | **106** |

**逐区处置（每区要么归零、要么书面豁免）**：

| 区 | 清零后 | 处置 |
|---|---|---|
| assets 配置（`android/app/src/main/assets/config|data`，除 changelog） | **0** | **清零** ✅（与 G12 侦察 0 一致） |
| changelog 当前条（4.01.16 合并条） | 28 处 | **豁免+理由**：全部是「××玩法下线」公告与寻访活玩法描述（内容即下线告知，合法玩家文案，非死文案） |
| changelog 历史条 | 106 处 | **豁免+理由**：历史版本条目描述当时状态，按 `rules/version-release.md` 追加式纪律不可改写 |
| Kotlin src/main | 133 处 | **豁免+理由**（逐文件分类复测）：① 路径豁免类——`GameDatabaseMigrations*`（迁移链不可改写）、`ActionIds.kt`（退役 desc，「只增不复用」红线内容）、`*CleanupRule`（读档清理规则注册与注释）、`OldSerializableSaveData`/backwardcompat（旧档兼容面）；② 语义同形词——「驱逐」=缓存逐出（`DynamicMemoryManager`/`CacheLayer`/`MemoryTrimLevel`）、「改名」=宗门改名（存活功能）与文件原子改名、「招募」=AI 宗门内部三年一度招募（存活机制，`recruitCountThisMonth` 经 G10 §5.2 判活）；③ 当前状态注释（非玩家可见文案）；④ 唯一玩家可见候选残余 = `android/core/domain/src/main/java/com/xianxia/sect/core/model/ResignGateResult.kt:36`「该弟子处于思过中，是否解除？」——思过系统下线且读档归一化（`DiscipleFacadeImpl.kt` 旧档 REFLECTING→IDLE）后**不可达的防御分支文案**，本批纯文档不改码，登记见 §四-4 |
| C++ gamecore | 41 处 | **豁免+理由**：注释/退役 desc（G12 侦察口径沿用：唯一非注释命中为 `scene/float_text.h` 浮字动画寿命假阳性） |
| 真玩家可见四串复核 | 「招募一次/十次」各 1（`GachaResultLayer.kt`，产品结果页口径 `docs/character-gacha-redesign-2026-09-23.md:187`，G12 D-1 豁免）；「招募失败」「刷新数量上限」**0** | **清零/豁免闭合** ✅ |

**死代码符号复测（主树，排除 `.worktrees` 存档树与 build；G10 删面全部保持归零）**：

| 符号/对象 | 清零前（G10 时点） | 清零后（本批复测） | 处置 |
|---|---|---|---|
| `NullSafeProtoBuf.relationIdToProto/relationIdFromProto` | 有（仅自测试引用） | **0** | 清零（G10；本批 grep 复测 0） |
| `Serializers.kt` 整文件（两 Nullable 序列化器） | 存在 | **文件不存在** | 清零（G10） |
| `DiffMonthSettlementFixture.buildProductionProcessor` | 1 死调用 | **0** | 清零（G10） |
| `GameNotification.RecruitFailed` + 消费分支 | 发布者 0/分支在 | **0** | 清零（G10；玩家文案「招募失败」同步 0） |
| `MIN_AGE` / `recruitListAggregates` | 仅测试引用/0 消费 | **0 / 0** | 清零（G10） |
| `DiscipleFacade.addDisciple` 全链 + `LEGACY_CRUD` 守卫类别 | 唯一调用方为测试 | **主树 0 定义 / LEGACY_CRUD 0** | 清零（G10；主树 grep 复测，`.worktrees` 存档树命中不计） |
| `rootCount = 1;` 自赋值 3 处 | 无效语句 | **自赋值形态 0**（现存命中均为声明初始化/合法解析代码，逐行语境复核） | 清零（G10） |
| 色旧表：`getSpiritRootCountColor`/`XianxiaColorScheme.rarityColors`/两转发壳 `getRarityColor` | 零调用 | **0 / 0 / 0**（`getSpiritRootCountColor` 唯一命中为守卫测试正则串，豁免） | 清零（G10/G12） |
| `AutoAssignDelegate` 俘虏过滤两死 UI 入口 | 零调用方 | **文件内 0 引用** | 清零（G10） |
| `breakthroughCompBonus`/`BREAKTHROUGH_COMP_BONUS`/`injuryHealPctPerPhase`/`INJURY_HEAL_PCT_PER_PHASE` | 悬空键 | **全 0**（G13 删；本批复测 4 符号全零） | 清零（G13） |
| `prisonerSpiritRootFilter` 字段 | 字段活 + UI 入口死 | UI 入口 0；字段保留（删需 Room 迁移） | **豁免+理由**：字段删除涉 Room 迁移与三端链，G10 §5.3 登记留拍板 |
| `predefinedCodes` | 空码表（仅 serverCode 填充） | 仍在（4 文件） | **豁免+理由**：运营码表口径，G10 §5.3 登记留拍板 |
| 通知通道后端管线（GameStateStore 队列五成员 + 转发） | 休眠预留 | 保留 | **豁免+理由**：退役 vs 绑定新事件源留用户拍板（G10 登记） |

---

## 四、诚实残余与过程记录

| # | 项 | 状态 |
|---|---|---|
| 1 | **多线并行干扰与分支落点**：本批实施期间，remove-law-enforcement 线在主树大规模实施（109+ 文件在途）、他线将分支切至 `refactor/remove-2x-speed`；用户 2026-09-27 03:39 拍板「任其落错，事后手术」——本批照常在该分支交付，看护 accepted 后执行 cherry-pick 收官笔回 `feat/gacha-m0-m1` + 他线分支重置（手术前置 = 用户确认他线空闲）。本批**零触碰**他线在途文件；`CHANGELOG.md`/`changelog_entries.json`/`docs/knowledge-base.md` 三个共享文件中**他线未提交 hunk 会随本批提交一并入库**（git add 按文件粒度，无法分 hunk）——已在提交说明中显式登记，供手术轮区分 | 已按拍板执行，登记 |
| 2 | `changelog_entries.json` 合并内容含他线批内新增的 4 条执法堂下线公告（99 条中的 4 条）——若手术轮把他线分支重置而其功能尚未另行落库，该 4 条公告将先行存在于玩家 changelog | 登记，随手术轮处置 |
| 3 | 合并条目 `date=2026-09-23` 按任务书钉死值执行；`rules/version-release.md`「首条发布日」字面口径应为 09-11（四条目中最旧日期） | 按任务书执行，差异登记 |
| 4 | **死文案残余 1 处登记**：`android/core/domain/src/main/java/com/xianxia/sect/core/model/ResignGateResult.kt:36` 思过中确认文案（不可达防御分支）——处置需删代码，超出本批纯文档面，留后续代码批/拍板 | 登记 |
| 5 | 白皮书 §15.1 仍写「7 领域 Facade + GameEngine 扩展文件」——v1.4 方案时点历史表述（§15.2 即写明「新增 GachaFacade」），非现况断言，未回改；G13 §8-3 移交面（§5/§6.2/§7/§9）已全部统一 | 登记 |
| 6 | `CODE_WIKI.md` ActionId「31 handler」字段删除（未替换新值）：handler 数无「一条命令复现」的文档化口径，按 D-3 反易腐原则删除而非猜测新值 | 登记 |
| 7 | 本批 Gradle 门（compile/test/detekt/lint）未实跑：纯文档零代码面 + 树上他线在途代码面使结果不可归因（任务书 §10 门禁清单亦未含）；验收轮按看护干扰归因规则实跑 | 登记 |
| 8 | 未经用户/看护验收：本报告与提交**不自登记 accepted** | 常态 |

---

## 五、pending-device 清单（真机验证汇总）

- **存量 18 项**：M1 的 G11 十二项（`docs/design/gacha-batches/report-G11.md` §7，D-1…D-12）+ M2 的 G12 六项（`docs/design/gacha-batches/report-G12.md` §9）。
- **G13 新增 0 项**（零行为零 UI，`docs/design/gacha-batches/report-G13.md` §8）。
- **G14 新增 0 项**（纯文档批；`version.properties` 版本号变更的真机可见面仅「设置页版本号显示」，并入全链真机走通轮一并确认）。
- 执行口径：M1「G11 真机通」与 M2「全链真机走通」**同一台设备同一轮**（任务书 §11-2）。

---

## 六、收官笔材料清单

- [x] `version.properties` → 4.01.16/4116
- [x] `android/app/src/main/assets/changelog_entries.json` 四合一（99 条升序只增不减）
- [x] `CHANGELOG.md` G14 技术节（[4.01.16] 段内）
- [x] `CODE_WIKI.md` 计数四处 + 台账补行 + Delegate 分组（G10 三处交叉确认）
- [x] `docs/architecture.md` 五处
- [x] `docs/knowledge-base.md` 经济行
- [x] 白皮书表体统一七处（G13 §8-3 移交闭合）
- [x] 本报告 + `docs/design/gacha-batches/report-G14.md`
- [x] 规范门禁复跑（报告落盘后）
- [x] 单次提交、明确文件名 `git add`
- [ ] 用户/看护验收 accepted（**留验收轮**）
- [ ] 看护分支手术（cherry-pick 回 feat；前置 = 用户确认他线空闲）

---

## 七、提交前建议复跑（验收轮）

```bash
node scripts/check-agent-instructions.mjs
node -e "const j=JSON.parse(require('fs').readFileSync('android/app/src/main/assets/changelog_entries.json','utf8'));console.log(j.length,j[0].version,j[0].date,j[0].changes.length)"
# 期望：62 条目 / 4.01.16 / 2026-09-23 / 99 条
node scripts/gen-action-ids.mjs && git diff --exit-code -- android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt
# 纯文档批不强制 Gradle 门；若验收轮实跑，按看护「他线产物不计违规、干扰红归因」口径读数
```
