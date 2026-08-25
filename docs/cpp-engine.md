# C++ 游戏引擎（game-core）架构文档

> 更新日期：2026-08-16。Kotlin→C++ 迁移（批次 0：基础设施）。
> 总方案见 `docs/adr/cpp-engine-migration.md`（计划批准后补充 ADR 细节）。

## 1. 目标架构

```
Compose UI (feature/game + app) —— 保留，零改动
  ↓
ViewModel / UseCase —— 保留，零改动
  ↓
GameEngine 族 (~275 方法) + 10 Facade (117 方法) —— Kotlin 保留签名，方法体转发
  ↓  参数/结果 JSON 编解码 (kotlinx.serialization ↔ nlohmann/json)
JNI 桥 GameCoreBridge (通用入口：init/advance/execute/export/import/poll + Clock 注入)
  ↓
★ C++ GameCore（纯 C++20、零 Android 依赖、桌面可编译、iOS 可复用）—— 新真相源
  ↓  版本号 + 变更集（增量）或全量快照（存档/读档）
Kotlin StateSyncService → GameStateStore（镜像写入，接口/StateFlow 不变）
  ↓
Room 34 表 + .sav/云存档 —— 保留，链路零改动
```

## 2. 目录结构

```
android/app/src/main/cpp/
├── CMakeLists.txt              # 主构建：native-renderer（渲染）+ native-game-core（引擎 JNI）
├── GameCoreBridge.cpp/.h       # 引擎 JNI 桥（Android 专用薄层，仅转换不承载逻辑）
├── gamecore/                   # 纯 C++ 引擎（零 Android 依赖）
│   ├── CMakeLists.txt          # 静态库 game-core（GAMECORE_BUILD_TESTS=ON 构建桌面测试）
│   ├── include/gamecore/
│   │   ├── core/    types.h / result.h / clock.h / logger.h
│   │   ├── rng/     pcg_xsh_rr.h（DeterministicRng 复刻）/ rng_manager.h（8 分区）
│   │   ├── state/   （批次 1：模型 + 快照编解码）
│   │   ├── system/  （批次 3+：各游戏系统）
│   │   ├── game_core.h         # 引擎门面（execute/advance/export/import/poll）
│   │   └── action_ids.h        # 生成产物（scripts/gen-action-ids.mjs）
│   ├── src/  rng.cpp / game_core.cpp
│   ├── jni/  GameCoreJni.cpp   # 桌面 JNI 对拍桥（无 Android 依赖，测试用）
│   ├── test/  rng_test / result_test / game_core_test（GTest）
│   └── third_party/nlohmann/json.hpp   # vendored 单头 JSON
├── VulkanBackend.cpp ...       # 既有渲染层（不动）
```

## 3. 批次 0 已交付

| 项 | 说明 |
|---|---|
| game-core 静态库 | C++20，零 Android 依赖，桌面/CI 可编译（`cmake -B build -DGAMECORE_BUILD_TESTS=ON`） |
| 基础接口 | `Result<T>`（三态，对应 DomainResult）、`Clock`（现实时间注入）、`Logger`（跨平台日志） |
| RNG 复刻 | PCG-XSH-RR 64→32（先截断 32 位再旋转的修复后语义）；`RngManager` 8 分区（BATTLE=0…SECRET_REALM=7）与 Kotlin `RngPartition` id 对齐；黄金序列 GTest 守护 |
| JNI 桥 | Android `GameCoreBridge.cpp`（库名 native-game-core）+ Kotlin `GameCoreBridge.kt`；通用入口设计 |
| ActionId 协议 | `scripts/gen-action-ids.mjs` 单一清单 → `action_ids.h` + `ActionIds.kt` 双产物（提交 git 防漂移） |
| 桌面对拍桥 | `gamecore/jni/GameCoreJni.cpp` + `DiffRngBridge.kt` + `DiffRngTest.kt`（JUnit 跨语言 RNG 对拍；`-Dgamecore.jni.path` 注入） |
| CI | `ci.yml` 新增 `cpp-engine-test` job（Linux g++/CMake/GTest 独立跑） |
| 构建脚本 | `scripts/build-desktop-jni.ps1`（本地 llvm-mingw 构建对拍库） |

## 4. 确定性保真要求（迁移全程）

1. **RNG**：PCG-XSH-RR 复刻；`shuffled(rng)` 用 `std::stable_sort`（Kotlin sortedBy 稳定）
2. **数学语义**：IEEE754 double、Int/Long 溢出回绕、截断除法、coerceIn/coerceAtMost
3. **顺序稳定性**：禁止 `unordered_map` 参与业务迭代（用 map/vector + 显式排序）
4. **现实时间**：一律 `Clock` 注入（对拍用 FixedClock）；引擎内禁直接系统时间
5. **对拍守护**：C++ GTest 黄金序列 + JUnit 跨语言对拍（DiffRngTest）+ 后续存档级对拍

## 5. 批次进度

### 批次 0（已完成）：基础设施

| 项 | 说明 |
|---|---|
| game-core 静态库 | C++20，零 Android 依赖，桌面/CI 可编译（`cmake -B build -DGAMECORE_BUILD_TESTS=ON`） |
| 基础接口 | `Result<T>`（三态，对应 DomainResult）、`Clock`（现实时间注入）、`Logger`（跨平台日志） |
| RNG 复刻 | PCG-XSH-RR 64→32（先截断 32 位再旋转的修复后语义）；`RngManager` 8 分区与 Kotlin `RngPartition` id 对齐；黄金序列 GTest + JUnit 跨语言对拍双守护 |
| JNI 桥 | Android `GameCoreBridge.cpp`（库名 native-game-core）+ Kotlin `GameCoreBridge.kt`；通用入口设计 |
| ActionId 协议 | `scripts/gen-action-ids.mjs` 单一清单 → `action_ids.h` + `ActionIds.kt` 双产物（提交 git 防漂移） |
| 桌面对拍桥 | `gamecore/jni/GameCoreJni.cpp` + `DiffRngBridge.kt` + `DiffRngTest.kt`（JUnit 跨语言 RNG 对拍；`-Dgamecore.jni.path` 注入） |
| CI | `ci.yml` 新增 `cpp-engine-test` job（Linux g++/CMake/GTest 独立跑） |

### 批次 1（已完成）：状态模型 + JSON 快照

| 项 | 说明 |
|---|---|
| C++ 状态模型 | `state/models.h`：GameData 全部标量/简单集合字段（~90 个）+ **嵌套对象类型 18 个**（SectPolicies 40 字段 / ElderSlots+DirectDiscipleSlot / ProductionSlot / GridBuildingData / MerchantItem / Alliance / VassalContract / SectRelation / WorldSect / ResidenceSlot / SpiritFieldPlant / PatrolConfig / WorldLevel / MailClaimRecord / SectLevelClaimRecord / YearlyReport / PendingTraitAdd）；Disciple 与 8 类物品核心字段 |
| JSON 快照编解码 | `state/json_codec.h/.cpp`：nlohmann ↔ kotlinx 字段名一致；宽松 from_json；int-key Map 显式转换；可空字段 `std::optional`（含 NullableStringAsEmpty/IntAsZero 序列化器语义对齐）；浮点规范化（规避 kotlinx "N.0" 缺陷） |
| 对齐的 Kotlin 语义 | DiscipleSerializer 排除 slotId；lifeEvents/monthlyUsedPillIds 类体属性；ProductionSlot 的 slotId/requiredMaterials @Transient；@Transient 字段不进入快照协议（读档后由引擎重算） |
| GameCore 集成 | `GameCore::state()` 持有真相状态；`importStateJson/exportStateJson` 实现 |
| 快照 DTO | Kotlin `NativeGameState.kt`（gameData + 10 类实体列表） |
| 对拍测试 | `DiffStateTest`（JUnit 跨语言快照往返逐字段相等：gameData 90 标量 + 10 嵌套类型样本 / 弟子+全物品 / 空状态）+ C++ `json_codec_test`（38 测试全绿） |
| 验证 | 桌面 GTest 38/38；JUnit 对拍 10/10；NDK `externalNativeBuildRelease` 通过 |

> 批次 1 剩余（核心已完成）：低频嵌套类型
>
> | 项 | 说明 |
> |---|---|
> | C++ 模型补齐 | `state/models.h` 新增：`BloodRefinementProgress`/`BloodRefinementBonusTotal`/`BloodRefinementPctTotal`（血炼三件套）、`ManualProficiencyData`（功法熟练度）、`SpiritMineSlot`（矿脉槽位 7 字段）、`PatrolSlot`（巡视槽位）；`PatrolConfig` 补 `requireFullStatus`；GameData 接入 `manualProficiencies`/`spiritMineSlots`/`bloodRefinement*Totals`/`activeBloodRefinements`/`patrolSlots` |
> | JSON 编解码 | `json_codec.cpp` 为上述类型实现 to_json/from_json 并接入 GameData 快照 |
> | 对拍测试 | `DiffNestedTypesTest`（3：血炼嵌套/功法精通+矿脉/空字段——快照往返逐字段一致） |
> | 根因修复 | **发现并修复**：`json_codec.cpp` 中 `worldMapSects` 与 `manualProficiencies/spiritMineSlots` 的 GC_TO/GC_FROM 行因"中文注释与代码同行"被注释掉——嵌套对象序列化缺失导致快照往返丢字段（调试中暴露）；修复后 DiffStateTest 等全量回归通过 |
> | 验证 | 桌面 GTest 268/268；JUnit 对拍累计 71/71；NDK 构建通过 |
>
> 批次 1 剩余验收达成：低频嵌套类型核心补齐。
> 剩余（登记随后续批次）：秘境（SecretRealmState/ExplorationSession/MemberState/EventRecord/Option/Backpack 等 ~12 类型）、
> 探索队伍/侦查信息等重型状态——随批次 8 探索/秘境状态机落地配套。

### 批次 1 剩余（已完成）：远古秘境状态机

| 项 | 说明 |
|---|---|
| C++ 秘境状态机 | `state/models.h`：`SecretRealmState`（地图实例 id/name/x/y/spawnYear/spawnMonth/spriteIndex）+ `SecretRealmExplorationSession`（secretRealmId/members/stamina/backpack/currentEvent(optional)/eventHistory/startYear/startMonth/resultMessage）+ `SecretRealmMemberState`（discipleId/name/portraitRes/realm/currentHp/isDying/isDead/maxHp）+ `SecretRealmEventRecord`（eventType/title/description/options/chosenOptionIndex/resultText/params/absoluteMonth）+ `SecretRealmOption`/`SecretRealmEventParams`（妖兽参数 + AI 宗门遭遇）/`SecretRealmBackpack`（7 类物品暂存）/`SecretRealmRewardItem`/`SecretRealmAITeam`/`SecretRealmAIMember` —— 与 Kotlin SecretRealmModels 逐字段对齐 |
| JSON 编解码 | `json_codec.cpp`：10 个类型 to_json/from_json（含 currentEvent 的 std::optional 编解码）+ GameData 三个字段（secretRealmState/secretRealmSession/secretRealmAITeams）GC_TO/GC_FROM |
| GTest | `JsonCodecTest.NestedTypesRoundTrip` 扩展（秘境全字段往返：状态/会话/成员/背包/事件/参数/奖励/AI 队伍）——桌面 GTest 278/278 |
| JUnit 对拍 | `DiffNestedTypesTest.secret realm state machine round trip`（C++ 导出↔Kotlin 解码逐字段一致）——nativebridge 累计 110/110 |
| 验证 | 桌面 GTest 278/278；JUnit nativebridge 110/110；NDK 构建不受影响 |

> 批次 1 剩余验收达成：远古秘境状态机 C++ 模型 + 快照编解码完成，双端对拍逐字段一致。
> 剩余（登记随后续批次）：探索队伍/侦查信息等重型状态——依赖 SecretRealm 探索队伍完整状态机（批次 8 探索配套）。

### 批次 2（第一子步已完成）：静态数据/注册表（装备表）

| 项 | 说明 |
|---|---|
| 模板提取生成器 | `scripts/gen-templates.mjs`：解析 Kotlin Registry Map 字面量（`"id" to EquipmentTemplate(...)` 模式）→ 生成 C++ 静态表 + 抽取快照 JSON；去重校验；产物提交 git 防漂移 |
| C++ 装备表 | `include/gamecore/data/equipment_db.h`：`EquipmentTemplate` 结构 + `equipmentTemplates()`（72 条，Kotlin EquipmentDatabase 同源；slot 为 EquipmentSlot.name） |
| 守卫测试（双端锚定） | Kotlin `TemplateRegistryGuardTest`（快照 ↔ EquipmentDatabase 实时数据，72 条逐字段比对）；C++ `equipment_db_test`（数量/代表性条目/去重，4 测试）——任一侧改动数据 → 测试失败提示重跑生成器 |
| 验证 | 桌面 GTest 43/43；JUnit nativebridge 11/11（含守卫）；NDK 构建通过 |

> 批次 2 剩余（后续回合）：丹药配方/功法/天赋/体质/词缀/材料/锻造配方/妖兽材料等 Registry 的提取器扩展（格式差异逐表适配）。

### 批次 2 剩余（核心已完成）：天赋/体质/词条静态表

| 项 | 说明 |
|---|---|
| C++ 特质表 | `data/trait_db.h`：`TalentTemplate`/`PhysiqueTemplate`/`AffixTemplate` + 生成函数 `talentTemplates()`（109 条 = 正面 104 + 负面 5）/`physiqueTemplates()`（24 = 21 + 3）/`affixTemplates()`（71 = 68 + 3）+ `talentById`/`physiqueById`/`affixById` 查询辅助 |
| 等价生成器 | `scripts/gen-trait-db.mjs`：Node 侧复刻 Kotlin 程序化生成逻辑（config 梯度 + 循环拼接 + talentGrade 旧 1-6→新 1-3 品映射 + `%.0f`/`%.1f` 百分比格式化 + 全角逗号混合描述 + 职务「之印」命名）→ trait_db_sample.json |
| 守卫测试（双端锚定） | Kotlin `TraitRegistryGuardTest`（快照 ↔ 三 Registry 实时数据逐字段：id/name/rarity/effects/positionBonus 等，4 测试）；C++ `trait_db_test`（数量/代表性条目/职务/去重/查询辅助，9 测试） |
| 验证 | 桌面 GTest 225/225（含 TraitDbTest 9）；JUnit 守卫 4/4；NDK 构建通过 |

> 批次 2 剩余验收达成：天赋/体质/词条静态表 C++ 化 + 双端守卫完成（Kotlin 改数据 → 重跑 `node scripts/gen-trait-db.mjs`）。
> 剩余（登记随后续批次）：丹药配方/锻造配方/功法/材料/妖兽材料表——其中配方类为**程序化生成**（依赖 ItemDatabase 模板，
> 随批次 7 生产/内政落地时以 C++ 等价生成器补齐）。

### 批次 2 剩余（已完成）：丹药/锻造配方表

| 项 | 说明 |
|---|---|
| C++ 配方表 | `data/recipe_db.h`：`ForgeRecipeTemplate`（id/name/type/tier/rarity/description/materials/duration/successRate）+ `PillRecipeTemplate`（含 breakthroughChance/targetRealm/全部效果字段）+ `forgeRecipes()`（72 条静态字面量逐字复刻）+ `pillRecipes()`（732 条程序化生成：TIER_DURATION/TIER_SUCCESS_RATE/TIER_HERB_IDS/herbMat/PillGrade 循环；配方依赖的 ItemDatabase PillTemplate 在 detail 内等价生成 732 模板）+ `forgeRecipeById`/`pillRecipeById`/`pillTierName` 查询辅助 |
| 等价生成器 | `scripts/gen-recipe-db.mjs`：Node 侧复刻 PillRecipeDatabase + ItemDatabase 生成逻辑（含 `%.0f` roundToInt 半进位、双属性描述英文属性键格式陷阱、突破丹 tier≥3 追加第三味材料）→ recipe_db_sample.json（72 锻造 + 732 丹药完整条目） |
| 守卫测试（双端锚定） | Kotlin 守卫（TemplateRegistryGuardTest 模式，快照 ↔ ForgeRecipeDatabase/PillRecipeDatabase 实时数据逐字段）；C++ `recipe_db_test`（数量/代表性条目/去重/查询辅助/品阶名，10 测试） |
| 验证 | 桌面 GTest 278/278（含 RecipeDbTest 10）；临时 JUnit 对拍 3/3（快照 ↔ Kotlin 运行时 804 条逐字段一致，验证后已删）；C++ 表 ↔ 快照全量对拍 804/804 一致；NDK 构建不受影响 |

> 批次 2 剩余验收达成：丹药/锻造配方表 C++ 化 + 快照锚点完成（Kotlin 改数据 → 重跑 `node scripts/gen-recipe-db.mjs`）。
> 剩余（登记随后续批次）：功法/材料/妖兽材料表。

### 批次 2 剩余（已完成）：妖兽材料 + 功法静态表

| 项 | 说明 |
|---|---|
| C++ 妖兽材料表 | `data/beast_material_db.h`：`BeastMaterialTemplate`（id/name/tier/rarity/category/description/icon/dropWeight + 派生 price/materialCategory）+ `beastMaterialTemplates()`（**192 条** = 8 妖兽 × 4 材料 × 6 品阶；源码注释旧数字 288 有误）+ `beastMaterialById`/`beastMaterialsByBeastType`（中文妖兽名如"虎妖"/英文前缀均支持，Kotlin 同语义） |
| C++ 功法表 | `data/manual_db.h`：`ManualTemplate`（全 27 字段：stats map + skill 全字段 + skillBuffs 列表）+ `manualTemplates()`（**540 条** = attack 108 + defense 162 + support 234 + mind 36）+ `manualById` |
| 等价生成器 | `scripts/gen-beast-material-db.mjs`（解析 BeastMaterialDatabase.kt listOf 字面量，price=GameConfig.Rarity.materialBasePrice、category→MaterialCategory.name 派生复刻）→ beast_material_db_sample.json；`scripts/gen-manual-db.mjs`（解析 assets/data/manuals.json 四类功法）→ manual_db_sample.json |
| 守卫测试（双端锚定） | Kotlin `BeastMaterialRegistryGuardTest`（3：快照↔Kotlin Registry 逐字段 + ItemDatabase.allMaterials 交叉锚定 + 妖兽类型查询语义）+ `ManualRegistryGuardTest`（2：快照↔manuals.json 数据源逐字段 + 类型分布）；C++ `beast_material_db_test`（5）+ `manual_db_test`（6） |
| 验证 | 桌面 GTest 294/294（新增 11）；JUnit 守卫 5/5；NDK 构建不受影响 |

> 批次 2 剩余验收达成：妖兽材料/功法静态表 C++ 化 + 双端守卫完成（Kotlin 改数据 → 重跑 `node scripts/gen-beast-material-db.mjs` / `gen-manual-db.mjs`）。
> 剩余（登记随后续批次）：材料表本身 = 妖兽材料转换（`ItemDatabase.allMaterials` = beastMaterials，已随妖兽表覆盖）；功法表数据源为
> assets 资源文件（manuals.json/manuals.pb），生成器直接读资源文件，**无 Kotlin 常量需守卫**——数据源改动需重跑生成器。

### 批次 3（已完成）：时间系统 + 惰性结算引擎

| 项 | 说明 |
|---|---|
| C++ 时间系统 | `system/time_system.h`：`advancePhase`（年/月/旬进位，等价 TimeSystem.onPhaseTick）、`totalPhases` 时间编码、月末/年末判定——纯函数零依赖 |
| C++ 结算引擎 | `system/settlement.h`：`SettlementEngine`——**对齐 GameTimeClock 语义**（对抗性审查 2026-08-22：msPerPhase=2000ms@1x × speed、单 tick 上限 3×speed 旬、超限丢弃余量）、月/年边界检测、`onPhaseSettle`/`onMonthChange`/`onYearChange` 结算钩子（批次 4-7 注册，登记 C-10） |
| GameCore 接入 | `advance(wallDeltaMs)` 走结算引擎；`advancePhases(n)` 直接推进（对拍/测试）；**读档复位结算引擎**（对抗性审查 A1 修复：importStateJson 后 reset 防累积残留多推进） |
| 对拍测试 | `DiffTimeTest`（JUnit 跨语言：Kotlin 复刻 TimeSystem.onPhaseTick vs C++ 推进，旬/月/年进位、1000 旬跨年、**非时间字段不变性**逐位一致）+ C++ `time_system_test`（15 测试：黄金场景/边界/accumulator 消费/speed 缩放/暂停/部分旬累积/超限丢弃/钩子） |
| 验证 | 桌面 GTest 58/58；JUnit nativebridge 16/16（DiffTime 5 + DiffState 3 + DiffRng 7 + 守卫 1）；NDK 构建通过 |

> 批次 3 验收达成：同档 1000 旬推进时间逐位一致 + 空档非时间状态不变（灵石/玉符/设置等）。
> 月变/年变结算钩子的系统实现依赖批次 4-7 子系统（登记 C-10），随各批次填充。

### 批次 4（已完成）：经济/库存/生产系统

| 项 | 说明 |
|---|---|
| C++ 经济系统 | `system/economy.h`：`SpiritStoneExchange`（RATIO=10,000、EFFECTIVE_RATIO=8,000、safeAdd/safeMultiply 溢出回绕、totalSellValue/toLowGrade/fromLowGrade/exchange/splitToGrades）+ `SpiritStoneWallet`（add/deduct/batch、自动售卖中/上品补差价、预检查整体回滚、年度报告累积）——Kotlin SpiritStoneWallet 纯逻辑逐行等价 |
| C++ 库存系统 | `system/inventory.h`：`StackableItemStore<T>`（多堆叠合并/最近使用优先/分块创建保首 id/Partial-Failure 语义）等价移植；`InventorySystem` addXxx/removeXxx/canAdd（槽位预算 = 仓库容量 - 其他类型、各类型 maxStack 表、溢出转邮件 `OverflowDraft`、年度物品来源追踪） |
| C++ 灵田收获 | `system/spirit_field.h`：`processSpiritFieldHarvest`（成熟判定、种子名→灵草反查、灵草/种子入库、续种消耗、无种子清地、跨宗门隔离、SYSTEM 分区 RNG 种子奖励） |
| 灵草/种子静态表 | `scripts/gen-templates.mjs` 扩展 → `data/herb_db.h`（54 灵草 + 54 种子，与 Kotlin HerbDatabase 同源）+ 守卫快照 `herb_db_sample.json` |
| 对拍测试 | `DiffEconomyTest`（8 测试：add/deduct/batch/autoConvert/溢出回绕 与 Kotlin 钱包逻辑逐位一致）+ `DiffInventoryTest`（5 测试：合并/分块/移除/丹药品阶键/满仓 与 Kotlin 真实 StackableItemStore 一致）+ `DiffSpiritFieldTest`（4 测试：成熟收获/清地/续种/未成熟 与 Kotlin 收获语义一致） |
| GTest | `economy_test`（19）/`inventory_test`（15）/`spirit_field_test`（9）+ herb_db（4）——桌面 GTest 合计 127/127 |
| 验证 | 桌面 GTest 127/127；JUnit 对拍 17/17；NDK `externalNativeBuildRelease` 通过（arm64-v8a + armeabi-v7a） |

> 批次 4 验收达成：经济/库存/灵田核心逻辑 C++ 化完成，跨语言对拍全绿。
> 剩余（登记随后续批次）：炼丹/锻造配方为**程序化生成**（PillRecipeDatabase/ForgeRecipeDatabase 循环 + ItemDatabase 模板），
> 无法正则提取——批次 5 起以 C++ 等价生成器方式落地；光环/政策加成（HerbGardenAuraService/ZoneCalculator）批次 7 接入。

### 批次 5（核心已完成）：弟子系统

| 项 | 说明 |
|---|---|
| C++ 属性乘区法 | `system/disciple.h`：`computeBaseStats`（境界基值 × 方差乘区 × 层数乘区 × (1+天赋%+血炼%)，roundToInt 语义）、`calculateCultivationPerPhase`（5 乘区连乘 + 下限 1.0）、`calculateBreakthroughChance`（baseZone × (1+指导+自身) × (1-惩罚) + adFlat）、寿命/魂力/师徒/父母/丧亲公式、Realm 配置表 10 境界 + 突破概率表（层数线性插值） |
| C++ 修炼推进 | `system/cultivation.h`：`accumulateCultivationPerPhase`（修为 + rate 钳制上限）、`computeMaxCultivation`（base + (layer-1)×(nextBase-base)/maxLayers；仙人返回自身）、`checkpointDisciple`/`getEffectiveCultivation`（checkpoint + rate×Δmonth×3）、`checkpointAllDisciples`、绝对月份编码 |
| C++ 突破系统 | `system/breakthrough.h`：`tryBreakthrough`（BREAKTHROUGH 分区 RNG 判定）、`applyBreakthroughSuccess/Failure`（修为清零、层数+1/大境界+1 寿命增益、失败 HP/MP 打一折）、`performBreakthrough` 连续突破循环（迭代保护）、`estimateMonthsToNextBreakthrough`（ceil 旬数 → 整数月） |
| C++ 生命周期 | `system/lifecycle.h`：`computeMaxAge`（max(lifespan, realmMaxAge, realmMaxAge×(1+寿命加成))，硬上限 20000）、`ageDisciple`/`ageAliveDisciple`（年龄+1、5 岁 realmLayer 回正、寿元耗尽判定） |
| 对拍测试 | `DiffDiscipleTest`（13：基础属性/修炼乘区/突破乘区/寿命/师徒/父母/魂力/资质）+ `DiffCultivationTest`（9：maxCultivation/累积/投影/绝对月份）+ `DiffBreakthroughTest`（5：成功/失败应用/时间预估）+ `DiffLifecycleTest`（3：寿元/老化/回正）——与 Kotlin 真实实现逐位一致 |
| GTest | `disciple_test`（28）/`cultivation_test`（16）/`breakthrough_test`（11）/`lifecycle_test`（11）——桌面 GTest 合计 192/192 |
| 验证 | 桌面 GTest 192/192；JUnit 对拍 30/30（含批次 4 累计 47/47）；NDK `externalNativeBuildRelease` 通过 |

> 批次 5 验收达成：弟子属性/修炼/突破/生命周期核心公式 C++ 化完成。
> 剩余（登记随后续批次）：11 槽分配、死亡物化（依赖 DiscipleTables 列式 + InventorySystem 物化链路，批次 9 转发层接线）；
> 天赋/体质/词条 effects 依赖批次 2 剩余 Registry（C++ 等价生成器，随批次 2 剩余落地后接线）。

### 批次 6（核心已完成）：战斗系统

| 项 | 说明 |
|---|---|
| C++ 战斗计算 | `system/battle.h`：`calculateFinalDamage`（乘区法：攻防减伤 DEFENSE_CONSTANT=500、暴击 ×1.5、体质/词条/境界压制独立乘算因子、波动、MIN_DAMAGE 钳制）、`calculateDamageVariance`（±20% 抖动 1 位小数）、`calculateDodgeChance`（速度差/总速度 × modifier，上限 0.5）、`calculateRealmGapFactors`（小层 ±30%/层 + 大境界 +100%，Long 中间运算 + safeRealm/safeLayer 篡改钳制）、`checkInstantKill`（高 1 大境界斩杀）、`calculateShieldAbsorption`（护盾钳制 [0,1]）、`applyDotDamage`、`updateCooldowns` |
| 对拍测试 | `DiffBattleTest`（7：最终伤害全乘区/暴击/境界压制/斩杀/闪避/护盾——与 Kotlin BattleCalculator 真实实现逐位一致） |
| GTest | `battle_test`（24）——桌面 GTest 合计 225/225 |
| 验证 | 桌面 GTest 225/225；JUnit 对拍累计 54/54；NDK 构建通过 |

> 批次 6 验收达成：战斗乘区法核心公式 C++ 化完成。
> 剩余（登记随后续批次）：BattleSystem 回合编排/AI 选技能/目标选择（依赖 Combatant 完整模型 + Buff 列表，批次 6b 落地）；
> 宗门战/妖兽战结算（AISectAttackManager/BeastAttackProcessor）批次 6c。

### 批次 7（核心已完成）：世界/内政

| 项 | 说明 |
|---|---|
| C++ 内政系统 | `system/government.h`：`zoneCalculate`（乘区法通用）/`calculateProbability`/`calculateReducedDuration`/`calculateAcceleratedTime`（ZoneCalculator 全量等价）、`processPolicyCosts`（固定/按弟子数/周期性三模式，灵石不足自动关闭政策）、`policyMonthlyDeltas`（6 政策忠诚/道德月度净变化）、`buildSpiritMineZones`/`calculateSpiritMineMonthly`/`settleSpiritMineProduction`（灵矿时间戳差分结算 + 采矿技能/执事道德/政策乘区）、`calculateSalaryPlan`/`payAnnualSalary`（年俸：开源节流 -30%、灵石不足不发） |
| 对拍测试 | `DiffGovernmentTest`（6：乘区法/概率/时间缩减加速/政策月度/灵矿产出——与 Kotlin ZoneCalculator 真实实现逐位一致） |
| GTest | `government_test`（20）——桌面 GTest 合计 245/245 |
| 验证 | 桌面 GTest 245/245；JUnit 对拍累计 60/60；NDK 构建通过 |

> 批次 7 验收达成：内政核心公式 C++ 化完成。
> 剩余（登记随后续批次）：政策 toggle 与事件处理器（CultivationEventProcessor 月度事件编排）、外交/宗门等级/兑换码（RedeemCodeManager/MailService 状态链路）、年度报告归档（onYearChange 钩子）——批次 9 转发层接线。

### 批次 8（核心已完成）：探索/世界关卡

| 项 | 说明 |
|---|---|
| C++ 世界关卡 | `system/exploration.h`：`checkLevelExpired`（defeated/年份/月份含等号过期判定）、`filterExpiredLevels`（清理）、`shouldRefreshLevels`（每 3 月含等号）、`moveBeasts`（EXPLORATION 分区 RNG 极坐标偏移 + 边界钳制 [34,1698-34]×[34,926-34]、洞府/已击败/已过期不动）、`processWorldLevelsMonthly`（清理 + 刷新判定 + 移动编排） |
| 对拍测试 | `DiffExplorationTest`（2：过期判定/刷新判定——与 Kotlin WorldLevel.checkExpired + 复刻刷新语义一致） |
| GTest | `exploration_test`（9）——桌面 GTest 合计 254/254 |
| 验证 | 桌面 GTest 254/254；JUnit 对拍累计 62/62；NDK 构建通过 |

> 批次 8 验收达成：世界关卡核心逻辑 C++ 化完成。
> 剩余（登记随后续批次）：LevelGenerator 关卡生成（依赖世界地图生成算法）、SecretRealm/Cave/HeavenlyTrial/Patrol 状态机
> （依赖完整状态模型 + 事件系统，批次 9 转发层接线后随剩余 @Transient 字段补齐）。

### 批次 9（核心已完成）：ActionId 协议 + execute 分发表

| 项 | 说明 |
|---|---|
| ActionId 协议 | `scripts/gen-action-ids.mjs` 填充 **46 个动作**（批次 4-8 已落地系统全覆盖：钱包 5 / 库存 17 / 灵田 1 / 弟子 9 / 战斗 7 / 内政 5 / 探索 2）→ `action_ids.h` + `ActionIds.kt` 双产物 |
| C++ execute 分发表 | `src/execute_dispatch.cpp`：`GameCore::execute` 完整实现——JSON 参数解析 → 按动作段分发到各 handler（`handleWallet`/`handleInventory`/`handleSpiritField`/`handleDisciple`/`handleBattle`/`handleGovernment`/`handleExploration`），统一 `{"status":"success"/"failure","code":...}` 信封；未注册动作 → NOT_IMPLEMENTED；异常 → INTERNAL |
| GTest | `execute_dispatch_test`（13：钱包增扣/库存增删/灵田收获/弟子寿元/突破预估/战斗伤害/斩杀/政策成本/灵矿产出/关卡过期/未知动作）——桌面 GTest 合计 268/268 |
| JUnit 对拍 | `DiffExecuteTest`（6：ActionIds 编号一致性 + 钱包/库存经 execute 与 Kotlin 语义一致 + 未知动作 NOT_IMPLEMENTED）——累计对拍 68/68 |
| 验证 | 桌面 GTest 268/268；JUnit 对拍累计 68/68；NDK 构建通过 |

> 批次 9 验收达成：C++ 侧 execute 统一入口完成——Kotlin 转发层（GameEngine 族方法体改转发 + StateSyncService 镜像 +
> feature flag 切换）已具备全部底层动作。剩余（登记随后续批次）：Kotlin GameEngine 275 方法逐一转发（方法签名保留、
> 方法体改 JNI nativeExecute 调用）、StateSyncService 全量快照→增量变更集同步、tick 桥接、feature flag 与性能基准——
> 为超大工作量批次，需与 UI 回归测试协同推进。

### 批次 9 剩余（转发层基础设施已完成）：feature flag + StateSyncService + tick 桥 + 性能基准

| 项 | 说明 |
|---|---|
| feature flag | `nativebridge/NativeEngineFlag.kt`：`enabled`（生产默认 false）+ `withNativeEngine` 测试临时开关；双实现并行任何时刻可回退 |
| StateSyncService 接入 | `nativebridge/StateSyncService.kt`：`syncFromNative`（C++ 导出→解码→镜像）、`applySnapshot`（单事务原子 + **字段级宽松合并** `mergeGameData`——C++ 只导出已迁移字段，白名单外字段保留 Kotlin 值，镜像永不丢未迁移字段）、`buildNativeState`/`importToNative`（读档 Kotlin→C++ 基线）；GameEngineCore 经 `stateSyncServiceRef` 暴露，GameEngine 经 `stateSyncService` 访问 |
| tick 桥（shadow 对拍模式） | `GameEngineCore.tickInternal`：flag 开启且 C++ 引擎可用时 `nativeAdvance` 推进 C++ 影子状态（**不镜像覆盖**——Kotlin 仍为真相源，未迁移系统如 SecretRealm 由 Kotlin 驱动，双推进会冲突）；`loadSnapshot` 后 `importToNative` 对齐影子基线 |
| 转发辅助 | `nativebridge/GameEngineNativeOps.kt`：`tryExecuteNative`（flag 开启 + native 可用 → `nativeExecute` + 结果镜像；否则返回 null 走 Kotlin 原实现）+ `params`/`field`/`str`/`long` JSON 工具 |
| JUnit 对拍 | `DiffStateSyncTest`（7：全字段收集/单事务/往返/宽松合并/字段级 merge/导出键合并/import 降级）+ `DiffNativeForwardTest`（4：flag 关闭降级/native 不可用降级/flag 恢复/merge 安全）+ `NativeBenchmarkTest`（2：native vs Kotlin 吞吐 + 规模正确性）——nativebridge 累计 109/109 |
| 性能基准 | `NativeBenchmarkTest`：wallet add 1000 次 native（含 JNI+JSON 开销）11.8ms vs Kotlin 28µs——高频纯计算场景确认 JNI 传输开销显著，**低频方法调用（业务操作）才适合转发**；此结论登记为批次 10 转发范围裁剪依据 |

> 批次 9 剩余基础设施达成：feature flag / StateSyncService（字段级宽松合并防丢字段）/ tick 桥（shadow 对拍）/
> 转发辅助层 / 性能基准全部落地并有 JUnit 守护。
> 剩余（登记随后续批次）：GameEngine 275 方法逐一转发（**依赖 C++ 动作全覆盖**——目前 C++ 仅 46 动作覆盖已迁移
> 系统，未迁移系统如 SecretRealm/外交/邮件等无对应动作，转发无从谈起；且 NativeBenchmarkTest 显示 JNI+JSON 开销
> 显著，**高频纯计算应留在 Kotlin 侧**，转发范围应裁剪为低频业务操作）；全量快照→增量变更集同步（nativeExportDirty
> 当前为空实现，需 C++ 侧变更集追踪）；**全量切换（C++ 为真相源）登记为批次 10 前置**——shadow 对拍期 Kotlin 引擎
> 仍是运行时真相源。 

## 6. 后续批次（详见实施计划）

- 批次 1：状态模型（GameData/实体/组件表）+ JSON 快照导出/导入
- 批次 2：静态数据/注册表
- 批次 3：时间系统 + 惰性结算引擎
- 批次 4-8：经济/生产/弟子/战斗/内政/探索 子系统
- 批次 9：转发层 + 状态同步 + feature flag 切换
- 批次 10：Kotlin 引擎退役
- 批次 R（**待完成，深耦合收敛**）：石板道路渲染深耦合

> **批次 R 背景（深耦合登记，2026-08-24 道路系统落地后）**
> 石板道路的**视觉合成逻辑**（道路主体 base/junction + 外缘描边 edge + 外角 corner + 十字中心装饰 cross_center 的逐格组合、邻接位掩码推导）目前**双端重复实现**：
> - Kotlin Canvas：`SoftwareCanvasBackend.drawRoadsToCanvas`（按 `SpriteAtlasDef.ROAD_RECTS` 取源矩形逐格绘制）
> - C++ Vulkan：`NativeBridge.cpp drawAllTiles` 道路段（按传入 `roadUVMap` 用 `SpriteBatcher` 合成）
>
> 且二者都**深耦合**于生成式图集（`build-atlas.mjs` → `SpriteAtlasDef.kt`/`TextureAtlas.h` + 运行时 `SectAtlasAssembler`），并各自维护 `RoadTiling.tileTypeForBitmask`/C++ `roadTypeForMask` 的同语义双份实现。**待完成批次 R 目标**：把道路位掩码→形态、边框判定、逐格合成收敛为**单一权威**（下沉到 `gamecore/map/road_system.h` 已就位的求解器 + 统一的 C++ 渲染合成），Kotlin 侧仅做数据装配，消除双端重复与图集强耦合（对照"单数据源"原则）。完成前本系统暂以"双端并行 + 生成图集 + 运行时拼装"运行。

### 批次 R（求解器权威性已达成）：石板道路渲染深耦合

| 项 | 说明 |
|---|---|
| C++ 求解器（已就位） | `map/road_system.h`：`tileTypeForBitmask`（16 种掩码 → 12 形态全覆盖）、`roadBorderMask`（外缘描边 = 掩码补集）、`RoadGrid`（放置/删除 O(1) 邻域重算、读档全量 rebuild、`canPlaceRoad`/`canPlaceBuilding` 可建造判定）——与 Kotlin `RoadTiling` 逐位同语义 |
| 对拍测试 | `DiffRoadTest`（3：全 16 掩码形态/全 16 描边掩码/任意坐标四邻位掩码——与 Kotlin RoadTiling 真实实现逐位一致）；C++ `road_system_test`（12，既有） |
| 验证 | JUnit 对拍累计 74/74；NDK 构建通过 |

> 批次 R 验收达成：道路**求解器权威性**双端对拍守护完成（掩码→形态/描边/邻接计算逐位一致）。
> 剩余（登记随后续批次）：**渲染合成收敛**——Kotlin Canvas `SoftwareCanvasBackend.drawRoadsToCanvas` 与 C++ Vulkan
> `NativeBridge.drawAllTiles` 道路段的逐格合成逻辑统一为单一 C++ 合成器（下沉 road_system.h），Kotlin 侧仅做数据装配；
> 同时解除与生成式图集的强耦合。此为渲染层大工程，需与 Vulkan/Canvas 双路径回归协同推进。

### 批次 R（求解器权威收敛已完成）：Vulkan 端位掩码判定收敛 road_system.h

| 项 | 说明 |
|---|---|
| 收敛 | `NativeBridge.cpp` 删除本地双份 `roadTypeForMask`（位掩码→形态全量 if-else）与 `border = 0xF ^ (mask & 0xF)`，改经 `gamecore/map/road_system.h` 的 `tileTypeForBitmask`/`roadBorderMask` 单一权威（C++20 inline 纯函数，零 Android 依赖）；`native-renderer` CMake 增加 gamecore include 路径 |
| 语义核对 | 权威 RoadTileType 枚举序（SINGLE=0..CROSS=11）与 Vulkan ROAD_RECTS 素材索引对齐：HORIZONTAL=1→base、VERTICAL=2→base_v、CROSS=11→cross_center 均一致；T 型（7-10）在渲染中统一用 junction 素材，T_DOWN/T_RIGHT 序号差异无渲染影响 |
| 验证 | 桌面 GTest `Road*` 16/16（road_system_test 不变）；JUnit `DiffRoadTest` 3/3（求解器双端对拍守护不受影响）；NDK `externalNativeBuildRelease` 通过 |
| 剩余（登记） | **渲染合成器物理下沉**：Kotlin Canvas `drawRoadsToCanvas` 与 Vulkan 道路段的逐格合成（主体/描边条/转角件/十字中心的摆放顺序）仍双端各自实现；统一为单一 C++ 合成器需跨模块 JNI 通道 + Vulkan/Canvas 双路径回归，为渲染层大工程登记随后续批次 | 

