# EQ-B3 实施报告——装备体系原子替换（六部位 / 实例轨 / 升级分解 / Room v64）

> 批次：EQ-B3（派发真源 = `IMPLEMENTATION-BATCHES.md` §4.4 + 方案 HEAD
> `docs/design/equipment-set-system-refactor-plan.md`）
> 工作区：`feat/equipment-set` worktree，基 HEAD `fa36109fc`（B2 收官笔），单笔收官。
> 本报告由恢复会话（HANDOVER-B3 → HANDOVER-B3-R2 → 本会话）接力完成。

---

## 1. 交付面摘要

- **领域/静态数据**：`EquipmentInstance` 新模型（setId/part/growth/meta）、`EquipmentSlot` 六值
  （10..15）、72 条展开条目、12 部件 × 2 套装、`EquipMainStatPool`/`EquipAffixPool`/
  `EquipmentSetDatabase`、`EquipmentFactory` 唯一产出入口（品阶境界钳制 S17）。
- **存档**：Room v63→v64 七步迁移（影子表搬运 / 堆叠表 DROP / 实例表重建 / disciples
  +5 部位列 + 清六槽 / −9 旧列 / game_data 补偿标记列）；`LegacyEquipmentCompensationRule`
  （order=27，四路折算+1 亿上限+幂等）与 `EquipmentValueSanitizeRule`（order=28）。
- **引擎**：`EquipmentLevelSystem`/`EquipmentUpgradeService`（ActionId 1486/1487）、
  `EquipStatResolver`（totalBonus+套装 2/4/6 档）、`DiscipleEquipmentService/Manager`
  单轨六部位重写（卸装=实例保留表内 isEquipped=false）、产出链全改工厂化、
  `Combatant.critDamageBonus` + 六名接线、镜像面六列（proto 67-70/122/123）。
- **C++**：`equipment_tx.h`/`equipment_factory.h`/`equipment_entries.h` 三新头 + 24 个
  消费文件逐位对齐 Kotlin；RngPartition.EQUIPMENT(13) 双端；critDamage 全链接线；
  CharacterTemplate.innateDamageType 镜像补齐。
- **UI**：装备详情=套装·部件名+Lv+词条摘要从简语义；孕养段/材质明细/四槽死面删净；
  18 文件修复（含 app AppModule DI 级联）。

## 2. 门禁实测（恢复会话二阶段，2026-09-30）

<!-- GATE-NUMBERS 占位：门禁跑完后逐项回填 -->

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin`（全模块） | ✅ BUILD SUCCESSFUL（UI 面 198 错清零后实测；六模块主代码+测试源集零错） |
| 六模块 JVM 全量（`--max-workers=1`，jni.path 指 worktree .so） | ✅ **BUILD SUCCESSFUL，7640/0/0，22 skipped**（2026-09-30 汇总实测） |
| `:core:domain` 单测 | ✅ **1600/0** |
| `:core:data` 单测 | ✅ **881/0，15 skipped** |
| `:core:engine` 单测 | ✅ **2985/0，5 skipped**（均为既有 assumeTrue 跳过；含 `DiffEquipmentUpgradeTest`/`DiffMonthSettlementTest` 双臂对拍全绿） |
| `:feature:game` / `:app` 单测 | ✅ 全绿（含 1028 组合跑；app 的 DiscipleMergeCoverage 退役字段清单已随 B3 更新） |
| C++ `cmake --build build` 全量 | ✅ **零错**（llvm-mingw + cmake 3.22.1） |
| `ctest` 全量 | ✅ **1521/1521**（B3 前基线 1480，净增 41；修复后两轮复跑确认） |
| `build-desktop-jni.ps1` → .so | ✅ `android/core/engine/build/desktop-jni/libgamecorejni.so`，mtime **2026-09-30 07:26**（9,017,344 B，259 源文件同源指纹） |
| lint + detekt（baseline 只缩不增） | ✅ **BUILD SUCCESSFUL**（四模块 57 项 B3 新面违规全部清零：UnusedImports/死参/死属性实修、LongMethod/NestedBlockDepth 清单型抑制+理由、两处循环实改、一处死测试辅助实删；**baseline 零改动**；处置后六模块复跑 BUILD SUCCESSFUL） |
| jni-count | ✅ **87/87**（无新增生产 JNI 口） |
| agent-instructions | ✅ 规范分发架构门禁全部通过 |
| G0：gen-templates 重跑零差异 | ✅ **七产物逐字节零差异**（四表+herb_db+两 JSON 副本；模板已同步 operator==/Mutable 契约——§3.3 #6/#7 手工修复的生成器同步） |
| gen-game-data --check | ✅ 与中性源一致（sha256 7c1e5377…） |

## 3. 本会话真根因修复（主代码，⚠️ 重点验收项）

### 3.1 patch 组装列组映射漏登记六部位新列（静默丢列写）

- **症状**：`AssemblePatchEquivalenceTest` 混合组/全组脏两用例红——patch 臂丢
  `bodyId`/`legsId`（全量臂正确）。
- **根因**：B3 把孕养列换成六部位列后，`AssembleGroup.kt` 的
  `discipleColumnGroupByName()` **没有登记 headIds/bodyIds/handsIds/feetIds/legsIds**，
  旧 armorIds/nurture 键残留。新列在 `_allCopyableRefs` 有注册（故不触发「列索引越界
  →退化全量」安全网），映射 miss 落进 `-1 = 本体列` 语义 → EQUIPMENT 组不被判脏 →
  `assembleAllPatched` 静默复用旧子对象。**这正是该守卫测试注释写明的隐患**
  （「patch 误用列→组映射（漏判脏组）会导致静默复用旧子对象」）——测试抓到了真 bug。
- **修复**：六列注册进 EQUIPMENT 组、旧四槽/nurture 键清除
  （`core/domain/src/main/java/com/xianxia/sect/core/state/AssembleGroup.kt`）。
- **影响面**：任何「写六槽位→patch 组装」的生产事务（穿戴/自动装配/迁移装配）此前
  会在增量组装路径丢写，已修复。
- **遗留锐度（登记不阻塞）**：`-1` 同时承担「本体列」与「映射 miss」两语义，安全网
  只对「索引越界」退化全量——建议后续把本体列显式登记、miss 一律退化全量（防新列
  再漏登记）。

### 3.2 MIGRATION_63_64 步①非幂等（重跑破坏补偿数据源）

- **症状**：`RoomMigrationV63To64Test is idempotent` 红——二次 `migrate()` 以
  `CREATE TABLE legacy_equipment_stacks AS SELECT * FROM equipment_stacks` 撞
  「no such table」（源表已在首轮被 DROP）；若仅加 IF NOT EXISTS，实例影子表会被
  **新模型空表覆盖**（更危险：静默清空补偿源）。
- **根因**：KDoc 声称「七步全部幂等」但步①的搬运无源形态守卫。
- **修复**（`GameDatabaseMigrationsV64.kt`）：搬运仅在**旧形态源表仍在**时执行——
  stacks 探 `equipment_stacks.id` 列、instances 探 `equipment_instances.nurtureLevel`
  列（旧模型独有；新模型无此列，绝不回写影子表）。中断重试语义：影子行首轮已保真，
  原样保留。

### 3.3 C++ 侧测试暴露的主库 bug（8 处，最小修复；⚠️ 重点验收项）

| # | 严重度 | 文件:位置 | 问题与后果 | 修法 |
|---|---|---|---|---|
| 1 | 🔴 行为级 | `disciple_tx.h:218` unequipInternal | `slotToClear` 解引用取到**装备 id** 而非槽位名 → `clearEquipSlot` 恒 no-op → 独立卸下路径 100% 不清槽（替换路径被后续覆写掩盖） | 槽位列匹配改显式槽位名映射（HEAD..LEGS）再 clear；恢复与 Kotlin 卸装语义对拍一致 |
| 2 | 🔴 行为级 | `equipment_factory.h` create() | 实例 id 未赋值即返回，`addEquipmentInstance` 拒绝空 id → **锻造/商人购买/任务奖励/秘境/购买五条产出链全部恒失败** | `inst.id = nextInstanceId()`（+inventory.h include） |
| 3 | 🔴 行为级 | `data_inject.h` mainStatPools | 注入器按数组解析，而 gen-game-data 产物是 part 键对象 → **真实数据注入恒失败落兜底**（gacha/data_store 守卫全红的根因） | 按键展开为 MainStatPoolDef 行（与 mainStatBase 同约定；槽内 stats 序保留） |
| 4 | 编译面 | `merchant_settlement.h` | 缺 equipment_factory.h include（主库绿仅因包含序巧合） | 补 include |
| 5 | 编译面 | `data_json.h` | 缺三张新表头 include（独立包含即编译错） | 补 include |
| 6 | 生成件回归 | `herb_db.h`（gen-templates 生成件） | B3 重写版丢 `herbTemplatesMutable()/seedTemplatesMutable()` 与 `operator==` → Android 桥接/注入路径无法编译 | 按约定补齐；**gen-templates.mjs 模板须同步（见 §2 G0 说明）** |
| 7 | 生成件回归 | `equip_set_db.h`/`equip_main_stat_db.h`/`equip_affix_db.h` | 缺 `operator==`（与 D9/D10 文档契约不符） | 补齐；**gen-templates.mjs 同步（同上）** |
| 8 | 守卫面 | `json_codec.cpp` Disciple to_json | 仍写已退役的 `equipmentNurturingCompletion{Month,Phase}`（违反「退役禁写」） | 删两条 GC_TO（from_json 宽松读取保留）；ColumnDirtyTest 双射守卫抓获 |

### 3.4 Kotlin 侧 engine 测试运行暴露的主库 bug（4 处，最小修复；⚠️ 重点验收项）

| # | 严重度 | 文件:位置 | 问题与后果 | 修法 |
|---|---|---|---|---|
| 9 | 🔴🔴 行为级（最高） | `EquipStatResolver.kt:51/88` | `bonus += plusStat(bonus, statValue)` 复合赋值——`plusStat` 已折入 current，语义=「旧值×2 再加」→ **全部装备加成（词条+套装）逐条翻倍**（EQ-B2 记忆教训「`x += f()` 复合赋值坑」复发；`flat三维度求和` 实测 12→164 逐步吻合） | 两处改 `bonus = plusStat(bonus, statValue)` + 注释钉死禁写法；C++ 对偶（`disciple_stats.h`）实现正确，其 0..6 档测试已证 |
| 10 | 🔴 行为级 | `EquipmentFactory.kt` create 默认形参 + C++ `equipment_factory.h` 同款 | 默认 `discipleRealm = Int.MAX_VALUE/INT32_MAX` 意图「不设限」，但本口径（值越小境界越高）下 MAX=最低境界 → `maxWearableRarity=1` → **全部未显式传境界的产出链（商店/邮件/兑换码/试炼/AI/敌人等 12+ 处）被钳到 T1** | 双端默认改哨兵 `REALM_UNRESTRICTED=1`（顶境不设限）+ KDoc 钉死禁用 MAX；显式传 MAX 的 `HeavenlyTrialService:482`/`MissionSystemRewardOps:298`/C++ `mission_completion.h:822` 三处调用点同修 |
| 11 | 🔴 行为级 | `GameViewMirrorCodec.kt` 字段表 | 只有 `weaponId`，缺 headId/bodyId/handsId/feetId/legsId 五条 str 项 → 镜像 JSON 树静默丢五列（protobuf typed 臂正常、JSON 树重建臂丢写）——`GameViewDiscipleProjectionTest` 双臂分歧抓获 | 补五条 `str(...)` 登记（proto 67-70/122/123） |
| 12 | 行为级 | `BuildingService.kt:206` startAlchemy | 把 `maxCraftableTier(workerLevel)` 当配方 tier 传入职业门禁 → `canCraftTier(level, maxCraftableTier(level))` 恒真，**炼丹职业品阶门禁形同虚设** | 改传 `recipe.tier`（一行；startForging:235 同款恒真调用因 B3 配方无 tier 属无害死代码，登记收口批清理） |
| 13 | 🔴 行为级 | `inventory_tx.h` deductSoldStock 装备分支（C++）+ Kotlin `deductSoldEquipmentInstances` 同款 | 收购扣减按 `acquisitionItem.itemId`（需求侧模板锚）查实例表——同 id 至多 1 条 → **「付 N 件款只扣 1 件」复制 bug**（双端同患；C++ 黄金测试正文与自名矛盾地钉了错语义） | 双端改按名称+品阶匹配未锁定实例逐件移除；黄金测试重钉正确语义 |
| 14 | 行为级 | 双端 `warehouseCount` 装备臂 + 天劫奖励（`HeavenlyTrialService`） | ①计数含锁定件但扣减跳锁定 → 「锁定价凭空收款」矛盾（B3 新引入）；②天劫产出被 B3 diff 改成 pickRarity 随机升档（破坏卡面稀有度=发放实例契约）且 `kotlin.random.Random` 未播种（RNG 治理违规） | ①双端出售计数排除锁定（与扣减谓词一致）；②恢复精确品阶 `targetRarity`（对齐 randomPill min==max 契约）+ EQUIPMENT 分区 RNG（生产注入 rngManager、测试固定种子兜底） |
| 15 | 缺口→修复 | 双端 `addEquipmentInstance` 年度追踪 | B3 堆叠轨退役时装备年度来源追踪丢失（Kotlin addPill 等堆叠臂都有 withTrackingSource 记账，装备实例轨两侧都不记）→ 月度购买对拍缺 `merchant:N` 面 | Kotlin 在 addEquipmentInstance 内读 withTrackingSource 上下文记账；C++ 加 `trackingSource` 参数（默认空=不记）并在 merchant/quest/building 三链传源——与 Kotlin 作用域口径一致 |
| 16 | 行为级（对拍抓获） | C++ `merchant_settlement.h` executeAutoBuy | 保留 B3 前容量门级 `toEquipment` 预转换，而 B3 装备容量谓词已改纯槽位检查（转换产物被 `(void)eq` 丢弃）→ 每装备条目**白掷 kEquipment 分区 5 抽**（C++ 20 抽 vs Kotlin 15 抽）→ 双臂 13 号分区终态分叉（`DiffMonthSettlementTest` December 场景抓获；灵石/库存/年度追踪显式断言全过、唯 RNG 终态暴露） | 删门级预转换与 `canAddToWarehouse` 死参 eq；词条 roll 只留入库逐件 toEquipment，与 Kotlin AutoBuyService 消费序逐位对齐 |

## 4. 测试面修复记录（存量用例处置表）

### 4.1 删除（测试对象已随 B3 退役）

| 文件/用例 | 理由 |
|---|---|
| `StackRebuildTest.kt`（删，前会话） | `rebuildEquipmentStacks` 已删 |
| `EquipmentFinalStatsCacheTest.kt`（删，前会话） | 旧装备 finalStats 缓存面已删 |
| `EquipmentNurtureSystemTest.kt`（删，本会话） | 孕养体系整体退役（R4→升级系统） |

### 4.2 语义改写（旧行为断言 → B3 新语义）

| 文件 | 改法 |
|---|---|
| `SaveValidatorTest`（前会话） | 六部位参数/断言；堆叠引用 keeps→cleared 两用例按「堆叠引用=孤立」改写 |
| `ItemsTest`/`StackKeysTest`/`MergeStackableTest`/`GameEventRecordTest`/`DiscipleModelsTest`/`AlchemySystemTest`/`AssemblePatchEquivalenceTest`（前会话） | 六值枚举/孕养列→六槽/构造形状 |
| `InventoryConfigTest`（本会话） | equipment_stack 三例 → 退役键走默认 9999/序列化面不复活 |
| `StorageBagMaterializerTest`（本会话） | 装备三类条目改「原样保留不物化」直通断言（4 例） |
| `EntityCountBoundsRuleTest`（本会话） | 装备改 warn-only 无硬上限语义（3 例） |
| `EquipmentProtoNumberFrozenTest`（本会话） | 98/99 从「在册指向」移入 reserved 禁复用（B3 整属性删除） |
| `RoomMigrationV58To59/V60To61/V61To62/V62To63Test`（本会话） | 终版列数锚 83→79（v64 −9+5） |
| `RoomMigrationV63To64Test`（本会话） | List↔Set 恒假断言修正；迁移后快照改 countRows（Robolectric 语句缓存伪影） |
| `RoomMigrationTest`（本会话） | 全链尾追加 `MIGRATION_63_TO_64 passes real Room schema validation` |
| `FakeGameStateStore`（本会话） | 堆叠面全删对齐新接口（equipmentInstances 单轨） |
| `TemplateRegistryGuardTest`（本会话） | 重写为五段复合结构快照逐条比对（12 部件/72 展开/套装/主词条池/副词条池） |
| **engine 存量（测试面代理，5 轮迭代 439→0 错）**：`AISectDiscipleManagerTest`（删 4 孕养用例+达标判定六槽化）、`CultivationCoreProficiencyNurtureTest`（删 5 装备孕养用例留熟练度族）、`HpMpRecoveryEquivalenceTest`（六槽映射+eq 夹具）、`CaptiveGearMaterializationTest`（俘虏装备改 72 条条目+占位空面断言）、`DiscipleEquipmentManagerBagAutoEquipTest`（全文件 B3 重写：袋实例单源/等级保真/同品阶按级比较）、`InventoryNativeTxGateTest`/`InventoryFacadeConfiscateTest`/`InventoryBagTransferTest`（实例轨语义+防复制路径）、`DiscipleFacadeRewardTest`（实例轨赏赐+下线态保反转）、`BuildingService*`/`ProductionProcessor*` 4 文件（锻造产出真实 InventorySystem 实例轨断言）、`BagItemReconstructorTest`（装备恒不重建守卫+分型契约）、`Merchant*Test` 2 文件（实例轨+真 RNG）、`GameEngineCoordinationTest`/`GameEngineWatchItemTest`/`BootSequenceControllerTest`（内嵌 Fake 按新接口改写）、`BattleSystemTest`/`AISectAttackManagerTest`/`StatCalculator*Test`（affix 承载+bodyId）、`RecipeRegistryGuardTest`（旧 73 条形状 assumeTrue 跳过+新形状逐字段比对——守卫不放宽）、`Diff*` 族 6 文件（六槽新形状+新构造参）、`FakeAtomicStateStore`/`MirrorProtoFeedFixture`/`MirrorProtoFeedEquivalenceTest`/`GameViewDiscipleColumnApplyEquivalenceTest`（镜像面六列）、约 30 个 1-2 错文件机械修（MutableGameState 构造删 equipmentStacks 行等） | |
| **feature:game（47→0）**：`ReplaceSelectionDataTest`（全文件 B3 重写：品阶降序+词条摘要副标题）、`MerchantDialogJadeFlowTest`/`GameViewModel*Test` 4 文件（流改名 instances）、`SaveDataTrimmerMailTest`/`SaveLoadViewModel*Test` 3 文件（快照构造删 equipmentStacks） | |
| **app（55→0）**：`InventorySystemTest`（装备段全改实例轨+重复 id 拒绝+9999 默认键）、`GameStateStoreRollbackTest`/`StateRevertRegressionTest`（15 流→14 流）、`GameStateStoreLoadRaceTest` 等竞态 3 文件（loadFromSnapshot 删堆叠参）、`DiscipleMergeCoverageTest`（退役孕养列清单清除） | |
| **detekt 批（本会话收尾）**：四模块 B3 新面违规清零（UnusedImports 实修/结构性清单型抑制+理由），baseline 零新增 | |

### 4.3 新增（方案 §6.1 B3 范围）

| 测试 | 模块 | 覆盖 |
|---|---|---|
| `EquipMainStatPoolTest` | domain | 池容量/内容/声明序/系数/基数表/确定性 |
| `EquipAffixPoolTest`（含 EquipAffixRoll 用例） | domain | 权重 13/13/14/15/15/15/15/不放回 3 条/确定性/分布抽样 |
| `EquipmentSetDatabaseTest` | domain | 两套三档/门槛不越级/2 件套类型差异化/穿满 +30% |
| `EquipmentSlotOrderGuardTest` | domain | displayOrder=声明序/@ProtoNumber 10..15 无重复 |
| `EquipmentSingleSourceGuardTest` | domain | D1 转发一致性/分类视图完全划分/Registry 源码扫描禁字面量 |
| `EquipmentLevelSystemTest` | engine | 等级曲线/满级清零/强化节点/消耗与分解返还公式 |
| `EquipmentUpgradeServiceTest` | engine | 先验后扣/满级失败/强化节点 subRolls/兽材升序扣/分解 50%/锁与穿戴拒分解 |
| `EquipStatResolverTest` | engine | 分项通道/等级成长/强化放大/损坏词条健壮 |
| `EquipmentSetBonusTest` | engine | 0..6 件逐档/两套混穿/超量封顶/散件未知套 |
| `EquipmentLevelPersistGuardTest` | engine | R5 根因守卫：装卸往返/跨弟子/袋装配成长逐位保真 |
| `EquipmentRarityGateTest` | engine | S17：可穿上限阶梯/产出钳制/pickRarity 分层阈值 |
| `EquipmentStackRemovalGuardTest` | engine | 符号面：状态面与 UI 消费面归零 + 白名单登记 |
| `TemplateCodegenIntegrityGuardTest` | engine | D9/D10：生成器无死输出/幂等面/四表消费面/中性源结构 |
| `EquipmentValueSanitizeRuleTest` | data | coerce 口径/重复词条保首条/幂等/无损坏恒 Passed |
| `EquipmentLegacyCompensationTest` | data | 折算公式/四路数据源/1 亿上限/幂等/无存量恒 Passed |
| `EquipmentNoCapGuardTest` | data | warn 800/页面 1200/不截断/溢出邮件装备键白名单 |
| `DiffEquipmentUpgradeTest` | engine | 1486/1487 双臂：Lv1→30 全序列/强化演化/RNG 分区状态全等/分解返还/失败臂零消费 |
| `RoomMigrationV63To64Test`（前会话写，本会话修+跑绿） | data | 七步迁移/真实 Room 校验/幂等 |

## 5. 跨语言对拍状态（方案 §6.4，诚实归因）

| 对拍 | 状态 | 说明 |
|---|---|---|
| `DiffEquipmentUpgradeTest` | ✅ **全绿**（Lv1→30 全序列/强化演化/分区终态全等/分解返还/失败臂零消费） | 1486/1487 双臂逐位（含 RNG 消费序锚） |
| `DiffMonthSettlementTest` | ✅ **5/5 全绿**（购买结算/autoBuy 双臂逐位，含 13 号分区终态全等——抓获 §3.4 #16） | 月结购买链双臂 |
| `DiffEquipmentGenerationTest` | ⚠️ **缺口登记** | C++ 侧生成（factory.create）无干净的既有派发动作可达（藏在任务奖励/锻造/商人链内部）；为不造假绿，本批不落场景脚手架。**补偿守卫链**：①四方数据对齐（TemplateRegistryGuardTest ↔ equipment_db_test ↔ 中性源）；②C++ `equip_main_stat_test`/`equip_affix_test` 同算法逐位实现（§6.3）；③Kotlin 池/工厂单测；④`DiffEquipmentUpgradeTest` 的 13 号分区消费序全等断言（强化抽取走同一分区，任何一侧算法漂移即分叉）。建议 B4 补测试桥端口（nativeCoreGenerateEquipment 桌面专用）后落地 |
| `DiffEquipmentSetBonusTest` | ⚠️ **缺口登记** | C++ resolveSetBonus 消费在 stat 计算内部，无直接查询动作；补偿=`EquipmentSetBonusTest`（Kotlin 0..6 件全档）+ C++ `equip_set_bonus_test`（同语义）+ 双端表同源。同建议 B4 随 GameView/属性查询通道补 |

## 6. 决策登记（HANDOVER-B3 §五 / R2 §四 原样有效 + 本会话新增）

- 影子表方案（方案盲区补齐）、卸装实例保留、ForgeRecipe 12 条不分 tier、六名展示、
  proto 六列号、ActionId 1486/1487、kEquipment=13、`nextBoolean()` 语义锚定、
  AI 轻量实例、forgeSlots 占位——见两份交接文档，**勿翻案**。
- **本会话新增**：
  1. `EquipmentStackRemovalGuardTest` 白名单 = 有意偏差面（deprecated 载体/补偿链/
     秘境旧轨待 B4/旧 ForgeRecipe DTO 待后续批），名单文件删除即红。
  2. 生成/套装两对拍按 §5 登记缺口（不造假绿）。
  3. UI 语义从简落地口径：详情=套装·部件名+Lv+词条摘要；升级/分解完整对话框留续批。

## 7. 途中发现（不阻塞，报告项）

1. **B3 前会话遗留真 bug 两处已修**（§3.1/§3.2）——其中 §3.1 属主代码生产缺陷，
   建议验收重点回归穿戴/自动装备链。
2. `AlchemySystem.ForgeRecipe`（旧 11 字段 DTO）仍被引擎 10+ 文件消费——B3 未迁，登记后续批。
3. Kotlin `SecretRealmBackpack.equipment` 仍是旧堆叠轨（「待 B4」在册）；跨端载体临时不对称，
   对拍注意背包结算段（C++ addEquipmentInstance vs Kotlin toLegacyInstance→addEquipmentInstance，
   语义等价）。
4. 旧档储物袋 `equipment_instance` 引用式条目（无 payload）在「物化直通 + 补偿两类型
   白名单」下成为永久悬挂项——量级估计趋零（D-03 后物化面已覆盖绝大多数旧档），建议
   B4 补偿规则把无 payload 的该类型纳入摘除（零价值，实例本体已经影子行折算）。
5. `ForgeRecipeDatabase.TIER_DURATION` 注释单位「旬」vs 消费语义「月」不一致（UI 代理发现）。
6. `ForgeViewModel.showTierLockedHint()` 职业门禁退役后无 UI 调用点（未越权删）。
7. Robolectric 语句缓存教训：**迁移后勿用迁移前同 SQL 字符串快照**（`SELECT *` 游标
   复用旧列数元数据 → IndexOutOfBounds 伪影）；用 countRows/带 WHERE 查询。
8. 子代理派发在账号 5h 配额窗口会 1308 中断——本会话三代理接力未触顶。

## 8. I1 不可回退公告（文案预留，B5 发布时使用）

> 本次更新对装备系统进行了彻底重构：所有装备转换为全新的六部位套装体系
> （头/身/手/脚/武器/腿），旧装备已按原价折算为灵石并通过邮件发放补偿。
> 由于存档结构升级，**更新后无法回退到旧版本**，旧版本客户端将无法读取新存档，
> 请更新前确认。给您带来不便，敬请理解。

## 9. 收官

- 单笔提交：`feat(equip): B3 装备体系原子替换——…`（提交前还原
  `android/app/src/main/assets/atlas-rgba-manifest.json` codegen 副产物）。
- 随笔入库：HANDOVER-B3.md / HANDOVER-B3-R2.md / 本报告 / ui-errs-r2-snapshot.txt（删除）。
- 双 changelog 归 B5 不动（方案 §九批次表）。
