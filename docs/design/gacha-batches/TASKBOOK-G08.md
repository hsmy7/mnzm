# TASKBOOK-G08 · 角色模板层 + 开局 + 兑换码改道 + 立绘解析链

> **本文件是 G08 的派工真源**，取代 `recon-G05-G06-G08-G09.md` §G08 全部落点表与
> `HANDOVER-m1-remaining-3.md` §8.D 的内联清单。上位文档的失真条目逐条列在 §2，**执行者只认本文件**。
> 开工时点：**2026-09-25，HEAD `fa853b541`，`git status --porcelain` = 2 行**（仅 `docs/research/`×2，不提交）。
> 侦察方式：主线程 1 轮定向 grep + 5 个只读子代理穷举面审计（templateId 三端链 / 构造路径 / 开局+配置层 /
> 兑换码+碎片 / 解析链），下表行号均为本会话直读所得，非文档转引。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 建立 6 具名角色模板层，把弟子构造从「逐字段随机」收敛为「模板实例化」；新档开局=周明 + 5 万灵石；兑换码/邮件不再直造弟子、改发角色碎片；修掉具名角色立绘显示回落通用像的解析链缺口 |
| 验收① | `templateId` 有生产写入者：新档名册弟子 `templateId="zhouming"`；存量旧弟子仍 `""`；`portraitRes` 为 `portrait_zhouming` |
| 验收② | 新档灵石 = **50,000**，且**不进入年度收入统计**（`annualTotalIncome`/`annualIncomeBySource` 不含开局量）；`GameData()` 默认值仍 1000（哨兵语义保留） |
| 验收③ | 开局账本：`gachaStarMap["zhouming"] == 1` 且 `gachaFragmentCounts` **不含**该键（0/100）——Q34 字面 |
| 验收④ | 全仓**零**「直造随机弟子」生产入口：`RedeemCodeManager.generateDisciple` / `buildRedeemDisciple` + 5 helper / `distributeDiscipleAttachment` / `DiscipleService.recruitDisciple` 全部消失；碎片入账只有 `addFragment` 一个函数（C++）+ 其 Kotlin 回退臂 |
| 验收⑤ | `PortraitResolver.resolvePortraitResId("portrait_zhouming") != 0`，8 个 UI 消费点统一走它；`PortraitPool` 37 键语义与 L0 预载清单**不变** |
| 验收⑥ | **零新增 B 类**：ctest 三条红与 G16 逐条同名（`DiscipleFactory.GoldenSequenceSeed42` / `…Seed987654321Female` / `DeterminismProbeTest.DigestMatchesGoldenBaseline`），金黄 **不在本批重录**（§6.1 红线） |
| 验收⑦ | 新守卫齐备且经「退回旧状态判红」自证：模板表 ↔ 配置 ↔ 开局三向、构造路径单点、portraitKey 可解析、兑换码无弟子类型 |
| 不做 | 不做寻访 UI 与抽卡 roll/保底/历史（G09/G11）；不重录任何金黄/对拍基线（G10）；不动 37 张通用肖像素材；不给 `characterTemplates` 新增数值字段（模板仍是 id/性别/姓名/灵根/头像键/立绘键 6 项）；不改版本号 |

---

## 2. 🔴 上位交接失真清单（执行者必读，按实测走）

| # | 上位记载 | 实测真值 | 后果 |
|---|---|---|---|
| 1 | HANDOVER-3 §8.D-5「本批动 `templateId` 列 ⇒ **Room v59→v60 四件套 + 迁移测试**」 | ❌ **不需要**。`templateId` 列自 **v54** 就在库（`GameDatabaseMigrationsV54.kt:21`，`Disciple.kt:85-86`，`54..59.json` 均含），G08 纯写值不改 schema；`rules/database-migration.md:5/9` 的触发条件是「字段新增/删除/重命名/改类型/@Ignore」 | 照字面做会白造一条 v60 空迁移并撞 `RoomMigrationTest` 全链期望集 |
| 2 | HANDOVER-3 §8.D-5 顺带列的「`DiscipleColumn` 枚举 / proto `@ProtoNumber` reserved / `lock_beast_tx.h` SETTINGS_PATCH 三处清单 / GameDataFieldPatch」非弟子表三端环 | ❌ 本批**不删列、不删 @ProtoNumber** ⇒ 四处全不涉及。`DiscipleColumn::TemplateId` 已在 `disciple_store.h:134`（枚举 **91** 项、末列，`kCount=91`），`gameview_encode.cpp:213 {"templateId",112}` + `game_view.proto:251` + `GameViewDiscipleRows.kt:482` presence 位齐备；`GameDataFieldPatch.kt` 根本不含 templateId 分支（弟子行走 `GameViewMirrorCodec.kt:428`） | 照清单派工会让子代理去改不存在的面 |
| 3 | recon §G08-1「21 条构造路径，portraitRes roll 落点 4 处」，C++ `createDisciple` 是「单点收敛目标、入口唯一」 | ❌ **C++ `createDisciple` 生产调用点 = 0**（只剩 `determinism_probe.h:182`、`GameCoreJni.cpp:238`、4 个 GTest）；`year_settlement.h` 招募刷新与 `child_birth.h` 已被 G05/G03 物理删除；`ai_sect_recruit.h:105-165` 的 AI 旁路是**零调用死面**。真正在跑的只有 3 条 Kotlin 臂：`DiscipleFactory.kt:103-105`、`AISectDiscipleManager.kt:236`（**recon 完全漏报**）、`RedeemCodeRewardOps.kt:236` | 「改 C++ 工厂 = 改生产行为」是错的；生产收敛点在 Kotlin |
| 4 | recon §G08-4 seed 字段含 `age`/`social`/`random`；§G08-6 引 `child_birth_test.cpp:85`、`DiscipleTablesSelfHealTest.kt:172`、`disciple_factory_test.cpp:47,92,132-134`、`DiffDiscipleFactoryTest.kt:52` | ❌ C++ seed 实为 `disciple_factory.h:178-186`（id/gender/fullName/spiritRootType/realm=9/realmLayer=1，**无 age**）；Kotlin `DiscipleSeed` 实为 `DiscipleFactory.kt:76-85`（7 参，无 social/random）；`child_birth_test.cpp` 与 `DiscipleTablesSelfHealTest.kt` **不存在**；portrait 黄金断言实为 `:42`/`:79`/`:111`，对拍断言实为 `DiffDiscipleFactoryTest.kt:42`（portraitRes）与 `:43`（templateId） | 行号派工必扑空 |
| 5 | recon §G08-2/§G08-3 行号（`GameEngineLoadDataOps.kt:306/:374`、`GameData.kt:141-143`、`GameConfig.kt:217`、`db` 12 键、game-data.json 1,132,046 字节、中立源在 `android/scripts/`） | ❌ 实为 `:286`/`:353`、`GameData.kt:124-128`、`GameConfig.kt:215`、`db` **9 键**（talents/physiques/affixes 已 G04 删）、**1,008,933 字节**、脚本在**仓库根** `scripts/`（`android/scripts/` 下无 `gen-game-data.mjs`、无 `data/`） | 路径失真 |
| 6 | HANDOVER-1 §5#6「兑换码改道牵动 **4 个**测试 + 6 个死 helper（含 `resolveTalentIds`/`avoidSentinel50`）」 | ❌ `RedeemCodeManagerTalentTest.kt` 已 G04 整删、`resolveTalentIds`/`avoidSentinel50` 已不存在。实际牵动 **7 处**，其中 recon 未列的有 `WallClockReflowGuardTest`（**硬编码 4 个兑换码文件名 + `CONVERGED_FILE_COUNT=15`，删/改名文件即红**）、`RedeemCodeTest.kt`（domain 枚举数量守卫）、`redeem_code_test.cpp`、`RedeemCodeWallClockTest.kt` | 漏改 → 编译或守卫红 |
| 7 | 隐含「兑换码只有一条本地直造弟子臂」 | ❌ **三条**：本地臂 `RedeemCodeService.kt:478-497`、**API 服务端臂** `:233 → :326-342`（服务端可下发 `type:"disciple"`）、**邮件弟子附件** `MailAttachmentVariantsOps.kt:88-113`（`generateDisciple` 唯一的非兑换码调用方，且 `:108` 手写 id + `:110` `insert` 是第二处旁路） | 只改本地臂 ⇒ `buildRedeemDisciple` 链删不掉，验收④失败 |
| 8 | 隐含「初始名册只有一个 `repeat(3)` 臂」 | ❌ **三臂**：`createNewGame:286`、`restartGameInternal:353`、**`restartGameInternal:366-374` else 臂**（`sectName.isBlank()` 时 `GameData().copy(...)`，既吃 1000 灵石又完全不建名册）；且新档 1000 灵石的**真正注入点**是 `GameStateStoreImpl.kt:1498 _gameDataFlow.value = GameData()`（`resetForSlot` `:1532-1535`） | 漏第三臂 ⇒ 「重置后开局口径不一致」 |
| 9 | report-G16 §七-1 修法建议「`PortraitPool.getResourceId` 内改查 `SpriteResRegistry`」 | ❌ **违反模块边界**：`PortraitPool` 在 `:core:domain`、`SpriteResRegistry` 在 `:core:ui`，依赖方向 `domain ← ui`（根 AGENTS.md §5 2.1），反向引用 Konsist 拦 | 落点必须上移到 `:core:ui` |
| 10 | report-G16 §七-1「`portraitRes` 三处消费点」 | ❌ 实测 **8 处读点**，且 `DiplomacyChatUi.kt:97`（AIAvatar）/`:125`（PlayerAvatar）是**唯一无兜底**的读点——置为角色键后不是回落通用像，而是**完全空白**；`HeavenlyTrialComponents.kt:195-204` 另有「空值随机补一个」分支 | 照三处改会留外交头像空白回归 |
| 11 | recon §G08-3「`characterTemplates` 无任何读取方」 | ✅ 仍成立，但 recon 未点出关键事实：**`ConfigLoader` 根本不读 `game-data.json`**（`ConfigLoader.kt:109 CONFIG_PATH="config/game_config.json"`），两条通道互不相通；`game-data.json` 的唯一生产读取方是 `GameDataNativeBridge.kt:68-96 → nativeSetGameData`，**Kotlin 侧没有解析它的类** | 「加个 GameConfigData 字段」的方案不成立 |
| 12 | 上位未提 | ⚠️ **三套初始灵石口径并存**：`GameData.kt:128 = 1000`、`GameConfigData.kt:104-108 StartingSection.spiritStones = 2000`（产物 `assets/config/game_config.json:48-52`，**`.starting` 全仓零读取**）、`GameConfig.kt:215 = 50000`。只改一处 ⇒ 命中根 AGENTS.md §0.4/双真相源红线 | 同批清死段（D-14） |
| 13 | 上位未提 | ⚠️ 13+ 处测试断言 `assertEquals(1000L, …spiritStones)`（`InventoryNativeTxGateTest:150,183`、`MerchantPriceValidationTest:100,111,138,149`、`RoadFacadeImplTest:43,59`、`VassalServiceTest:441`、`SaveLoadViewModelLoadTest:699`、`RedeemCodeServiceTest:109` 等）⇒ **禁止改 `GameData` 默认值** | 改默认值 = 13+ 红 |
| 14 | 上位未提 | ✅ 已核实（主线程直读生成物）：`app/build/generated/sprite/SpriteRegistryData.kt:367 internal val SPRITES_CHARACTER` 含 12 键、`:401 register(SpriteCategory.CHARACTER, SPRITES_CHARACTER)`，由 `build-atlas.mjs --codegen` 产出、`XianxiaApplication.kt:146 registerAllSprites()` 调用 ⇒ **运行时 `resolve("portrait_zhouming")` 可解析**，G08 不需补注册 | 一路子代理把 build/ 生成物当手写源，误报「零注册」——已推翻 |
| 15 | 上位未提 | 引导：`GuideTask.kt:177` 文案仍是「累计**招募**N名弟子」（招募玩法 G05 已下线），判据读 `guideCounters["disciplesRecruited"]`，唯一自增点在 `DiscipleService.kt:161-163`；开局改道后必须继续自增，否则**新手引导卡死**（G02 先例） | D-15 |
| 16 | 上位未提 | 死码：`GameEngineLifecycleOps.kt:19-23 initializeNewGameSuspend` 全仓零调用；`GameEngine.recruitDisciple()`→`DiscipleFacadeImpl.kt:99`→`DiscipleFacade.kt:30` 链零 UI/VM 调用者；`GachaService.kt:17` 缺 `@GameService`（违反根 AGENTS.md §5.5）；`RedeemCodeManager.kt:76-83` 等多处悬空 KDoc | D-14 一并收 |

---

## 3. 决策（D-1…D-17，含依据）

| # | 决策 | 依据 |
|---|---|---|
| D-1 | 模板读取层**只落 Kotlin**：`:core:domain` 新增 `CharacterTemplate.kt`（data class + `CharacterTemplateDb` 6 条硬编码 + `byId`/`ids`/`spiritRootTypeOf`），**不在 C++ 建 `character_template_db.h`** | 本批 C++ 无需查表（§2.3：C++ 工厂零生产调用）；`docs/character-gacha-redesign-2026-09-23.md` §15.6 明禁「C++ 手写第二份概率表」；本仓 6 类静态表的既有纪律是「Kotlin Registry + RegistryGuardTest 全量比对中性源」（见 `StaticDataSingleSourceGuardTest` 头注链条），C 批新增守卫即等价兜底 |
| D-2 | **开局注入留 Kotlin**（写在既有单事务内），不下沉 C++ | `createNewGame` 末尾 `syncNativeBaselineAfterLoad():301` = `StateSyncService.importToNative()`（`:291-309`）全量重建 C++，这是**唯一合法** Kotlin→C++ 写通道；`GameEngineCoordinationTest:276-289` 已把「开局后重导基线」写成断言；强下沉需先补 C++ 模板表 + `gacha.mjs` 空段实装 + JNI 基线递增，收益为零 |
| D-3 | 灵石 50,000 走 `gameData.copy(spiritStones = GameConfig.Gacha.START_SPIRIT_STONES)` 写在开局三臂，**不改 `GameData` 默认值、不走 `SpiritStoneWallet.add`** | 产品 §7 明写「一次性开局 sink 前注入，**不进入月度收入曲线**」；`SpiritStoneWallet.recordAndEmit:393-417` 会累加 `annualIncomeBySource`/`annualTotalIncome` ⇒ 走 wallet 直接违反该口径；§2.13 的 13+ 测试锁默认值 |
| D-4 | 开局账本 = `gachaStarMap["zhouming"] = STARTER_STAR(1)` **直写**，`gachaFragmentCounts` 不含该键 | Q34「仅 star=1 + 实例存在；碎片进度 0/100」+ §4.5 其余项「**无送碎片**」。⚠️ §15.3 守卫 2 的「开局周明 key 存在且 ≥100 等效」是架构对齐段的旧措辞，与 Q34 逐项裁决记录冲突 ⇒ **按 Q34 字面执行**，差异写进 `report-G08.md` 诚实声明 |
| D-5 | 模板**只钉身份**（`name`/`gender`/`spiritRootType`/`portraitRes`/`templateId`/`realm`+`realmLayer`）；六维方差、悟性、8 技能沿用既有确定性 roll 链 | §4.3 模板字段恰为 6 项、不含任何数值字段；「无资质/悟性」读作「模板不预设」，且口径 15 明确悟性系统整体保留 ⇒ 无值可钉；`spiritRootCount = 1 + count(',')` 会随单/双灵根自动改变悟性区间，属既有生成器的正确行为 |
| D-6 | 🔴 **空 `templateId` 路径的 RNG 消费序逐字节不变**；模板路径才跳过肖像那 1 次 `nextInt` | §6.1「金黄/baseline 只在 G10 重录，中间批次 B 类逐条登记**不重录**」。子代理建议「G08 顺手把 3 条红重录为绿」**已否决**——那会把 G10 的唯一重录窗口污染成两个。⇒ 三条红必须仍与 G16 逐条同名同值（`actual=0xb4f3c6912207f597` 不变、金序列实际值不变） |
| D-7 | 碎片入账 = **C++ 单函数** `gacha_fragment.h::addFragment(GameData&, templateId, count)`（含 `while (进度>=100 && star<5)` 升星循环）+ 零 RNG native tx `GACHA_FRAGMENT_GRANT_TX = 1870`（`gacha.mjs` 空段首号）+ Kotlin 回退臂同式 + `DiffGachaFragmentTest` 对拍 | §730 字面「收敛到 C++ `addFragment(templateId,n)` 单函数（抽卡/兑换码/邮件/活动共用）、禁止多处 `fragment[key]++`」+ 口径 12；同形先例 `disciple_lifecycle_tx.h:42-48 salaryToggleTransaction`（写 `state.gameData.<Map>[key]`、无校验、回执 `{ok}`）；碎片不占仓库 ⇒ **无溢出/邮件语义**，这是它能下沉而物品不能下沉的分界 |
| D-8 | `gacha_fragment.h` 里的 `kFragmentsPerStar = 100` / `kMaxStar = 5` 为 C++ `inline constexpr`，与 Kotlin `GameConfig.Gacha` 同值，由 C 批守卫做**三向比对**（配置 `db.gachaPools[0].fragmentsPerStar/maxStar` ↔ Kotlin 常量 ↔ C++ 常量） | §15.6「禁止 C++ 手写第二份概率表」的例外是**常数**，但常数也必须有单源守卫否则漂移无人知；`GachaConfigGuardTest` 现只断配置自身 |
| D-9 | 三条直造弟子入口**全部**改道：①本地兑换码臂 ②API 服务端臂（`type:"disciple"` → **拒绝该奖励项 + 记日志**，并新增 `type:"fragment"` 携带 `templateId`）③邮件弟子附件（消费分支删除 + 新增 `SaveValidator` 规则清存量） | §6.10「未来新码不得直造弟子」若服务端臂不改，该口径在服务端路径上不成立；`§4.3:150`「37 张通用弟子肖像与程序化生成：**从获取渠道中退役**」⇒ 邮件附件也是获取渠道，必须一并退役。已实测 `type=="disciple"` 的 MailAttachment **生产侧零生产者**（唯二产生点在 `RedeemCodeRewardOps.kt:102/:144`，本批整删）⇒ 存量风险仅剩「更早版本已落库的未领取邮件」，用清理规则兜 |
| D-10 | `RedeemCodeManager.kt:99-108 predefinedCodes` 置空（唯一条目 `8982` = 10 名弟子）；`RedeemRewardType` 删 `DISCIPLE`/`STARTER_PACK`、加 `FRAGMENT`；`DiscipleRewardConfig` 整删 | §6.10「仅删除现有已配置的兑换码条目」；码表是 **Kotlin 编译期 map**，无 Entity/assets/RemoteConfig ⇒ **不动 Room、不动 proto**（`usedRedeemCodes: List<String>` 历史值原样保留，无害）；`RedeemCodeManager.kt:369 when` 穷举无 else ⇒ 枚举改动同批改 `RedeemCodeTest.kt:10-30` 的「10 个值」断言 |
| D-11 | 解析链：`:core:ui` 新增 `PortraitResolver.kt` → `resolvePortraitResId(name: String): Int`，顺序 = `PortraitPool.getResourceId`（O(1) map，37 张通用像热路径）→ `SpriteResRegistry.resolve`（15 分类线性扫）→ 0；**8 个消费点**统一改它，并给 `DiplomacyChatUi` 两处补上现无的通用像兜底 | §2.9 模块边界；§2.10 消费点实测数；两域互斥（37 通用像**不在** registry，`grep -c male_disciple android/scripts/resource-registry.json` = 0）⇒ 顺序不影响正确性、只影响热点列表性能 |
| D-12 | `PortraitPool` 的 37 键清单、`getRandomPortrait` 语义、`ResourcePreloader.kt:205` 预载清单**一律不动**；**禁止**把 12 个角色键塞进 `PortraitPool`/预载 | `PortraitPoolTest:12 assertEquals(37,…)` 会红；且 `MAX_PORTRAIT_DIMENSION=256` 会把 1024 档立绘降质预载——G16 D-6 的「大图类不预载」决策会被推翻 |
| D-13 | 限持 1：`instantiateTemplate(templateId)` 若名册已存在同 `templateId` 弟子 ⇒ 返回 `DomainResult.Failure(TemplateAlreadyOwned)`，不入库；判定用 `DiscipleTables` 的 `templateIds` 列（不装配整对象） | 实施计划 G08 验收「周明限持」+ 对抗审查要点「限持双实例」；G09 解锁实例化共用此判定 |
| D-14 | 同批清理（不留尾巴）：删 `GameEngineLifecycleOps.kt:19-23 initializeNewGameSuspend`（零调用死码）、删 `GameConfigData.kt:27/:104-108 StartingSection` + `assets/config/game_config.json` 的 `starting` 段（第三套灵石口径、零读取）、`GachaService` 补 `@GameService("GachaService")`、修 `InitialMineSizeGuardTest.kt:31` 指错文件名、修 `RedeemCodeManager.kt:76-83` 与 `RedeemCodeRewardOps.kt:44-50/:92` 悬空 KDoc、`DiscipleFactory.kt:57/:72` 与 `disciple_factory.h:4-8/:176-177` 的过期注释（「三站点/createChild/refreshRecruitList」） | 根 AGENTS.md §1 公约 13「清理一次性代码」+ `rules/code-comment.md` 七项（注释只描述当前状态）+ §0.4 魔法数字/双真相源 |
| D-15 | 引导：`GuideTask.kt:177` 文案改「累计**入门**N名弟子」；`GuideCounterKeys.DISCIPLES_RECRUITED = "disciplesRecruited"` **键名与常量都不改**（改键 = 旧档计数器归零 = 引导倒退）；开局实例化必须在同一事务内继续 `guideCounters[DISCIPLES_RECRUITED] += 1` | §4.5「不得引用已删除的旧招募文案」；G02 先例「玩法下线后引导必须改判据，否则卡死」；`GuideTaskTest:323/374` 断言键名字面量 ⇒ 改键必红且真伤害存档 |
| D-16 | 年报口径**定论**：解锁/开局入宗**计入** `annualNewDisciples`。G08 落开局这一笔（Kotlin 事务内 `+1`）；G09 在 C++ 解锁分支接线（本批不预留空壳） | 实施计划 §开放项 3「产品方案倾向计入，G08 须落一种并在 G05 预留的计数点接线」；G05 保留的三个写入点（`DiscipleService.kt:164`、`RedeemCodeService.kt:339/:504`）中后两个随改道删除，故本批必须明确计数语义归属 |
| D-18 | 碎片入账的对拍**不得新增 `external fun`**（铁律 3 + `check-jni-count` 恒 86/86）：首选既有动作执行通道（`GameCoreBridge.nativeExecuteAction` / `StateSyncService` 路径）驱动 native tx 做真对拍；若既有 Diff 夹具确实无法驱动 dispatch，则退化为「**同一组 golden 向量在 C++ GTest 与 Kotlin JUnit 各跑一遍**」的双守护等价形态，并须在 `report-G08.md` 明写退化原因，不得静默 | `gamecore/AGENTS.md`「测试双守护」+ 根 `AGENTS.md` §2 的 `check-jni-count.mjs` 门；`external fun` 只允许出现在在册的两个桥文件 |
| D-19 | 账本保持**稀疏**：升星结果为 `star == 0` 时**不写** `gachaStarMap` 键（等价于缺省 0），`count <= 0`/空 id 不建任何空条目；🔴 两侧拒绝条件统一为 **`empty()` / `isEmpty()`**（C++ 用 `empty()`、Kotlin 用 `isBlank()` 会让「纯空白 id」两侧行为不同 ⇒ 对拍分歧），且 `DiffGachaFragment` 侧的 golden 向量不得用空白 id 试探 | 否则 `GameData.kt:814` 的「未解锁无键」注释失真、且验收③（`gachaFragmentCounts` 不含 zhouming）与「授予过但未满星也建键」自相矛盾；A-3 实测已把这一分歧摆到台面 |
| D-20 | 门面签名定为 **2 参** `grantFragments(templateId, count)`，🔴 不加 Kotlin-only 的 `source` 形参 | native params 只有 `templateId`/`count`（T-2 落地），加一个 C++ 侧无落点的形参就是为假想需求建模（根 AGENTS.md「不为假想的未来需求设计」）；发放来源审计已由 `SpiritStoneSource` / `withTrackingSource` 在物品与灵石分支承担，碎片不占仓库、无需来源名 |
| D-17 | C++ 侧只改 `disciple_factory.h`（seed +`templateId` +`portraitResOverride`、`createDisciple` 模板分支）+ `determinism_probe.h`/`GameCoreJni.cpp` seed 面 + `disciple_factory_test.cpp`（保留空模板三条用例原断言值不动，新增模板分支用例）。**AI 旁路 `ai_sect_recruit.h:105-165` 保留死面 + 加 KDoc 钉死「AI 弟子不走模板、`templateId` 恒空」**，本批不删 | 口径 9「AI 宗弟子构造保持旁路」；`report-G05.md:41` 记「死面按指令保留候 G08/G10」⇒ 本批的处置是「显式声明 + 交 G10 决定是否连 `ai_sect_ops` 面一并重建」，删除会连带打断 `item_id_reseed_test.cpp:133,154` 与 `ai_corpse_budget_test.cpp:116` 两条在册守卫 |

### 3.1 C++ REDEEM_* 端口的退役边界（配套 D-8/D-9）

| 号 | 名称 | Kotlin  counterpart | 本批处置 |
|---|---|---|---|
| 1435 | `REDEEM_ROLL_SPIRIT_ROOT` | `SpiritRootGenerator`（仍活，AI 臂 `Generation.kt:11` 在用）+ C++ `spiritRootGenerate`（`ai_sect_recruit.h:118`）；C++ `resolveSpiritRoot` 的默认分支走 `spiritRootGenerate`（`redeem_code.h:245`），**不依赖** `rollBySpiritRootCount` ⇒ 该 helper 可安全删 | **保留端口**（`DiffRedeemCodeTest:86-99` 对拍基准仍成立） |
| **1436**（TASKBOOK 初版误记为 1437；1437 是 G04 已退役的 `REDEEM_ROLL_SKILLS`） | `REDEEM_RESOLVE_AGE_LIFESPAN` | `RedeemCodeRewardOps.resolveAgeAndLifespan:332-349`（返回值本就丢弃，`Disciple` 无 age/lifespan 列） | **退役**：删 Kotlin 死码 + C++ `redeem_code.h` 的 `resolveAgeLifespan` + `execute_dispatch.cpp` case + `redeem_code_test.cpp` `:90`/`:101` 两例 + `core.mjs` desc 标【已退役，编号禁复用】+ `dispatch_guard_test.cpp:105-129` retired 集登记 |
| 1438 | `REDEEM_GENERATE_VARIANCE` | `generateVariance:353`（唯一调用方 `generateDisciple:439-446`） | **退役**（同上处置；另连带删 `rollBySpiritRootCount` helper 与 `redeem_code_test.cpp:66` ⇒ **C++ 用例净 -4 条，非 -3**；`buildRedeemSkills` 无 C++ 端口，纯 Kotlin 删） |
| — | `REDEEM_VALIDATE_INPUT` / `MAIL_ATTACHMENT_ENCODE` | 活 | 保留 |

🔴 **保号留洞**：`kAllActionIdsCount` 恒 **198**、`maxId=1861` 不变，`node scripts/gen-action-ids.mjs` 后 regen 前 cp 两份与工作树**自比**判幂等（§2.3 坑 12），retired 集 **22 → 24**（T-3 实测：catalog↔guard 按符号名双向零差集）。
🔴 **端口行实测位置**：`execute_dispatch.cpp` 全文 2554 行，端口链在 `:2524-2530`（上位 recon 记的 `:2774-2787` 已失真）；`dispatchGacha` 前置声明登记在 `src/execute_dispatch.cpp` 的 `namespace gamecore {` 首行处（`:61-64`），签名为 `std::optional<nlohmann::json> dispatchGacha(GameCore& core, int32_t actionId, const nlohmann::json& params);` —— **T-2 的定义必须落在同一命名空间/同一签名**，否则 undefined symbol。

---

## 4. 文件面与切片（≤10 文件/片，共 14 片 + 主线程）

> 分片并行期整树不可编译 ⇒ 🔴 **子代理一律禁止跑 `gradlew` / `cmake` / `ctest` / 任何写盘 node 脚本**（§8.C-3），
> 只做精确编辑 + `grep` 自检；全部门禁由主线程终树复跑。改自己片外的文件即算越界，须停手报告。

| 片 | 允许改 | 禁止改 |
|---|---|---|
| **T-1** C++ 工厂 | `gamecore/include/gamecore/system/disciple_factory.h`（seed `:178-186` +`templateId`/`portraitResOverride`；`createDisciple:189-239` 加模板分支：非空 override ⇒ 用之并**跳过** `:219-221` 的 1 次 `nextInt`、写 `d.templateId`；空 ⇒ 逐字节走现路径；修 `:4-8`/`:10-21`/`:176-177` 注释）、`include/gamecore/determinism_probe.h:174-182`（seed 补字段、保持空模板）、`jni/GameCoreJni.cpp:225-240`（seed 解析 `templateId`/`portraitRes` 两 key）、`test/disciple_factory_test.cpp`（`:25-35 kSeed()` 补字段；**`:42/:79/:111` 三条原断言值一字不改**；新增 2 例：模板 override 生效 + 空模板序列不变） | 任何 `.kt`、`action-catalog/*`、生成物、Room、docs |
| **T-2** C++ 碎片入账 | 新建 `include/gamecore/system/gacha_fragment.h`（`inline constexpr kFragmentsPerStar/kMaxStar` + `addFragment(GameData&, tid, n)` 入账/升星 + `grantFragmentsTransaction(GameState&, params)` 回执）、新建 `src/dispatch_gacha.cpp`（`dispatchGacha()` 认领 1870–1889）、🔴 **`src/execute_dispatch.cpp` 归 T-3 独占**（本片不得碰该文件，端口认领行由 T-3 代加，见下行）、新建 `test/gacha_fragment_test.cpp`、新建 `test/gacha_tests.cmake`、`test/CMakeLists.txt`（`:170-176` 段后追加 `${GACHA_TEST_SOURCES}`）、🔴 `gamecore/CMakeLists.txt`（把 `src/dispatch_gacha.cpp` 加进 **game-core 库源列表**，照 `src/dispatch_w4a.cpp` 的登记方式——桌面测试与 Android 共用该列表，漏登记即链接不到符号）、`scripts/action-catalog/gacha.mjs`（`CATALOG += {id:1870,name:'GACHA_FRAGMENT_GRANT_TX',desc:'角色碎片入账事务（零 RNG；碎片累加+满 100 升星；抽卡/兑换码/邮件/活动共用）'}`） | 生成物 `action_ids.h`/`ActionIds.kt`（主线程跑生成器）、`.kt`、docs |
| **T-3** C++ REDEEM 退役 | `include/gamecore/system/redeem_code.h`（删 `resolveAgeAndLifespan:191-202`、`generateVariance:187`、`rollBySpiritRootCount:171-184`；🔴 **`spiritRootGenerate`/`JavaRandomCompat`/`kotlinTrim`/`validateRedeemInput` 必须留**——AI 与活路径依赖）、`src/execute_dispatch.cpp`（**本片独占该文件**：删 1437/1438 两个 case + 代 T-2 追加 1 行 gacha 端口认领，照 `:2774-2787` 的 W4 端口 `else if (... dispatchW4A(...) )` 范式，🔴 不并入裸区间——1730 吞号事故先例；另需在派发头/前置声明处登记 `dispatchGacha` 的声明，照 `dispatchW4A` 同位置）、`test/redeem_code_test.cpp`（删 `:79,:90,:101` 三例）、`test/dispatch_guard_test.cpp`（retired 集 +2；1870 可达性自动枚举）、`scripts/action-catalog/core.mjs`（🔴 **只改 1437/1438 两行 desc，绝不删条目/改号**） | `gacha.mjs`（T-2 独占）、`.kt` |
| **A-1** 模板层（domain） | 新建 `core/domain/.../model/CharacterTemplate.kt`（`@Immutable data class CharacterTemplate(id,name,gender,spiritRoots:List<String>,avatarKey,portraitKey)` + `object CharacterTemplateDb{ ALL/BY_ID/STARTUP_REALM=9/STARTUP_REALM_LAYER=1/STARTER_STAR=1 }`，6 条内容**逐字取自** `android/app/src/main/assets/data/game-data.json` 的 `db.characterTemplates`）、`core/domain/.../core/GameConfig.kt`（`object Gacha :208-243` 内补 KDoc 指认「开局常量单源在 CharacterTemplateDb」） | `src/test`、`.cpp/.h`、生成物 |
| **A-2** 构造与开局 | `core/engine/.../domain/disciple/DiscipleFactory.kt`（`DiscipleSeed:76-85` +`templateId`/`portraitResOverride`；`create:88-127` 模板分支；修 `:57/:72` KDoc）、`domain/disciple/DiscipleService.kt`（删 `recruitDisciple:120-170` ⇒ 新增 `instantiateTemplate(templateId)`：查 `CharacterTemplateDb` → 限持判定 → 绕 `NameService`/`SpiritRootGenerator` → 工厂 → `allocateAndInsert` + `lifeEvents += "加入宗门"` + `guideCounters` + `annualNewDisciples`；🔴 删 `:123-125` 对恒空 `recruitList` 的空转读取）、`domain/disciple/DiscipleFacade.kt:30`、`domain/disciple/DiscipleFacadeImpl.kt:99`、`GameEngineDiscipleOps.kt:29`、`GameEngineLoadDataOps.kt`（**三臂同改**：`:286`、`:353`、`:366-374` else 臂；三处 `gameData.copy` 各加 `spiritStones = GameConfig.Gacha.START_SPIRIT_STONES`；`:255-287`/`:327-354` 事务内实例化周明 + `gachaStarMap` 直写；`:389-395` AI 立绘分配**不动**） | `src/test`、`.cpp/.h`、Room 迁移、docs |
| **A-3** 碎片入账（Kotlin） | 新建 `core/engine/.../domain/gacha/GachaFragmentLedger.kt`（`grant(gameData, templateId, count)`：与 C++ **同式**的入账 + `while` 升星，禁止 `fragment[key]++` 外泄）、`domain/gacha/GachaService.kt`（补 `@GameService` + 唯一公开入账口）、`domain/gacha/GachaFacade.kt`（+`suspend fun grantFragments(templateId, count, source): GachaGrantResult`）、`domain/gacha/GachaFacadeImpl.kt`（native 臂 + 回退臂 + 失败 sealed）、新建 `core/engine/.../nativebridge/GachaNativeTx.kt`（`tryExecuteNative(ActionIds.GACHA_FRAGMENT_GRANT_TX, …)` + `applyDirtyFromNative`，抄 `DiscipleLifecycleNativeTx.kt:29-49` 的 `NativeEngineFlag.authoritative` 门控） | `RedeemCode*`（A-4）、`.cpp/.h`、`src/test` |
| **A-4** 兑换码/邮件改道 | `core/engine/.../RedeemCodeRewardOps.kt`（删 `addDiscipleRewards:84-110`、`addStarterPackRewards:114-152`、`buildRedeemDisciple:224-263`、`buildRedeemSkills:267-287`、`rollBySpiritRootCount:291-300`、`resolveSpiritRoot:304-328`、`resolveAgeAndLifespan:332-349`、`generateVariance:353` ⇒ 新增 `addFragmentRewards`（按 `templateId` 生成 `RewardSelectedItem(type="fragment")` 并入账）；修 `:44-50/:92` 悬空 KDoc）、`RedeemCodeManager.kt`（`predefinedCodes:99-108` 置空；删 `generateDisciple:422-461`；`when:369` 改 `FRAGMENT` 分支、删 `DISCIPLE`/`STARTER_PACK`；修 `:76-83` 悬空 KDoc）、`service/RedeemCodeService.kt`（API 臂 `:233/:326-342` 改拒绝+日志；本地臂 `:478-497` 弟子入表删除；🔴 **`:339`/`:504` 两处 `annualNewDisciples` 写入点随之消失**（D-16 计数改挂开局/解锁）；`:433/:511/:526` 弟子文案与卡片过滤同步）、`service/MailAttachmentVariantsOps.kt`（删 `distributeDiscipleAttachment:88-113` 整函数 + `:146` 分支）、`service/MailAttachmentDistributeOps.kt`（删 `:243`/`:295` 两个 `"disciple"` 分支）、`core/domain/.../model/RedeemCode.kt`（枚举删 2 值 + 加 `FRAGMENT` + 删 `DiscipleRewardConfig`） | `src/test`、`.cpp/.h` |
| **A-5** 存档与引导 | 新建 `core/data/.../integrity/rules/MailDiscipleAttachmentCleanupRule.kt`（`id="mail_disciple_attachment_cleanup"`，把存量邮件里 `type=="disciple"` 的附件摘除，恒 Repaired 以触发落盘并记明细）、`core/data/.../integrity/rules/SaveValidationRuleDefaults.kt`（注册 1 行，`order` 取未占用值、**注释写明与相邻规则的先后理由**）、`core/domain/.../model/guide/GuideTask.kt:177`（文案改「累计入门」）、`core/domain/.../config/GameConfigData.kt`（删 `:27 starting` 与 `:104-108 StartingSection`）、`app/src/main/assets/config/game_config.json`（删 `starting` 段——改前 grep `.starting` 与 `starting` 测试命中，确认零消费方） | `src/test`、`.cpp/.h`、Room 迁移本体 |
| **A-6** 解析链（core/ui） | 新建 `core/ui/.../components/PortraitResolver.kt`（D-11 语义 + KDoc 写清两域互斥与顺序理由）、`core/domain/.../util/PortraitPool.kt`（**只加 KDoc**：池=37 张通用像、角色键属 `SpriteCategory.CHARACTER`、禁止入池/入预载） | `src/test`、业务写路径 |
| **A-7** 解析链消费点（feature/game） | `ui/components/DiscipleComponents.kt:175-179`、`components/DiscipleSlotComponents.kt:57-70` **与** `:238-251`（🔴 保留 `beast_*` 前置分支）、`components/detail/DetailRightPanel.kt:110-116`、`dialogs/DiscipleChatDialog.kt:393-399`、`dialogs/SecretRealmComponents.kt:51-54`、`dialogs/heavenlytrial/HeavenlyTrialComponents.kt:186-204`（含 `:195-204` 空值随机分支加注释）、`dialogs/DiplomacyChatUi.kt:97`、`dialogs/DiplomacyChatUi.kt:125`（两处**补通用像兜底**） | `:core:*`、`src/test`、`ResourcePreloader` 预载清单 |
| **c340-1** 工厂/开局测试 | `core/engine/src/test/.../domain/disciple/DiscipleFactoryTest.kt`（`:18-32 newSeed`、`:150/154` 中文性别 `"男"/"女"` ⇒ 改 `"male"/"female"`、新增模板强制断言例）、`nativebridge/DiffDiscipleFactoryTest.kt`（`:86-102` seed+JSON 补两 key、新增模板用例）、`GameEngineCoordinationTest.kt`（开局名册/灵石/star 断言）、`feature/game/src/test/.../InitialMineSizeGuardTest.kt`（文案文件名修正）、`core/data/src/test/.../RoomMigrationV53To54Test.kt` 与 `serialization/unified/SaveDataDirectSerializationTest.kt`（**保持绿，预期零改动**；若红即说明动了 schema） | `src/main`、`.cpp/.h` |
| **c340-2** 模板/入账守卫测试 | 新建 `core/engine/src/test/.../nativebridge/CharacterTemplateGuardTest.kt`（三向：`CharacterTemplateDb.ALL` ↔ `game-data.json db.characterTemplates` 逐字段 ↔ `portraitKey/avatarKey ∈ 可解析域` ↔ `GameConfig.Gacha`/`CharacterTemplateDb` 开局常量 ↔ C++ `kFragmentsPerStar`（读 `gacha_fragment.h` 文本比对，§2.3 坑 9 同族手工复刻表））、新建 `nativebridge/DiffGachaFragmentTest.kt`（JNI 对拍：多档 count 跨界升星、已 5 星溢出、star 不降级）、`nativebridge/GachaConfigGuardTest.kt`（`:52-57/:91-96` 的 `assumeTrue` **改硬断言**（X-2 #6 口径）+ 补「碎片/星 map key ⊆ 模板 id」「开局 id 在模板表内」两断言）、`app/src/test/.../GachaCharacterSpriteGuardTest.kt`（补 1 例：每个 `portraitKey` 经 `PortraitResolver` 解析非 0） | `src/main`、`.cpp/.h` |
| **c340-3** 兑换码/构造路径/解析链测试 | `RedeemCodeServiceTest.kt`（`:101-144` 两条弟子断言 ⇒ 重写为碎片断言 + 「重复领取不双增」）、`core/domain/src/test/.../RedeemCodeTest.kt`（`:10-30` 枚举数量/成员、`:34-84/:157-168` `DiscipleRewardConfig` 用例删）、`RedeemCodeManagerTest.kt`（新增守卫：`predefinedCodes` 不含任何 DISCIPLE/STARTER_PACK 码）、`core/architecture/WallClockReflowGuardTest.kt:176-181`+`:192`（🔴 文件名清单：本批**不改名不删文件** ⇒ 预期零改动，若红即说明越界删了文件）、`nativebridge/DiffRedeemCodeTest.kt:32-34`（已知边界注释按死码清理重写）、新建 `core/architecture/DiscipleCreationPathGuardTest.kt`（AGENTS.md §9.5 三要素：枚举驱动「弟子入册入口 = `DiscipleTables.allocateAndInsert`/`insert` 调用点」遍历 + `intentionallyExcluded`（测试夹具/AI 容器/导入链）+ 失败消息写「新入册口必须走模板实例化或登记排除」）、新建 `core/ui` 或 `app` 侧 `PortraitResolverTest.kt`（37 通用名 / `portrait_zhouming` / `beast_3` / 空串 / 未知名 五路） | `src/main`、`.cpp/.h` |
| **主线程** | 生成物两份（`node scripts/gen-action-ids.mjs`）、`docs/threading-contract.md` 登记（新跨语言动作 1870）、双 changelog、`report-G08.md`、HANDOVER-3 回写、**全部门禁与提交** | — |

---

### 4.1 主线程已完成项与已否决项（实跑记录，非计划）

**已落地（主线程亲自改，均为片外文件）**：`node scripts/gen-action-ids.mjs`（**199 动作 / maxId=1870**；两次跑与工作树自比幂等；对 HEAD 差异 = 1870 新增 + 1436/1438 两条 desc 退役标注）；`scripts/build-desktop-jni.ps1` 与 `-linux.sh` 源清单补 `src/dispatch_gacha.cpp`；`GameSystemRegistryDefaults` 补 `register("engine.domain", "GachaService")`；`docs/threading-contract.md` 表四登记 1870 与双臂回退契约；`AppError.Domain.Disciple` 补 `TemplateUnknown`(DISCIPLE_006) / `TemplateAlreadyOwned`(DISCIPLE_007)，`DiscipleService` 两个失败叶子随之替换、`ownsTemplate` 改返回拥有者 id；D-19 两侧对齐（C++ `star > 0` 才写 `gachaStarMap`、Kotlin `isBlank()` → `isEmpty()`）；D-14 死码清理（删 `initializeNewGameSuspend`、删 `DiscipleConstants.GENDER_MALE/FEMALE`、删**第四套**灵石口径 `GameConfig.Starting` + `StartingResources` + `GameConfigData.starting` + `StartingSection` + `game_config.json` 的 `starting` 段并同步删 5 条测例、`SettingsTab` 的 `"disciple"` 分支改 `"fragment"`、`PortraitImage.kt` 的 `@param name` KDoc 改口径）。

**已否决的片内建议（各有实测依据）**：① `MailEntity.kt:88` 附件类型 KDoc 更新（A-4 提出，实测该文件乃至全仓 `src/main` 都不含把 `disciple` 列为附件类型的文档串）；② `build-desktop-jni*.sh` 顺带补 `src/data_store.cpp`（预存遗漏、与本批无关 ⇒ 登记 G10）；③ `BeastAndStartingConfigTest` 改名（`docs/cpp-migration-handover-m0.md:219` 按名字引用它，改名收益为负）；④ `MailAttachment.extra` 字段删除（邮件 wire 协议面，本批只是失去消费者，删除属独立协议退役）；⑤ A-4 关于「肖像 roll 双臂都消费一次 ⇒ 验收⑥ 不可能成立」的判断（实测 `PortraitPool.getRandomPortrait` 只做 1 次 `nextInt`、无权重掷数量，且 `resolvePortraitRes` 命中模板键即早退 ⇒ 验收⑥ 成立）。

**测试期发现的既有失配（本片外，主线程登记）**：`GameConfig.Disciple.MIN_AGE` 与 `GameConfigData.disciple.minAge` 仍在（G02 已删弟子年龄），`GameConfigConsistencyTest` 仍断言「年龄最小值两源一致」⇒ 死配置面残留，交 G10。

---

## 5. 门禁清单（主线程终树同轮重跑，判据是命令输出原文）

| 门 | 命令（`dir_path = C:\Mnzm\XianxiaSectNative\android`，仓库根注明） | 预期（G16 基线 → G08 目标） |
|---|---|---|
| 桌面 C++ 重编 | `cd app/src/main/cpp/gamecore/build/desktop-test` + PATH 三段前置（上位 §2.1）+ `cmake --build .` | 🔴 **本批必跑**（G16 未重编）：`[N/N] Linking game-core-tests.exe`，新增 `gacha_fragment.h`/`dispatch_gacha.cpp` 进构建图 |
| 桌面 ctest | `ctest` | 总数 **1394 → 1394 + 新用例数**（T-1 模板 2 例、T-2 `gacha_fragment_test` 若干、T-3 删 3 例）；失败集 = **仍是同名 3 条 B 类**（`DiscipleFactory.GoldenSequenceSeed42`/`…987654321Female`/`DeterminismProbeTest.DigestMatchesGoldenBaseline`），🔴 **零新增**；`-R SceneEquivalence` 仍 **13/13**（本批不动图集） |
| `DeterminismProbe` | `ctest -R Determinism` | `actual=0xb4f3c6912207f597` **不变**（D-6：空模板路径零平移） |
| Kotlin 编译 | `./gradlew.bat compileReleaseKotlin` | BUILD SUCCESSFUL |
| 测试源编译 | 六模块 `compileReleaseUnitTestKotlin` | 0 错误 |
| JNI 对拍库 | `pwsh -File scripts/build-desktop-jni.ps1`（🔴 **必须 `pwsh` 不是 `powershell`**，坑 11）+ 验 `.so` mtime/字节**确实变化** | `desktop-jni/libgamecorejni.so` 重建成功 |
| JUnit 全量 | `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks --continue "-Dgamecore.jni.path=<绝对 .so>"` | **7343 + 新增用例数 / 0 失败 / 0 错误 / 18 skip**，按 XML **逐模块**汇总对账（坑 5：读它自己的日志；坑 7：`--tests` 过滤须模块限定） |
| detekt | 六模块 `./gradlew.bat detekt` | 绿；`detekt-baseline.xml` **零改动**（只缩不增）。⚠️ 坑 13：`detekt` 不覆盖 `src/test` ⇒ 新写测试另跑 `:<模块>:detekt<Variant>UnitTest` 自查，**本批零新增违规**为判据 |
| lint | `./gradlew.bat lintRelease` | `0 errors / 36 warnings` 且按本批符号过滤**零命中**（全预存）；跑完查 `atlas-rgba-manifest.json` 是否被写脏（坑 2） |
| ActionId | 仓库根 `node scripts/gen-action-ids.mjs`（🔴 **无 `--check`**，零参数覆写两份） | 🔴 **198 → 199 动作、`maxId` 1861 → 1870**（新增 1870 落在 G 批独占空段，是合法递增不是漂移；`kAllActionIdsCount` 仅作循环上界、无硬断言）。零漂移判据 = regen 前 `cp` 两份、regen 后与**工作树自比**（坑 12），对 HEAD 的差异须逐行解释为「本批有意新增 1870」 |
| 🔴 桌面 JNI 源清单 | `scripts/build-desktop-jni.ps1:58` 与 `scripts/build-desktop-jni-linux.sh:38` 是**手写源文件清单**（非 glob） | 两处均**漏** `src/dispatch_gacha.cpp` ⇒ 不补则桌面对拍 `.so` undefined symbol、**全部 `Diff*Test` 红**（T-2 实测发现）。同批补 `src/data_store.cpp`（预存遗漏，现无人引用故未暴露）。改完验 `.so` 的 mtime 与字节数**确实变化**（坑 11） |
| 注册面 | `core/engine/.../registry/GameSystemRegistryDefaults.kt`（`engine.domain` 段） | 🔴 必须补 `register("engine.domain", "GachaService")`，否则 `GameSystemRegistryCoverageTest` 判红（A-3 实测发现，该片外文件两片都不拥有） |
| catalog↔guard 退役集 | 脚本扫 `scripts/action-catalog/*.mjs` 六文件 + `dispatch_guard_test.cpp` | 🔴 **本批必重跑**（G16 未跑）：catalog **199** == 生成器 **199**；retired **22 → 24** 双向零差集（T-3 已按符号名实测零差集） |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef` **逐字符不变**（D-1 未动中性源） |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **86 / 86 不变**（新事务复用 `tryExecuteNative`，禁新增 `external fun`） |
| Room | — | `DATABASE_VERSION` **保持 59**、`disciples` 90 列 / `game_data` 128 列、`schemas/59.json` 零改写（§2.1：本批**无迁移**） |
| 图集/精灵 | `node scripts/build-atlas.mjs --codegen` | `sprites=41 / layoutHash 6a122ed22d66bee5` **不变**；`SpriteCodegenSyncTest`/`SpriteSourceMappingGuardTest`/`ResourceManifestCompletenessTest`/`GachaCharacterSpriteGuardTest` 同轮绿 |
| 规范门禁 | `node scripts/check-agent-instructions.mjs`（仓库根） | `✓ 全部通过`；🔴 **预存告警必须仍是 2 条、规则③ 仍 1 处**（坑 14：本批若写文档引用必须给全路径） |
| 守卫判别力自证 | 逐条「退回旧状态」实验：藏 `CharacterTemplateDb` 一行 → `CharacterTemplateGuardTest` 判红；只改 C++ `kFragmentsPerStar` → 三向断言判红；`portraitRes` 置角色键但 `A-7` 未改 → `PortraitResolverTest`/新守卫判红 | 每条判红后**当场还原并复跑判绿**，做法与结果写进 `report-G08.md` |

---

## 6. 本批登记项 / 产品歧义（写进 `report-G08.md` 末节）

| # | 项 | 归属 |
|---|---|---|
| 1 | D-4 的规格冲突：§15.3 守卫 2「开局周明 key 存在且 ≥100 等效」vs Q34「碎片进度 0/100」+ §4.5「无送碎片」⇒ 本批按 **Q34 字面**执行，`gachaFragmentCounts` 不含 zhouming 键 | 已在 §3 D-4 裁定，report 复述 |
| 2 | D-5 的解读：§4.3「无随机 roll」按「模板不预设数值」执行，六维/悟性/技能仍 roll。若产品实际要「模板弟子数值也固定」，那是**独立数值口径**（模板表须先加 10+ 个数值字段），不在本批 | 待拍板（report 列明） |
| 3 | 重名：模板名固定后 `NameService` 的 50 次冲突循环 + 兜底后缀对模板路径不再适用；AI 宗弟子与玩家模板弟子重名**当前无防护**（两者在不同容器） | G10/待拍板 |
| 4 | 模板弟子入册后 `Disciple.name` 终身固定（改名已 G06 删），但 `SectDetail.portraitRes`（`GameEngineLoadDataOps.kt:389-395`）与 AI 臂仍共用同一解析链 | 已随 A-6/A-7 一并覆盖，无残留 |
| 5 | `ai_sect_recruit.h` C++ 死面（G05 按指令留候 G08/G10）本批保留 + 加口径注释；`RedeemCodeRateLimitOps`/`RedeemCodeLifecycleOps` 内与改道无关的悬空 KDoc 未逐条清 | G10 |
| 6 | `scripts/split_engine/specs/redeem_code.json` 仍列 15 个函数（含 G04 已删的两个 + 本批新删的）⇒ 拆分工具函数清单继续失真（`budget.py` 不校验存在性故不判红） | G10 |
| 7 | 真机验证：本批所有显示链改动（周明立绘/头像、外交头像）只有静态证据链，**无视觉证据**，与全停摆的 `pending-device` 批同批做 | pending-device |
| 8 | `assembleRelease` 未跑（包体无新增素材，本批不涉及资源体积） | 与历史批同口径 |
| 9 | 🟡 **档位错配（A-7 实测新发现，本批未改）**：`portraitRes` 是弟子实例唯一的持久化显示字段，模板弟子置为 `portrait_<id>`（1024 档全身像）⇒ 40~56dp 的列表头像位会按需解码全身像，而 **`avatar_<id>`（512 档头像）在本批之后仍无消费方**。修法需要「小头像位改读 `avatarKey`」，但 `DiscipleSlotComponents` / 战报槽位只收 `portraitRes: String` 形参（不收 `Disciple`），改口径要动一串签名与调用点；且头像位/图鉴/结果页的取键规则本属寻访 UI 的显示尺寸口径 ⇒ 归 G11 一并定 | **G11** |
| 10 | 🟢 `PortraitImage.kt:19` KDoc 仍称 name 是「`PortraitPool` 键」，而 resolver 已允许已注册精灵名走第二跳（角色键必然 cache-miss → `painterResource` 按需解码，属 G16 D-6 有意行为）；A-7 片外文件未越界改 | 主线程集成期一并改（一行 KDoc） |

---

## 7. 一句话给执行者

本批**最难的不是写代码，是抵住两处诱惑**：(1) 顺手把 3 条金黄重录成绿（§6.1 红线，D-6 已给出「零新增 B 类」的正确做法）；(2) 顺手给 `Disciple`/`game-data.json` 加字段来「补齐模板」（D-1/D-5 已钉死模板只有 6 项、开局数值走常量）。越界即污染 G09/G10 的基线口径。
