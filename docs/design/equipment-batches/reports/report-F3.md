# report-F3 — 装备四部位化 F3 批：锻造配方链全量迁移 + 外围收口 + 精灵图 72→24

> 日期：2026-10-02 ｜ 分支：`feat/equipment-four-slot-f1`（F1/F2 之上续批）
> 方案：`docs/design/equipment-four-slot-refactor-plan.md`（v1.0，批次表 §八）
> 性质：对未上线装备系统的收缩型重构；测试期 destructive rebuild，**无迁移、无补偿**。
> **数值表一字未动**（F-12/FA4：材料族表逐条对偶 Kotlin 现行字面量，时长/成功率派生口径与 Kotlin 同式，零隐藏补偿）。

## 一、交付范围（实测后落地面，235 文件；+3559/−4102 含 144 张退役 webp 删除）

### 1.1 实测修正（执行前重测 §二）

方案预估 F3 =「锻造/掉落/商店清理 + AI 配装 + UI + 36→24 精灵图」。实测（F2 交付后）：

- **掉落/商店/秘境/年结算/AI 配装/排序筛选/背包去重的逻辑面在 F2 已全部 4 化**——掉落与商店产出走 `EquipmentFactory` 唯一入口（部件表驱动）、AI 配装槽序已 `{"HEAD","BODY","HANDS","FEET"}`、规则文件走 `displayOrder` 单一真源；残留仅注释级。
- **精灵图实际是 72→24 而非方案预估的 36→24**：旧体系是 72 件独立装备图（`SpriteCategory.EQUIPMENT` 中文名键），非 36 张套装图。按实测口径执行。
- **真正的 F3 主战场 = 锻造配方链**：C++ 侧整条旧 72 条配方体系（`ForgeRecipeTemplate` 旧形状 type/tier/rarity/materials/duration/successRate + `buildForgeRecipes()` 兜底 + 中性源快照 + `game-data.json` 注入段 + `production.h` 四段消费）与 Kotlin 真源（B3/F1 已收敛的 24 条套装部件配方）**结构性脱节**——B3 只迁移了完成期产出（`forge_{pieceId}` 反查），启动/自动/晋升面仍挂旧模板。

### 1.2 逐项落地面

| # | 项 | 落点 | 终态 |
|---|---|---|---|
| 1 | C++ 配方结构 | `recipe_db.h` `ForgeRecipeTemplate` | 新形状 `id/pieceId/setId/part/name/description/tierMaterials[6]`（对偶 Kotlin）；派生 `forgeMaterialsFor(tier)`（`getOrElse(tier-1)` 越界落末档，与 Kotlin 同式）+ `detail::forgeDurationByTier`（`TIER_DURATION[tier] ?: 2` 越界不 coerce 同式）；`operator==` 默认派生全字段比对保持 |
| 2 | C++ 兜底表 | `recipe_db.h` `detail::buildForgeRecipes()` | 旧 72 条**整段删除**（不留死代码，FA1）；24 条 = 6 套 × 4 部位（套内序 HEAD/BODY/HANDS/FEET 与 Kotlin 声明序一致）；name/description 逐字复刻部件模板派生结果；材料表按部位族 6 档（Kotlin「材料表逐部位同构复用」同构展开） |
| 3 | C++ JSON 编解码 | `data_json.h` | `tierMaterials` object 形状（`"tier1".."tier6"` 键）↔ C++ vector（下标 tier-1）双向转换，与中性源快照/`RecipeRegistryGuardTest` 同一形状 |
| 4 | C++ 自动锻造 | `production.h` `processAutoForgeStep` | 按 Kotlin `processAutoForgeSlot` 现行重写：**删 rarity 降序 kSorted 缓存**（新配方无排序键）；候选恒全量（表序 firstOrNull）；续炼=同 id 且材料足；材料/时长恒凡品档（`materialsFor(1)` / `forgeDurationByTier(1)`）；成功率档 = 弟子 forgeLevel（`maxCraftableTier`）；`outputItemRarity=0` |
| 5 | C++ 手动启动 | `production.h` `startProductionTransaction` 锻造臂 | 凡品档启动（时长 3 旬、材料第一档、`recipeRarity=0`）；**2.5 成功率段条件化**：锻造 = 槽位弟子 forgeLevel 钳 [1,6]（Kotlin `buildForgingSuccessRate` 同式）、炼丹保持配方 tier（`buildAlchemySuccessRate` 口径零扰动） |
| 6 | C++ 完成期晋升 | `production.h` `completeSlot` | 锻造 `recipeTier = 配方存在 ? 1 : 0`（Kotlin `settleForgeCompletionShadow` `?.let { 1 } ?: 0` 对偶；旧代码取 `recipe->tier`） |
| 7 | 数据链重生成 | `scripts/gen-recipe-db.mjs`（锻段重写 24 条新形状）→ `scripts/data/recipe_db_sample.json`（中性源）→ `gen-recipe-db.mjs`（engine 测试快照）→ `gen-game-data.mjs`（`game-data.json` + hash sidecar，sha `648cbc1c…`） | 四层数据链（中性源→快照→运行时注入→C++ 兜底）逐位一致（`DataStoreGuardTest` 双用例自愈转绿） |
| 8 | C++ 测试 | `recipe_db_test.cpp` 锻造段重写（24 条/四部位覆盖/退役 id 失配/派生时长）；`data_store_test.cpp` 72→24；**6 个测试文件 32 处夹具 `part="WEAPON"`→`"HANDS"`**（equipment_tx/execute_dispatch/json_codec/inventory/month_settlement/inventory_tx）+ `equip_set_bonus_test`/`mission_completion_test` 两处漏网；`production_test.cpp` 两用例材料夹具换新配方族（凡熊皮/凡熊骨） | 断言语义不变（part 为透明字符串） |
| 9 | 精灵图 72→24 | `resource-registry.json`（EQUIPMENT 72→24，键=部件中文名、res=全名拼音）；**删 144 张旧 webp**（72×双模块）；24 张新 webp（2 张真源烘焙：裂天罡煞头冠/重铠 ← `装备/裂天罡煞/` 源图经 `MANUAL_OVERRIDES` 登记；22 张程序化占位 = sharp SVG→无损 WebP，五套装主题色+套装首字）| `sources-imported.json` 导入账本 / `sprite-uid-map.json` UID 稳定表随批入库；图集（atlas）不含装备图，零图集重建 |
| 10 | 守卫收口 | `EquipmentSlotRetirementGuardTest` | **按 F2 交接移除 `data/recipe_db.h` 排除项**（F3 已收口，主源扫描覆盖 recipe_db.h）；`SpriteCodegenSyncTest` 装备锚点「精铁剑→jing_tie_jian」改「裂天罡煞·头冠→lie_tian_gang_sha_tou_guan」 |
| 11 | 注释收口 | C++ 9 文件（auto_gear/ai_sect_ops/ai_sect_recruit/models/disciple_tx/equipment_factory/mission_completion/equipment_entries/recipe_db 头注释）「六部位/B3 六部位版」→ 四部位表述；Kotlin 10 处（ForgeRecipeDatabase「36 条」/ForgeRecipeRegistry「12 条」/EquipmentFactory/HeavenlyTrialBuildOps/FindOps5/BuildingServiceLoadPathTest/RecipeRegistryGuardTest 文案/DetailEquipmentSection「六部位宫格」/ForgeRecipeDatabaseTest 守卫消息） | 「六部位」主源归零；`12/36/72 条` 过时计数归零 |

### 1.3 有意保留（非残留）

| 项 | 理由 |
|---|---|
| `Items.kt` `LegacyEquipmentPrices`（旧 72 模板名→价格） | 方案级退役补偿快照（B3 立项「模板已删，折算表由静态快照提供」），语义同 reserved，非装备部位字段 |
| `JsonConverters.kt` 旧枚举名回退 HEAD | 读旧档兼容臂（退役枚举名不在四部位枚举内回退），功能代码 |
| `StorageBagMaterializerTest` 等 equipment_stack 兼容测试用「精铁剑」 | B3 装备堆叠轨退役后的旧档物化兼容路径测试，名称为任意字符串夹具 |
| `battle_json.h`/`mission_completion.h` `weaponName` 注释 | 描述 Kotlin 角色 weaponName 展示字段「不入 C++ 战斗状态」，非装备部位 |
| game-data.json「诛仙剑」命中 | 功法「诛仙剑诀」，无关装备 |

## 二、门禁与实跑数字

| 门禁 | 结果 |
|---|---|
| `ctest`（gamecore 全量 1538，含 bench 串行） | **1538/1538 全绿**（59.07s；改造途中 4 红=2 DataStoreGuard 待数据链重生成自愈 + 2 自动锻造夹具换配方族，均闭环） |
| Kotlin 全量 `testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=…` | 全模块绿（`:core:engine` 3023 tests / 0 fail / 260 skip 单模块复跑绿；`DiffBridgeGateTest` IN8 门首跑拦对——桥重编后带参复跑通过，与 F2 同教训）；`EquipmentSlotRetirementGuardTest` 排除项移除后绿（recipe_db.h 纳入扫描面） |
| 桌面 JNI 桥 | `build-desktop-jni.ps1` 重编（recipe_db/production 头面变更后）；`Diff*` 家族带桥实跑 |
| `lintRelease` + `detekt` | BUILD SUCCESSFUL |
| codegen 零漂移 | `gen-templates.mjs` 重跑主源零漂移；`gen-game-data.mjs --check` 通过（sha256 `648cbc1c…`） |
| 精灵守卫 | `SpriteCodegenSyncTest`（新锚点）/`ResourceManifestCompletenessTest`/`ResourceManifestUidTest` 全绿；scaffold 后 EQUIPMENT 恰 24 条=2 真源+22 待补 |
| `check-jni-count.mjs` | 87/87 双桥无扩散 |
| `check-agent-instructions.mjs` | 全绿（489 引用、7 AGENTS.md、32574/32768 字节） |
| 版本号 | 未动（4.01.16）；Room 零 schema 变更（配方为资产数据非 Room 实体） |
| 工作区噪音 | `atlas-rgba-manifest.json`/`scene_uv_tables.h`/`bg_recruit_normal.webp`（feature+app）构建重生成/美术源侧重烘焙已还原不入批 |

## 三、旧用例处置表

| 处置 | 文件 | 说明 |
|---|---|---|
| 重写 | `recipe_db_test.cpp` 锻造段 | `ForgeRecipeCount`（24=6 套×4 部位/非法部位零容忍/id 规则/6 档材料表）、`ForgeRecipeSample`（lietian_HEAD 全字段+tier6 档+houtu_FEET+派生时长+旧 id 失配）；丹药段一字未动 |
| 数据锚点更新 | `data_store_test.cpp`（72→24） | 兜底==注入面随数据链重生成自愈 |
| 机械替换（部位值无关断言） | equipment_tx/execute_dispatch/json_codec/inventory/month_settlement/inventory_tx（32 处 `"WEAPON"`→`"HANDS"`、「战刃」→「战手」）+ `equip_set_bonus_test`（套装夹具）+ `mission_completion_test`（LCG 洗牌表→四部位） | |
| 材料夹具换配方族 | `production_test.cpp` `AutoRestartForgeConsumesMaterials`/`ForgeTwoSlotContentionSecondSkips` | 凡虎血/凡虎牙→凡熊皮/凡熊骨；断言改 `forge_lietian_HEAD` + `outputItemRarity=0` |
| 守卫锚点更新 | `SpriteCodegenSyncTest`（装备映射锚点）、`RecipeRegistryGuardTest`（stale 提示文案 12→24 条） | 快照重生成后 stale=false 自动激活逐字段比对（B3 预设机制首秀） |
| 排除项移除 | `EquipmentSlotRetirementGuardTest` | `data/recipe_db.h` 纳入主源扫描（F2 交接义务） |
| 保留验证 | `LegacyForgeRecipeIdMissFails`/`ForgeMatchedByIdAlchemyMatchedByType`（`ironSword` 夹具） | 前者语义即「旧 id 失配→产出失败」；后者 recipeId 内容无关断言面——均零改动通过 |

## 四、途中发现（预存问题处置）

1. **C++ 锻造启动面与 Kotlin 现行行为分叉（本批修正）**：B3 迁移只改了完成期产出，`processAutoForgeStep`/`startProductionTransaction` 仍按旧配方 tier/rarity 选配方取档（rarity 降序、tier 门槛过滤、`kTierDuration[chosen->tier]` 档时长）——与 Kotlin 现行「恒全量、凡品档启动、成功率按弟子档」不一致。本批以 Kotlin 现行为对偶基准重写（新旧候选面差异：旧族 rarity 降序首取 vs 新族表序首取，均确定性、无 RNG 消费差异）。
2. **装备精灵图 72 张的源图已被美术侧清走**（`装备/` 目录仅存 `裂天罡煞/` 2 张）——旧注册表若不删会在下次 scaffold/import 以 source=null 大面积待补。本批同步删注册面+webp+导入账本，三方一致。
3. **`ForgeRecipeDatabaseTest.getCraftableRecipes_alwaysReturnsAll` 守卫消息「36 条」自 B3 起过时**（F1 变 24 后未跟）——断言动态计数无实害，消息已校准。
4. **F2 报告交接项逐一闭环**：`recipe_db.h` 排除项移除 ✅、`processAutoForgeStep` 旧族选配方 ✅、24 精灵图 ✅、掉落/商店/`CaptiveGearUtils`/`RedeemCode` 深层面复查零残留 ✅。

## 五、遗留与交接（F4 写入面）

| 项 | 交接 |
|---|---|
| 数值校准 | 装备占总战力实测（预期约 27%）——`EquipmentPowerParityTest` 实跑出报告（F-5） |
| 22 张待补源图 | `source-mapping.json` 已置 source=null 待补（程序化占位兜底显示，F-10 合规）；美术补图后 `MANUAL_OVERRIDES`/分类自动推导登记 → `import-art-assets.mjs` 烘焙即替换占位 |
| 文档/ADR/双 changelog | `docs/architecture.md`/`knowledge-base.md`/`cpp-engine.md` 装备节 + `CHANGELOG.md` + `changelog_entries.json`（F4 收口） |
| 台账 | `DISPATCH-LEDGER.md` §9 留言区已随批登记 F3 收官（FB7；F4 终登记） |

## 六、DoD 对照（本批相关项）

| DoD | 状态 |
|---|---|
| 2. 武器/腿部全链零残留 | ✅（退役守卫排除面收缩至测试树；主源「六部位」归零） |
| 4. 配方产出部位 ∈ 4 部位 | ✅（`ForgeRecipeSlotGuardTest` + `recipe_db_test` 非法部位零容忍 + 24 条/144 展开条目断言） |
| 8. 双端确定性 | ✅（ctest 1538/1538 + Diff 家族带桥全绿 + DataStoreGuard 四层数据链逐位一致） |
| 9. 门禁全绿 | ✅（lint/detekt/codegen 零漂移/jni-count/agent-instructions） |
| 10. 24 张精灵图或占位兜底 | ✅（2 真源 + 22 程序化占位；注册面/映射/清单三方一致） |
| 5/11（占比实测/双 changelog） | F4 |
