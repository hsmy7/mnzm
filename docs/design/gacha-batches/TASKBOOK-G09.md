# TASKBOOK-G09 · 抽卡核心（roll / 保底 / 碎片 / 升星 / 解锁 / 入库）

> **本文件是 G09 的派工真源**，取代 `recon-G05-G06-G08-G09.md` §G09（`:415-575`）全部落点表、
> `HANDOVER-m1-remaining-3.md` §8.F 的 G09 清单。本轮实测原始记录见 [`recon-G09.md`](recon-G09.md)。
> 上位失真条目逐条列在 §2，**执行者只认本文件**。
> **更新时点：2026-09-26（用户六项拍板后）**——`§7.1` 记录拍板结果，`§3.6.1` 是来源文案整改细则，
> `§3.8` 已改为**口径 A**，`D-3` 已拍板为**独立抽卡分区** `GACHA = 12`（六项拍板全部落地，无待决阻塞项）。
> 开工时点：HEAD `6c5e0daa1`，工作树 = `docs/research/`×2（未跟踪，不提交）
> ＋ 构建副产物 `android/app/src/main/assets/atlas/atlas-rgba-manifest.json`（提交前 `git checkout --` 还原）。
> 侦察方式：主线程直读关键文件 ＋ 4 个只读子代理穷举（需求口径 / C++ 现状面 / Kotlin·Room·守卫面 / 8 批删除面复核）
> ＋ 3 个对标子代理（见 [`BENCHMARK-gacha-rng-partition.md`](BENCHMARK-gacha-rng-partition.md)）。
> 行号为本轮实测；**标 ⚠️ 的行由子代理给出、主线程未直读**，实施时按 `grep` 复核再改。

---

## 0. 剩余工作总纲（M1 剩余 = G09 → G11 → G10）

> **为什么只把 G09 写到派出级、G11/G10 只给入口/出口判据**：`EXECUTION-PROTOCOL.md` §5 与
> `HANDOVER-m1-remaining-3.md` §8.C-1 规定「派工前必须把该批的侦察缺口回查写成独立 `TASKBOOK-Gxx.md` 再切片」，
> 而 G11 的 DTO 契约与 G10 的登记集**都取决于 G09 的落地形态**（G11 依赖 G09 的结果 DTO 字段，
> G10 的 RNG 重录范围取决于 G09 的抽卡随机分区决策）。提前给出细粒度切片会重演 G16/G08
> 「上位内联任务书无文件面、照字面派工踩坑」的代价（见 `TASKBOOK-G16.md` §2、`TASKBOOK-G08.md` §2）。
> 故此处给出三批的**入口判据 / 出口判据 / 硬门 / 依赖**，G11/G10 开工时各自补自己的 TASKBOOK。

| 批次 | 内容 | 入口判据（开工前置） | 出口判据（验收） | 硬门 |
|---|---|---|---|---|
| **G09**（本批） | 抽卡核心：roll（单抽/十连）、保底、碎片、升星、解锁、入库、历史、星级乘区 | 工作树干净；`db.gachaPools` 已注入 C++（本批做，见 D-1）；G08 碎片单点可复用 | 单抽/十连经 **GTest ＋ 跨语言 Diff 双臂对拍**绿；四本抽卡账本首现写者；入库经统一入口且来源名同批登记；C++≈Kotlin 星级乘区同名同参；无新增 A 类；B 类逐条登记 | C++ AUTHORITATIVE（`MirrorReadOnlyGuardTest` 零命中）；零新增 `external fun`（JNI 86/86）；金黄**不重录**（唯一窗口是 G10） |
| **G11** | 最简寻访 UI：主界面 ＋ 结果页 Q30/Q31 ＋ 图鉴最小 ＋ `GachaDelegate` 接线 | G09 单次提交；`GachaPullResult.Success` 与结果格 DTO 冻结；G16 的 12 键可解析 | **真机通**（M1 完成判据之一）；色表强制 Q31；头像位读 512 档 `avatarKey`（G08 移交项） | UI 不驱动 tick；不新增第二通知总线；流光须 Vulkan ＋ Canvas 双路径可过（低端降级） |
| **G10** | RNG 对拍基线**唯一一次**重录 ＋ 全量回归 ＋ 死代码 grep 清零 ＋ 文档收口 | G02–G07 全合入（已满足）＋ G09/G11 合入；工作树干净 | ctest 全绿（金黄已重录）＋ 全量 JUnit 绿 ＋ `HANDOVER-3` §6.3 八份登记逐条处置 ＋ 死代码归零 | 重录窗口唯一，任何批次不得提前重录（§6.1 红线） |

**M1 完成判据（实施计划 §批次速查）**：**G10 全绿 ＋ G11 最简 UI 真机通**。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 把「寻访」从空壳变成可玩闭环：花 5,000 灵石抽一次，21% 出角色碎片、79% 出物品；每第 10 抽必出随机角色碎片 ×5；碎片满 100 升 1 星（上限 5 星），开局即 1 星的周明与升星都拿到战斗/修炼加成；物品走统一入库入口、满仓自动转邮件 |
| 验收① | `pullOnce/pullTen` 走 **native 事务**（ActionId 1871/1872）＋ `applyDirtyFromNative` 回读；`GachaPullResult.NotReady` 占位消失；`MirrorReadOnlyGuardTest` 零命中 |
| 验收② | 保底语义＝**第 10 抽本身**为随机角色碎片 ×5（六选一，含已解锁/已满星），该抽**不 roll 类别**；`gachaPityCounters[poolId]` 由 0 起 `+1`，到 `pullThreshold` 归零 |
| 验收③ | 角色命中得该角色碎片 **×1**；碎片入账**只经** `gacha_fragment.h::addFragment`（Kotlin 回退臂 `GachaFragmentLedger`），全仓无第二个 `gachaFragmentCounts[...] =` 写点 |
| 验收④ | 解锁（首次到 1 星）生成 1 名在册弟子，且**只经** `DiscipleService.instantiateTemplate`（`DiscipleCreationPathGuardTest` 白名单内）；`annualNewDisciples` / `guideCounters["disciplesRecruited"]` **只加一次** |
| 验收⑤ | 三类物品（`beastMaterials`/`herbs`/`seeds`）入库经统一入口 ＋ 来源名同批登记；**满仓抽卡不失败**（溢出转邮件）；品阶严格 ≤ 池内 `maxRarity`=4 |
| 验收⑤·补 | 🔴 **来源文案整改达标**（用户 2026-09-26 拍板）：`SOURCE_DISPLAY_NAMES` 全表显示名均为**玩家可明确得知的游戏内出处**；4 条死条目删除；邮件标题/正文模板不再对「归还」型来源使用「奖励」二字；`OverflowMailSenderTest` 同步 ＋ 新增「map 无死 key」反向断言（细则 §3.6.1） |
| 验收⑥ | `gachaHistory` 环缓冲生效：新在前、条数 ≤ `HISTORY_RING_SIZE`(50)、保底条 `isPity=true` |
| 验收⑦ | 星级乘区双端同名同参（**口径 A**：`×(1 + (star-1) × pct)`，1★ 基线 ×1.00，5★ 战斗 +32%/修炼 +20%）：C++ `disciple_stats.h`/`sect_power.h` 与 Kotlin `DiscipleStatCalculator`/`SectCombatPowerCalculator` 同式；`star=0`（未解锁/存量旧弟子）恒 ×1.00；Equivalence/Diff 测试锁死 |
| 验收⑧ | **零新增 A 类**；独立分区落地后须实测证明既有三条 B 类红（`DiscipleFactory.GoldenSequence*`×2、`DeterminismProbeTest.DigestMatchesGoldenBaseline`）**逐条同名且 `actual` 指纹不变**，并把指纹原文写进报告；本批**不重录**任何金黄/对拍基线 |
| 验收⑨ | `GachaConfigGuardTest` 三向常量守卫扩到星级百分比（C++ 常量 ↔ `GameConfig.Gacha` ↔ `gachaDefaults`）；新增「运行期账本 key ⊆ 模板表」「`pity` key ⊆ `gachaPools`」守卫 |
| 验收⑩ | 双 changelog ＋ `report-G09.md` ＋ 单次提交（共享五件套同 commit） |
| **不做** | 不做寻访 UI / 结果页 / 图鉴 / `GameViewModel` 接线（G11）；不新增 Room 列、**不做迁移**（D-12）；不给 `GachaHistoryEntry` 加「格序」字段（D-13）；不重录金序列/对拍基线（G10）；不改 `execute_dispatch.cpp` 的端口认领形态；不改版本号；**不做突破补偿**（P-6）；**不做保底自选**（P-5）；**不改 RNG 种子派生方案**（D-3 已否决哈希派生）；**不做防回档加固**（§7.2#4，单机不根除） |

---

## 2. 🔴 上位失真与冲突清单（执行者必读，按实测走）

### 2.1 `recon-G05-G06-G08-G09.md` §G09 落点表失真（该段为 2026-09-23 快照，G08 之后大面积失效）

| # | recon 原文（§G09） | 实测真值（本轮） | 后果 |
|---|---|---|---|
| 1 | `G09-3`：`gacha.mjs` 的 `CATALOG=[]`、1870–1889 是「G01 预留空段」；「`GACHA_PULL_ONCE/TEN` 不存在，`kAllActionIds` max=1861、count=198」 | **1870 已实裁**（`scripts/action-catalog/gacha.mjs:18-21`）；`action_ids.h` 现 maxId=**1870**、count=**199**；`GACHA_PULL_*` 仍不存在 | 照字面会去「新建」已有的东西；**下一可用号 = 1871/1872** |
| 2 | `G09-4` #4/#5：「新建 `src/dispatch_gacha.cpp` ＋ 在 `execute_dispatch.cpp` 追加端口」「G09 应新建 `test/gacha_tests.cmake`」 | **三项均已由 G08 完成**：`dispatch_gacha.cpp`（80 行，含 1870 case）、端口已接（`execute_dispatch.cpp` 内 `dispatchGacha` 前置声明 ＋ 调用点，⚠️ 具体行号两路子代理给出 `:61-65`/`:62-64` 与 `:2524-2530`/`:2527-2532`，**实施时以 `grep -n dispatchGacha` 为准**）、`test/gacha_tests.cmake` 存在且已 include 进 `test/CMakeLists.txt` | 别重建，只**追加** |
| 3 | `G09-4`：`gacha_tx.h` 职责含「碎片入账／升星」 | **碎片入账＋升星已由 G08 交付**（`gacha_fragment.h:37-38,63-97`、ActionId 1870） | 重复实现 = 第二写者，直接违反验收③ |
| 4 | `G09-2`：`GameData` 字段 `:900-921`、`models.h:1400-1403`、`json_codec.cpp:1327/1418`、`GameDataFieldPatch.kt:368-379`、`CollectionConverters.kt:618-624`、`GameDatabase.kt:94=54` | 实测：`GameData.kt:807-829`、`models.h:1317-1320`、`json_codec.cpp:1247-1248/1333-1334`、`GameDataFieldPatch.kt:327-337`、`CollectionConverters.kt:555-561`、`DATABASE_VERSION=59`（`.../data/local/GameDatabase.kt:95`） | 行号派工必扑空 |
| 5 | `G09-2`：「`GachaHistoryEntry` 的 ProtoNumber 1..3 待复核」 | 实测 Kotlin/C++ 双端均 **8 字段**（`GachaHistoryEntry.kt:13-26`、`models.h:1131-1140` 逐字段同名） | — |
| 6 | `G09-1`：「`GachaFacadeImpl` 无 `gameEngineCore`、无法走 `tryExecuteNative`」 | **已失效**：`GachaFacadeImpl.kt:27` 已注入 `GameEngineCore`，`:45` 建 `GachaNativeTx`，`:53-66` 碎片入账双臂已通 | — |
| 7 | `G09-1`：「`GachaService` 不写 Store」 | **已失效**：`:58-73` 新增 `grantFragmentsLocally`（`updateAndReturn` 单事务）＋ `@GameService`(`:25`) | — |
| 8 | `G09-9`：「`GachaConfigGuardTest` 有 `assumeTrue`，待 G09 改硬断言；色表单源 `object Gacha` 在 `:210-245`」 | G08 已**取消 `assumeTrue` 改硬断言**并补了配置侧 key 域封闭与开局 id 守卫；`GameConfig.kt` 的 `object Gacha` 常量实测 `:220-239` | 别重复改；**仍缺**的是运行期账本 key 域守卫（D-15） |
| 9 | `G09-7`：「物品两级 roll 落点：C++ 只 roll 品阶＋表内索引（三表在 Kotlin Registry，C++ 不可复刻）」 | **前提不成立**：C++ 侧**已有** `herbs`/`seeds`/`beastMaterials` 三张表（`data_inject.h:62-83` 注入 ＋ `gamecore/data/herb_db.h` / `beast_material_db.h` 的 `herbTemplates()/seedTemplates()/beastMaterialTemplates()`，生产先例 `spirit_field.h:91-188` 直接按模板建 `state::Herb`/`state::Seed` 并入库） | 见 D-2：C++ 可自行完成「两级 roll ＋ 出 item id」，无需 Kotlin 二次 roll |
| 10 | `G09-5`：`InventorySystem`/`ItemAdder` 路径省略了 `engine/system/` 段（⚠️ 来自子代理） | 实路径 `android/core/engine/src/main/java/com/xianxia/sect/core/engine/system/InventorySystem.kt`（主线程已直读 `:939-994` 的 C++ 等价臂；Kotlin 侧 `:216-226` ⚠️） | — |
| 11 | `G09-6`：「新增 `仙缘寻访` 必须同批加入 `SOURCE_DISPLAY_NAMES`」 | **仍未做**：`OverflowMailSender.kt:96-122` 实测 25 项、无该键 ⇒ 落地入库即触发 `OverflowMailSenderTest` 判红。属**必做** | 见 D-9 |
| 12 | `G09-8`：Kotlin 先例引 `RecruitService.kt:61` | 该文件属 G05 整类删除面 ⇒ ⚠️ **引用已失效**，别照抄 | — |

### 2.2 产品方案 / 白皮书内部冲突（G09 必须显式取一个口径）

| # | 冲突 | 涉及 | 本任务书口径 |
|---|---|---|---|
| 1 | 产品 §15.3 守卫 2「开局周明 key 存在且 **≥100 等效**」 vs Q34/§4.5「开局碎片进度 **0/100**」 | 守卫断言 | **以 Q34 为准**（G08 已落：`gachaStarMap["zhouming"]=1` 且碎片账本**不含**该键）。G09 **不得**复活 §15.3 那条断言 |
| 2 | 产品 §15.1/§15.2 把「开局注入」「角色实例化」列入 C++ `gacha_tx` 职责 | C++ 边界 | **以 G08 实际边界为准**：模板表与实例化在 Kotlin（`CharacterTemplateDb` / `DiscipleService.instantiateTemplate`）⇒ C++ `gacha_tx` 只出**描述符**（`unlockedTemplateIds`），物化在 Kotlin。见 D-5 |
| 3 | `TASKBOOK-G08.md` D-16：「G09 在 **C++ 解锁分支**接线 `annualNewDisciples`」 | 计数归属 | 🔴 **作废**：解锁只可能在 Kotlin 发生，且 `DiscipleService.kt:192-196` 的 `insertTemplateDisciple` **已经**在同事务内自增 `guideCounters["disciplesRecruited"]` 与 `annualNewDisciples`。C++ 再计一次 = **双计**。见 D-6 |
| 4 | 白皮书 §4.1 结论「采纳口径 B（`star×8%`/`star×5%`）」 vs 同章 §4.2 表（1★=+0%）与 §4.1 建议列（☑ A） | 星级乘区公式 | ✅ **已拍板（用户 2026-09-26）：取口径 A**——`1★` 为基线 ×1.00，`star≥1` 时 `×(1 + (star-1) × pct)` ⇒ 5★ 战斗 **+32%** / 修炼 **+20%**。产品 §4.2 的「5 星满 +40%/+25%」按此口径**不再采用**（其字面对应口径 B），登记为已否决项 |
| 5 | 产品 §15.3 要求历史条目含「格序」；实现体 8 字段无 slotIndex | 是否加 proto 字段 | **豁免**（D-13）：历史「按抽记条」，格序由结果页 DTO 数组下标承担，持久化层无消费方 |
| 6 | 白皮书 §3 杠杆 ⑦⑧ 已勾「采纳 +8%/+5%」，⑨⑩（突破补偿/回血）**两空** | 是否本批做 | ⑦⑧ 本批做（配置已定）；⑨⑩ **不做**（回血 G07 已落地；突破补偿属 M0/M2 待拍板） |
| 7 | 产品 §4.1「物品品阶概率」表为**四阶**（12/33/33/22），Q16 曾给**六阶**（1/3/10/34/32/20） | 品阶表 | **以四阶为准**（Q37「池内最高四阶」，配置 `itemRarityWeights` 即四阶且和为 100%） |

### 2.3 另两条上位不一致（非阻塞，登记备查）

- `HANDOVER-m1-remaining-3.md` §1.1 G16 行称「`catalog↔guard` 退役集 22」、G08 行称「24」——本轮实测 **catalog 24 == guard 24 双向零差集**（`scripts/action-catalog/*.mjs` 的 24 条 `id` 条目 vs `test/dispatch_guard_test.cpp` 的 `retired` 块 24 项），G08 的 24 为准。
- `recon-G05-G06-G08-G09.md` §X-1 把 `RecruitService.kt` 列为 G05 删除面（正确），但 §G09-8 又引它作 Kotlin RNG 先例（失效）。

---

## 3. 需求口径（做什么、按什么顺序）

### 3.1 单抽（`GACHA_PULL_ONCE` = 1871）——**消费序逐位钉死**

```
pullOnce(poolId):
  ① 前置校验（零 RNG、零写入）
     - poolId 存在且 enabled  ⇒ 否则失败信封 PoolNotFound / PoolDisabled
     - 池配置自洽（权重和=100、每类别 templateIds 非空 / itemSource 各品阶桶非空）
       ⇒ 否则失败信封 PoolMalformed（**必须前置**：见 D-11 原子性）
     - gameData.spiritStones >= pricePerPull ⇒ 否则 InsufficientSpiritStones
  ② spiritStones -= pricePerPull          （复用钱包扣减语义，禁自算余额）
  ③ pity = (gachaPityCounters[poolId] ?: 0) + 1
  ④ if pity >= pity.pullThreshold：
        tid = allTemplateIds[ rng.nextInt(allTemplateIds.size()) ]      // 1 次抽取；六选一
        addFragment(tid, pity.fragmentCount)                            // 零 RNG
        pity = 0; category = "pity"; isPity = true
     else：
        cat = weightedPick(categories, weightPct)                       // 1 次 nextInt(100)
        if cat.kind 是角色类：
            tid = cat.templateIds[ rng.nextInt(cat.templateIds.size()) ] // 1 次 nextInt
            addFragment(tid, 1)
        else：
            rarity = weightedPick(itemRarityWeights, weightPct)          // 1 次 nextInt(100)
            pool   = candidates(cat.itemSource).filter{ it.rarity == rarity }.sortBy(id)
            item   = pool[ rng.nextInt(pool.size) ]                      // 1 次 nextInt
            addItem(item, source="gacha_pull")                           // 溢出转邮件
        category = "character" | "item"; isPity = false
  ⑤ gachaHistory.pushFront(entry); truncate(HISTORY_RING_SIZE=50)
  ⑥ gachaPityCounters[poolId] = pity
  ⑦ 回执信封：status=success + {poolId, pricePaid, spiritStonesAfter, pityAfter, rows[]}
```

**硬约束**
- 🔴 **随机源 = 独立抽卡分区 `RngPartition::kGacha`(12) / Kotlin `RngPartition.GACHA`**（D-3 已拍板）：**禁止**取 `kSystem`；一次抽卡内部的所有抽取**全部**从该分区取序。
- `weightedPick` 口径必须两端逐位一致：`r = rng.nextInt(100)`；按**声明顺序**累加 `weightPct`，取第一个 `r < 累计和` 的项。权重和必须恰为 100（配置守卫已断言）。
- 品阶内候选必须 **`filter(rarity) → sortBy(id) 升序**（**禁止依赖数组/注册表迭代序**）——C++ `herbTemplates()` 的序是 JSON 数组序、Kotlin Registry 的序可能不同，不钉死就会两头抽到不同物品而 `Diff*` 判红。
- **失败路径零 RNG 消费**（校验全在 ②/③ 之前）：这是双臂等价与「SL 回档可复现」的前提。
- 池内 `maxRarity`=4 是**截断**：品阶 ≥5 不进寻访（配置侧已有守卫，代码侧本批补）。
- 单抽价 5,000 灵石（`GameConfig.Gacha.PRICE_PER_PULL`，配置同值）。

### 3.2 十连（`GACHA_PULL_TEN` = 1872）

- **一笔事务内顺序执行 10 次单抽语义**（共享同一 RNG 流与同一 `pity` 计数，跨十连连续）；一次性前置校验「余额 ≥ 10 × pricePerPull」并**一次性扣费**；不做差额部分抽取。
- 结果 10 格一次回执（`rows.size == 10`）；历史写 10 条（第 10 抽在最前）；保底格 `isPity=true`。
- **原子性实现方式**：`GameCore::execute` **没有 state 快照/回滚**（`src/execute_dispatch.cpp:2405-2420` 直进 handler，`game_core.cpp` 无 `state_ =` 回滚臂）⇒ 原子性**只能靠「前置校验全覆盖 ⇒ 中途无失败分支」**实现；校验必须在 ② 之前完成（见 D-11）。

### 3.3 保底（Q33 语义，不是「额外赠送」）

- 计数器 `map<poolId, uint32>` 0–9；`gachaPityCounters` 字段已存在但**全仓零写者**（本批首写）。
- 第 10 抽**本身**= 随机角色碎片 ×5，且**该抽不 roll 类别**；归属六选一（含已解锁/已满星）。
- `pickMode`：配置现为 `"random"`；其它取值（自选）**本批不实现**，非 `random` 时按 `PoolMalformed` 拒绝并登记（白皮书 §3③ 列为 G13 备选）。

### 3.4 碎片与升星（**复用 G08 单点，禁止第二写者**）

- 唯一写者：C++ `gacha_fragment.h::addFragment(gameData, templateId, count)`（`:63-97`，满 100 进 1 星、上限 5、满星后继续累加、0 星不落键）；Kotlin 回退臂 `GachaFragmentLedger.grant`（经 `GachaService.grantFragmentsLocally`）。
- `kFragmentsPerStar=100` / `kMaxStar=5` 已三向守卫（config ↔ `GameConfig.Gacha` ↔ `gacha_fragment.h:37-38`）⇒ 本批**只读**。
- 升星回执（`starBefore/starAfter/fragmentsAfter`）复用 `GachaGrantResult.Granted`（`GachaFacade.kt:72-91`），**禁改三态语义**。

### 3.5 解锁（1 星 → 入册，唯一入册口在 Kotlin）

- C++ 回执携带 `unlockedTemplateIds: [...]`：本抽使某模板由 **0 星 → 1 星**（即首次跨过第 1 个 100 门槛）时记入；保底/普通角色抽都可能触发。
- Kotlin 臂逐个调 `DiscipleService.instantiateTemplate(templateId)`（`DiscipleService.kt:137-168`）：查 `CharacterTemplateDb` → 限持判定（已有实例返回 `TemplateAlreadyOwned`，**视为已解锁、不重复入册、不报错**）→ 工厂 → `insertTemplateDisciple`（`:185-198`，内含 `guideCounters["disciplesRecruited"] +1` 与 `annualNewDisciples +1`）。
- **不得**在 C++ 计 `annualNewDisciples`（D-6）；**不得**新增第二个入册口（`DiscipleCreationPathGuardTest` 白名单）。
- **一致性兜底（D-7）**：`gachaStarMap[tid] >= 1` 且名册无该 `templateId` 的实例 ⇒ 读档/全量导入后补一次 `instantiateTemplate`（幂等）。判据只读两张既有账本，**不新增持久化字段**。

### 3.6 物品入库（统一入口 ＋ 溢出邮件，抽卡永不因满仓失败）

- 权威臂（C++）：`inventory.h::addMaterial` / `addHerb` / `addSeed(state, item, overflowMail, trackingSource, overflowMailSuppressed=false, merge=true)`（`:938-994` 段，含年度来源统计 `annualHerbBySource` 与溢出草稿）。
- 回退臂（Kotlin）：`InventorySystem.withTrackingSource("gacha_pull") { addMaterial/addHerb/addSeed(...) }`（**发放类**，**禁包** `withOverflowMailSuppressed`）。
- **来源名同批登记**：`OverflowMailSender.SOURCE_DISPLAY_NAMES` 加 `"gacha_pull" to "仙缘寻访"`（D-9 已拍板：ASCII 内部键 + 中文玩家显示名）。
- 物品构造字段须与 Kotlin 注册表口径一致（`id/name/rarity/quantity`，草药含 `category`，种子含 `growTime/yield`）——C++ 侧模板结构 `HerbTemplate`/`SeedTemplate`/`BeastMaterialTemplate` 含这些字段（⚠️ 实施时逐字段对照）。

#### 3.6.1 🔴 来源文案整体修订（用户 2026-09-26 拍板「一并改」）

**用户口径**：邮件/年报里的「来源」必须让玩家**明确得知出处**；**既有的 25 项写法一并改**。

**实测现状**（本会话逐字面量普查：Kotlin `withTrackingSource("…")` ＋ C++ `source = "…"`）：

| source | 实际使用处（实测） | 现显示名 | 新显示名（明确出处） |
|---|---|---|---|
| `battle` | `GameEngineWarRewardOps.kt:46,54` | 宗门战 | **宗门战** |
| `beast_world` | `GameEngineWorldBattleOps.kt:263` | 妖兽战 | **妖兽战场** |
| `cave_world` | `GameEngineWorldBattleOps.kt:316,330,344` | 洞穴战 | **洞穴战场** |
| `beast_raid` | `ExplorationService.kt:326` | 妖兽侵袭 | **妖兽侵袭** |
| `patrol` | `PatrolBattleSystem.kt:548` | 巡视塔 | **巡视塔** |
| `forge` | `ProductionProcessor处理Ops1.kt:131` | 锻造 | **锻造台** |
| `alchemy` | `ProductionProcessor处理Ops1.kt:105` | 炼丹 | **丹房** |
| `spirit_field` | C++ `spirit_field.h:125,134,169,178` | 灵田 | **灵田** |
| `storage_bag` | `InventoryFacadeImpl.kt:722` | 储物袋 | **储物袋开启** |
| `merchant` | `InventoryFacadeImpl.kt:472`、`AutoBuyService.kt:230` | 商人 | **商人交易** |
| `redeem` | `RedeemCodeService.kt:207,521` | 兑换码 | **兑换码** |
| `mail` | `MailAttachmentDistributeOps.kt:266` | 邮件 | **邮件附件** |
| `trial` | `HeavenlyTrialService.kt:309`、`CultivationEventProcessor.kt:204` | 天道试炼 | **天道试炼** |
| `sect_level` | `GameEngineSectLevelOps.kt:284` | 宗门等级 | **宗门升级** |
| `sect_trade` | `DiplomacyService.kt:759` | 宗门贸易 | **宗门贸易** |
| `quest` | `CultivationEventProcessor.kt:200`、`GameEngineMissionOps.kt:143` | 任务 | **任务奖励** |
| `building` | `BuildingService.kt:483,611,655` | 建筑 | **建筑产出** |
| `confiscate` | `InventoryFacadeImpl.kt:151` | 储物袋回收 | **没收弟子物品** |
| `secret_realm` | `SecretRealmService.kt:1238` | 远古秘境 | **远古秘境** |
| `disciple_death` | `DiscipleLifecycleProcessor.kt:106`、`YearSettlementResidualExecutor.kt:76` | 弟子陨落归还 | **弟子遗物归还** |
| `gacha_pull`（**本批新增**） | 本批 | — | **仙缘寻访** |

**删除（实测 0 使用，仅存在于 map）**：`cave`、`disciple_reward`、`disciple_unequip`、`disciple_expel`
（`unknown` 为 `sourceDisplayName()` 的兜底键，**保留**）。
⚠️ 连带面：`OverflowMail.kt:9` 的 KDoc 用 `"cave"` 作举例、`BattleLogDialogs.kt:272/296/308/318` 另有一张日志来源表含 `disciple_expel` —— **同批对齐**（同一语义用同一文案；死条目一并清）。

**邮件文案根因修复**（现标题模板 `【仓库已满】${sourceName}奖励转入邮件`，`OverflowMailSender.kt:416`）：
「归还」型来源（弟子遗物/没收）被套上「奖励」二字语义错误 ⇒ 改为
- 标题：`【仓库已满】来自「{sourceName}」的物品已转入邮件`
- 正文首行：`以下物品来自「{sourceName}」，因仓库已满改为邮件送达。`

**同批必须改的测试**（`OverflowMailSenderTest.kt`，本会话实测）：
`:119` 标题断言、`:120` 正文包含断言、`:139` 「未知」标题断言、`:144`/`:145` 显示名断言；
并把现有单向守卫（「代码字面量 ⊆ map key」）**加一条反向断言**（map 中每个 key 都必须有生产使用，`unknown` 与批内新增白名单除外）——否则死条目会再长回来。
**双 changelog 必须写这条玩家可见文案变更**（`CHANGELOG.md` ＋ `assets/changelog_entries.json`）。


### 3.7 寻访历史（环缓冲）

- 字段沿用 8 项：`poolId/category/templateId/itemId/rarity/count/isPity/gameMonthIndex`（`models.h:1131-1140`、`GachaHistoryEntry.kt:13-26`）。
- **新在前**（下标 0 最新）；写入后 `truncate` 到 `GameConfig.Gacha.HISTORY_RING_SIZE`(50)。当前全仓**零写者、零截断**实现（本批首写）。
- `category` 取值域：`character` / `item` / `pity`（`GachaHistoryEntry.kt` KDoc 口径）。
- `gameMonthIndex = gameYear * 12 + gameMonth`（与 `usage.recruitedMonth` 同式，`DiscipleService.kt:164`）。

### 3.8 星级乘区 `StarZone`（✅ 口径 A：`1★` 为基线，`×(1 + (star-1) × pct)`）

- 战斗：`starMult = 1 + (star - 1) × STAR_BATTLE_PCT_PER_STAR`（`pct = 0.08`，`GameConfig.kt:238`，配置 `gachaDefaults.starBattlePctPerStar`）
- 修炼：`starMult = 1 + (star - 1) × STAR_CULT_PCT_PER_STAR`（`pct = 0.05`，`GameConfig.kt:239`）
- **星级表（口径 A 的唯一正确读法）**：

  | star | 0（未解锁 / 存量旧弟子） | 1（解锁即 1 星） | 2 | 3 | 4 | 5（满） |
  |---|---|---|---|---|---|---|
  | 战斗乘区 | ×1.00 | **×1.00** | ×1.08 | ×1.16 | ×1.24 | **×1.32** |
  | 修炼乘区 | ×1.00 | **×1.00** | ×1.05 | ×1.10 | ×1.15 | **×1.20** |
  | 相对 1 星 | — | 基线 0 | +8% / +5% | +16% / +10% | +24% / +15% | **+32% / +20%** |

- ⇒ **开局送的周明（1★）不带任何加成**（口径 A 的直接好处：不凭空拔高开局名册，且**不扰动任何既有属性/战力期望表**）。
- `star` 由 `templateId` 反查 `gachaStarMap`（**稀疏**：0 星无键）；`templateId=""`（存量旧弟子）或未解锁 ⇒ `star=0` ⇒ **恒 ×1.00**。
- 落点（**同名同参、双端对拍**）：Kotlin `DiscipleStatCalculator.CultivationSpeedZones`（`DiscipleStatCalculator.kt:82` 定义，`DiscipleStatCalculator属性Ops4.kt:151-213` 构建）＋ `SectCombatPowerCalculator`（`SectCombatPower` 口径，⚠️ 路径 `android/core/engine/.../core/engine/SectCombatPowerCalculator.kt` 待实施时确认）；C++ `disciple_stats.h`（⚠️ 子代理给 `:342` 附近）＋ `sect_power.h`（⚠️ 子代理给 `month_settlement.h:822` 相关链）。
- 命名乘区（禁止散落 `if`）：把星级加成作为 `CultivationSpeedZones` / 战斗乘区数据类的**命名字段**（架构 `§乘区法`）。
- 注意：本批**不再**新增任何「资质/特质/血炼」项（G04 已删）；`innerPos/outerPos`（职位特质）恒 0，形参保留（口径 15）。
- ⚠️ **白皮书 `m0-economic-whitepaper.md:143-144` 正文写「采纳口径 B」与其 §4.2 表/建议列（A）矛盾**；本批以**用户 2026-09-26 拍板 = A** 为准，并在 `report-G09.md` 登记「白皮书正文该句应改为 A」的文档债（G12 文档收口批处理）。

---

## 4. 决策（D-1…D-18，含依据）

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | 🔴 **扩 `data_inject.h` 注入 `db.gachaPools` 与 `db.characterTemplates`**（新增 `gamecore/data/gacha_pool_db.h` 模板结构 ＋ `GachaPoolTemplate`/`CharacterTemplate` 存取器 ＋ `AppliedCounts.gachaPools/characterTemplates` ＋ `data_store_test` 三层守卫）。**不走「Kotlin 在 params 里传池快照」** | ① C++ AUTHORITATIVE ＋ `architecture.md` T-CPP-2「静态数据 codegen ＋ RegistryGuard，禁双端各抄」；② 传快照会让「概率由 Kotlin 决定」并制造第二真源；③ 与既有 7 张表同形制（`data_inject.h:55-112`）；④ 桌面/iOS 腿独立可跑。<br>⚠️ **陷阱**：该注入器语义是「段缺失⇒跳过、**段在而非数组/空 ⇒ 整体 `return false`**」（`data_inject.h:19-21,56`）⇒ 落空数组会让 **10 张表全部静默落内联兜底**（G04 实测红线）。必须：配置恒非空（现 1 池/6 模板）＋ `data_store_test` 断言「有段即非空且 `AppliedCounts` 与段长一致」 |
| **D-2** | **物品两级 roll 与 item id 全在 C++ 完成**；C++ 直接 `inventory.h::addXxx` 入库并产出溢出草稿。**不采用 `storage_bag_tx.h` 的「C++ 只 roll kind、Kotlin 物化」半程方案** | C++ 侧**已有** herbs/seeds/beastMaterials 三表（`data_inject.h:62-83`）与含溢出邮件的入库臂（`inventory.h:938-994`），生产先例 `spirit_field.h:91-188`；半程方案会让 Kotlin 再消费一次 RNG（`storage_bag_tx.h:11-14` 正是在修这个缺陷），并制造临界契约。<br>代价：Kotlin 回退臂须**逐字同式**复刻（含 `sortBy(id)`），由 `DiffGachaPullTest` 双腿对拍锁死 |
| **D-3** | ✅ **已拍板（用户 2026-09-26）：抽卡走独立分区 `RngPartition::kGacha = 12`**（对标结论见 [`BENCHMARK-gacha-rng-partition.md`](BENCHMARK-gacha-rng-partition.md)） | ① 🔴 **本仓已有完全同形的先例**：`RngPartition.kt:56-63` 的 `CHAT(10)` 明文写「用户时序驱动的独立流——**不与既有分区共用**：抽取的插入时机由玩家行为决定，混入任何结算分区都会扰动该分区的既有抽取序（红线 1）」；`RESIDUAL(11)` 追加时走的也是同一套「追加 id + C++ 播种 + 守卫登记 + 老档缺失键重播」流程 ⇒ 抽卡（玩家点击驱动、次数无上限）与 CHAT 同类。② 外部对标一致：Unity 官方明文「让一个随机源的种子与另一个不同、可有任意多实例」，Godot 把分流绑在实例 API 上，Unreal `RandomStream` 每实例持种子；CK3 跨平台 OOS / Epic「同种子仍失步」/ Factorio 线程确定性三例同因（消费次数成隐式全局契约）。③ 头部抽卡产品是「逐卡池类型独立、跨设备持久化的保底状态」（HoYoverse 官方帮助页）⇒ 官方只承诺概率与保底语义，从不承诺共用一条流。<br>🔴 **实测修正（推翻本任务书初版表述）**：`DeterminismProbe`（`determinism_probe.h:152-227`）哈希的是**行为 transcript**，**不含 `rngStates` 映射**；现有金黄夹具**不做抽卡** ⇒ **两个方案在现有套件下都不扰动既有金黄**，本决策**不是**由红点数量驱动，而是结构性的（耦合 / 可解释性 / 公平性 / 并行前提）。<br>🔴 **不采纳**「改用哈希派生种子」的建议：STS2 的泄漏根因是 C# `System.Random` 对种子近似线性，本项目是 PCG-XSH-RR ⇒ `seed + id` 不产生可互推流；改派生会作废全部既有分区的随机值，收益为零。<br>**改动面（本会话已直读核实）**：<br>① `include/gamecore/rng/rng_manager.h:49-62` 枚举追加 `kGacha = 12`；`:69` 的 `kMaxPartitionId` 由 `kResidual` 上移到 `kGacha`（**该常量上方 `:66-68` 的注释已写明「新增分区时写死的守卫会静默拒绝新 id，MISSION(8) 曾因此在 AUTHORITATIVE 下恒返回 0」**）；`:75-94` 的 `initSystemSeed` 追加 `partitions_[kGacha] = DeterministicRng::fromSeed(seed + 12)`（与既有 12 条逐一同式）。<br>② `android/core/engine/src/main/java/com/xianxia/sect/core/util/RngPartition.kt` 枚举追加 `GACHA(12)`（`inSnapshot` 默认 `true`、`isLocal` 默认 `false` ⇒ AUTHORITATIVE 下实例化为 `NativeBackedRng` 逐 roll 委托 C++，与抽卡「C++ AUTHORITATIVE」一致）；文件头 `:8-12` 已写明「新增分区只能追加新 id，且必须同步 C++ 枚举 + 播种 + 登记」。<br>③ `RngSourceGuardTest` 的 `registeredPartitionIds` 登记（不登记即判红）。<br>**老档兼容**：无 12 号键 ⇒ 按 `systemSeed + 12` 确定性重种（与 MISSION(8)/CHAT(10)/RESIDUAL(11) 同款恢复语义，`RngPartition.kt:87-90` 有明文范式）。**零迁移、零新字段**（`rngStates` 本就是 `map<id, state>`）。<br>🔴 **必须同批排查的连带面**：新增分区会让 `syncRngStates` 导出的 `rngStates` **多一个键** ⇒ 检查所有对 `rngStates` **键集/条数**做断言或两侧比对的测试与守卫（`RngSourceGuardTest`、`BaselineFieldCoverageGuardTest`、`Diff*` 族、`SaveDataDirectSerializationTest`），避免 A 类红。<br>**守卫**：① 一次抽卡的随机消费次数 == 常量；② 抽卡前后 `rngStates` 快照差分**只含 12 号键**；③ 分区状态存取往返一致；④ 双端同分区同序（`DiffGachaPullTest`）；⑤ `kMaxPartitionId` 上移后 `rngNextInt(12)` 不再被 JNI 守卫静默拒绝（回归 MISSION(8) 教训） |
| **D-4** | **两段式入账：`gacha_tx` 只做「校验→扣费→roll→碎片/星/历史/物品」；不得重写碎片账本与升星** | 复用 `gacha_fragment.h`（G08 单点）；验收③ |
| **D-5** | **解锁描述符化**：C++ 回执出 `unlockedTemplateIds`，Kotlin 物化 | C++ 无 `DiscipleTables`，也**不应**有（Kotlin 侧模板库/弟子表是权威）；与 §2.2#2 的实际边界一致 |
| **D-6** | 🔴 **作废 `TASKBOOK-G08.md` D-16**：`annualNewDisciples` / `guideCounters["disciplesRecruited"]` 的**唯一计数点 = Kotlin `insertTemplateDisciple`**（`DiscipleService.kt:192-196`），C++ 不接、不预留 | 实测该函数已在同一 `updateAndReturn` 事务内自增两处；C++ 再计 = 双计（G08 D-16 的前提「C++ 解锁分支」不成立） |
| **D-7** | **解锁一致性用「读档/导入后幂等补齐」**，不新增持久化字段、不做迁移 | 判据 = `gachaStarMap[tid] >= 1` ∧ 名册无该 `templateId`；补齐走同一 `instantiateTemplate`（限持判定天然幂等）。代价：需一个读档/全量导入后的补齐点 ＋ 幂等测试 |
| **D-8** | **`gachaPityCounters` / `gachaHistory` 由 C++ 首写**（字段与编解码 G01 已齐）；镜像走既有泛化脏段 | `dirty_tracker.h:15-25,43-45` 是「gameData 字段级 JSON diff」通用机制；`GameDataFieldPatch.kt:327-337` 已有四字段 decode 臂；`gameview_encode.cpp` 对 `gameData.` 前缀全字段自动入流（⚠️ 子代理实测），**零 proto 改动** |
| **D-9** | ✅ **已拍板**：**来源名取 ASCII 内部键 + 中文玩家显示名**——C++ `trackingSource = "gacha_pull"`，Kotlin 回退臂 `withTrackingSource("gacha_pull")`，`SOURCE_DISPLAY_NAMES["gacha_pull"] = "仙缘寻访"`；**且既有 25 项显示名一并修订**（用户口径「让玩家明确得知出处」），细则与逐条文案见 §3.6.1 | 既有 25 项**全为 ASCII 键 + 中文显示值**（`OverflowMailSender.kt:96-122`），玩家可见的是 value ⇒ 满足产品 §4.1 的「仙缘寻访」意图，且不破 25/25 惯例 |
| **D-10** | **结果 DTO 只带 id，不带资源键**：`GachaPullRow(templateId?, itemId?, category, rarity, count, isPity)` | 资源键（`avatarKey`/`portraitKey`）由 G11 经 `CharacterTemplateDb.byId(tid)` 查（G08 已交付）；DTO 带键会造成第二真源。**但须在 DTO KDoc 里钉死档位口径**：小头像位读 `avatar_<id>`（512 档）**不读** `portraitRes`（1024 档全身像）——G08 移交项 `report-G08.md` §七-7 |
| **D-11** | 🔴 **原子性靠「前置校验全覆盖」**：十连在扣费前须校验 10 抽全程不会失败（池自洽性、余额、`pickMode`、每类别候选桶非空） | `GameCore::execute`（`execute_dispatch.cpp:2405-2420`）**无快照/回滚**，handler 直接改 `state_` ⇒ 中途失败会留半成品状态。此点与 `storage_bag_tx.h:33-34`「失败零抽取」同精神，但 G09 还多了「写入」面，故必须前置 |
| **D-12** | **本批零 Room 迁移、零新 `@ProtoNumber`**（Room 仍 v59） | 四本账本与 `templateId` 列自 v54 就在库（`GameDatabaseMigrationsV54.kt:21-33`）；历史「格序」豁免（D-13）；解锁补齐不落新字段（D-7）。⚠️ 别照「新批就 bump 版本」的惯性白造一条空迁移（G08 已踩过同一坑：`HANDOVER-3` §8.D-5 失真） |
| **D-13** | **不给 `GachaHistoryEntry` 加「格序」字段**（豁免产品 §15.3 的表述） | 历史语义是「按抽记条」（Q40）；格序在结果页由 DTO 数组下标承担，持久化层无消费方；加字段要付「proto 只增 + 双端编解码 + 集合转换器 + 迁移纪律」的成本（`ProtoNumberUniquenessTest`/`BaselineFieldCoverageGuardTest`） |
| **D-14** | **星级百分比与历史环大小在 C++ 用 `inline constexpr`，不注入顶层 `gachaDefaults`**；由 `CharacterTemplateGuardTest` 扩成**三向**断言 | 与 G08 已交付的 `gacha_fragment.h:37-38` 同形制（`data_inject.h` 只吃 `db.*` 段，注入顶层键要改注入器结构，属超范围）；三向守卫已能防漂移（`CharacterTemplateGuardTest.kt` 现有 `gachaPools[0] ↔ GameConfig.Gacha ↔ gacha_fragment.h` 先例；`starBattlePctPerStar ↔ STAR_BATTLE_PCT_PER_STAR` 已在 `:308-311`，本批补 C++ 腿） |
| **D-15** | **补两条运行期守卫**：① 运行期 `gachaFragmentCounts.keySet ⊆ characterTemplates.ids`、`gachaStarMap.keySet ⊆ characterTemplates.ids`；② `gachaPityCounters.keySet ⊆ gachaPools.poolIds` | 产品 §15.3 守卫 2/3 的**代码侧**缺口（配置侧 G08 已补 key 域封闭，运行期账本 key 仍无守卫）⇒ 旧档污染/手改配置才不会被静默放过 |
| **D-16** | **Kotlin 回退臂的 roll 不得调用 `generateRandomHerb/Seed/Material` 的默认实参** | 这些函数的 `random` 形参默认值是 `kotlin.random.Random`（`RngSourceGuardTest` 的⑤类默认值陷阱，core/domain 上限 19 / core/engine 上限 5）。回退臂应走「候选表 `filter(rarity).sortBy(id)` + 序号取值」，序号来自 **D-3 的抽卡分区**（若 P-4 未点头则暂用 `RngPartition.SYSTEM`，该处一行） |
| **D-17** | **新 ActionId 编号 = 1871 `GACHA_PULL_ONCE` / 1872 `GACHA_PULL_TEN`**（预分配段 1870–1889）；`kAllActionIdsCount` 199→**201**、maxId 1870→**1872** | 1870 已由 G08 实裁；段内余量 1871–1889（`gacha.mjs:11` 段声明）。新号必须**分派可达**（`dispatch_guard_test.cpp` 对非 retired 号逐号断言本域 handler 认领），否则守卫红 |
| **D-18** | **本批交付 `report-G09.md` 时，B 类清单必须给「抽卡随机消费」的因果链**（走独立分区则须证明既有三条 B 类红**逐条同名、`actual` 不变**；若走 SYSTEM 则须列明被平移的既有消费点与 `actual` 指纹） | §6.1 红线：金黄只在 G10 重录；G09 属**新增** RNG 消费，风险等级高于 G15/G16/G08 三轮的「零新增」 |

---

## 5. 文件面与切片（≤10 文件/片；「T」=C++ 面、「A」=Kotlin main、「c」=测试面）

> 依据 `EXECUTION-PROTOCOL.md` §5 与 `HANDOVER-3` §3.2：**≤10 文件/片**、分片并行期**禁止子代理跑 gradle/cmake**、
> 主线程终树复跑全部门禁。G03 的 5 片全部撞 150 轮上限的教训（`HANDOVER-3` §3.2）必须遵守。

| 片 | 允许改（文件面） | 禁止改 | 自检项 |
|---|---|---|---|
| **T-09a** 卡池数据面 | `include/gamecore/data/gacha_pool_db.h`（**新**）、`include/gamecore/data/data_inject.h`、`test/data_store_test.cpp` | Kotlin、`models.h`、派发链 | 段缺失⇒跳过、段在而空⇒`return false`；`AppliedCounts` 计数与段长一致；`db.gachaPools` 1 条、`db.characterTemplates` 6 条 |
| **T-09b** 抽卡本体 | `include/gamecore/system/gacha_tx.h`（**新**）、`src/dispatch_gacha.cpp`、`scripts/action-catalog/gacha.mjs`、生成物 `action_ids.h`（跑生成器，禁手改） | `execute_dispatch.cpp`（已接线，**0 改**）、`gacha_fragment.h`（只读复用） | `node scripts/gen-action-ids.mjs` → 201/1872；`git diff --exit-code` 零漂移自证（regen 前后自比） |
| **T-09c** 星级乘区（C++） | `include/gamecore/system/disciple_stats.h`、`include/gamecore/system/sect_power.h`（⚠️ 及 `month_settlement.h` 中战力链，实施时 grep 定位） | Kotlin、`gacha_tx.h` | 命名乘区、`star=0 ⇒ ×1.00`；与 Kotlin 同式 |
| **T-09d** C++ 测试 | `test/gacha_pull_test.cpp`（**新**）、`test/gacha_tests.cmake`、`test/CMakeLists.txt`（**仅当**需新增源清单；G08 已接好则 0 改）、星级乘区相关 GTest | Kotlin | 单抽/十连/保底/边界/零 RNG 差分/池自洽拒绝；进 `gacha_tests.cmake` 唯一追加点 |
| **A-09a** DTO 与转发 | `domain/gacha/GachaFacade.kt`、`domain/gacha/GachaFacadeImpl.kt`、`nativebridge/GachaNativeTx.kt`、生成物 `nativebridge/ActionIds.kt` | C++、`GachaFragmentLedger` | `GachaPullResult.Success` 分支穷尽；`pullOnce/pullTen` 双臂；`tryPullOnce/tryPullTen` 复用私有 `tx()`（零新增 `external fun`） |
| **A-09b** Kotlin 回退臂 | `domain/gacha/GachaPullLedger.kt`（**新**，逐字同式 roll）、`domain/gacha/GachaService.kt` | C++、`GachaFragmentLedger.kt`（复用不改） | `updateAndReturn` 单事务；`sortBy(id)`；**抽卡分区**（D-3）；失败零消费 |
| **A-09c** 入库来源 ＋ 解锁 | `service/OverflowMailSender.kt`（**整表重写 ＋ 标题/正文模板**，§3.6.1）、`core/domain/.../overflow/OverflowMail.kt`（KDoc 举例）、`feature/game/.../dialogs/BattleLogDialogs.kt`（日志来源表对齐）、解锁调用点（`GachaFacadeImpl` 或 `GameEngine{LoadData,Gacha}Ops`）、读档补齐点 | C++ | `SOURCE_DISPLAY_NAMES` 键与字面量逐字一致；新显示名全部为玩家可读出处；4 条死条目删除；`instantiateTemplate` 唯一入册口 |
| **T-09e** 抽卡 RNG 分区 | `include/gamecore/rng/rng_manager.h`、`core/engine/.../util/RngPartition.kt`、`core/engine/src/test/.../architecture/RngSourceGuardTest.kt` | 既有分区 id 语义、`kSystem` 的消费点 | 枚举 `kGacha = 12` / `GACHA(12)` 双侧同名同 id；`initSystemSeed` 补 `seed + 12`；`kMaxPartitionId` 上移到 `kGacha`（不上移会被 JNI 守卫静默拒绝，`rng_manager.h:66-68` 有 MISSION(8) 先例注释）；老档缺键按 `seed+12` 重种；`rngStates` 键集变化引发的连带测试（见 D-3） |
| **A-09d** 星级乘区（Kotlin） | `domain/disciple/DiscipleStatCalculator.kt`、`domain/disciple/DiscipleStatCalculator属性Ops4.kt`、`SectCombatPowerCalculator.kt`（⚠️ 路径待 grep）、`GameConfig.kt`（**只读**，如需常量） | C++ 之外 | 命名乘区；`star=0 ⇒ ×1.00`；与 C++ 同名同参 |
| **A-09e** 运行期守卫 | `GachaConfigGuardTest.kt`（扩三向 ＋ key 域）、`CharacterTemplateGuardTest.kt`（补 C++ 腿） | 生产源 | 断言消息带操作指引；不做 `assumeTrue` |
| **c909-a/b** Kotlin 测试 | `DiffGachaPullTest.kt`（**新**，照 `DiffGachaFragmentTest.kt` 骨架）、`GachaPullGuardTest.kt`（**新**）、`RedeemCodeServiceTest.kt`（`RecordingGachaFacade` 适配 `Success` 分支） | 生产源、C++ | 双臂对拍同一 golden 向量；无 JNI 时跳过语义与既有 Diff 族一致 |
| **c909-c** 期望表同步 | 星级乘区引起的弟子属性/战力期望表（实施时以红点为准，逐文件登记） | — | 每改一处登记「为什么」 |
| **主线程** | `docs/ui-read-surface.md` §2.1 登记（4 个 gacha 字段 ＋ 本批新增读面）、双 changelog、`report-G09.md`、门禁复跑、单次提交 | — | 见 §6 |

**共享面（本批独占，禁并行）**：`scripts/action-catalog/gacha.mjs`、两份生成物、`src/dispatch_gacha.cpp`、
`include/gamecore/system/gacha_tx.h`、`gacha_pool_db.h`、`data_inject.h`、`test/gacha_tests.cmake`。

---

## 6. 门禁清单（主线程终树同轮重跑，判据是**命令自己的输出原文**）

```powershell
# 0) 环境：llvm-mingw bin + x86_64-w64-mingw32\bin + Android cmake\3.22.1\bin 三段前置（缺一即 STATUS_DLL_NOT_FOUND）
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;" +
            "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;" +
            "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;" + $env:PATH

# 1) 桌面 C++（工作目录 = android/app/src/main/cpp/gamecore/build/desktop-test）
cmake --build . ; ctest                     # 基线 1401 总 / 1398 过 / 3 红（三条 B 类）
ctest -R SceneEquivalence                   # 13/13（图集/生成物变更批必查）
ctest -R Determinism                        # DigestMatchesGoldenBaseline 允许红；DigestIsStableAcrossRepeatedRuns 必须 Passed

# 2) 桌面 JNI 重建（触碰 C++ 必跑；🔴 必须 pwsh 不是 powershell；验产物 mtime/体积变化）
pwsh -File scripts/build-desktop-jni.ps1    # 工作目录 = 仓库根；基线 .so = 8542208 字节

# 3) Kotlin 全门（工作目录 = android）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" `
  detekt lintRelease --console=plain        # 基线：JUnit 7391/0/0/18、detekt 六模块 0 error、lint 36 警告全预存

# 4) 代码生成门禁（工作目录 = 仓库根）
node scripts/gen-action-ids.mjs             # 目标 201 动作 / maxId=1872；零漂移 = regen 前 cp 两份 → regen 后与工作树自比
node scripts/gen-game-data.mjs --check      # 本批应仍为 sha256 035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef
node scripts/check-jni-count.mjs            # 必须仍 86/86（零新增 external fun）
node scripts/check-agent-instructions.mjs   # EXIT=0；预存告警 2 条（规则③ 1 处、规则⑤ 37890 字节），本批不得增加

# 5) catalog↔guard 退役集（G09 只增号，退役集应仍 24 == 24 双向零差集）
#    判据：先切出 dispatch_guard_test.cpp 的 `std::set<int32_t> retired = {` … `};` 块，再取 action::([A-Z0-9_]+)
```

**提交前**：`git status` 只留本批改动；`atlas-rgba-manifest.json` / `sprite-uid-map.json`
等构建副产物一律 `git checkout --` 还原（G08 实测：`lintRelease` 也会写脏 `atlas-rgba-manifest.json`）；
双 changelog 用**编辑工具**改（脚本重排整个文件会被审阅打回，G04 实证），改完 `node -e "JSON.parse(...)"` 校验。

---

## 7. 拍板记录（用户 2026-09-26）／登记项 / 剩余待决

### 7.1 ✅ 已拍板（6 项，本任务书已按此改写，执行者直接照做）

| # | 议题 | 拍板结果 | 落点 |
|---|---|---|---|
| P-1 | 星级乘区口径 | **口径 A**：`1★` 基线 ×1.00，`×(1 + (star-1) × pct)` ⇒ 5★ 战斗 **+32%** / 修炼 **+20%**；**不采用**产品 §4.2 字面的 +40%/+25%（那是口径 B） | §2.2#4、§3.8 |
| P-2 | 入库来源文案 | **ASCII 内部键 + 中文玩家显示名**；且**既有 25 项显示名一并修订为「玩家明确可知的出处」**，邮件标题/正文模板同步去掉对「归还」型来源不适用的「奖励」二字 | §3.6.1、D-9 |
| P-3 | 历史条目「格序」字段 | **不加**（豁免产品 §15.3 字面）——历史按抽记条，格序由结果页 DTO 数组下标承担 | D-13 |
| P-4 | 抽卡随机源 | ✅ **已拍板：走独立分区**（`RngPartition::kGacha = 12`）——理由与改动面见 D-3；本仓 `CHAT(10)` 就是「用户时序驱动必须独立分区」的现成先例 | D-3、T-09e |
| P-5 | 保底归属 | **六个角色全随机**（与 Q38 一致） | §3.3 |
| P-6 | 突破补偿 `breakthroughCompBonus` | **不补**（白皮书 §3 杠杆 ⑨ 记为「不采纳」，本批不做） | §2.2#6 |

### 7.2 剩余待决（不阻塞开工）

1. 🟠 **历史条目是否要「格序」**：本批已按 P-3 豁免；若后续产品要求按 §15.3 字面落，需 `@ProtoNumber(9)` ＋ 三端 ＋ 迁移纪律（G11 图鉴/历史页会用到）。
2. 🟠 **保底自选（`pickMode != "random"`）**：白皮书 §3③ 列为 G13 备选；本批对非 `random` 取值按 `PoolMalformed` 拒绝并登记。
3. 🟠 **防回档是否要加「存档外不可回退量」**：对标结论是**单机不根除**（无可信第三方），保留「保底计数随存档回退」即可；如需彻底封堵，代价是放弃这部分对拍确定性（D-3 备注）。
4. 🟠 **来源文案的中文措辞定稿**：§3.6.1 的逐条新显示名已按实测调用点给出，实施时按游戏内界面名逐条核对；如有与当前 UI 用词不一致的，以 UI 为准并回写本表。

### 7.3 跨批登记（G10 承接）

5. 🔴 **B 类清单与 `DeterminismProbe.actual`**：独立分区落地后须实测证明三条既有 B 类红**逐条同名且 `actual` 不变**（本会话已给判据：probe 不哈希 `rngStates`，且现有夹具不做抽卡）；**一律不重录**（唯一窗口 G10）。
6. 🔴 `RngSourceGuardTest` 的登记表需同步（新调用点/分区使用计数漂移）；`docs/rng-source-inventory.md` 的整行盘点仍归 G10。
7. 🔴 `docs/ui-read-surface.md` §2.1 补登 `gachaFragmentCounts`/`gachaStarMap`/`gachaPityCounters`/`gachaHistory` 四行（事实已在协议面可传，文档未登记）。
8. 🔴 **文档债**：`m0-economic-whitepaper.md:143-144` 正文写「采纳口径 B」与已拍板的 A 矛盾 ⇒ G12 文档收口批改正（本批只登记）。
9. 🟠 `scripts/build-desktop-jni-linux.sh` 与 `.ps1` 的源清单分歧（🔴 若本批新增 `.cpp` 必须两处同加；纯头文件方案则 0 改）——⚠️ 子代理报 Linux 腿缺 `gameview_encode.cpp`，属预存问题，登记不改。
10. 🟠 `CI .github/workflows/ci.yml` **只跑 `check-jni-count`、不跑 `gen-action-ids`** ⇒ 生成物漏提交只在干净检出时炸（本批必须自己跑 regen 并自证）。
11. 🟠 `GachaDelegate` 全仓**零实例化**（`GameViewModel` 无 gacha 字段）⇒ G09 完成后仍无生产入口，验收只依赖 GTest + Diff（G11 接线）。⚠️ 勿在 G09 顺手接 UI。
12. 🟠 来源文案修订的连带面：`OverflowMail.kt:9` KDoc 举例、`BattleLogDialogs.kt:272/296/308/318` 日志来源表（含死条目 `disciple_expel`）同批对齐（§3.6.1）。

---

## 8. 一句话给执行者

**G09 的新增面只有三件：把 `db.gachaPools` 注入 C++（D-1）、写 `gacha_tx.h` 的 roll/保底/历史/物品两级 roll 与 `dispatch_gacha.cpp` 两个 case（D-2）、
新增独立抽卡随机分区 `GACHA = 12`（D-3）并接上抽卡消费；再加星级乘区双端同名同参（§3.8，**口径 A**）；
外加一件用户新加的收尾：**来源文案整体修订**（§3.6.1，既有 25 项显示名一并改成玩家明确的出处，含邮件标题模板）；
碎片与升星（`gacha_fragment.h`）、解锁入册（`DiscipleService.instantiateTemplate`）、弟子计数（`insertTemplateDisciple`）、
入库溢出（`inventory.h::addXxx`）、镜像通道（泛化脏段）**全部已存在，一律复用，禁止另开第二写者**；
本批零 Room 迁移、零 proto 变更、零新增 JNI 导出，金黄一律不重录。**
