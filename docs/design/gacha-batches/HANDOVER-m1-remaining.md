# M1 剩余工作 · 交接文档（HANDOVER）

> 用途：接手《角色卡池重构 G 批》M1 剩余批次的**唯一入口文档**。
> 权威需求：[`../../character-gacha-redesign-2026-09-23.md`](../../character-gacha-redesign-2026-09-23.md)（产品方案 v1.5）
> 实施拆批：[`../character-gacha-implementation.md`](../character-gacha-implementation.md)（15 批 = M0×1 + M1×11 + M2×3）
> 作业规程：[`EXECUTION-PROTOCOL.md`](EXECUTION-PROTOCOL.md)（**每个批次开工前必读**）
> 日期：2026-09-23 ｜ 分支：`feat/gacha-m0-m1`

---

## 1. 当前状态（已完成）

| 批次 | 状态 | commit | 门禁实测 |
|---|---|---|---|
| **G00** 数值白皮书 | ✅ 完成 | `89281bcc9`（与 G01 同笔） | — |
| **G01** 脚手架（配置/协议/域骨架/ActionId 预留） | ✅ 完成 | `89281bcc9` | 桌面 ctest 1609/1609 · `GachaConfigGuardTest` 绿 · `node scripts/gen-game-data.mjs --check` 绿 · 生成器 198 动作 / maxId=1861 |
| **G07** 玩家侧战死 → 重伤 + 复用既有每旬回血 | ✅ 完成 | `19dffb6d2` | 桌面 ctest **1606/1606** · `:core:domain`/`:core:engine`/`:app` G07 过滤面 JUnit 全绿 · detekt 四模块绿 · `compileReleaseKotlin` 绿 |
| 执行协议 | ✅ 入库 | `d38e07099` | — |
| 落点侦察清单（G02–G09） | ✅ 入库 | `112e2cbd7` | — |

**已完成批次的报告**：[`report-G01.md`](report-G01.md)、[`report-G07.md`](report-G07.md)。

**未提交**：`docs/research/*.md`×2、`模拟宗门美术素材/`、`模拟宗门音乐音效/`（**与本批无关，勿混提交**）。

---

## 2. 未完成批次总表（M1 剩余 9 批 + M2 3 批）

| 批次 | 内容 | 依赖 | 落点清单 | 建议顺序 |
|---|---|---|---|---|
| **G02** | 删寿命/年龄/忠诚/叛逃/偷盗/神魂 | G01 | [`recon-G02-G03.md`](recon-G02-G03.md) `## G02` + `# §9` | **1** |
| **G05** | 删招募链 + `recruitList` 迁移恒空 + 按钮占位 | G01 | [`recon-G05-G06-G08-G09.md`](recon-G05-G06-G08-G09.md) `G05` | **2** |
| **G06** | 删逐出弟子 + 弟子改名 | G01 | 同上 `G06` | **3** |
| **G03** | 删生育/道侣/亲缘（parentId 全链） | G01 | [`recon-G02-G03.md`](recon-G02-G03.md) `## G03` | **4** |
| **G04** | 删洗炼/资质/悟性/天赋体质词条/血炼/职位特质/战斗随机成长 | G01 | [`recon-G04.md`](recon-G04.md)（含 `§11` 产品缺口） | **5** |
| **G08** | 角色模板层 + `templateId` 实例化 + 开局周明/5 万 + 兑换码改道 | G01；**建议在 G05 后**（招募是 `createDisciple` 最大调用方） | [`recon-G05-G06-G08-G09.md`](recon-G05-G06-G08-G09.md) `G08` | **6** |
| **G09** | 抽卡核心 `gacha_tx`（roll/保底/碎片/升星/解锁/入库） | G08（解锁依赖模板读取层） | 同上 `G09` | **7** |
| **G11** | 最简寻访 UI：主界面 + 结果页 Q30/Q31 + 图鉴最小 + `GachaDelegate` | G09；**素材注册为硬阻塞** | 同上 `§G11` | **8** |
| **G10** | RNG 对拍基线重录 + 全量回归 + 死代码 grep 清零 | **G02–G07 全部合入后**；G08/G09 建议同窗 | — | **9（串行汇合）** |
| M2 · G12 / G13 / G14 | 体验完成 / 数值落地 / 文档收口 | G11 / G00+G09+G10 / G11–G13 | — | M2 |

**M1 完成判据**（实施计划 §批次速查）：**G10 全绿 + G11 最简 UI 真机通**。

---

## 3. 建议的顺序理由（为什么不是文档原始顺序）

1. **G02 先做**：删除面最大、RNG 扰动最大，且与已落地的 G07 伤口最近（寿元真死亡链是 G07 唯一保留的玩家侧死亡路径）。做掉它，G07 的死码收口项才能一并清理。
2. **G05 第二**：它是 `createDisciple` / `DiscipleFactory.create` 的**最大调用方**；先删掉它，G08 的「构造路径收敛到单点」分母从 21 条降到个位数。
3. **G06 第三**：与 G05 共享 `execute_dispatch.cpp` / `OverflowMailSender.SOURCE_DISPLAY_NAMES` / `disciple_tx.h`（改名 tx 依赖 `recruit_settle::isSamePerson`）。**G06 必须在 G05 删 `recruit_settlement.h` 之前或同批**，否则改名 tx 先断。
4. **G03 第四**、**G04 第五**：两者都不阻塞别人，但都动 `DiscipleColumn` 枚举 → 必须与 G02 串行（见 §6）。
5. **G08 → G09 → G11**：产品核心链路，G09 的「解锁触发模板实例化」硬依赖 G08 的模板读取层（当前 `characterTemplates` **零生产读取方**）。
6. **G10 最后**：删除造成的 RNG 平移只在**唯一一次**重录窗口里消化（实施计划风险表红线）。

---

## 4. 已拍板的口径（后续批次必须沿用，不要重新发明）

| # | 口径 | 依据 |
|---|---|---|
| 1 | **玩家侧弟子永不死亡 = 重伤**（气血钳到 `GameConfig.Disciple.INJURED_HP`、`isAlive` 保持 1、不清槽/不解绑/不清装/不物化行囊、不计年报死亡）；**回血复用既有每旬机制**（`PHASE_HP_MP_RECOVERY_RATE = 0.2` + `phase_settlement.h`），**不新增回血机制、不新增配置项** | G07（`report-G07.md`）；用户明确口径 |
| 2 | **AI 宗弟子 / 妖兽 / 随机人类敌人照旧可死**（物理分离：玩家 = `DiscipleStore`/`DiscipleTables`；AI = `GameData.aiSectDisciples`；妖兽与人类敌人 = 战斗局部 `Combatant`，从不落弟子表） | G07 侦察已核实 |
| 3 | **ActionId 只增不复用**：`scripts/action-catalog/core.mjs`、`w4a.mjs` 等**冻结文件不改**；退役 = 保留编号 + 删 dispatch 实现 + 注释标「已退役，编号禁复用」；产物由 `node scripts/gen-action-ids.mjs` 生成（零参数覆写两份，**无 `--check`**，零漂移自证 = `git diff --exit-code`） | `action-catalog/README.md` + 实施计划 S2.3 |
| 4 | **Room 列禁 DROP**：保留旧列 + Kotlin 字段 `@Ignore`（或 create-copy-drop-rename），递增 `@Database(version)` + 新增 `MIGRATION_N_M` + 注册 + 迁移测试；旧 `Index` 声明不得删 | `rules/database-migration.md` |
| 5 | **ProtoBuf `@ProtoNumber` 只增不复用**：删字段在两处 proto 定义（`SerializableXxx` 与 `OldSerializableSaveData.SerializableXxx`）写 `reserved` | 根 `AGENTS.md` §7.3 |
| 6 | **`materializeDiscipleBagAndMarkDead` 保留**（Kotlin `InventorySystem` + C++ `secret_realm_session.h`）：删掉玩家侧战斗消费方后仍有 3 个消费方（`exploration_tx.h`、`SecretRealmService.kt`、`GameEngineScoutOps.kt`） | `recon-G02-G03.md` §9.2 |
| 7 | **`kDeadStatusName` 常量保留**（只改注释）：仍是两处「不应写 DEAD」反向断言的守卫锚点（`death_handler_test.cpp`、`exploration_tx_test.cpp`） | `recon-G02-G03.md` §9.8 |
| 8 | **`markDead` 系列改名（名字=死亡/行为=重伤）与 `isOutsideSect` 语义过载重构**，`DISCIPLE_MARK_DEAD`/`DISCIPLE_BACKFILL_DEATH_YEARS` 编号不变：**登记为后续收口项，不在 G02 实施** | `recon-G02-G03.md` §9.5/§9.6 |
| 9 | **G08：AI 宗弟子构造保持旁路**（`ai_sect_recruit.h` 不走模板、`templateId=""`）——产品方案 §6.5「AI 宗弟子生成分布维持现状（1–5 根，对手侧不随卡池设定走）」 | 产品方案 §6.5 |
| 10 | **兑换码不再直造弟子**，改发对应角色碎片（走与升星同一函数）；兑换码系统本体保留 | 产品方案 §6.10 |
| 11 | **抽卡物品入库唯一入口**：`InventorySystem.withTrackingSource("仙缘寻访") { addXxx(...) }` + 同批登记 `OverflowMailSender.SOURCE_DISPLAY_NAMES`（守卫会扫字面量）；发放类溢出转邮件，抽卡永不因满仓失败 | 产品方案 §4.1 + 实施计划 S2.3 |
| 12 | **C++ AUTHORITATIVE**：抽卡/保底/碎片/升星/开局注入/重伤写入全部下沉 C++（`gacha_tx`），Kotlin 只读展示；`MirrorReadOnlyGuardTest` 必须零命中 | 实施计划 S2.3 |
| 13 | **G11 结果页色表强制 Q31**（`GameConfig.Gacha` 单源），**禁止复用 `ItemCard.getRarityColor` 旧色表**；灵根徽章同用 Q31 | 产品方案 §4.4 颜色总表 |
| 14 | **UI 不驱动系统 tick**；界面实时数据订阅 `GameEngine` StateFlow 派生 | 根 `AGENTS.md` §6.5 |

---

## 5. 待产品拍板 / 硬阻塞（开工前必须解决或绕开）

| # | 项 | 影响批次 | 现状与证据 | 建议 |
|---|---|---|---|---|
| 1 | 🔴 **悟性删除后突破率公式无定义**：`comprehensionBreakthroughBonus`（C++ `disciple_stats.h` 本体 + 3 个消费点）与「长老有效悟性」（`DetailBasicInfoSection.kt`）在悟性删除后归零，产品方案 §6.6 **只定义了修炼速度新秩序、未定义突破率** | **G04** | [`recon-G04.md`](recon-G04.md) `§11`（三口径逐条落点 + 对拍影响） | 三口径：①随悟性一并删除（**唯一无新协议可独立完成**，推荐）；②权重转移到星级/境界（**跨 G12/M0，C++ `gameData` 无 `gachaStarMap` 协议字段，本批不可独立完成**）；③长老改纯槽位加成（数值源是天赋 PositionBonus，天赋删后也归零，须先定新数值源） |
| 2 | 🟠 **G05「招贤伯乐」与 G04「特质三表整删」职责重叠** | G05 / G04 | `recon-G05-G06-G08-G09.md` X-2 #12 | G05 只删「招募数值效果」（`year_settlement.h` 的 +50%、`positionEffectBonus("RECRUITING")` 调用），特质条目交 G04 |
| 3 | 🟠 **`annualDesertedDisciples` 语义拆分**：玩家逐出计数（2 处，随 G06 删）与执法/叛逃计数（4 处，G02 后仍在）**共用同一字段**；G06 与 G02 先后顺序会影响该字段残留 | G02 / G06 | `recon-G05-G06-G08-G09.md` `G06-2` | 保留字段（只删玩家逐出写入点）；报告里显式登记剩余 4 个写入点的语义 |
| 4 | 🔴 **G11 素材硬阻塞**：`avatar_zhouming` / `portrait_zhouming` 等 12 个键**全部未注册**（仅存在于 `game-data.json`），`resource-registry.json` 无、`sprite-uid-map.json` 无、双模块 `drawable-nodpi` 无文件 | **G11** | `recon-G05-G06-G08-G09.md` `§G11-4`（含 `rules/static-resources.md` 七步逐条映射） | G11 前先做素材批：无损 WebP + 双模块 + `SpriteResRegistry.register` + `ResourcePreloader` 同步点；素材源在 `模拟宗门美术素材\<角色名>\{头像,全身像}.png` |
| 5 | 🟠 **G11 边框流光无既有实现**：`core/ui` + `feature/game` grep `InfiniteTransition` 零命中，只有静态渐变边框 | G11 | 同上 | 需新增；低端降级（静态描边/呼吸）须 Vulkan + Canvas 双路径可过 |
| 6 | 🟠 **G08 兑换码改道会打断 4 个既有测试**（`RedeemCodeServiceTest`/`RedeemCodeManagerTest`/`RedeemCodeManagerTalentTest`/`DiffRedeemCodeTest`）与 `RedeemCodeRewardOps.buildRedeemDisciple` + 6 个 helper 成死码 | G08 | `recon-G05-G06-G08-G09.md` X-2 #15 | 同批改测试 + 清死码 |
| 7 | 🟠 **`data_inject.h` 「段缺失即拒」陷阱**：删 `talent/physique/affix` 三表时若不同步改 `data_inject.h` 段映射与计数字段，数据注入会整体 `return false` → 10 张表**静默落内联兜底** | **G04** | [`recon-G04.md`](recon-G04.md) §11 开放项 9 | 同批删 `data_inject.h` 段映射 + 计数字段 |

---

## 6. 跨批串行约束（硬，违反会导致返工）

- **删列批 G02 / G03 / G04 共享** `models.h`、`disciple_store.{h,cpp}`、`column_dirty.h`、`DiscipleTables*.kt`、`Disciple.kt` → **必须串行合入，禁并行编辑**；每批开工前 `git status` 必须干净。
- `DiscipleColumn` 枚举删项会**平移后续所有列索引** → `column_dirty.h` 双射 switch、`gameview_encode.cpp` 字段表、Kotlin `DiscipleTablesColumnRegistry` / `columnGroupByIndex` 必须**同 commit** 改完。
- `execute_dispatch.cpp` 已声明「此后冻结」→ 新域走独立 `src/dispatch_*.cpp`，只在该文件加 1 行端口认领（`sect_defense_battle.h` 注释与 `dispatch_w4c.cpp` 端口先例可抄）。**注意区间吞号先例**（1730 曾被 1520–1531 区间吞进库存 handler）。
- 多批共享文件：`battle_residual_tx.h`、`disciple_tx.h`、`disciple_lifecycle_tx.h`、`month_settlement.h`、`year_settlement.h`、`InventorySystem.kt`、`OverflowMailSender.kt`（G06 删 `disciple_expel` 与 G09 加「仙缘寻访」同文件）、`DiscipleService.kt`（G06 删逐出 + G08 改开局）、`DialogType.kt`/`OverlayDialogRouter.kt`/`DialogFeatureRoutes.kt`/`DialogTypeRenderCoverageTest.kt`（G05 占位与 G11 寻访同文件）。
- **RNG 基线全局只保留一次权威重录窗口 = G10**；任何批次不得自行重录对拍夹具。

---

## 7. 门禁基线与运行方式

见 [`EXECUTION-PROTOCOL.md`](EXECUTION-PROTOCOL.md) §2。当前实测基线：

| 门 | 当前值 | 备注 |
|---|---|---|
| 桌面 C++ ctest | **1606 / 1606** | `android/app/src/main/cpp/gamecore/build/desktop-test`；运行需 llvm-mingw `bin` 在 PATH |
| `:core:engine` JUnit | 3406 用例（G07 过滤面全绿） | 必须 `--max-workers=1`；跨语言对拍需先 `pwsh scripts/build-desktop-jni.ps1` 并传 `-Dgamecore.jni.path=<.so 绝对路径>` + `--rerun-tasks` |
| detekt | 六模块全绿（baseline 全 0） | 只缩不增 |
| 生成器 | 198 动作 / maxId=1861；`game-data.json` sha256 `c7626a0a…` | `gen-action-ids.mjs` 无 `--check`；`gen-game-data.mjs --check` 有 |

---

## 8. 下次开工第一步（checklist）

1. `git status` 确认工作树干净（构建副产物 `atlas-rgba-manifest.json` / `sprite-uid-map.json` 用 `git checkout --` 还原）。
2. 读 [`EXECUTION-PROTOCOL.md`](EXECUTION-PROTOCOL.md) + 对应批次的 recon 文件章节 + 产品方案对应小节。
3. 按 §3 顺序挑下一批（当前应是 **G02**），把批次范围写成「做 / 不做」两栏，确认不与 §6 的串行约束冲突。
4. 涉及列删除先读 `rules/database-migration.md`；涉及新随机逻辑先读 `rules/cpp-priority.md`；涉及新对话框先读 `rules/new-dialog-checklist.md`。
5. 提交前过 `rules/pr-review-checklist.md`；功能面触达则同步评估双 changelog（**G14 为硬门**）。
6. 每批交付 `report-Gxx.md`（格式见协议 §3）。

---

## 9. 中途发现但未处理的问题（诚实清单）

| # | 问题 | 归属 | 证据 |
|---|---|---|---|
| 1 | **`DiscipleTablesAssemblers` 缺列默认值不一致**：`assembleCombat` 原 `currentHp/currentMp` 默认 `0`，而三端默认皆为 `-1`（满血）→ 缺列弟子被误判「未满血」并参与每旬回血 | ✅ 已在 G07 顺带修复（`FULL_HP_SENTINEL = -1`） | `report-G07.md` |
| 2 | **秘境会话态与弟子态语义不一致**：会话成员可被标「失能/永久死亡」但弟子行已改为重伤存活 | ✅ 已在 G07 改为「会话内失能 + 行内重伤」并加注释；会话内「濒死」机制本身是否保留未拍板 | `SecretRealmService.writeBackBattleMembers` |
| 3 | **`hasReviveEffect` 无任何游戏行为消费者**（仅声明/列桥接/序列化/镜像流动，无 `if` 分支）；玩家侧「首败保命」实际由会话局部 `isDying` 承担 | G07 登记 → 后续字段清理批 | `recon-G02-G03.md` §9 前置说明 |
| 4 | **`comprehensionAdd` 与 `SkillStats.comprehension` 是两套概念**；且 UI 有 5 处把 `intelligenceAdd` 标为「悟性」（预存文案 bug）——删 `comprehensionAdd` 时**不要被误导** | G04 | `recon-G04.md` §1.2 与开放项 7 |
| 5 | **`ELDER_SKILL_BASELINE` 双重身份**：既被突破率长老加成引用，也被教学公式引用——G04 只删前者，删错会连带打断教学 | G04 | `recon-G04.md` §11.3 口径① |
| 6 | **`sprite-uid-map.json` / `atlas-rgba-manifest.json` 是构建副产物**，每次构建都会改（时间戳）→ 提交前必须还原，否则污染 diff | 全体批次 | G07 实测 |
| 7 | **`disciple_expelled` 事件只定义、无发布点**（死事件） | G06 | `recon-G05-G06-G08-G09.md` §1.E |
| 8 | **`game-data.json` 单行 1.1 MB**：中性源改动后必须重跑生成器，否则 `DataStoreGuardTest` 红（G01 后曾出现该红点，已修） | 全体 | G07 实测 |
| 9 | **`DiscipleTables.markDead` 等命名与语义已不符**（名字=死亡、行为=重伤） | 后续收口批（G10 或独立清理批） | `recon-G02-G03.md` §9.6 |
| 10 | **`isOutsideSect` 布尔语义过载**（原义「死在宗门外→遗物不回收」被反用为「走重伤」），且默认值 `true` 是危险默认值 | 后续收口批 | `recon-G02-G03.md` §9.5 |

---

## 10. 一句话交接

**G00/G01/G07 已落库且门禁全绿；M1 剩余 9 批（G02→G05→G06→G03→G04→G08→G09→G11→G10）已有逐文件落点清单与作业规程，可直接开工；开工前必须先解决 §5 的产品歧义与素材硬阻塞，且必须遵守 §6 的串行约束。**
