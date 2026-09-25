# G09 实施输入侦察（抽卡核心 `gacha_tx`）· 只读复核

> 基线：`HEAD=6c5e0daa1`（2026-09-25）。**逐行号为本轮实测**，与 `recon-G05-G06-G08-G09.md` 的旧行号有漂移（该文件 G01–G08 段落记的是 2026-09-23 快照，见文末「漂移勘误」）。
> 纪律：本文件只读侦察产物；不含实现。

## 1. Kotlin 抽卡现有面（逐文件 · 空壳标注）

| 文件:行 | 现状 | G09 影响 |
|---|---|---|
| `android/core/engine/.../domain/gacha/GachaFacade.kt:13-63` | 接口 6 成员：`pityCounters:15` / `fragmentCounts:18` / `starMap:26` / `history:29`（四个 `StateFlow`）+ `pullOnce:35` / `pullTen:38` / `grantFragments:62` | `pullOnce/pullTen` 签名已定，**返回值不可承载结果**（见下行） |
| 同上 `:66-69` `GachaPullResult` | **仅 `NotReady:67` + `Failure(reason):68`，无 Success/结果项** | 空壳。G09 必加 `Success`（10 抽结果项 DTO）；`When` 穷尽面波及 `RedeemCodeServiceTest.kt:311-313` 的 `RecordingGachaFacade`（须同批实现新分支） |
| 同上 `:72-91` `GachaGrantResult` | `Granted(starBefore/starAfter/fragmentsAfter):80-84` / `Invalid:87` / `Failure:90` —— **已实现，非空壳** | 碎片入账结果面已冻结；G09 复用它做升星回执，禁改三态语义 |
| `GachaFacadeImpl.kt:40-43` | 四个 StateFlow 直转发 `gachaService` | 无需改 |
| 同上 `:45` | `private val nativeTx by lazy { GachaNativeTx(gameEngineCore) }` —— **构造已注入 `GameEngineCore`**（旧侦察记「无 core」已失效） | G09 走既有 `tryExecuteNative` 通道，零新增 `external fun` |
| 同上 `:47-51` | `pullOnce/pullTen` = **恒 `return GachaPullResult.NotReady`** | **空壳=G09 主战场** |
| 同上 `:53-66` | `grantFragments`：`isEmpty():56` / `count<=0:57` 预闸 → native 臂 `:58` → Kotlin 回退臂 `:65` | 预闸判据 `isEmpty()`（非 `isBlank()`）已被 `DiffGachaFragmentTest` 钉死；G09 的抽卡事务**必须复用本入口**入碎片（禁第二条写者） |
| 同上 `:30-38` | 三个 reason 常量 `INVALID_TEMPLATE_ID` / `INVALID_COUNT` / `LEDGER_REJECTED` | 文案口径沿用 |
| `GachaService.kt:32-46` | 四个 `stateStore.gameData.map{}.stateIn(Eagerly)` 只读派生 | UI 取数面唯一入口（G11 用） |
| 同上 `:58-73` | `grantFragmentsLocally` = `stateStore.updateAndReturn` 单事务读写两账本 | Kotlin 回退臂；G09 若在 Kotlin 侧做 roll 会被 `RngSourceGuardTest` 拦（见 §5） |
| `GachaFragmentLedger.kt:32-93` | `internal object`；`:35-38` 常量取 `GameConfig.Gacha`；`:49-55` `GrantOutcome`；`:69-93` 纯函数 `grant`（稀疏星级账本 `:88`） | **C++ `addFragment` 的逐字同式对拍臂**；G09 禁开第二碎片写者 |
| `GachaNativeTx.kt:17-21` | `internal data class GachaNativeGrant(starBefore/starAfter/fragmentsAfter)` | 抽卡事务回执可扩展（但只增不改） |
| 同上 `:46-56` | `tryGrantFragments` 走 `tx(ActionIds.GACHA_FRAGMENT_GRANT_TX)` | G09 按此形制加 `tryPullOnce/tryPullTen` |
| 同上 `:59-75` | `tx()`：`NativeEngineFlag.authoritative` 门控 + `stateSyncServiceRef` 空安全 + `tryExecuteNative` | 新事务**必须复用本私有助手**，禁另起 JNI 调用 |
| `feature/game/.../delegate/GachaDelegate.kt:12-21` | 2 行转发（`pullOnce:16` / `pullTen:19`）；KDoc 自称「G01 空壳，G11 承接」 | **全仓零实例化**（`GameViewModel` 无 `gacha` 字段）⇒ 即便 G09 打通 Facade，UI 仍不可达；接入归 G11 |
| `android/app/.../di/CoreModule.kt:145` | `provideGachaFacade(GachaFacadeImpl)` 已注册 | DI 面 G09 零改动 |

## 2. Room / ProtoBuf 面

| 文件:行 | 现状 | G09 影响 |
|---|---|---|
| `GameData.kt:808-811` | `gachaFragmentCounts` · `@ProtoNumber(164)` · `gacha_fragment_counts` DEFAULT `'{}'` · `PRESERVE_OLD` | 已落；碎碎片账本读写在 `gacha_fragment.h`/`GachaFragmentLedger` |
| 同上 `:814-817` | `gachaStarMap` · `@ProtoNumber(165)` · `gacha_star_map` `'{}'` | 同上 |
| 同上 `:820-823` | `gachaPityCounters` · `@ProtoNumber(166)` · `gacha_pity_counters` `'{}'` | **守恒点**：`poolId → 已抽次数`，G09 roll 的保底读写面 |
| 同上 `:826-829` | `gachaHistory` · `@ProtoNumber(167)` · `gacha_history` `'[]'` | **环缓冲无实现**：`GameConfig.Gacha.HISTORY_RING_SIZE=50`（`GameConfig.kt:220`）+ `GachaHistoryEntry.kt:9` 只在 KDoc 声明，全仓**无截断/写入代码** ⇒ G09 必须落 50 条淘汰逻辑 |
| 同上 `:780` | `// reserved 1002;`（`pendingTraitAdds` 退役号） | 1000+ 段只有 `mapGenVersion:789`(1000) / `terrainTiles:801`(1001) |
| **号段建议（依据）** | 普通号：现有最大 `@ProtoNumber(167)`（`:826`）⇒ **G09 新字段用 168 起**；reserved 集 = `102,103,115,133,134,146,150,151,152,210,1002`（`:384/:541/:572/:621/:742/:780`）——**禁复用**；1000+ 特殊段（地图冻结族）可用 1003 起 | 「只增不复用」由 `ProtoNumberUniquenessTest.kt:104`（reserved 禁复用）+ `:47`（同类唯一）看护 |
| `GachaHistoryEntry.kt:13-25` | **8 字段** `@ProtoNumber(1..8)`（`poolId/category/templateId/itemId/rarity/count/isPity/gameMonthIndex`） | 旧侦察记「1..3，待复核」已勘误；G09 若要加「本次抽序号/池快照」⇒ **用 9 起** |
| `game_view.proto:104` `DiscipleRow` | **`reserved 10,11,26,88,104,106`（`:107`）/ `78-85`（`:111`）/ `"masterId"`（`:113`）/ `17,18,19,96`（`:116`）**；现有最大 = `templateId = 112`（`:251`）、`storageBagItemsPresent=111`（`:248`） | G09 若要新增**弟子列**（如 `star`）⇒ 用 **113 起**；**或**走 `gameDataChange` 泛化段（下两行） |
| 同上 `:79` `JsonFieldChange` + `:344 valueTyped` | gameData 非头部字段走**字段名 + TypedValue** 泛化承载，**不需要新 proto 号** | 🔴 **G09 首要结论**：gacha 四字段已在 C++ `json_codec`（§见下）⇒ **镜像通道零 proto 改动**即可传输；只有**新增 gameData 字段**才需要新 `GameData` proto 号 |
| `gameview_encode.cpp:486-493` | `gameDataChange`：`isGameDataPath(it.key())`（`:318` = 前缀 `gameData.`）**全部字段**自动入流，仅 `gameData.spiritStones`（`:488`）走头部 | 同上：新 gameData 字段自动进镜像，但**必须**同步 C++ `json_codec` 双向（否则 `BaselineFieldCoverageGuardTest` 红） |
| `json_codec.cpp:1247-1248 / 1333-1334` | `GC_TO/GC_FROM(gachaFragmentCounts/gachaStarMap/gachaPityCounters/gachaHistory)` 双向齐备 | C++ 侧序列化已完成；`gachaHistory` 条目 `:873-883` 八字段与 Kotlin 逐字同名 |
| `models.h:1130-1140 / 1317-1320` | `struct GachaHistoryEntry`（8 字段全默认值）+ `GameData` 四个成员 | 同上 |
| `59.json:776-802` | `game_data` 四列 `TEXT NOT NULL` + DEFAULT（`'{}'`×3 / `'[]'`） | 已入库；`59.json` 历史快照**禁改** |
| `59.json:862 / 943-944` | `disciples.templateId TEXT NOT NULL DEFAULT ''`（`createSql` 内联 + field 段） | 存量弟子默认 `''`；G09 解锁入册写值不改 schema |
| `GameDatabase.kt:95` | `DATABASE_VERSION = 59`；`game_data` **128 列** / `disciples` **90 列**（59.json 实测） | **G09 加列 ⇒ 必须 v59→v60 四件套**（迁移类 + 版本 + `ALL_MIGRATIONS` 注册 + `60.json` + 迁移测试）；不加列则保持 59 |
| `GameDatabaseMigrationsV54.kt:18-35` | `MIGRATION_53_54` 八列纯加法（含四 gacha 列 + `templateId`） | 加列先例可照抄（`ALTER TABLE ... DEFAULT`）；**禁 DROP COLUMN** |
| `CollectionConverters.kt:555-561` + `ProtobufConverters.kt:249-259` | `gachaHistory` 走 `ListSerializer` base64；`Map<String,Int>` 走 `stringIntMapSerializer` | ⚠️ 与 `android/core/data/AGENTS.md`「ProtoBuf 仅 List，禁 Map」条款**冲突但已既成**（Room 侧）；云存档 `SaveData` 走 kotlinx proto，Map 有 map 编码支持。G09 新增 gacha 字段**优先 List**，避免扩大该例外 |

## 3. 镜像合法面（`docs/ui-read-surface.md` §2）

| 文件:行 | 现状 | G09 影响 |
|---|---|---|
| `docs/ui-read-surface.md:31-46`（§2.1 gameData 面） | 列举的字段清单**不含任何 gacha 字段**（`gachaFragmentCounts/StarMap/PityCounters/History` 零出现） | **登记表缺口**：四字段事实上已在 C++ 序列化面（`json_codec.cpp:1247`）+ 泛化镜像段（`gameview_encode.cpp:486`），文档未登记 |
| 同上 `:52`（§2.3） | 顶层运行态载体仅 4 项（`aiSectDisciples` 等） | gacha 不属此面 |
| 同上 `:8` + `:56-57` | 维护纪律：**新增 UI 读取字段必须先确认在 §2 面内；不在则先扩 C++ 协议（编码 + 对拍）** | G09 若要新增 UI 可读字段：① 先扩 `json_codec` GC_TO/GC_FROM；② 同步 `BaselineFieldCoverageGuardTest.kt:34-41`（`intentionallyExcluded` 或进基线）；③ 在 §2.1 清单**补登 gacha 四行 + 新增行** |
| 结论（G09 结果页/图鉴字段） | `starMap` / `fragmentCounts` / `pityCounters` / `history` **均属合法镜像面**（C++ GameData 已建模并导出），但**未登记** | 需登记 4 行（+ 新增字段行）；无需新增 proto 号（除新 gameData 字段） |
| `MirrorConsumerSurfaceGuardTest.kt:72-82` | UI 模块主源对 `StateSyncService|GameViewMirrorCodec|proto.gameview|applyDirty` 等符号**零命中** | G11/结果页读取**只能**经 `GameStateStore` 只读流（`GachaService` 的四个 StateFlow 已合规） |
| `MirrorReadOnlyGuardTest.kt:45-48,51` | 六模块主源对 `ReverseChannelPolicy|ReverseDirty|applyDirtyToNative|applyReverseDirty` **零命中** | G09 禁引入任何反向回导符号 |

## 4. 入库与邮件

| 文件:行 | 现状 | G09 影响 |
|---|---|---|
| `InventorySystem.kt:222-226` | `fun <T> withTrackingSource(source: String, block: () -> T): T`（`prev/restore`） | 抽卡产物入库**唯一合法包裹**形式 |
| 用法样例 ① | `GameEngineWorldBattleOps.kt:263` `withTrackingSource("beast_world") { addMaterial(...) }` | 妖兽材料范式（与抽卡物品类别一致） |
| 用法样例 ② | `ProductionProcessor处理Ops1.kt:105 / :131`（`"alchemy"` / `"forge"`）；`BuildingService.kt:483`（`"building"`） | 丹药/装备范式 |
| `InventorySystem.kt:216-219` | `addPill/addMaterial/addHerb/addSeed(item) = addXxx(item, merge=true)` 单参便捷版 | G09 三类掉落（妖兽材料/草药/种子）用对应 `addXxx` |
| `InventorySystem.kt:234-238 / 251-264` | `withOverflowMailSuppressed` / `sendOverflowMail(source,...)` | 抽卡走**发放类**语义（**不包** suppressed）⇒ 满仓自动转邮件，抽卡不因满仓失败 |
| `OverflowMailSender.kt:96-122` | `SOURCE_DISPLAY_NAMES: Map<String,String>` **25 项**：`battle/beast_world/cave_world/cave/beast_raid/patrol/forge/alchemy/spirit_field/storage_bag/merchant/redeem/mail/disciple_reward/disciple_unequip/trial/sect_level/sect_trade/quest/building/confiscate/secret_realm/disciple_death/disciple_expel/unknown` | G09 新增 `"仙缘寻访"`（产品方案口径）**必须同批登记**，且 **key 必须与 `withTrackingSource` 字面量逐字相同**（中文 key 亦可，`:126` 缺省回退「未知」） |
| 守卫 | `OverflowMailSenderTest.kt:150-163` 用 `Regex("withTrackingSource\(\"([^\"]+)\"\)")` 扫 `core/engine/src/main/java`，缺失即红（消息含「请补映射」） | **旁路风险**：正则只匹配单行单参调用；若写成多行/变量传参，守卫**静默不覆盖** ⇒ 仍须人工登记 |
| 守卫 | `InventoryAddPathGuardTest.kt:108/119/133/145/196/208/219`（白名单存在 / 禁 `coerceAtMost` 截断 / 禁直 append 仓库表 / 禁内联合并 / 禁手写 `StackableItemStore` / 实例表白名单） | G09 抽卡入库**只能** `withTrackingSource { addXxx }` |

## 5. 会约束 G09 的既有守卫测试

| 守卫（文件:用例行） | 断言要旨 | G09 约束 |
|---|---|---|
| `RngSourceGuardTest.kt:183`（`registeredLimits:93-136`） | 六模块主源五类随机源逐类计数**不得超过登记上限**（core/engine：②14 / ④2 / ⑤5） | Kotlin 侧抽卡 roll **禁** `Random.Default/.random()/nextInt 裸抽取`；必须 `GameRngManager.getRng(...)` |
| 同上 `:243`（`registeredPartitionIds:144`） | 新增 `RngPartition` 值必须登记（当前 0..11 全占） | G09 若开 GACHA 分区 ⇒ 同步 `RngPartition.kt:98`、`rng_manager.h:49-62`、`:69 kMaxPartitionId`、本守卫、`docs/rng-source-inventory.md`；**不开分区则零改动** |
| 同上 `:229/:256/:283/:306` | `inSnapshot` 标记一致 / id↔name 无漂移 / RESIDUAL 本地性 / 通道 id 9 语义 | 同上 |
| `MirrorReadOnlyGuardTest.kt:51` | 六模块主源反向通道符号族零命中 | 见 §3 |
| `MirrorConsumerSurfaceGuardTest.kt:50/61/72/85/129/159` | 镜像导出点唯一 / 信封解码唯一 / UI 零直连 / B18 单臂 / 投影台账 / 每旬热路径零 `replaceAll` | G11 结果页禁直连；G09 禁新增拉取点 |
| `CharacterTemplateGuardTest.kt:164/190/210/232/266/302/339` | ① 模板表↔产物逐字段全等（顺序敏感）② 比对域双向覆盖（加属性必须同步 `MIRRORED_FIELDS`）③ id 唯一 ④ 开局模板与 `gachaDefaults` 同值 **且 `startBonusFragments=0`** ⑤ **碎片升星常量三向一致**（`game-data.json db.gachaPools[0]` ↔ `GameConfig.Gacha` ↔ C++ `gacha_fragment.h:37-38`）⑥ 卡池经济常量同值（`pricePerPull`/`pity.*`/`startSpiritStones`）⑦ 灵根取值域 | 🔴 **三向常量守卫就在此**；G09 改任何经济/概率常量必须三处同改；产物 `db.gachaPools[0]` 必须是 standard 池（`:293` 池位锚点） |
| `GachaConfigGuardTest.kt:70/119/153/182/196` | 类别权重和=100、品阶权重和=100 且 `maxRarity<=4`、保底 10/5、每星 100/星级 5、无转化比例、六模板 id 集合、**池引用 templateId ⊆ `CharacterTemplateDb.ids`**、开局 id 在表内、Q31 色值 | G09 新增池类别/权重必须同步中性源 + 重跑生成器；引用表外 id 会红 |
| `DiscipleCreationPathGuardTest.kt:103-149`（用例 `:172/:198/:236/:253/:284`） | 弟子入册/回写调用点集合 ⊆ 白名单（`implementTemplate`@`DiscipleService.insertTemplateDisciple:105` 等 11 项）；白名单不得含僵尸条目；`PORTRAIT_GENERATION_WHITELIST:403` = `DiscipleFactory.kt` / `AISectDiscipleManager.kt`；`GRANT_CHANNEL_FILE_COUNT=420` = **4** | 🔴 **G09 解锁入册必须走 `DiscipleService.instantiateTemplate`**（唯一生产构造口）；**禁**自建 `Disciple(` 或直调 `discipleTables.insert`；新增发放渠道文件会撞 `GRANT_CHANNEL_FILE_COUNT=4` 计数 |
| `DiscipleService.kt:137-178` | `instantiateTemplate` **限持 1**：`:140-144` 已持有时返回 `TemplateAlreadyOwned` | G09 解锁语义必须处理「已持有」分支（碎片满/解锁 ≠ 可重复入册） |
| `DiffGachaFragmentTest.kt:71`（用例面 `:33-69` KDoc 表） | 五条双臂对拍（入账向量/拒绝臂/可加性/零 RNG/native 参数盲取）+ **无 JNI 也判绿的黄金表兜底**；稀疏账本按整张 map 字面量断言 | G09 新事务**必须补同类 `Diff*` 对拍**（`gamecore/AGENTS.md`「双守护」：GTest + JUnit 对拍，缺一即未完成）；碎片语义禁改 |
| `GachaConfigGuardTest` / `gacha_fragment_test.cpp` | C++ GTest 黄金向量（门槛/跨星/满星不截断/拒绝臂零改动/可加性/事务入口） | G09 新 `gacha_tx_test.cpp` 应照此形制 |
| `ProtoNumberUniquenessTest.kt:47/87/104/132` | 同类内号唯一 / `162` 归属锁 / **G04·G15 退役号 reserved 禁复用** / `SaveData.mails` tag 56 锁 | 见 §2 号段建议 |
| `ProtoNumberCoverageTest.kt:53/64/75/88` | `GameData`/`SaveData` 每字段必须有 `@ProtoNumber`；**非零默认值字段必须有 `@EncodeDefault(ALWAYS)`**；`excludedFields:40-50` 仅 computed property | 新字段默认值 `emptyList()/emptyMap()` 可省 `@EncodeDefault`；非空默认（如 `"standard"`）**必须**加 |
| `BaselineFieldCoverageGuardTest.kt:71` | `GameData.serializer().elementNames` ↔ C++ `json_codec.cpp to_json(GameData)` 键集**双射**；`intentionallyExcluded:34-41` 仅 6 项（全为 Kotlin-only 存档兼容域） | 🔴 **新增任何 gameData 字段（含 gacha 新字段）必须同批改 C++ `json_codec` 双向**，否则红 |
| `GameDataFieldPatchGuardTest.kt:61/74` | 「写入器键集 == GameData 序列化面」双射（漏登记即红）+ 逐值 golden 等价 | 🔴 新 gacha gameData 字段必须在 `GameDataFieldPatch.kt:327-338` 追加 `f("字段名", …)` 分支，否则运行期 fail-fast + 守卫红 |
| `OverflowMailSenderTest.kt:150` | 见 §4 | 见 §4 |
| `InventoryAddPathGuardTest.kt` + `DomainInventoryAddPathGuardTest.kt` | 统一入库入口 | 见 §4 |
| `MigrationChainGuardTest.kt:22` | `ALL_MIGRATIONS` 自 3 连续覆盖到 `DATABASE_VERSION` 且逐条 N→N+1 | G09 若加列 ⇒ 迁移四件套同笔 |
| `dispatch_guard_test.cpp:102`（`retired:105-130`） | **每个 `kAllActionIds` 条目必须分派可达**（退役号除外）；升序唯一 | 🔴 G09 新号若注册进 catalog 却**未接 dispatch**，C++ CI 立即红 |
| `StaticDataSingleSourceGuardTest.kt:39` | `scripts/data/*_sample.json` 与测试快照**字节全等** | G09 改 `gacha_config_sample.json` 必须同步 `templates/` 快照 |
| `DiffAuthoritativeTickTest`（`.kt:245` 遍历全分区） | 100 旬 AUTHORITATIVE 管线 `nonMirrorWriteCount == 0` | 行为面只读契约 |

## 6. ActionId / 端口段

| 文件:行 | 现状 | G09 影响 |
|---|---|---|
| `scripts/action-catalog/gacha.mjs:18-21` | `CATALOG` **仅 1 条**：`{1870, GACHA_FRAGMENT_GRANT_TX}`；`:11` 声明预分配段 **1870–1889**；`:19` 注释「G01 预留空段，G09 实跑分配」 | **可用空段 = 1871–1889**（19 个号）；G09 追加条目后必须跑 `node scripts/gen-action-ids.mjs`（禁手改生成物） |
| 生成器自检 | `README.md:15-22`：id 全局唯一 / 段两两不相交 / 条目须落本段 / `core.mjs` 不得落批段 | 跨批占段会生成失败 |
| 段占用全貌（实测） | `core.mjs` 1000–1734（冻结）；`w4a` 1740–1759 + 1810–1829 + 1850–1854；`w4b` 1760–1779 + 1840–1849；`w4c` 1780–1809 + 1855–1859；`w4d` 1830–1839 + 1860–1869；**`gacha` 1870–1889** | 1870–1889 **确为 G 批独占**，无外部竞争 |
| `ActionIds.kt:603` / `action_ids.h:607` | `GACHA_FRAGMENT_GRANT_TX = 1870` 已生成；`kAllActionIdsCount = 199`（升序表 `:606-617`） | 新号生成后 `kAllActionIds` 自动追加 |
| `dispatch_gacha.cpp:54-78` | 独立端口 `dispatchGacha`：`:60-72` switch 仅 `GACHA_FRAGMENT_GRANT_TX`；`:73-77` 段内余量返回 `std::nullopt`；`:76` 注释明写「1871–1889 尚无事务产出」 | G09 在**本文件**加 `case`（禁并入裸区间——`:6-10` 记 1730 被区间吞号事故） |
| `execute_dispatch.cpp:2527-2532` | 端口已接线 `else if (auto gacha = dispatchGacha(...))`；`:2519-2523` 注释「本文件此后冻结」 | G09 **零改动 `execute_dispatch.cpp`**（端口已在链上） |
| `test/gacha_tests.cmake`（已存在） | `GACHA_TEST_SOURCES` 现含 `gacha_fragment_test.cpp`；`test/CMakeLists.txt:172-173` 已 `include` 并并入 | G09 追加 `gacha_tx_test.cpp` + `dispatch_gacha_test.cpp` 到本清单即可 |
| `gacha_fragment.h:37-38` | `kFragmentsPerStar=100` / `kMaxStar=5`（三向之一） | 见 §5 |

---

## G09 实施时最容易漏的 10 项连带面

| # | 连带面 | 依据（file:line） |
|---|---|---|
| 1 | **C++ 侧根本没有卡池配置读取器**：`db.gachaPools` / `db.characterTemplates` 只写进 `assets/data/game-data.json`，而 `data_inject.h` 的 7 段白名单（`equipment/herbs/seeds/manuals/beastMaterials/forgeRecipes/pillRecipes`）**不含**二者 ⇒ C++ 做 roll 时无「类别权重 + 品级权重 + 池内 templateIds + 品阶表」可用。必须先扩 `data_inject.h`（含 `AppliedCounts`）+ `data_store` 逐段守卫，或改由 Kotlin 传入池快照 | `data_inject.h:37-45`（`AppliedCounts` 7 项）、`:48-114`（applyGameData 段白名单）、`game-data.json` 的 `db.gachaPools`、`scripts/gen-game-data.mjs:155-156` |
| 2 | **物品模板物化必须留 Kotlin**：草药/种子/妖兽材料表在 Kotlin Registry（C++ 不可复刻），且入库唯一入口受守卫约束 ⇒ C++ 只能回传「品阶 + 表内索引」描述符，由 Kotlin 物化并 `withTrackingSource("仙缘寻访") { addXxx }` | `storage_bag_tx.h:19-23`（同款先例）、`HerbDatabase.kt:218-252`、`ItemDatabase.kt:777`、`InventoryAddPathGuardTest.kt:196` |
| 3 | **`SOURCE_DISPLAY_NAMES` 的 key 必须与 `withTrackingSource` 字面量逐字相同**，且守卫正则只扫**单行单参**调用；写多行/变量传参即静默漏检 | `OverflowMailSender.kt:96-122`、`OverflowMailSenderTest.kt:150-163` |
| 4 | **抽卡入仓必须是「发放类」溢出语义**（不包 `withOverflowMailSuppressed`）——包了就变成「满仓即失败/丢件」 | `InventorySystem.kt:234-238`、`:251-264`；`docs/character-gacha-redesign-2026-09-23.md:113` |
| 5 | **解锁入册只能走 `instantiateTemplate`，且它限持 1**（已持有返回 `TemplateAlreadyOwned`）；新增「解锁服务文件」会撞 `GRANT_CHANNEL_FILE_COUNT` 计数 | `DiscipleService.kt:137-144`、`DiscipleCreationPathGuardTest.kt:103-149`、`:420` |
| 6 | **任何新 gameData 字段必须三处同笔**：C++ `json_codec` 双向 + `GameDataFieldPatch` 写入器分支 + `BaselineFieldCoverageGuardTest.intentionallyExcluded`（若 Kotlin-only） | `BaselineFieldCoverageGuardTest.kt:71`、`GameDataFieldPatchGuardTest.kt:61`、`GameDataFieldPatch.kt:327-338`、`json_codec.cpp:1247/1333` |
| 7 | **历史环缓冲 50 条淘汰逻辑当前不存在**（`HISTORY_RING_SIZE` 只是常量 + KDoc），G09 不写就是无界增长 | `GameConfig.kt:220`、`GachaHistoryEntry.kt:9`、`GameData.kt:826-829`（全仓零消费） |
| 8 | **`GachaPullResult` 无 `Success` 面**，加分支会波及 `RedeemCodeServiceTest.kt` 的 `RecordingGachaFacade`（实现接口）；同时 `GachaDelegate` 全仓零实例化 ⇒ Facade 打通 ≠ UI 可用 | `GachaFacade.kt:66-69`、`RedeemCodeServiceTest.kt:311-313`、`GachaDelegate.kt:12-21`、`GameViewModel.kt`（无 gacha 字段） |
| 9 | **C++ 新事务号必须 dispatch 可达**，否则 `DispatchGuard` 红；且生成物必须重跑 `gen-action-ids.mjs` 并 `git diff --exit-code` 自证零漂移（CI **不跑**该 codegen 门，漏提交只在干净检出时炸） | `dispatch_guard_test.cpp:102-133`、`action-catalog/README.md:42-50`、`.github/workflows/ci.yml:114`（仅 `check-jni-count`，无 gen-action-ids） |
| 10 | **`docs/ui-read-surface.md` §2.1 未登记 gacha 字段**；G09/G11 若要读新字段，须「先扩 C++ 协议 → 再登记 → 再用」，并同步文档，否则违反 §2 纪律（结果页/图鉴字段虽已实际可传输，但文档面缺失会被下一个审计判为未登记读面） | `docs/ui-read-surface.md:8`、`:31-46`、`:56-57`；`gameview_encode.cpp:486-493` |

### 附：与 `recon-G05-G06-G08-G09.md` 的行号漂移勘误（本轮实测）

| 旧记 | 实测 |
|---|---|
| `GameData.kt:900-921` gacha 四字段 | **`:808-829`** |
| `GameDataFieldPatch.kt:368-379` | **`:327-338`** |
| `mirror`/`json_codec.cpp:1327/1418` | **`:1247-1248 / 1333-1334`** |
| `models.h:1400-1403` | **`:1317-1320`** |
| `GameDatabase.kt:94 DATABASE_VERSION=54` | **`:95` = 59** |
| `GachaHistoryEntry` ProtoNumber「1..3 待复核」 | **1..8（8 字段）** |
| `GachaConfigGuardTest` 「两层 `assumeTrue`」 | **已取消全部 assumeTrue（`:20-22`），缺键/空数组判红**；用例增至 5 条（`:153 pool referenced template ids are closed` 为新增） |
| `GachaFacadeImpl`「无 `gameEngineCore`」 | **已注入（`:25-28/:45`）** |
| `gacha.mjs`「`CATALOG=[]` 空」 | **已有 1870 一条**；`dispatch_gacha.cpp` 已建（旧侦察记「G09 应新建」已过时，只需加 `case`） |
| `test/CMakeLists.txt` 「G09 应新建 `gacha_tests.cmake`」 | **已存在并已 include** |
