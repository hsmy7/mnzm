# report-G10 · RNG 对拍基线重录 + 全量回归收口 + 死代码 grep 清零 + 活文档收口

> 派工真源：[`TASKBOOK-G10.md`](TASKBOOK-G10.md)；开工时点 2026-09-26，G11 已收官（`6186e3943` 工作树）。
> 本报告的每个数字都取自**本会话同轮命令输出**，未跑的一律显式登记为未做（§8）。
> 会话纪律遵守：只实施本批、不自登记 accepted、`git add` 一律明确文件名。

---

## 1. 结论一页速览

| 项 | 结论 |
|---|---|
| 状态 | **四任务（A 重录 / B 回归 / C 死码 / D 文档）全部落地 + 本机可得的门禁全绿**；真机面不适用本批（无新 UI） |
| 任务 A | 🔴 唯一窗口用掉：探针摘要**两处同批**改 `0x490e8dc522e12921` → **`0xb4f3c6912207f597`**；金序列两条 18 个期望值按实测定值；`kProbeVersion` / `advancePhaseBaseline` / `gacha_tx.h` / `seed + 12` **全部未动**（§2 证据） |
| 任务 C | §5.1 死码表逐条归零（删前逐一按名 grep 零命中贴证）；`RngSourceGuardTest` 上限对齐实测（假绿消除，判别力红证实测）；`docs/rng-source-inventory.md` §2/§3/§8.2 全表重盘 |
| 任务 D | §6.1 活文档清单逐条完成（5 份 + inventory）；§6.2 保留清单未动 |
| 硬门 | 零 Room 迁移（仍 v59）、零 `@ProtoNumber`、零 ActionId 变更（201/1872）、零新 JNI 面（86/86）、零 protocol 变更 |
| 🔴 唯一裁决点 | `GameNotification` 变体清零后**后端通道管线的去留**（D-3 只授权删变体+UI 分支；管线保留为休眠预留，整体退役待拍板，见 §8-1） |

---

## 2. 任务 A：RNG 基线重录（唯一窗口）

### 2.1 开工固证（§3.1 实测）

| 项 | 实测值 |
|---|---|
| 分支 / HEAD | `feat/gacha-m0-m1` / `6186e3943` |
| 树况 | `docs/gacha-watch/dispatch-ledger.md` 一处 **diff 为 0 行的行尾幻影改**（看护通道产物，非他批在途代码，本批不触碰）＋ `docs/research/`×2 未跟踪（任务书允许） |
| `ctest -N` | **1437**（= G11 基线，零增减） |
| 探针红原文 | `actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921`——与 G11 交付树**逐字符一致** ⇒ G11 之后无人平移 RNG |
| .so 三件套（开工） | 9015808 字节 / mtime 2026-09-26 16:34 / sha256 `40de6b25b2e03512…`（= G11 交付值） |

### 2.2 三条红的定性（§3.2 步骤 2）

| 红 | 失配面 | 定性 |
|---|---|---|
| `DiscipleFactory.GoldenSequenceSeed42` | `portraitRes`(14→15) + 八项资质（intelligence/charm/morality/artifactRefining/pillRefining/spiritPlanting/mining/teaching）；**方差 7 项 + comprehension + baseHp/MP/攻防速 7 项全部保持** | B 类：预期删除平移（G02–G09 在 comprehension 与资质段之间摘除了随机消费点），**进重录** |
| `DiscipleFactory.GoldenSequenceSeed987654321Female` | 同上模式（portrait 8→9 + 同八项资质），方差/基础属性零平移 | B 类，进重录 |
| `DeterminismProbeTest.DigestMatchesGoldenBaseline` | 整体 digest 平移（含上述弟子创建面） | B 类，进重录 |

非三条的红：**零**（ctest 3 败同名，与 G11 记录一致）。

### 2.3 重录内容（只改期望值层，D-1 遵守）

| 文件 | 改动 |
|---|---|
| `gamecore/test/disciple_factory_test.cpp` | 18 个期望常量（2 测试 × 9 字段）改为实测值；被测逻辑零改动 |
| `gamecore/include/gamecore/determinism_probe.h:37` | `kGoldenDigest = 0xb4f3c6912207f597ULL`；**`kProbeVersion` 未动** |
| `android/app/src/androidTest/java/com/xianxia/sect/NativeFpDeterminismTest.kt` | 字符串常量 + KDoc 同值改 `b4f3c6912207f597` |

**「本批未改任何 RNG 消费次数/顺序」证据**：`git diff --stat` 对
`system/disciple_factory.h` / `phase_settlement.h` / `month_settlement.h` / `year_settlement.h` / `gacha_tx.h`
**零改动**（本批 C++ 面仅 `determinism_probe.h` 1 行常量、`ai_sect_ops.h`/`disciple_stats.h` 3 行死语句删除、
金序列测试期望值）。

### 2.4 验证

`ctest` **1437/1437 全绿**（重录后首轮即全绿，Total 50.59s）；`ctest -R Determinism` **2/2 Passed**；
`ctest -R SceneEquivalence` **13/13**。C++ 死语句删除后复跑 `ctest` **1437/1437**（53.42s）——
删除 `rootCount = 1;` 自赋值零行为漂移的实证。

---

## 3. 任务 C：死代码清零

### 3.1 删前零命中贴证（§5.4-3 正向：删除模式归零）

每个符号删除前重跑按名 grep（防任务书 §2-7 式过期结论），全部与 §5.1 登记一致；
删除后主源复扫全零。例外贴证：`getSpiritRootCountColor` 主源残留 1 处 =
`GachaColorSingleSourceGuardTest.kt:177` 的**禁引正则字面量**（守卫词汇表，非调用）；`XianxiaColorScheme`
类本体活（`DialogCommon`/`ForgeDialog`/`AlchemyDialog` 消费），只删死的 `rarityColors` 属性。

### 3.2 清零清单（T-10b / A-10c / A-10d）

| 面 | 条目 | 同批配套 |
|---|---|---|
| C++（T-10b） | `rootCount = 1;` 自赋值 3 处（`ai_sect_ops.h`、`disciple_stats.h`×2）——只删 `if` 行，声明行保留、空串兜底语义不变 | ctest 1437 复绿 |
| Kotlin 主源 | `Serializers.kt` **整文件**（`NullableStringSerializer`/`NullableLongSerializer`）；`NullSafeProtoBuf.relationIdToProto/FromProto` + KDoc 示例两行；`BeastMaterialDatabase` 血炼四方法 + 段头注释；`GameConfig.Disciple.MIN_AGE`；`Color.kt` 五色常量 + `getSpiritRootCountColor` + `XianxiaColorScheme.rarityColors`；`GameViewModel.recruitListAggregates`；`WarehouseTab:893`/`MerchantDialog:679` 转发壳；`AutoAssignDelegate.setPrisonerSpiritRootFilter/togglePrisonerFilter`（import + KDoc「俘虏过滤」同步删） | `GachaColors.kt` KDoc「四份旧表」→「三份」 |
| Facade 链（A-10d） | `DiscipleFacade.addDisciple` → `DiscipleFacadeImpl` → `DiscipleService` → `DiscipleLifecycleManager` → `GameEngineDiscipleOps.addDisciple` 扩展，五文件全链 | 见 §3.3 守卫与测试 |
| 通知通道（D-3） | `GameNotification.RecruitFailed` 变体 → `GameNotification` 成为空 sealed 接口（KDoc 注明通道休眠）；`GameOverlayHost.GameNotificationSection` 整函数 + 调用点 + `GameOverlayDialogData.pendingNotification` 字段 + `anyGameOverlayVisible` 死条件与 `@Suppress("LongParameterList")`；`GameViewModel.pendingNotification/notifications/clearNotification`（`notifications` 删前即零消费） | 守卫测试改造见 §3.3 |
| 测试面（c10-a） | `NullSafeProtoBufTest` 5 例、`DiscipleServiceCrudTest` 2 例、`GameAndDiscipleConfigTest` 1 例、`GameConfigConsistencyTest` 1 例（处置表见 §5） | — |

### 3.3 守卫与测试面同步

- **`DiscipleCreationPathGuardTest`**：`DiscipleLifecycleManager.addDisciple` 的 `LEGACY_CRUD` 登记条目删除
  （守卫自带僵尸条目用例会强制）；`SiteKind.LEGACY_CRUD` 枚举值 + `when` 分支同批删除（登记清零后的死词汇）。
- **`GameStateStoreTransientQueueGuardTest`**：`GUARD_NOTIFICATION`（`RecruitFailed` 实例）不可再构造 ⇒
  `notificationQueue` 灌入从公开入口改为与 `violationsAfterReset` **同一反射口径**；不变量（队列必须登记
  `clearTransientQueues`）守卫强度不减。
- **`RngSourceGuardTest` 上限下调**（§4）。

### 3.4 🔴 裁决边界：通知通道后端管线（D-3 的切面）

任务书 D-3 授权「删变体 + 删 `GameOverlayHost.kt:466` 分支」。实测发现任务书未记载的通路事实：
`enqueueNotification` **生产者本就为 0**、`consumeNotification`/`clearPendingNotification` 唯一调用方是被删的
`GameViewModel.clearNotification`——**整条通道都是死基建**，不止一个死分支。

**本批切面**：删变体 + UI/VM 层全链（编译器强制的死码级联，全部在 `feature:game` 面）；**保留**后端管线
（`GameStateStore` 接口五成员、`GameStateStoreImpl` 队列、`GameEngine`/`DiscipleFacade` 转发、8 个测试替身的
接口实现）——整体退役横跨 `core:domain`/`:app`/`:core:engine` 契约面与 20+ 文件，超出本批授权文件面，
按 EXECUTION-PROTOCOL #14 不越界，登记 §8-1 交拍板（退役 vs 绑定新事件源）。

### 3.5 RngSourceGuardTest 上限下调（§5.4-1）+ 判别力自证

按守卫同口径（剔注释 + 四类正则、六模块主源）实跑实测，登记值对齐：

| 模块 | 类别 | 旧登记 | 实测 | 差额去向 |
|---|---|---|---|---|
| core/domain | ② | 6 | 6（持平） | — |
| core/domain | ⑤ | 19 | **12** | Affix/Physique/Talent 三库 7 处默认陷阱随天赋/体质/词条下线（G02/G04） |
| core/engine | ② | 14 | **1** | `BattleDescriptionGenerator` 14 处已治理 + 开袋奖励族随删除面下线 |
| core/engine | ④/⑤ | 2/5 | 2/5（持平） | — |
| core/data | ② | 1 | 1（白名单） | — |
| feature/game | ②/⑤ | 1/1 | **0/0（锁死）** | 末两处随删除面下线 |

每条登记附「实测值来源」注释（G10 实测字样 + 明细文件:行）。**判别力红证**：临时把 core/engine ② 降为 0 →
`主源随机源逐类计数不超过登记上限` **FAILED**（点名命中）→ 复原为 1 → 绿。假绿消除实证。

### 3.6 途中新发现（不在 §5.1 清单，登记不擅自扩删）

- `SpiritRootGenerator.generate(random = Random = Random)`：**生产零调用**（仅测试 18 处引用）——本体删除归
  后续死码批/拍板（本批不动，§8-2）。
- `GameEngineWorldBattleOps:315/329/343` 位置实参缺陷（`maxRarity` 吃默认 6）**仍在**——inventory §3.10
  旧登记有效，归 RNG 阶段 3。

---

## 4. 任务 D：活文档收口

| 文件 | 落地 |
|---|---|
| `docs/cpp-engine.md` | 阶段表 2 行（血炼三件套/天赋体质词条 204 加「已删」注记）；batch-14/15/16 三行同注记；S-13/S-14 加「（执法堂域已删）/（执法域已删）」注记 |
| `docs/architecture.md` | L3/L4 树同步现况（盗窃/执法/叛逃/伴侣配对/忠诚衰减/招募三件套标注）；T1 细目同步；**存档校验链 ASCII 图按 `SaveValidationRuleDefaults.kt` 现状重画**（23 规则 + order=3/7 空洞说明——两条年龄规则已删且整图原止于 order=14） |
| `docs/knowledge-base.md` | TOC + 整节「偷盗系统年上限」删除；技能表 9→8（loyalty 移除）；`DiscipleFactory.create` 入口描述改为对拍孪生实现现况（生产唯一构造口 = C++ `createDisciple`）；规则表重盘为 23 条现值（原「20 条 2026-08-01」快照过期）；已知限制两条 stale ✅ 行删除（ChildBirthSystem/renameDisciple 均零命中）；世界外交行删「跨宗道侣配对」；经济表删偷盗损失行 |
| `CODE_WIKI.md` | ActionId 台账 batch-14/15/16 三行加注记（:93 探索行经核 侦察/分舵驻守均存活、不动）；`DiscipleDelegate` 职责改现值（关注/类型/奖励/建筑分派/交谈——招募/驱逐/道侣零残留）；**`DiscipleCompact 轻量表`整节删除**（v55 迁移已整类删除、零消费者）；**Facade 清单 7→12**（补 Cultivation/Economy/Exploration/Gacha/Road 五个 + 目录树同步）；SectDelegate「改名」经核存活（`renameSect`）不动 |
| `docs/ui-read-surface.md` | **零改动**——:124/:137 经核均位于自带时间戳横幅内（:124 在「🔴 2026-09-15 口径更正」块、:137 在「首裁原文（保留以便追溯）」引文内），属 §6.2 保留类（§6.1 与 §6.2 冲突时按 dated 取证段处置，理由登记 §8-5） |
| `docs/rng-source-inventory.md` | §2 汇总表 G10 重盘版（合计 50→27）；§3 全部明细表重写为实测现值（判定汇总 🔴9/🟡0/⚪18）；§8.2 分区消费表重跑（共 76→72，`SYSTEM(3)` 35→16；`LawEnforcement*`(9)/RecruitService/ChildBirthSystem/PartnerSystem/GameEngineSpiritRootOps/TraitAddOps/TraitWashOps 消费方已删）；`:99`/`:143` 死引用（ChildBirthSystem:147 / MerchantAndRecruitService:224）随表重写消除 |

---

## 5. 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `disciple_factory_test.cpp` 金序列两条 | **保留，期望值重录**（18 常量） | 唯一窗口重录，逻辑零改动 |
| `DeterminismProbeTest` 两条 | **保留**（其一常量经头文件重录） | — |
| `NativeFpDeterminismTest` | **保留**（常量 + KDoc 同批改） | androidTest 编译门过 |
| `NullSafeProtoBufTest` relationId×5 | **删除** | 被测函数删除 |
| `DiscipleServiceCrudTest` addDisciple×2 | **删除** | 被测链删除 |
| `GameAndDiscipleConfigTest` 年龄最小值应为5 | **删除** | `MIN_AGE` 删除（D-5） |
| `GameConfigConsistencyTest` 年龄最小值两源一致 | **删除** | 同上（一致性锚点随常量消失） |
| `GameStateStoreTransientQueueGuardTest` 两条 | **保留，灌入通道改造**（反射口径） | 不变量守卫强度不减 |
| `DiscipleCreationPathGuardTest` 三条 | **保留**（僵尸登记条目 + `LEGACY_CRUD` 类别删除） | 僵尸用例自身强制 |
| `DiscipleServiceCrudTest` 其余用例 | 保留 | 基建（`insertDisciple` 助手）仍被其余用例消费 |

---

## 6. 门禁表（终树同轮实测；数值取自命令输出原文）

| 门 | 实测值 | 对照 G11 基线 |
|---|---|---|
| 桌面 ctest | **1437/1437 全绿**（重录后 50.59s；死语句删除后复跑 53.42s 亦全绿）；`ctest -R Determinism` **2/2 Passed**；`ctest -R SceneEquivalence` **13/13** | 1437 总数一致；3 败 → 0 败 |
| JNI 重建 | `build-desktop-jni.ps1` 成功；`.so` **9015808 字节 / mtime 2026-09-26 23:32 / sha256 `b1eb1bec32d6c1bc3ca9d62b4696b4955aaaa100d297d05e06baae8b2de23068`**（重建前 `40de6b25…`）⇒ 体积不变、mtime+sha 变（常量+死语句面） | 三件套按判据更新 |
| Kotlin 组合门 | `compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=<新 .so> detekt lintRelease` ⇒ **BUILD SUCCESSFUL in 21m 48s**（339 tasks 全执行）；JUnit **7462 / 0 failures / 0 errors / 18 skipped（692 XML；app 1011 / domain 1569 / data 809 / engine 2934 / ui 151 / feature:game 988）**；detekt 六模块 **0 error 0 warning**；lint **36 warnings（+3 baseline 过滤，app 模块报告口径）· 六模块 0 errors** | JUnit 总数 7471 → 7462 = **−9**（删除 9 例：data 5 + engine 2 + domain 2），逐模块账闭合见 §6.1 |
| AndroidTest 编译门 | `:app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL | 覆盖 `NativeFpDeterminismTest` 改动 |
| ActionId | `gen-action-ids.mjs` = **201 actions (maxId=1872)**，regen 前后两产物零漂移 | 同值（本批零 catalog 变更） |
| 游戏数据 | `gen-game-data.mjs --check` ✓，sha256 `035066cb…94ef` **逐字符不变** | 同值 |
| JNI 计数 | `check-jni-count.mjs` ✓ **86/86 双桥无扩散** | 同值 |
| 规范门禁 | `check-agent-instructions.mjs` ✓ 五规则全过：路由闭包 **42 篇 / 444 条引用零死链**（改前 444 ⇒ 不精确引用计数**不增长**，验收⑤）；最坏链路 32180/32768 | 引用计数持平 |
| 双 changelog | `changelog_entries.json` 追加 1 条玩家文案（`node JSON.parse` ✓）；`CHANGELOG.md` [4.01.16] 段内新增 G10 小节（编辑工具改，无整文件重排） | 版本号未动（G14 拍板项） |

### 6.1 JUnit 逐模块账闭合说明

G11 基线 7471（app 1011 / domain 1571 / data 814 / engine 2936 / ui 151 / feature 988）→ 本批 7462：
**纯删除 9 例 + 零新增**——data 814→809（−5，relationId×5）、engine 2936→2934（−2，addDisciple×2）、
domain 1571→1569（−2，MIN_AGE×2）；app/ui/feature:game 零删例（OverlayHost/GameViewModel 改动不删用例，
`GameStateStoreTransientQueueGuardTest` 改造后 2 例保留）。skipped 18 = app 2 + engine 1 + data 15（同 G11）。

---

## 7. 判别力自证（退回旧态判红）

| # | 构造反例 | 实跑结果 |
|---|---|---|
| 1 | `RngSourceGuardTest` core/engine ② 登记值临时 1 → 0 | 守卫 **FAILED**（命中数超上限并列出站点）⇒ 复原 1 后绿——上限=实值时任何新增命中必红，假绿消除 |
| 2 | 三条金黄红在本批前常态复现 | ctest 重录前 1437 总 / 3 败（同名），重录后 0 败——红→绿转换即重录判别力 |
| 3 | `rootCount = 1;` 删除 | 若删陂数值面行为，ctest 金黄/结算族必红——实测 1437/1437 两轮全绿 ⇒ 零行为漂移 |

---

## 8. 未完成 / 登记

1. 🟠 🔴 **通知通道后端管线退役拍板**（§3.4）：`GameStateStore` 的 `pendingNotification/notifications/`
   `enqueueNotification/consumeNotification/clearPendingNotification` 五成员 + `GameStateStoreImpl` 队列 +
   `GameEngine`/`DiscipleFacade` 转发 + 8 个测试替身实现——生产事件源为 0、唯一消费链已删；保留为休眠扩展
   预留。**交用户拍板**：①整链退役（跨 domain 契约 + app/engine/ui + 测试替身，约 20+ 文件）
   ②保留并绑定未来事件源。不做拍板即维持现状（编译绿、无行为影响）。
2. 🟠 `prisonerSpiritRootFilter` 字段本体与 `MailAttachment.extra` 字段本体（需 Room 迁移）——按 D-4 未动，
   交后续清理批（任务书 §10-1 原登记有效）。`GameEngineSettingsOps.setPrisonerSpiritRootFilter` 扩展 +
   `GameEngineResidualNativeTxGateTest` 测试触达保留（字段链活口）。
3. 🟠 `SpiritRootGenerator` 整对象生产零调用（仅测试 18 处）——§3.6 新发现，交后续死码批复盘。
4. 🟠 `predefinedCodes` 置空后的运营下发通道——任务书 §5.3 原登记有效，归 G12 商业化口径。
5. 🟠 **§6.1/§6.2 冲突的处置口径**（本批裁决，供下批复用）：`ui-read-surface.md` :124/:137 与 `CODE_WIKI.md`
   焦点域节（:519-523「已废弃」横幅）均位于**自带时间戳横幅/追溯引文**内 ⇒ 按 §6.2 dated 取证段保留；
   `cpp-engine.md`/`CODE_WIKI.md` 迁移台账三行无内联时间标记 ⇒ 按 §6.1 加「已删」注记。
6. 🟠 `CODE_WIKI.md` Delegate 计数（9）与 ActionId 台账头部数字（198/1861/退役 21）仍为旧值——**G14 任务书
   已认领**（Facade 7→12 部分本批已完成，Delegate 9→27 与 ActionId 数字归 G14，本批不越界）。
7. 🟠 真机 pending-device 12 项（G11 §7 D-1…D-12）——不适用本批、仍未消（本机无设备）。
8. 🟠 `docs/gacha-watch/dispatch-ledger.md` 行尾幻影改（diff 0 行）——看护通道产物，本批不提交不还原。

---

## 9. 树身份与证据边界

| 项 | 值 |
|---|---|
| 分支 / 开工 HEAD | `feat/gacha-m0-m1` / `6186e3943` |
| 树况 | 开工 = 台账行尾幻影 + `docs/research/`×2（允许）；此后全部改动为本批文件面 |
| 提交 | 单批内逐文件 `git add <file>`；构建副产物（`atlas-rgba-manifest.json`/`sprite-uid-map.json` 如出现）提交前 `git checkout --` 还原 |
