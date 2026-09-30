# 装备系统重构实施方案：六部位 · 套装 · 等级 1–30 · 主词条+三副词条 · 属性单列化

> 版本：v2.0（2026-09-28 重建；原 v1.x 为**未跟踪文件**，被并行实施流的 `git clean` 清除，无 git 历史可恢复 —— 本版按全部已拍板决策重写）
> 范围：全仓（`:core:domain` / `:core:data` / `:core:engine` / `:core:ui` / `:feature:game` / `:app` + C++ game-core + JNI 对拍 + 静态数据 codegen + 存档迁移 + 经济基线 + 双更新日志）
> 依据规范：`rules/design-plan-review.md`（原则 1–6 + 第一~八节自检清单）、`rules/database-migration.md`、`rules/cpp-priority.md`、`rules/economy-design.md`、`rules/industry-benchmark.md`、`rules/static-resources.md`、`rules/code-comment.md`、`android/app/src/main/cpp/gamecore/AGENTS.md`、`docs/AGENTS.md`
> **分批实施文档**：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（批次编排 B0–B5 与作业规程；本文件保留**方案侧**视图，两者共同构成实施依据）

---

## 〇、决策分级声明（`rules/design-plan-review.md` 第六节）

| 级别 | 判定 | 依据 |
|---|---|---|
| **架构级重构** | 按设计方案规则全流程（完整方案文档 + 对抗性审查） | ① **同模式问题 ≥3 处**：装备维度在 Kotlin 领域模型 / Kotlin 引擎 / C++ 引擎（`disciple_tx.h`、`auto_gear.h`、`disciple_stats.h`、`ai_sect_ops.h`）/ 存档（Room + ProtoBuf + 云存档）/ 静态数据 codegen / UI 六层同构复现；② 触及未来 6 个月规划（套装是后续全部装备内容的载体）；③ 跨平台路径（C++ game-core 是 iOS 复用的唯一引擎实现） |

**最小切入路径（备选，本方案不推荐）**：只把 `EquipmentSlot` 从 4 值改成 6 值、给 `EquipmentStack` 加 `setId`、把 `EquipmentNurtureSystem` 改名为 `EquipmentLevelSystem`，保留堆叠与双轨存储。

- 不推荐理由：① 需求明确要求"删除现有所有装备 / 移除堆叠 / 主词条+三副词条"，保留堆叠等于让"同名词条不同的两件装备"共享一个合并键（`StackKeys.equipment` = 名称+品阶+槽位），**词条必然被覆盖或丢失**——这是数据正确性问题，不是体验问题；② 孕养数据当前**同时**存在于实例（`EquipmentInstance.nurtureLevel`）与弟子槽位（`EquipmentSet.weaponNurture`）两处，只改名不合并双源，"装卸不改等级"无法达成（换装走仓库堆叠分支时新实例 `nurtureLevel` 恒为 0）；③ 留下 `equipment_stacks` 表 + Proto 字段 + 双轨查找 + 溢出邮件路径共 4 类死资产，违反编码规范 0.2/0.3 与用户公约 9/15。
- 结论：**采纳整维重构**，本文件即完整方案文档。

### 0.1 需求解读声明（**已拍板：读法①**）

"**要求包含两件装备套装，物理套+法术套**"存在两种合理解读：

| 读法 | 含义 | 结论 |
|---|---|---|
| **① 首批上线两套套装** | 物理套 1 套 + 法术套 1 套，每套 6 部位 | **✅ 已拍板采纳**：共 12 个套装部件定义（2 套 × 6 部位），每部件按品阶 1–6 展开 = 72 条可生成条目 |
| ② 只做"2 件套"档效果 | 套装效果只保留 2 件档 | 被①覆盖：方案同时实现 **2 / 4 / 6 件套全三档**，2 件档天然齐备 |

读法①生效后，两套的 6 件套效果（`physicalDamageBonus` / `magicDamageBonus`）**均有生产消费者**，通过 §10.1 的 YAGNI 检查。

### 0.2 需求盲区清单（14 项 + 补充 1 项；**甲组 6 项已拍板**）

> 全部是**需求文字未覆盖、但会直接决定这套系统上线后好不好玩/会不会出事**的点。标记 🔴 需实施前定 / 🟡 可先按默认上线 / 🟢 已登记债或风险。

#### 🔴 甲组：**已拍板（2026-09-28）**

**0.2-1 同名多件让"关注/锁定"键失效（实测缺陷）**
- *盲点*：关注键现在是 `ItemSortUtils.watchKey("equipment", 名称)`，存进 `GameData.watchedItemIds`。新体系下同套同部位同品阶会有**多件（词条不同）而名称完全相同** ⇒ 关注一件连带关注全部同名件；批量分解按名称筛选会误伤。
- *处置*：关注键改**实例 id**；旧档名称键一次性清空。落点见 §四 WP7。

**0.2-2 仓库容量模型会崩塌（删堆叠的隐藏代价）**
- *盲点*：容量现按"堆叠条数"算（`InventoryConfig.typeSpecificStackLimits["equipment_stack"]=999`，1 条堆叠放 999 件）；`InventorySystemHasOps6` 槽位计数、`SectWarehouseManager.itemId="equipment_名称_品阶"`、溢出转邮件判据全建立在此语义上 ⇒ **删堆叠后 1 件 = 1 槽，等效容量降到 1/999**。
- *✅ 拍板：不设硬上限，只告警*。
- *必须配套*：① `EntityCountBoundsRule` 对装备改**只告警不截断**（warn 800 / 页面提示 1200，均不阻断）；② `SectWarehouseManager.itemId` 改实例 id；③ 仓库 UI 必须提供**排序+筛选+批量分解**（否则"无上限"= 无法管理）；④ 装备产出**不再走溢出邮件** ⇒ 删 `OverflowMailSender` 装备分支（保留其它物品与"邮件附件发放装备"路径）。
- *风险接受*：实例数无界 ⇒ 存档体积无界增长，登记 **I10**（P95 超 1500 件或云存档体积超阈值 ⇒ 引入软上限）。

**0.2-3 装备在总战力中的目标占比**
- *✅ 拍板：装备（六部位，**含 2/4/6 件套**）占弟子总战力 **40% ± 5%***。⇒ §3.7 基数表以"装备块贡献 = 40%±5%"反推；§6.1 断言改为**装备占比 ∈ [35%,45%]**。

**0.2-4 双重随机下的期望获取成本**
- *盲点*：一件 = 部位 × 品阶 × 主词条（1/2~1/5）× 3 副词条（7 选 3 不放回 + 档位）；一整套 = 6 件同套。
- *✅ 拍板：不做保底，纯随机*。
- *缓解（必须一起上线）*：分解回收 50%；每 3 级强化一条副词条；可低成本切 §13-12 的"按流派过滤池"；`§6` 仍产出**期望获取成本量化报告**（I9 触发基线）。
- *债*：**I9 无保底/无定向**，触发条件 = 上线 4 周内玩家反馈"理想件过难"或分解/持有比异常 ⇒ 引入"锻造定向指定部位"。

**0.2-5 品阶与境界门槛的断崖**
- *✅ 拍板：沿用现有境界门槛*。口径：穿戴走 `GameConfig.Realm.getMinRealmForRarity`；**产出品阶不得高于玩家当前境界可穿上限**（单点实现在 `EquipmentFactory.create`）；品阶权重按境界分层 ⇒ T1–T5 内容不被跳过。

**0.2-6 升级材料产出 vs 消耗**
- *盲点*：T6 单件 1→30 需 156.6 万灵石 + 39 兽材，六件 ≈ 940 万灵石 + 234 兽材。
- *✅ 拍板：一套满级 ≈ 玩家 **1 个月左右**正常产出*（比值容差 [0.75,1.25]）。因"不做保底"，该比值**不宜偏低**（低于 0.75 会一周毕业）。

**0.2-15 "40% 是否含套装效果"** → *✅ 拍板：含在内*（套装与词条共用同一预算，不重复计）。

#### 🟡 乙组：已给默认口径，可直接实施

- **0.2-7 乘区叠加规则**：同类百分比在装备块内**相加**（`Σpct` 后一次乘）+ 总攻击加成设上限（如 ≤100%）。
- **0.2-8 满级 30 后无长线出口**：等级与强化次数双封顶 ⇒ 第二次套装上线时同步引入洗练/重铸（I3）。
- **0.2-9 AI 宗门/妖兽装备口径**：AI 按品阶随机部件、**不生成副词条**、等级按槽位存；跑宗门战难度回归。
- **0.2-10 12 张部件图的美术依赖**：程序化占位（品阶色边框 + 部位字形）可零美术上线（I6）。
- **0.2-11 新手引导 4 个新概念**：首期做最小引导（升级界面首次气泡 + 套装面板高亮），完整引导登记 I7。
- **0.2-12 战斗实体装备展示字段**：`Combatant` 现有 `weaponName/armorName/bootsName/accessoryName` 四个具名字段且进战斗 JSON 与 C++ 对拍面 ⇒ 六部位化必须三处同步。
- **0.2-13 镜像协议与云存档体积**：从"堆叠行数"变"每件一行 + 词条列表"（≈250 字节/件）；因 0.2-2 不设上限，体积无自然封顶 ⇒ 列入 I10 监控。

#### 🟢 丙组

- **0.2-14 死亡/逐出/俘虏路径**：`DiscipleDeathHandler`、`InventoryFacadeConfiscate`、`CaptiveGearUtils` 三处需从 4 件同步到 6 件。

---

## 一、需求确认与成功标准

### 1.1 需求转译（业务语言 → 工程语言）

| # | 用户原话 | 工程口径 |
|---|---|---|
| R1 | 改为头部、身体、**手部**、脚部、**武器**、**腿部**装备系统（**顺序照此**；**移除饰品位**） | `EquipmentSlot` 4 值 → 6 值，**声明序 = UI 显示序**：`HEAD / BODY / HANDS / FEET / WEAPON / LEGS`；`WEAPON` 第 5 位（由原"腿部"转换）、`LEGS` 第 6 位（原饰品位置）；`ACCESSORY` 从枚举移除 |
| R2 | 删除现有所有装备 | `EquipmentDatabase` 72 条 + `EquipmentRegistry` 第二份 72 条 + 73 条锻造配方 + 71 张装备美术全部作废；旧档装备实例/堆叠/槽位引用/储物袋装备条目按 §5.4 折算补偿后清空 |
| R3 | 装备改为套装形式上线，要有套装效果（2/4/6 件套） | 套装注册表 + 套装效果结算（2/4/6 三档），每档 1 条效果 |
| R4 | 孕养系统改为升级系统（初始 1 级最高 30 级） | 删 `EquipmentNurtureSystem` 与 `EquipmentNurtureData`；等级 1–30 + 主词条随等级成长 + 每 3 级强化一条副词条 |
| R5 | 装备的装卸不改变等级 | 等级/词条/强化次数**只存实例单点**；卸下→入袋→穿上全程保真；删槽位级孕养双源 |
| R6 | 移除装备堆叠功能 | 删 `EquipmentStack` 实体 + Proto 字段 + 双轨查找 + 堆叠键 + 分块/溢出邮件路径；仓库改实例网格（数量恒 1） |
| R7 | 装备加成改为主词条 + 三副词条 | 主词条**按部位池随机**（§3.4.2）；3 副词条从 **7 项池**按权重不放回抽取（去重），每 3 级强化一条 |
| R8 | 要求包含两件装备套装，物理套 + 法术套 | 首期 2 套（§3.7）：物理套「裂天罡煞」/ 法术套「紫府玄冥」，各 6 部位 |
| R9 | 主词条：头部随机（血量/防御力）；身体随机（防御力/攻击力/暴击率/暴击伤害）；手部随机（攻击力/暴击率/暴击伤害）；脚部随机（防御力/攻击力/暴击率/暴击伤害/血量）；**武器随机（攻击力/暴击率/暴击伤害）**；腿部随机（防御力/攻击力/暴击率/暴击伤害/血量）；腿部随机（防御力/攻击力/暴击率/暴击伤害/血量）；~~饰品~~（**该位移除**） | 部位 → 主词条候选池（§3.4.2）；`武器` = 输出向池（攻击力/暴击率/暴击伤害）、`腿部` = 原腿部池；配合 §15 单列重构，`攻击力`/`防御力` **即统一单列属性本身**，无需按流派固化 |
| R10 | 副词条池（经单列重构后定稿为 7 项） | **攻击力、防御力、血量、暴击率、暴击伤害、物理伤害加成、法术伤害加成**；权重 **13/13/14/15/15/15/15（合计 100 = 直接概率）**；全局池、与流派无关；3 条按权重**不放回**抽取 |
| R11 | 删除孕养类加成丹药 | 删 `nurtureAdd` / `nurtureSpeedPercent` 两类丹药**全部定义与效果链**（配方、`ItemEffect` 字段、`PillEffects` 字段、列式存储列、C++ 配方与分类分支）；已持有按 §5.7 折算补偿 |
| **R12** | **属性机制重构：采用单列** | 统一 `attack`/`defense` 两列，替换物攻/法攻 + 物防/法防四列；伤害类型由**普攻（角色模板固定的固有属性）+ 技能（自带 `damageType`）**决定；类型差异走**类型伤害加成**与**类型减伤分桶**（§15） |

### 1.2 成功标准（可验证）

| 编号 | 标准 | 验证方式 |
|---|---|---|
| S1 | 全仓不再存在"孕养/堆叠"语义的装备代码 | `grep -i "nurture" / "EquipmentStack"` 归零（功法熟练度 `ManualProficiency` 显式白名单） |
| S2 | 六个部位可穿可卸，装/卸往返后等级与词条逐位不变 | `EquipmentLevelPersistGuardTest` + `equipment_tx_test.cpp` + 对拍 |
| S3 | 套装效果 2/4/6 三档按穿戴件数正确生效，未达档不生效 | `EquipmentSetBonusTest` + `equip_set_bonus_test.cpp` + 对拍 |
| S4 | 等级上限恰为 30，1 级为初始态，30 级后不再获得经验 | 边界用例（0/1/29/30/31/负值/极大 EXP） |
| S5 | 每件恒有 1 主词条 + 3 副词条；主词条**落在该部位池内**，副词条互不重复且**落在 7 项池内** | `EquipMainStatPoolTest` + `EquipAffixPoolTest`（1 万次抽样断言去重与权重分布） |
| S6 | 仓库/储物袋/邮件/结算中**不存在**装备数量>1 的条目 | `EquipmentStackRemovalGuardTest`（符号面 + 序列化面） |
| S7 | 旧档升级后：装备区清空、补偿到账、其余数据零丢失 | 本批迁移测试 + `RoomMigrationTest` 全链 |
| S8 | 装备相关存档 Proto 编号冻结：`DiscipleSurrogate` 装备段 / `EquipmentInstance` / `StorageBagItem` / `SaveData` 的属性→编号映射与冻结表逐条一致 | `EquipmentProtoNumberFrozenTest` |
| S9 | 数值不回退且占比达标：装备（含套装）贡献 ∈ 总战力 **[35%,45%]**，且**按维度拆分**（速度/灵力单列） | `EquipmentPowerParityTest` |
| S10 | 双端确定性：装备生成/升级/词条强化的 Kotlin 与 C++ 结果逐位一致 | GTest 黄金序列 + `DiffEquipmentGenerationTest` |
| S11 | 门禁全绿 | `compileReleaseKotlin`、`testReleaseUnitTest --max-workers=1`、`lintRelease detekt`、`check-agent-instructions.mjs`、codegen 幂等（G0）、C++ `ctest` + 跨语言对拍 |
| S12 | 无死字段/死路径残留 | detekt 无未用符号告警；`EquipmentRegistry`/`EquipmentNurtureData`/`weaponNurture*`/`equipment_stacks` 符号面归零 |
| S13 | 孕养类加成丹药全链归零 | `grep` 归零：`nurtureAdd`/`nurtureSpeed`/`pillNurtureSpeedBonus`（含 C++ `recipe_db.h`）；旧档两字段 `reserved` 且**不再被读取** |
| S14 | 装备不再提供"速度"与"灵力" | `EquipStat` 不含 `SPEED`/`MP`；弟子面板速度/灵力只由基础属性+功法+丹药贡献（数值回归锁定） |
| S15 | 部位池与显示序口径唯一 | `EquipMainStatPool` 与 `EquipmentSlot.displayOrder` 为两处单一真源；任一处硬编码 `when` 即被 `EquipMainStatPoolTest`/`EquipmentSlotOrderGuardTest` 拦截 |
| S16 | 装备占弟子总战力 ∈ [35%,45%]（含套装）；一套六件满级消耗 ≈ 1 个月产出（比值 ∈ [0.75,1.25]） | `EquipmentPowerParityTest` + `EquipmentEconomyCalibrationTest` |
| S17 | 品阶受境界约束：无法获得/穿戴高于当前境界上限的品阶 | `EquipmentRarityGateTest` |
| S18 | 装备实例无硬上限但可管理：`EntityCountBoundsRule` 只告警不截断；仓库可排序/筛选/批量分解；装备不走溢出邮件 | `EquipmentNoCapGuardTest` |
| S19 | 属性单列化 + 类型通道：`DiscipleStats`/`Combatant` 只有 `attack`/`defense`；伤害类型由普攻/技能决定；类型桶默认 0.0 时**与旧公式逐位一致** | `DiffBattleTest` / `DiffBattleExecutionTest` + `SingleColumnStatGuardTest` |
| S20 | 旧双列→单列映射可审计：`attack=旧物攻+旧法攻`、`defense=旧物防+旧法防`，乘 `k` 后**总战力与 40% 占比目标不变** | `LegacyStatMigrationTest`（迁移前后总战力比 ∈ [0.98,1.02]） |
| S21 | 固有伤害属性由角色模板固定：同模板一致；旧档按 `templateId` **幂等**回填 | `InnateDamageTypeGuardTest` |

---

## 二、现状调查（逐行读实）

> 本节 `file:line` 均为实测。

### 2.1 数据模型现状

| 类型 | 位置 | 关键字段 |
|---|---|---|
| `EquipmentSlot`（枚举 4 值） | `android/core/domain/src/main/java/com/xianxia/sect/core/model/Items.kt:294-306` | `WEAPON/ARMOR/BOOTS/ACCESSORY`，`@ProtoNumber(0..3)` |
| `EquipmentStack`（Entity `equipment_stacks`） | `Items.kt:30-125` | `id`+`slotId` 复合主键；`slot`、7 项面板属性、`critChance`、`minRealm`、**`quantity`**、`isLocked` |
| `EquipmentInstance`（Entity `equipment_instances`） | `Items.kt:127-290` | 同上 + **`nurtureLevel`(13)/`nurtureProgress`(14)**、`ownerId`(16)、`isEquipped`(11)；`getFinalStats()` 按孕养乘区放大 |
| `EquipmentStats` | `Items.kt:308-342` | 7 项面板值 + `plus` + `toDiscipleStats()` |
| `EquipmentSet`（`@Embedded` 进 `disciples` 表） | `DiscipleComponents.kt:98-121` | `weaponId/armorId/bootsId/accessoryId` + 4 个 `*Nurture` + 储物袋三字段 |
| `EquipmentNurtureData` | `Disciple.kt:474-481` | `equipmentId`(1)/`rarity`(2)/`nurtureLevel`(3)/`nurtureProgress`(4) |
| `EquipmentDatabase.EquipmentTemplate` | `EquipmentDatabase.kt:12-27` | 硬编码 **72 条**（武器 24/护甲 24/靴 12/饰品 12） |

**结论**：装备加成当前只有"7 项面板值 + 暴击率"8 个维度，**不存在主词条/副词条、不存在套装、不存在百分比乘区**（`getNurtureMultiplier` 是唯一倍率且为装备私有）。

### 2.2 双轨存储（"移除堆叠"必须一起收的原因）

| 轨道 | 载体 | 数量语义 |
|---|---|---|
| 堆叠轨 | `EntityStore<EquipmentStack>`（`GameStateStore.kt:40`） | 同名同品阶同槽位可叠至 **999**（`InventoryConfig.kt:31`） |
| 实例轨 | `EntityStore<EquipmentInstance>`（`GameStateStore.kt:41`） | 单件 `maxStack = 1`（`InventoryConfig.kt:32`） |

穿装时两轨都会命中：`DiscipleEquipmentService.equipEquipmentInTransaction`（`DiscipleEquipmentService.kt:70-74`）先查堆叠再查实例；C++ 逐字对齐（`disciple_tx.h:413-421`，注释明写"堆叠优先"）。

**推演**：只要堆叠轨存在，"两件同名同品阶但词条不同"就会在 `StackKeys.equipment`（`StackKeys.kt:24-25`，键 = 名称+品阶+槽位）被合并 ⇒ 词条必然丢失。这是**数据正确性缺陷**，不是取舍。

### 2.3 穿/卸链路（C++ 真相先行 + Kotlin 回退）

```
DiscipleDetailScreen.kt:570            viewModel.disciple.equipItem(discipleId, equipmentId)
  → DiscipleDelegateGearOps.kt:20        gameEngine.launchOnEngine { gameEngine.equipItem(...) }
  → GameEngineManualOps.kt:62-73        tryDiscipleTxNative(ActionIds.DISCIPLE_TX_EQUIP=1480)
       ├ 命中 → C++ 权威写（execute_dispatch.cpp:1687 → disciple_tx.h:399 equipTransaction）
       └ 未命中 → DiscipleEquipmentService.equipEquipment（Kotlin 回退臂，逐字同语义）
DiscipleDetailScreen.kt:624            viewModel.disciple.unequipItem(discipleId, equipmentId)
  → GameEngineManualOps.kt:86-90        tryUnequipNative(DISCIPLE_TX_UNEQUIP=1481) → disciple_tx.h:500 unequipTransaction
```

卸下语义：实例**完整入弟子储物袋**（`DiscipleEquipmentService.kt:264-291`；C++ `disciple_tx.h:246-293`），从实例表移除，槽位清空。储物袋条目 `StorageBagItem`（`Disciple.kt:372-402`）用 `itemType="equipment_instance"` + `@ProtoNumber(13) equipmentInstance` 承载完整实例。

### 2.4 孕养链路（新等级系统必须替换的三条入口）

| 入口 | 位置 | 语义 |
|---|---|---|
| 每旬自动 | `EquipmentNurtureService.settleNurtureInPlace`（`service/EquipmentNurtureService.kt:22-40`） | 4 个槽位各 +10.0 经验（`EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE`，`EquipmentNurtureSystem.kt:13-14`） |
| 战斗胜利 | `EquipmentNurtureSystem.calculateExpGain`（`:49-53`） | 胜利获得"升级所需经验 × 10%" |
| 丹药 | `nurtureSpeedPercent` / `nurtureAdd`（`Disciple.kt:425,430`；C++ `recipe_db.h:399-432`） | 加速孕养 / 直接加孕养度 |

曲线与上限：`getMaxNurtureLevel(rarity)` = 5/9/13/17/21/25；`getNurtureMultiplier` 上限 4.0；C++ 复刻在 `nurture_constants.h:35-56` 与 `disciple_stats.h:124-146`。

**关键缺陷（D2）**：孕养数据**双源**——实例侧 `EquipmentInstance.nurtureLevel`（`Items.kt:177`）与槽位侧 `EquipmentSet.weaponNurture/...`（`DiscipleComponents.kt:107-113`）。而 `DiscipleEquipmentManager.applyEquipAction` 的仓库堆叠分支用 `stack.toInstance(...)`（`Items.kt:105-124`）产生 `nurtureLevel = 0` 的新实例 ⇒ **从仓库取装穿戴时等级被清零**。这正是 R5 要根治的对象。

C++ 侧另有第三份拷贝：AI 弟子按槽位存 `weaponNurture/armorNurture/...`（`models.h:124-127`、`ai_sect_ops.h:296-333`）。

### 2.5 属性结算链路（加成的唯一汇合点）

```
DiscipleStatCalculator.computeStatsWithEquipment（属性Ops3.kt:152-167）
  baseStats + Σ equipment.getFinalStats().toDiscipleStats()，critRate 另累加
DiscipleStatCalculator.computeFinalStats（属性Ops3.kt:195-212）
  applyPillStats(applyManualStats(applyEquipmentStats(StatAccum(base, base.critRate), eqIds, eqs), ...), ...)
DiscipleStatCalculator.applyEquipmentStats（属性Ops3.kt:216-231）  ← 装备加成唯一入口
getFinalStats(disciple)（属性Ops4.kt:21-42）      ← 4 个部位 id 硬编码列举
getMaxHpMpColumn（属性Ops4.kt:59-99）            ← 每旬热点列直读版，装备段硬编码 4 槽
C++ 对偶：disciple_stats.h:342-421 与 accumulateEquipmentHpMp（:190-205）
```

⇒ **加成模型只能在这 3 处改（Kotlin 2 处 + C++ 2 处）**，是改造成本最低的一环；但 `Combatant` 侧要新增战斗期字段（§3.10）。

### 2.6 堆叠机制全貌

| 环节 | 位置 |
|---|---|
| 合并键 | `StackKeys.equipment`（`StackKeys.kt:24-25`） |
| 合并/分块 | `InventorySystem.addEquipmentStack`（`InventorySystem.kt:119-155`）→ `InventorySystemWithOps1.kt:196-209` `consolidate`；999 上限分块 |
| C++ 对偶 | `inventory.h:156 equipmentKey`、`inventory.h:542-545` 槽位计数 |
| 溢出处理 | `handleOverflowResult(result, "equipment", item)`（`InventorySystem.kt:152`）→ 溢出邮件 |
| 容量硬上限 | `EntityCountBoundsRule.kt:24,33`：装备堆叠 warn 5000 / hard cap 50000 |
| 存量重建 | `SaveDataReconciler.kt:24-38` 从实例重建堆叠 |

### 2.7 静态数据与 codegen

```
scripts/data/equipment_db_sample.json（中性源，权威，72 条）
  → scripts/gen-templates.mjs（:218-247）→ gamecore/include/gamecore/data/equipment_db.h（72 行 C++ 表）
                                        → android/core/engine/src/test/resources/templates/equipment_db_sample.json（测试快照）
  → scripts/gen-game-data.mjs → android/app/src/main/assets/data/game-data.json（db.equipment）
```

三重守卫：`TemplateRegistryGuardTest`（快照 ↔ `EquipmentDatabase` 实时数据）、`StaticDataSingleSourceGuardTest`（中性源 ↔ 测试快照逐字节）、C++ `equipment_db_test.cpp`（快照 ↔ C++ 表）。

**关键缺陷（D1）**：`EquipmentRegistry`（316 行）是**第二份硬编码装备表，数值与 `EquipmentDatabase` 全线不一致**：

| 模板 | EquipmentDatabase（战利品/商店/价格） | EquipmentRegistry（锻造） |
|---|---|---|
| ironSword 精铁剑 | 物攻 15 / crit 0.03 / price 4000 | 物攻 10 / crit 0.02 / price 3600 |
| immortalSword 诛仙剑 | 物攻 4050 / crit 0.195 | 物攻 2700 / crit 0.13 |
| 使用方 | `DiscipleEquipmentService`、`EnemyGenerator`、`CaptiveGearUtils`、`MerchantItemConverter`、`ItemDetailDialog`、`EquipmentStack.basePrice` | `ForgeRecipeRegistry.getEquipmentTemplate/getFullForgeInfo`（`:78-92`）、`GameDataManager.equipment`（`:40,117`） |

`EquipmentRegistry` **无任何守卫测试**，两份表已长期漂移 ⇒ 锻造产物与掉落产物同名不同数值（且 `basePrice` 取的是另一份表）——本方案删除双表、只留一份 codegen 单源（D5 一并闭合）。

### 2.8 存档面现状

| 面 | 现状 |
|---|---|
| Room 版本 | `GameDatabaseConfig.DATABASE_VERSION = 61`（`GameDatabase.kt:95`，**实测**）；迁移文件已到 `GameDatabaseMigrationsV61.kt`（实时结算线已合法占用 v60/v61） |
| 装备表 | `equipment_stacks`、`equipment_instances`；DAO 见 `EquipmentDaos.kt` |
| 弟子列 | `Disciple` 用 `@Embedded var equipment: EquipmentSet`（`Disciple.kt:121-122`）⇒ `disciples` 表内联 4 个 id + 4 个 nurture + 储物袋三字段 |
| 枚举列编码 | `JsonConverters.fromEquipmentSlot = value.name`，**回退 `?: EquipmentSlot.WEAPON`**（`JsonConverters.kt:31-36`） |
| SaveData Proto | `@ProtoNumber(53) equipmentStacks`、`@ProtoNumber(5) equipmentInstances`、`@ProtoNumber(55) stacksSerialized`（`SaveData.kt:62-73`） |
| 完整性规则 | `EquipmentRefRule`（order 6）、`EquipmentDedupeRule`（order 15）、`EntityCountBoundsRule` |
| 旧格式反序列化 | `OldSaveFormatDeserializer.kt:77-93` 把旧 `equipment` 拆为 `equipmentInstances` + 空 `equipmentStacks` |
| 云存档重建 | `SaveDataReconciler.kt:24-38` 从实例反推堆叠 |

**存档编号实况（实测，含对初版方案的自我纠正）**：`Disciple` **不是**直接序列化的——它走自定义 `DiscipleSerializer` 的「复合 via 代理」模式，`EquipmentSet`/`CombatAttributes`/`PillEffects`/`SkillStats`/`UsageTracking` 全部**被摊平**进私有 `DiscipleSurrogate`（`:305-417`），该代理 91 个字段**全部有显式 `@ProtoNumber`**；装备段为 `weaponId(17)/armorId(18)/bootsId(19)/accessoryId(20)/weaponNurture(24)/armorNurture(25)/bootsNurture(26)/accessoryNurture(27)/spiritStones(28)/storageBagItems(30)/storageBagSpiritStones(31)`，孕养 checkpoint `98/99`，`pillNurtureSpeedBonus(47)`；已 `reserved` 的编号为 `7,8,11–16,22,29,50,76,88,93,102,104,105,110`。

⇒ **本方案要动的存档类型全部已有显式编号**，"改字段会让后续字段编号平移"的风险在本方案**不成立**（`EquipmentSet` 自身的 `@Serializable` 注解与存档 proto 无关）。需要注意的只有：新增编号只能取未占用且未 reserved 的值（可用段 **112+**）；退役字段必须写 `reserved` 注释而非复用编号。

**同时发现的真实（但与本方案无关的）潜在风险**：`GameData` 虽有显式编号，但它引用的**一批子消息类自身零 `@ProtoNumber`**（`DiplomacyState`、`SectPolicyState`、`ProductionState`、`WorldMapStateEntity`、`AlchemySystem`、`SectOrganizationState`、`BuildingState`、`ExplorationState`、`PatrolStateEntity`、`EconomicState`、`AISectPersonality`、`RedeemCode`、`GameConfigData` 等 ~20 个类）——这些类字段编号是**声明序隐式**的，任何字段插入/重排都会静默错位。本方案不触碰它们，登记为独立技术债 **I8**。

### 2.9 UI 面现状

| 界面/组件 | 位置 | 现状 |
|---|---|---|
| 弟子详情装备区（4 格） | `feature/game/.../components/detail/DetailEquipmentSection.kt:36-113` | 一行 4 格，`slotType` 字符串 `"weapon"/"armor"/"boots"/"accessory"` |
| 更换选择弹窗 | 同文件:132-183 + `ReplaceSelectionData.kt:64-…` | 左列表右详情，按品阶排序 |
| 装备详情/卸下/更换 | `DiscipleDetailScreen.kt:606-638` + `ItemDetailDialog.kt:272-310` | 孕养进度条 + 卸下/更换按钮 |
| 仓库 | `tabs/WarehouseTab.kt:95,116,538,676` | 装备堆叠列表 + **数量角标** + 批量出售 |
| 可售装备区 | `tabs/EquipmentSection.kt:16-46` | 4 列卡片 |
| 商店 | `dialogs/MerchantDialog.kt:205-212`、`MerchantInventoryDialog.kt:95-156` | 按名称+品阶统计堆叠数量 |
| 锻造 | `dialogs/ForgeDialog.kt:460,583,662` | 73 条配方按 `recipeId`（= 旧模板 id）展示 |
| 精灵图 | `core/ui/.../EquipmentSprite.kt:6-7` + `android/scripts/resource-registry.json` | 71 张 PNG 源图在 `模拟宗门美术素材/装备/` |
| 排序/关注 | `core/domain/.../ItemSortUtils.kt:19-39` | 关注键 `equipment:名称`（**同名多件会串**，见 0.2-1） |
| 仓库 itemId | `SectWarehouseManager` | `"equipment_名称_品阶"`（同上问题） |

### 2.10 C++ 面现状（装备逻辑已在 C++ 实现，不是只读表）

| 子系统 | 文件 | 内容 |
|---|---|---|
| 穿/卸事务 | `system/disciple_tx.h:395-540` | `equipTransaction`（校验链 7 段）/ `unequipTransaction` |
| 自动装备 | `system/auto_gear.h:101-470`（含 `nurtureLevel` 比较键 :123）、`:904-940` 落库 | 与 Kotlin `DiscipleEquipmentManager` 逐位对齐 |
| 属性结算 | `system/disciple_stats.h:113-146`（`equipmentFinalStats` + `nurtureMultiplier`）、`:190-205`、`:342-421` | 孕养乘区唯一实现 |
| 战斗 | `system/battle.h:37,179`（`kCritBaseMultiplier=0.5`）、`battle_calculator.h:552-556`、`battle_json.h:62,137` | 暴击固定倍率，**无暴击伤害加成通道**（D3） |
| AI 侧孕养 | `system/ai_sect_ops.h:296-349` + `system/nurture_constants.h` | 每旬按槽位增长 |
| 静态表 | `data/equipment_db.h`（551 行，codegen） | 72 条模板 |
| 状态列 | `state/disciple_store.h:221`、`state/models.h:70-71,124-127,370` | 列式存储 + 存档模型 |
| 仓库/堆叠 | `system/inventory.h:156,542-545,608` | 装备合并键 + 槽位计数 |
| 编解码 | `src/json_codec.cpp:275,332`、`src/gameview_encode.cpp:182`、`state/column_dirty.h:106-142,228-311` | 双向编解码与脏列 |
| 分发 | `src/execute_dispatch.cpp:1687`（`DISCIPLE_TX_EQUIP`）、`:2469` | ActionId 分发 |
| 测试 | `test/equipment_db_test.cpp`、`test/disciple_tx_test.cpp:140-549`、`test/phase_settlement_test.cpp:157-800`、`test/ai_sect_ops_test.cpp:161-293`、`test/bench/*` | GTest 覆盖已较完整 |

### 2.11 规模量化

| 指标 | 实测值 |
|---|---|
| 命中装备/孕养关键字的文件数 | **497**（生产 280 + 测试 217） |
| 装备相关测试文件 | **217**（含 `Diff*Test` 14 个、`Mirror*Test` 3 个、`RoomMigration*Test` 4 个） |
| 旧装备模板 | **72** 条（`EquipmentDatabase`）+ **72** 条（`EquipmentRegistry`，重复） |
| 锻造配方 | **73** 条（`ForgeRecipeDatabase`） |
| 装备美术资源 | **71** 张 PNG（`模拟宗门美术素材/装备/`） |

### 2.12 调查途中发现的预存缺陷（均须在本方案一次清偿）

| 编号 | 缺陷 | 证据 | 等级 | 处置 |
|---|---|---|---|---|
| **D0′** | **存档隐式编号（真实存在，但不在本方案路径上）**：~20 个嵌套存档子消息类零 `@ProtoNumber` | 实测零命中 | 🟡 中 | **不阻塞本方案**；登记独立债 I8 |
| **D1** | **装备双表漂移**：`EquipmentDatabase` 与 `EquipmentRegistry` 同 id 不同数值，后者无守卫 | §2.7 | 🔴 高 | §3.16 处置：`EquipmentRegistry` 降级为纯转发 + `EquipmentSingleSourceGuardTest` |
| **D2** | **孕养等级双源 + 仓库穿装清零** | `Items.kt:177` vs `DiscipleComponents.kt:107-113`；`DiscipleEquipmentManager.kt:265-267` | 🔴 高 | §3.5 合并为实例单点 |
| **D3** | **暴击伤害加成为死字段**：`pillCritEffectBonus` 全链写入但战斗公式只用固定常量 | `BattleModels.kt:32-58`（`Combatant` 无暴击伤害字段）；`BattleCalculator.kt:166-167`、`battle.h:37,179` | 🟡 中 | §3.10 新增 `critDamageBonus` 并接线 |
| **D4** | **孕养 checkpoint 列随孕养一起作废** | `Disciple.kt:109-111`、`DiscipleTables.kt:190-191` | 🟡 中 | §3.15 删除并同步列注册 |
| **D5** | 第三处口径：`EquipmentStack.basePrice` 用 Database 价、实例数值来自 Registry | `Items.kt:92-93,190-191` | 🟡 中 | 随 D1 闭合 |
| **D6** | **Room 枚举回退指向将被删除的枚举值** | `JsonConverters.kt:36` `?: EquipmentSlot.WEAPON` | 🟡 中 | 回退改 `HEAD` + 日志；迁移清空旧行 |
| **D7** | **装备阈值建立在堆叠语义上** | `EntityCountBoundsRule.kt:24,33` | 🟢 低 | 改为实例条数 + **只告警不截断**（S18） |
| **D9** | **codegen 非幂等**：已提交 `equipment_db.h`/`herb_db.h` 含 `operator==` 数值等价守卫、`*TemplatesMutable()` 运行期注入入口、「数值外置」KDoc 共 51 行，但生成函数不输出 | 实测重跑 `gen-templates.mjs` 后 `git diff` 删 51 行；`data_inject.h` 正引用这些符号 ⇒ 重跑即破坏 C++ 构建 | 🔴 高 | §3.16 生成器补全输出 + `TemplateCodegenIntegrityGuardTest` |
| **D10** | **生成器死代码**：`extractEquipment`/`extractHerbs`/`extractSeeds` + 4 个正则（~50 行）零调用，且文件头 KDoc 谎称"从 Kotlin Registry 源码提取" | `scripts/gen-templates.mjs:1-16,24-54,111-140` | 🟡 中 | §3.16 删死代码 + 重写 KDoc + 守卫 |
| **D8** | ~~`DisciplePurchaseService.MAX_EQUIPMENT_PURCHASES = 2` 与新六部位口径不符~~ | `DisciplePurchaseService.kt:71` | 🟢 低 | **自查更正：不是缺陷**——该常量是"每弟子每月装备购买次数"限流，与部位数无耦合，**不作为修改项** |

---

## 三、目标架构（技术方案）

### 3.1 部位枚举：`EquipmentSlot` 4 值 → 6 值（保留类型名）

**保留类型名 `EquipmentSlot`**（"装备槽位"语义在六部位下依然准确），仅改常量集：`EquipmentSlot` 被 280 个生产文件与 217 个测试文件引用，机械改名是纯噪声改动。

```kotlin
@Keep
@Serializable
enum class EquipmentSlot {
    // 编号 0..3 为已退役的四部位（原 WEAPON/ARMOR/BOOTS/ACCESSORY）：保留 reserved 语义，禁复用
    @ProtoNumber(10) HEAD,      // 头部
    @ProtoNumber(11) BODY,      // 身体
    @ProtoNumber(12) HANDS,     // 手部
    @ProtoNumber(13) FEET,      // 脚部
    @ProtoNumber(14) WEAPON,    // 武器
    @ProtoNumber(15) LEGS;      // 腿部

    val displayName: String get() = when (this) {
        HEAD -> "头部"; BODY -> "身体"; HANDS -> "手部"
        FEET -> "脚部"; WEAPON -> "武器"; LEGS -> "腿部"
    }
    /** UI 六宫格顺序（单一真源；声明序 = 显示序 = R1 指定序） */
    companion object {
        val displayOrder: List<EquipmentSlot> =
            listOf(HEAD, BODY, HANDS, FEET, WEAPON, LEGS)
    }
}
```

- 编号取 **10..15** 而非 0..5：ProtoBuf 枚举按编号落盘，旧档 `2` 表示 `BOOTS`，若新 `2` 是别的部位会产生"能解码但语义错误"的静默错位。取新段使"语义断裂"显式化，并由迁移清空旧行双保险。
- **六部位的最终构成（`ACCESSORY` 移除 + `WEAPON`/`LEGS` 并存）**：
  - `HANDS`（手部/护手）保留 R9 指定的主词条池「攻击力/暴击率/暴击伤害」；
  - `WEAPON`（武器）占**第 5 位**，由原"腿部"槽位转换而来；
  - `LEGS`（腿部）**回归占第 6 位**（原饰品位置），`ACCESSORY` 从枚举**移除**；
  - ⇒ 显示序 = **头 / 身 / 手 / 脚 / 武 / 腿**（3×2 宫格：上行 头·身·手，下行 脚·武·腿）。
- **主词条池**：`WEAPON` 为**输出向**（攻击力/暴击率/暴击伤害，系数 1.15）、`LEGS` 为 R9 原池（5 项，0.95）——**已拍板（2026-09-29）**，见 §3.4.2。
- **同名冲突（必须靠迁移兜底）**：只有 **`WEAPON` 一处**新旧同名（旧 `WEAPON(0)` / 新 `WEAPON(14)`）。已退役的 `ARMOR/BOOTS/ACCESSORY` 在旧行里也存在，其中 `ACCESSORY` 在**新枚举中已不存在** ⇒ 解码走 `JsonConverters` 回退分支（`?: HEAD` + `Log.w`）。Room 按枚举 **name** 落列（`JsonConverters.kt:31`），若迁移不清空旧行，旧 `slot="WEAPON"` 行会被当作新武器部位读出（数值字段已全部退役 ⇒ 幽灵件）。⇒ **迁移必须清空两张装备表与六个部位列**（§6.5 A1 / §13-10）。
- **Proto 编号上的对应处理**：`weaponId(17)` **被复用**为武器部位列——旧值由迁移清空，故复用无歧义；其余 5 个部位走**新增列**（`headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)`）。旧 `accessoryId(20)` 转为**退役**（随饰品位移除）。这样 6 个槽位 = 复用 1 个 + 新增 5 个，既避免"删了旧 `weaponId` 列又新增同名列"的迁移绕路，也少一次列搬迁。
- Room 侧以 **name** 落列，迁移清空旧行 + 回退目标改 `HEAD` 并记 `Log.w`（D6）。
- `EquipmentSlot.displayOrder` 为 UI 顺序单一真源，`EquipmentSlotOrderGuardTest` 同时断言它与枚举**声明序**一致。

### 3.2 装备定义：套装部件模板（单源 codegen）

```kotlin
object EquipmentDatabase {
    /**
     * 套装部件模板：套装 × 部位 共 12 条。
     * 每部位基础数值按品阶（1..6）展开，生成器据此产出 72 条可生成条目。
     */
    data class SetPieceTemplate(
        val id: String,            // "lietian_HEAD"
        val setId: String,         // "lietian"
        val part: EquipmentSlot,
        val name: String,          // "裂天罡煞·头冠"
        /** 该部位的主词条候选池（§3.4.2 单一真源） */
        val mainStatPool: List<EquipMainStat>,
        val minRealmByRarity: List<Int>,
        val priceByRarity: List<Int>,
        val description: String
    )

    /** 主词条候选词条（单列重构后：ATTACK/DEFENSE 即统一单列属性，无需按流派固化） */
    enum class EquipMainStat { ATTACK, DEFENSE, HP, CRIT_RATE, CRIT_DAMAGE }
}
```

**单一源链条（沿用现有 codegen，不新建管线）**：

```
scripts/data/equipment_db_sample.json（中性源，权威：12 部件 × 6 品阶 = 72 条 + 2 条套装定义）
  → scripts/gen-templates.mjs（产出 equipment_db.h / equip_set_db.h / equip_main_stat_db.h / equip_affix_db.h + 测试快照）
  → scripts/gen-game-data.mjs → android/app/src/main/assets/data/game-data.json（db.equipment 段）
  → EquipmentDatabase（Kotlin）
守卫：TemplateRegistryGuardTest + StaticDataSingleSourceGuardTest + equipment_db_test.cpp（三重，与现状同构）
```

### 3.3 套装定义与效果（新增，单源）

```kotlin
data class EquipmentSetDef(
    val id: String,            // "lietian" / "zifu"
    val name: String,          // "裂天罡煞" / "紫府玄冥"
    val school: EquipSchool,   // PHYSICAL / MAGIC
    val bonus2: SetBonus, val bonus4: SetBonus, val bonus6: SetBonus
)
data class SetBonus(val entries: List<EquipStatValue>)
```

- 门槛口径：件数达档即生效，**档位不叠加不越级**；穿满 6 件时 2/4/6 三档**同时生效**。
- 套装效果与词条**共用同一加成模型 `EquipStatValue`**（不做第二套加成体系）⇒ §3.10 只有一处结算实现（YAGNI：同一抽象有两个当前消费者）。

### 3.4 加成模型：`EquipStat` 维度 + 部位主词条池 + 7 项副词条池

#### 3.4.1 装备可提供的加成维度（`EquipStat`，8 项）

```kotlin
/** 装备可提供的加成维度（单列重构后 8 项）。EquipStat 为新引入枚举、从未上线 ⇒ 编号自 1 起，无历史包袱。 */
@Keep
@Serializable
enum class EquipStat {
    @ProtoNumber(1) ATTACK,               // 攻击力（flat，单列）
    @ProtoNumber(2) DEFENSE,              // 防御力（flat，单列）
    @ProtoNumber(3) HP,                   // 血量（flat）
    @ProtoNumber(4) CRIT_RATE,            // 暴击率（比例值）
    @ProtoNumber(5) CRIT_DAMAGE,          // 暴击伤害（比例值，同时接线 D3 死字段）
    @ProtoNumber(6) ATTACK_PCT,           // 攻击力%（乘区；仅套装/功法/丹药使用，不进副词条池）
    @ProtoNumber(7) PHYSICAL_DAMAGE_PCT,  // 物理伤害加成（乘区；套装/词条）
    @ProtoNumber(8) MAGIC_DAMAGE_PCT;     // 法术伤害加成（乘区；套装/词条）
}

@Keep
@Serializable
data class EquipStatValue(
    @ProtoNumber(1) val stat: EquipStat,
    @ProtoNumber(2) val value: Double
)
```

> **相对初版方案的两处实质收敛**：① 原 4 个分列维度（物攻/法攻/物防/法防）与"按流派固化"随单列重构一并取消——`攻击力`/`防御力` 即单列属性本身（§15.5 C1）；② 装备不提供"速度/灵力"（S14），故枚举不含这两项。

| `EquipStat` | 落到引擎的字段 | 结算位置 |
|---|---|---|
| `ATTACK` / `DEFENSE` / `HP`（3 项 flat） | `DiscipleStats.attack` / `.defense` / `.maxHp` | `applyEquipmentStats`（Kotlin `属性Ops3.kt:216-231` / C++ `disciple_stats.h:342-421`） |
| `CRIT_RATE` | `DiscipleStats.critRate` | 同上 |
| `CRIT_DAMAGE` | **新增** `Combatant.critDamageBonus` | `BattleCalculator` 伤害段 |
| `ATTACK_PCT` | 装备块攻击力百分比乘区 | 与 flat 同处，**只作用于 (base + 装备 flat)** |
| `PHYSICAL_DAMAGE_PCT` / `MAGIC_DAMAGE_PCT` | **新增** `Combatant.physicalDamageBonus` / `magicDamageBonus` | 伤害公式的类型增伤桶 |

**乘区口径（双端逐位一致）**：

```
atk = (baseAtk + Σ 装备flatAtk) × (1 + Σ 装备atkPct) + Σ 功法flat + Σ 丹药flat
```

理由：① 功法/丹药加法序与现有对拍基线**逐位不变**；② 装备乘区只放大装备自身贡献；③ 单点实现、测试面小。

#### 3.4.2 部位主词条池（R9，单一真源）

主词条**不按部位固定**，生成时从**该部位的候选池**随机抽取一条（走 `RngPartition.EQUIPMENT`）。

| 部位（**按显示序**） | 候选池 | 条目数 | 部位数值系数 |
|---|---|---|---|
| 头部 HEAD | 血量、防御力 | 2 | 1.00（生存向） |
| 身体 BODY | 防御力、攻击力、暴击率、暴击伤害 | 4 | 1.00（均衡） |
| **手部 HANDS** | 攻击力、暴击率、暴击伤害 | 3 | 1.15（输出向） |
| 脚部 FEET | 防御力、攻击力、暴击率、暴击伤害、血量 | 5 | 0.95（均衡） |
| **武器 WEAPON** | 攻击力、暴击率、暴击伤害 | 3 | 1.15（输出向） |
| **腿部 LEGS** | 防御力、攻击力、暴击率、暴击伤害、血量 | 5 | 0.95（均衡） |

> ✅ **武器与腿部的主词条池已拍板（2026-09-29）：武器转输出向、腿部用 R9 原池**——
> - **武器（第 5 位）**：攻击力 / 暴击率 / 暴击伤害，系数 **1.15（输出向）**；
> - **腿部（第 6 位）**：防御力 / 攻击力 / 暴击率 / 暴击伤害 / 血量，系数 **0.95（R9 原池）**；
> - **手部**：保持 R9 原话（攻击力 / 暴击率 / 暴击伤害，1.15）。
> ⇒ 饰品位移除后空出的"输出向"生态位由**武器**接手；主词条池**无冗余重复项**（武器/手部虽同为输出向，但分属不同部位、各自独立抽取）。
> **备选（未采纳，登记 §14.3）**：① 手部转防御向（防御力/血量）+ 武器输出向（分工最清晰）；② 腿部改纯防御向（防御力/血量）；③ 武器与腿部同用原腿部池（冗余）。
> 切换成本：**只改本表 + `EquipMainStatPool` 数据行 + C++ 对偶表**，架构、存档、编号、套装效果均不受影响。

**候选词条口径（单列重构后已简化）**：
- ✅ §15 单列重构已拍板 ⇒ `攻击力` = 统一 `attack`、`防御力` = 统一 `defense`，**无需按流派固化**（原"物理套→物攻/物防、法术套→法攻/法防"的固化口径作废，§13-11 关闭）；
- `血量 → HP`、`暴击率 → CRIT_RATE`、`暴击伤害 → CRIT_DAMAGE` 与流派无关；
- ⇒ 两套套装的主词条池**完全同构**，流派差异全部由**套装效果**承担（§3.7 / §15.5 C3）；
- 攻击力/防御力与其他维度**共用同一基数表**；同一部位的基数与部位系数在两套间一致。

**主词条数值口径**：`最终值 = 品阶基数（§3.7）× 部位系数 × 等级成长(1 + 0.10×(L-1))`。

#### 3.4.3 副词条池（R10：7 项）

| 词条 | 权重（= 概率 %） | 说明 |
|---|---|---|
| 攻击力 | 13 | flat（单列） |
| 防御力 | 13 | flat（单列） |
| 血量 | 14 | flat |
| 暴击率 | 15 | 比例值 |
| 暴击伤害 | 15 | 比例值 |
| **物理伤害加成** | 15 | 比例值（类型通道 1） |
| **法术伤害加成** | 15 | 比例值（类型通道 2） |
| **合计** | **100** | 权重即概率；按权重**不放回**抽 3 条（天然去重） |

- **权重结构提示（数值评审留意）**：比例类（暴击率+暴击伤害+物伤+法伤）合计 **60%**，flat 类合计 **40%** ⇒ 副词条整体偏"乘区型"，装备强度会**随主词条基数放大而加速**，需与"装备占战力 40%"（S16）一起复核；若后期乘区过强，优先下调两条类型伤害加成的权重。
- **全局池、与流派无关** ⇒ 物理套部件**允许**抽出"法术伤害加成"这类对自己无用的词条（刻意取舍，保留刷词条层次）。按本权重，单件**至少含 1 条对流派无用词条的概率 ≈ 39%**（`1 − (85·84·83)/(100·99·98) = 38.92%`）。缓解 = 分解回收 50% + 每 3 级强化 + 可切 §13-12 过滤池（I9 触发条件）。
- **声明序参与权重前缀和比较，禁止重排**（重排会改变同种子抽取序列，破坏跨端对拍与既有存档确定性）。

### 3.5 实例模型：`EquipmentInstance` 重定义（删除堆叠）

**构造参数收敛**：朴素写法会把 `rarity/minRealm/description/isLocked/level/exp/mainStat/subStats/subRolls/...` 全摊成构造参数（15 个），超出 detekt `constructorThreshold: 10`。拆为两个值对象（`EquipGrowth` / `EquipInstanceMeta`），最终 **9 个**构造参数。

```kotlin
// core/domain/.../model/EquipAffix.kt（新文件）
/** 词条面：1 主 + 3 副 + 各副词条强化次数（恒 3 条，去重） */
@Keep @Serializable @Immutable
data class EquipAffixSet(
    @ProtoNumber(1) val mainStat: EquipStatValue,
    @ProtoNumber(2) val subStats: List<EquipStatValue>,
    @ProtoNumber(3) val subRolls: List<Int>
) {
    fun mainStatFinal(level: Int): EquipStatValue
    fun totalBonus(level: Int): List<EquipStatValue>
}

/** 横切面：品阶/门槛/描述/锁 */
@Keep @Serializable @Immutable
data class EquipInstanceMeta(
    @ProtoNumber(1) val rarity: Int = 1,
    @ProtoNumber(2) val minRealm: Int = 9,
    @ProtoNumber(3) val description: String = "",
    @ProtoNumber(4) val isLocked: Boolean = false
)

/** 成长面：等级 + 经验 + 词条（同生共死，聚合为单值对象） */
@Keep @Serializable @Immutable
data class EquipGrowth(
    @ProtoNumber(1) val level: Int = EquipmentInstance.MIN_LEVEL,
    @ProtoNumber(2) val exp: Int = 0,
    @ProtoNumber(3) val affix: EquipAffixSet
)
```

```kotlin
// core/domain/.../model/Items.kt（EquipmentStack 整体删除）
@Keep @Serializable
@Entity(
    tableName = "equipment_instances",
    primaryKeys = ["id", "slot_id"],
    indices = [Index("ownerId"), Index("setId"), Index("part"), Index("rarity")]
)
data class EquipmentInstance(
    @ColumnInfo(name = "id")      @ProtoNumber(1)   override val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "slot_id") @ProtoNumber(100) var slotId: Int = 0,
    @ProtoNumber(2)  override val name: String = "",
    @ProtoNumber(3)  val setId: String = "",                        // 套装 id（空 = 散件）
    @ProtoNumber(4)  val part: EquipmentSlot = EquipmentSlot.HEAD,  // 六部位
    @ProtoNumber(5)  val growth: EquipGrowth,                       // 等级 + 经验 + 词条
    @ProtoNumber(6)  val meta: EquipInstanceMeta = EquipInstanceMeta(),
    @ProtoNumber(7)  val ownerId: String? = null,
    @ProtoNumber(8)  val isEquipped: Boolean = false
) : GameItem() {
    val level: Int get() = growth.level
    val exp: Int get() = growth.exp
    override val rarity: Int get() = meta.rarity
    override val description: String get() = meta.description
    val isLocked: Boolean get() = meta.isLocked
    fun totalBonus(): List<EquipStatValue> = growth.affix.totalBonus(growth.level)
    companion object { const val MIN_LEVEL = 1; const val MAX_LEVEL = 30 }
}
```

**关键设计点**：
1. **等级/词条只存实例单点** ⇒ R5 天然成立（清偿 D2）。
2. `@ProtoNumber` 全部显式；退役 `slot(3)/rarity(4)/description(7)/critChance(10)/nurtureLevel(13)/nurtureProgress(14)/minRealm(15)/面板属性(50–56)` 一律 `reserved` 不复用；新字段取 `setId(60)/part(61)/growth(62)/meta(63)`。
3. 删除 `quantity`；`EquipmentStack` 整体删除 ⇒ R6。
4. 保留 `slotId` 复合主键与 `slot_id` 列（存档槽位隔离机制）。
5. 面板数值不再"先算乘区再截断"，而是**以词条列表为唯一表示**，由 §3.10 单点消费。
6. 构造参数 9 个（`constructorThreshold: 10`；"类构造参数 7"是根 AGENTS 表述，以 detekt 实值为准）。Room 列经 `@Embedded`/ProtoBuf 列编码摊平，**DB 与存档结构不受聚合层影响**。

### 3.6 升级系统（替换孕养）：1 级初始，30 级封顶

| 项 | 设计 |
|---|---|
| 经验来源 | ① **升级材料**（主动）② **战斗胜利**（胜利获得"升级所需经验 × 10%"，仅对当前穿戴的 6 件生效）。**③ 孕养类加成丹药已按 R11 删除**——不再存在"装备经验丹"路径 |
| 经验曲线 | `expRequired(level) = 100 × level × rarityMul(rarity)`，`rarityMul` 沿用旧表 1.0/1.5/2.0/3.0/4.5/6.0；上限从"按品阶"改为一律 **30** |
| 满级 | `level = 30` 后 `exp` 置 0、不再累计；溢出不保留 |
| 主词条成长 | `mainLevelMultiplier(level) = 1 + 0.10 × (level - 1)`（Lv1 = 1.00，Lv30 = **3.90**） |
| 副词条强化 | **每 3 级**（Lv3/6/9/…/30，共 10 次）随机挑一条副词条 `subRolls[i] += 1`；走 `RngPartition.EQUIPMENT`（新增 id 13） |
| **强化节点时序（硬口径）** | 在**升级动作完成时**判定 `newLevel % 3 == 0` ⇒ 此次升级触发一次强化；Lv29→30 触发第 10 次；`level == 30` 之后**不再有**强化或经验累计。一次性从 Lv1 连升到 Lv30 时按 3,6,…,30 逐节点强化（共 10 次，顺序确定） |
| 装卸保真 | 等级/经验/词条/强化次数全部随实例走；卸下入袋（`StorageBagItem.equipmentInstance` 完整保真）、穿上原样恢复 |
| 每旬结算 | **删除** `EquipmentNurtureService` 与 `equipmentNurturingCompletionMonth/Phase`（清偿 D4）；升级由玩家交互 + 战斗胜利驱动（不引入新结算循环） |
| 上限校验 | `level.coerceIn(1,30)`；`exp.coerceIn(0, expRequired-1)`（满级收敛 0）；`subRolls` 各 `coerceIn(1,11)`；`subStats` 恒 3 条去重（不合法由 `EquipmentValueSanitizeRule` 补齐/去重，**截断用 coerce 而非 clamp 后归一**，保证双端逐位一致） |

**升级消耗（灵石 + 兽材，走 `InventorySystem` 统一入口）**：

| 从 Lv → Lv+1 | 灵石 | 兽材 |
|---|---|---|
| 通用公式 | `100 × rarity² × level` | `max(1, floor(level / 10))` 件 |
| T6 Lv1→2 | 3,600 | 1 |
| T6 Lv29→30 | 104,400 | 2 |
| T6 单件 1→30 累计 | **1,566,000 灵石**（`100·36·(29×30/2)`） | **39 件**（`9×1 + 10×1 + 10×2`） |
| T1 单件 1→30 累计 | **43,500 灵石** | 39 件 |

> **校准目标（拍板）**：一套六件 1→30 的累计消耗 ≈ 玩家 **1 个月**正常产出（比值容差 [0.75,1.25]，S16）。
> 实施时取经济基线表年灵石收入 ÷ 12 得月产出 `M`，令 `6 × 1,566,000 × k ≈ M` 解整体系数 `k`；`k ≠ 1` 时同步调整 §3.14 源汇表。**因不做保底，该比值不宜偏低**。
> **EQ-B4 实施记录（2026-09-30）**：经济基线表（knowledge-base）为源汇登记表、无数值月产出且月产出依赖玩家画像 ⇒ 以拍板口径**反推定锚**：`M = 940 万灵石/月`（T6 可穿阶段标准月画像锚，`EquipmentEconomyCalibrationTest.STANDARD_MONTH_OUTPUT_AT_T6_STAGE`；量级交叉核对 = T6 物品市价 2,688 万 ≈ 2.9 个月产出）。实测比值 9,396,000 / 9,400,000 = **0.9996** ∈ [0.75,1.25] ✓；升级消耗公式无需调整（k=1）。锚随 B5 落经济基线表。

### 3.7 首期两套套装（每套 6 部位）

> 主词条自 R9 起为**部位池随机**，下表只列"部件名 + 该部位可抽到的主词条池"。

#### 套装 A：物理套「裂天罡煞」（`lietian`）

| 部位（**按显示序**） | 部件名 | 主词条池 |
|---|---|---|
| 头部 | 裂天罡煞·头冠 | 血量 / 防御力 |
| 身体 | 裂天罡煞·重铠 | 防御力 / 攻击力 / 暴击率 / 暴击伤害 |
| **手部** | 裂天罡煞·战手 | 攻击力 / 暴击率 / 暴击伤害 |
| 脚部 | 裂天罡煞·战靴 | 防御力 / 攻击力 / 暴击率 / 暴击伤害 / 血量 |
| **武器** | 裂天罡煞·战刃 | 攻击力 / 暴击率 / 暴击伤害 |
| **腿部** | 裂天罡煞·胫甲 | 防御力 / 攻击力 / 暴击率 / 暴击伤害 / 血量 |

| 档位 | 效果 |
|---|---|
| **2 件套** | **物理伤害 +10%**（`PHYSICAL_DAMAGE_PCT = 0.10`） |
| **4 件套** | 暴击率 +12%（`CRIT_RATE = 0.12`） |
| **6 件套** | 物理伤害 +20%（`PHYSICAL_DAMAGE_PCT = 0.20`） |

#### 套装 B：法术套「紫府玄冥」（`zifu`）

| 部位（**按显示序**） | 部件名 | 主词条池 |
|---|---|---|
| 头部 | 紫府玄冥·灵冠 | 血量 / 防御力 |
| 身体 | 紫府玄冥·玄袍 | 防御力 / 攻击力 / 暴击率 / 暴击伤害 |
| **手部** | 紫府玄冥·灵手 | 攻击力 / 暴击率 / 暴击伤害 |
| 脚部 | 紫府玄冥·云履 | 防御力 / 攻击力 / 暴击率 / 暴击伤害 / 血量 |
| **武器** | 紫府玄冥·灵剑 | 攻击力 / 暴击率 / 暴击伤害 |
| **腿部** | 紫府玄冥·灵甲 | 防御力 / 攻击力 / 暴击率 / 暴击伤害 / 血量 |

| 档位 | 效果 |
|---|---|
| **2 件套** | **法术伤害 +10%**（`MAGIC_DAMAGE_PCT = 0.10`） |
| **4 件套** | 暴击伤害 +25%（`CRIT_DAMAGE = 0.25`，同时接线 D3） |
| **6 件套** | 法术伤害 +20%（`MAGIC_DAMAGE_PCT = 0.20`） |

> 两套主词条池**完全同构**；流派差异全由套装效果承担 ⇒ **穿满一套 = 该流派 +30% 类型伤害**。

#### 品阶基数表（主词条，品阶 1 的基础值）

| 主词条 | 品阶 1 | 品阶 2 | 品阶 3 | 品阶 4 | 品阶 5 | 品阶 6 |
|---|---|---|---|---|---|---|
| 攻击力（单列） | 3 | 9 | 27 | 84 | 255 | 780 |
| 防御力（单列） | 3 | 9 | 27 | 84 | 255 | 780 |
| 血量 | 30 | 90 | 270 | 840 | 2550 | 7800 |
| 暴击率 | 0.002 | 0.006 | 0.018 | 0.056 | 0.170 | 0.520 |

> 暴击伤害主词条 = 暴击率 × 2（同品阶），避免第二张表。
> **本表是"单列"基数**——旧双列合并按"两列之和 + 整体重标 `k`"（§15.7 Q7），`k` 同时作用于本表。

**缩放口径**：品阶间约 ×3.06；**品阶 6 的 Lv1 主词条即上表末列，Lv30 乘 3.90，再乘部位系数（§3.4.2）**。
- 例（手部，品阶 6，抽到攻击力，Lv30）：`780 × 1.15 × 3.90 ≈ 3,498`；叠 2 件套 +10% ⇒ ≈ 3,848。
- 例（头部，品阶 6，抽到血量，Lv30）：`7800 × 1.00 × 3.90 = 30,420`。
- 完整数值以 `EquipmentPowerParityTest`（§6.1）为准——该表为**可调单源**。

#### 副词条档位值表（单次强化收益，品阶 1..6）

| 词条 | 品阶 1 | 品阶 2 | 品阶 3 | 品阶 4 | 品阶 5 | 品阶 6 | 权重 |
|---|---|---|---|---|---|---|---|
| 攻击力 | 1 | 3 | 8 | 21 | 64 | 195 | 13 |
| 防御力 | 1 | 3 | 6 | 14 | 43 | 130 | 13 |
| 血量 | 14 | 40 | 106 | 280 | 860 | 2600 | 14 |
| 暴击率 | 0.002 | 0.003 | 0.004 | 0.006 | 0.008 | 0.010 | 15 |
| 暴击伤害 | 0.004 | 0.006 | 0.008 | 0.012 | 0.016 | 0.020 | 15 |
| **物理伤害加成** | 0.004 | 0.006 | 0.008 | 0.012 | 0.016 | 0.020 | 15 |
| **法术伤害加成** | 0.004 | 0.006 | 0.008 | 0.012 | 0.016 | 0.020 | 15 |
| **合计** | — | — | — | — | — | — | **100** |

- 生成时按权重**不放回抽 3 条**（天然去重，强化次数初值恒 1）；新增两项与暴击伤害同档，避免"类型加成压过主词条"。
- 单次强化收益 = 档位值 × 1（每 3 级 +1）；一条词条最多 1 + 10 = **11** 次。
- 主词条抽取、副词条抽取、强化抽取**全部走 `RngPartition.EQUIPMENT`**（新增 id 13，须同步 `rng_manager.h` 枚举 + `initSystemSeed` + `RngSourceGuardTest.registeredPartitionIds`）。
- **抽取顺序（同一件装备内）**：① 主词条（部位池，单次权重抽取）→ ② 副词条（7 项池，3 次不放回）→ ③ 等级强化（每 3 级一次）。该顺序是跨端对拍基准，禁止调整。

### 3.8 产出链（旧 73 配方 → 12 套装部件配方）

| 来源 | 现状 | 改造 |
|---|---|---|
| 锻造 | `ForgeRecipeDatabase` 73 条 → `ForgeRecipeRegistry` 走 `EquipmentRegistry` | **12 条配方**（2 套 × 6 部位），按品阶产出；走 `EquipmentFactory.create(setId, part, rarity, rng)` |
| 商店 / 自动购买 | `MerchantItemConverter.toEquipment`、`AutoBuyService.kt:189,232` | 按品阶随机部件 |
| 掉落/战斗 | `EnemyGenerator`、`GameEngineBattleOps.kt:369`、`GameEngineWorldBattleOps.kt:330` | 同上 |
| 秘境 | `SecretRealmRuinsResolver.kt:139`、`SecretRealmEventGenerator.kt:325`、`SecretRealmService.kt:1242` | 同上 |
| 试炼 / 任务 | `HeavenlyTrialService.kt:479-490`、`MissionSystemRewardOps.kt`、`GameEngineMissionOps.kt:159` | 同上 |
| 邮件/兑换码 | `MailAttachmentDistributeOps.kt:319-335`、`RedeemCodeService.kt:303` | 同上 |
| 外交/交易 | `DiplomacyService.kt:344,368,768` | 同上 |
| 储物袋取回 | `InventoryFacadeImpl.kt:493`、`BagItemReconstructor` | 改"实例取回"，无堆叠重建 |
| AI 弟子 | `ai_sect_recruit.h:176,261-281`、`Generation.kt:20`、`Gear.kt:168` | 按品阶随机部件；**AI 侧只存 (pieceId, rarity, level)，词条不落 AI 载荷** |

**品阶产出约束（0.2-5 拍板）**：全部产出点的品阶**不得高于玩家当前境界可穿戴的最高品阶**（`GameConfig.Realm.getMinRealmForRarity` 反查），且品阶权重按境界分层（低境界偏低品阶）。该约束在 `EquipmentFactory.create(...)` **单点实现**，由 `EquipmentRarityGateTest` 守护。

**无上限后的溢出路径（0.2-2 拍板）**：装备实例**不设硬上限** ⇒ 产出**不再走溢出邮件**（删 `OverflowMailSender` 装备分支）；`EntityCountBoundsRule` 对装备**只告警不截断**；仓库 UI 必须提供排序/筛选/批量分解作为"可管理"的替代保障。

### 3.9 分解与回收（无堆叠的必要配套）

| 项 | 设计 |
|---|---|
| 入口 | 装备详情「分解」；仓库「批量分解」（多选） |
| 返还 | 该装备**累计升级消耗的 50%**：灵石 `floor(Σ/2)` + 兽材 `floor(Σ/2)`（满级 T6 单件 ≈ 783,000 灵石 + 19 件）；不含 §5.4 的一次性补偿 |
| 保护 | `isLocked` 不可分解；已穿戴须先卸下 |
| 经济意义 | 无堆叠 + 随机词条必然产生冗余件，分解是唯一"消耗汇"（`rules/economy-design.md`：无汇不引入） |

### 3.10 属性结算接线（Kotlin + C++ 单点）

```
① 装备块（唯一新逻辑）
   EquipStatResolver.resolve(instances) -> EquipBonus
     EquipBonus = { flatAttack, flatDefense, flatHp, pctAttack,
                    critRate, critDamage, physDmgBonus, magicDmgBonus }
     输入：已装备 6 件实例的 totalBonus() + 命中套装的 SetBonus 条目（按件数 2/4/6 选取）
② Kotlin 接线点（3 处）
   - DiscipleStatCalculator.applyEquipmentStats（属性Ops3.kt:216-231）
   - DiscipleStatCalculator.computeStatsWithEquipment（属性Ops3.kt:152-167）
   - DiscipleStatCalculator.getMaxHpMpColumn 装备段（属性Ops4.kt:71-79，每旬热点）
③ C++ 接线点（3 处，逐位对齐）
   - disciple_stats.h:342-421（面板）/ :190-205（列直读）/ :345-360（map 版）
④ 属性面单列化（§15，与装备同批）
   - DiscipleStats / Combatant：physicalAttack+magicAttack → attack；physicalDefense+magicDefense → defense
   - 映射口径（Q7）：新值 = 旧两列之和 × 统一系数 k（再乘 §3.7 基数表重标）
   - 功法/丹药（双列保留）在**结算层**把两列相加计入 attack/defense（Q2）
⑤ 战斗期新字段（Combatant / CombatantStats）
   新增 critDamageBonus / physicalDamageBonus / magicDamageBonus（默认 0.0）
   - 赋值点：BattleSystem.convertDiscipleToCombatant（BattleSystem.kt:278-299）、BattleSystem战斗Ops1.kt:46、
             HeavenlyTrialService.kt:103,155、EnemyGenerator.kt:215、AISectAttackManager.kt:378、
             mission_completion.h:572,767、exploration_tx.h:128、secret_realm_session.h:589（8 处）
   - 类型判定：isPhysical = 普攻 ? 弟子.innateDamageType : 技能.damageType（Q1；替换原"物攻≥法攻"）
   - 伤害公式：critMult = 1 + kCritBaseMultiplier + critDamageBonus（暴击时）
              damage ×= (1 + (isPhysical ? physicalDamageBonus : magicDamageBonus))        ← 类型增伤桶
              damage ×= (1 − (isPhysical ? physicalDamageReduction : magicDamageReduction)) ← 类型减伤桶（Q4）
              Kotlin: BattleCalculator.kt:160-170,430-440；C++: battle.h:37,179 + battle_calculator.h:552-556
   - 减伤区分桶：DamageZones.damageReduction → physicalDamageReduction / magicDamageReduction（各默认 0.0）
   - 序列化：BattleJsonCodec.kt:39,105 与 battle_json.h:62,137 同步
   - 同时把 pillCritEffectBonus 接线到 critDamageBonus ⇒ 清偿 D3
⑥ 面板与新字段
   - 弟子详情：攻击力 / 防御力（两行）+ 固有属性标签（物理/法术）+ 类型伤害加成
   - 新增 Disciple.innateDamageType（按角色模板固定）：存档 1 字段 + Room 1 列 + C++ 1 列 + 面板标签
```

### 3.11 C++ 实现方案（`rules/cpp-priority.md` 第 4 节）

| 子系统 | 落点 | 关键接口 |
|---|---|---|
| 部位/实例模型 | `state/models.h`、`state/disciple_store.h:221`（`headIds/bodyIds/handsIds/feetIds/weaponIds/legsIds` 六列，按显示序） | 列式存储，与 Kotlin `DiscipleTables` 六列一一对应 |
| 词条 | **新增** `data/equip_affix_db.h`（codegen）+ `system/equip_affix.h`（权重不放回抽取 + 强化） | 禁 `unordered_map` 参与迭代（`std::vector` + 权重前缀和） |
| 套装效果 | **新增** `system/equip_set_bonus.h` | `resolveSetBonus(instances) -> EquipBonus` |
| 部位主词条池 | **新增** `system/equip_main_stat.h` + `data/equip_main_stat_db.h` | 与 Kotlin `EquipMainStatPool` 逐位对拍（单列后无固化逻辑） |
| 升级/分解事务 | **新增** `system/equipment_tx.h`：`upgradeTransaction` / `dismantleTransaction` + `action::EQUIP_UPGRADE` / `EQUIP_DISMANTLE` | 校验链 + 写段（扣材料、等级/exp、强化 roll） |
| 穿/卸事务 | 改写 `system/disciple_tx.h:395-540` 为单轨（删堆叠分支）、六部位 | |
| 自动装备 | 改写 `system/auto_gear.h`（比较键：品阶 → 部位适配 → 等级；删 `nurtureLevel`） | 与 Kotlin `DiscipleEquipmentManager` 逐位一致 |
| 属性 | 改写 `system/disciple_stats.h`（`EquipBonus` 求和 + 乘区）、`system/battle.h` / `battle_calculator.h` / `battle_json.h`（三新字段与类型桶） | 与 Kotlin 逐位一致 |
| AI 侧 | `system/ai_sect_ops.h:296-349` 改按槽位存 (pieceId, rarity, level)；`ai_sect_recruit.h:176,261-281` | AI 载荷不存词条；俘虏落库时用 `RngPartition.EQUIPMENT` 确定性 roll |
| 存档编解码 | `src/json_codec.cpp:275,332`、`state/column_dirty.h`、`src/gameview_encode.cpp:182` | 六槽位列 + 实例新字段 |
| 静态表 | `data/equipment_db.h`（重生成）、**新增** `data/equip_set_db.h` / `equip_main_stat_db.h` / `equip_affix_db.h` | 全部 codegen，禁手写 |
| ActionId | `scripts/gen-action-ids.mjs` 新增 `EQUIP_UPGRADE` / `EQUIP_DISMANTLE` | 禁手改生成物 |
| RNG | `rng/rng_manager.h` 新增 `kEquipment = 13` + `initSystemSeed` 播种 | 与 Kotlin `RngPartition.EQUIPMENT(13)` 对齐 |

**双守护**：每个新增/改写的 C++ 业务实现都必须有 ① GTest 黄金序列（`equipment_tx_test` / `equip_affix_test` / `equip_main_stat_test` / `equip_set_bonus_test`）② JUnit 跨语言对拍（`DiffEquipmentTest` / `DiffEquipmentGenerationTest` / `DiffEquipmentUpgradeTest` / `DiffEquipmentSetBonusTest` / `DiffBattle*`），缺一即任务未完成。

### 3.12 骨架影响：`GameStateStore` 与镜像协议

| 项 | 变更 |
|---|---|
| `GameStateStore.equipmentStacks` | **删除**（`GameStateStore.kt:40`、`GameStateSnapshotProvider.kt:29`、`UnifiedGameState.kt:25`、`MutableGameState.kt:30`） |
| `GameStateStore.equipmentInstances` | 保留（新模型） |
| `StateSyncService` | 删 `COLLECTION_EQUIPMENT_STACKS`（`:675`），保留 `equipmentInstances`（`:676`） |
| `DiffSurfaceAssertion` / `FakeGameStateStore` | 同步删 `equipmentStacks` 条目 |
| `docs/ui-read-surface.md` §2 | **先登记再实现**：装备实例新字段进镜像只读面 |
| `MirrorReadOnlyGuardTest` | 装备新字段加入符号面断言 |

### 3.13 UI 方案

| 界面 | 改造点 |
|---|---|
| 弟子详情装备区 | 4 格 → **3×2 六宫格**（头/身/手 / 脚/腿/饰）；每格显示部件名 + `Lv.N` + 品阶边框 |
| 套装效果面板（**新增**） | 显示「裂天罡煞 4/6」+ 三条效果（已激活高亮/未激活灰），随穿戴实时变化 |
| 装备详情 | 删孕养进度条；改为主词条（大字）+ 3 副词条（各带强化次数）+ 等级进度 + 升级/分解按钮 |
| 装备升级界面（**新增**） | Lv→Lv+1 消耗 + 材料持有量 + 升级后主词条预览；「每 3 级强化一条副词条」提示；材料不足禁用 |
| 更换选择弹窗 | 按**部位**筛选（六值），列表项显示套装名/等级/词条摘要；排序 = 品阶 → 等级 → 部件 |
| 仓库 | 去数量角标；改实例网格；新增筛选（部位/套装/品阶）与排序；新增「批量分解」 |
| 批量分解弹窗（**新增**） | 多选 + 返还预览 + 二次确认（遮罩统一 `Color(0x99000000)`） |
| 锻造 | 73 配方 → 12 套装部件配方 |
| 商店/自动购买/秘境背包/邮件 | 装备条目显示为「套装·部件」，无数量 |
| 精灵图 | 首期 **12 张**（2 套 × 6 部位）命名 `lietian_head`/`lietian_body`/`lietian_hands`/`lietian_feet`/`lietian_weapon`/`lietian_legs` + `zifu_*`；旧 71 张归档。**零美术产能可先上线**：程序化占位（品阶色边框 + 部位字形，I6） |
| 对话框规范 | 新增 3 个对话框按 `rules/new-dialog-checklist.md` 注册 `DialogType` + `GameOverlayHost` 穷举分支；**无输入框** |
| 流派提示 | 套装面板对"固有属性与套装流派不匹配"给出非阻断提示 |

### 3.14 经济（源与汇闭环，`rules/economy-design.md`）

| 资源 | 源（产出） | 汇（消耗） |
|---|---|---|
| 装备部件 | 锻造（12 配方）/ 商店 / 掉落 / 秘境 / 试炼 / 任务 / 邮件 / 兑换码 | **分解**（返还 50%） |
| 装备升级经验 | 升级材料（主动）/ 战斗胜利（×10%） | 升级消耗（每级清空） |
| ~~装备经验丹~~ | **已按 R11 删除**（不再是源） | — |
| 灵石 | 既有全部来源 + **孕养丹退役补偿**（纯新增源，§5.7） | **新增汇**：升级消耗（T6 单件 156.6 万；六件 ≈ 940 万）+ 分解返还为负汇 |
| 兽材 | 既有妖兽掉落 | **新增汇**：升级消耗（T6 单件 39 件；六件 ≈ 234 件） |

- **无新货币**：复用灵石 + 既有兽材 ⇒ 不触发 `rules/economy-design.md` 第 1 节"新货币引入流程"。
- 所有发放/消耗入口包裹 `withTrackingSource("equip_upgrade"/"equip_dismantle")`，来源名加入 `OverflowMailSender.SOURCE_DISPLAY_NAMES`，并**登记到 `docs/knowledge-base.md` 经济基线表**。
- 溢出语义：升级/分解为玩家主动操作，材料不足直接失败；装备产出**不走溢出邮件**（0.2-2）。

### 3.15 删除清单（R2/R6/R11 的完整落点）

| 删除对象 | 位置 |
|---|---|
| `EquipmentStack` 类型 + Entity + DAO | `Items.kt:30-125`、`EquipmentDaos.kt:15-68`、`GameDatabase.kt:140` |
| `equipment_stacks` 表 | 迁移 `DROP TABLE` |
| `EquipmentRegistry` 数据面 | `EquipmentRegistry.kt`（降级为纯转发）；`ForgeRecipeRegistry.kt:11,78-92`、`GameDataManager.kt:40,117` 引用改写 |
| `EquipmentNurtureSystem` / `EquipmentNurtureService` / `EquipmentNurtureData` | 三个文件/类型整体删除 |
| `equipmentNurturingCompletionMonth/Phase` | `Disciple.kt:109-111`、`DiscipleTables.kt:190-191`、列注册表、C++ `disciple_store.h`、`column_dirty.h` |
| 旧 72 模板 / 73 配方 | `EquipmentDatabase.kt`（重写）、`ForgeRecipeDatabase.kt`（重写为 12 条） |
| `StackKeys.equipment` | `StackKeys.kt:23-25` |
| `SaveData.equipmentStacks`(53) | `SaveData.kt:62`；编号 `reserved` |
| `InventoryConfig` 装备堆叠上限 | `InventoryConfig.kt:31,134`（`equipment_stack` 999） |
| `JsonConverters.toEquipmentSlot` 回退值 | `JsonConverters.kt:36` → `HEAD` + 日志（D6） |
| 堆叠溢出/分块路径 | `InventorySystem.kt:119-155`、`InventorySystemWithOps1.kt:196-209`、`inventory.h:156,542-545` |
| 旧装备美术资源 | `模拟宗门美术素材/装备/` 71 张 → 归档到 `已下线/`（**不删源素材**） |
| **孕养丹：效果字段** | `ItemEffect.nurtureSpeedPercent(3)`/`nurtureAdd(8)` → `reserved`；`PillEffects.pillNurtureSpeedBonus`、`DiscipleSurrogate.pillNurtureSpeedBonus(47)` 同 |
| **孕养丹：列式存储** | `DiscipleTables.pillNurtureSpeedBonuses`（`:170`）+ 列注册表/写入/装配/AssembleGroup → 迁移删列 |
| **孕养丹：C++ 配方与分类** | `recipe_db.h`（`:98,157,365-432` 生成段、`:692-699` 分类判据、`:788,1056` 模板映射、`:1084-1085` 类型清单） |
| **孕养丹：C++ 效果链** | `pill_system.h:76`、`disciple_tx.h:815,943,956,974`、`models.h:142,205`、`merchant_settlement.h`、`secret_realm_session.h`、`data_json.h:245-296` |
| **孕养丹：Kotlin 应用链** | `PillEffectApplier`、`AutoPillService`、`DiscipleFacadeImpl战斗Ops2`、`HpMpRecoveryService:285`、`NumericSanitizeRule:110-147`、`CultivationCore` |
| **孕养丹实体与配方** | 已持有丹药按 §5.7 折算补偿后清条目；`unlockedRecipes` 内孕养丹配方回滚 |

### 3.16 调查发现的其他缺陷的处置（D1 / D9 / D10）

> 三项不是本次需求的一部分，但都在重构路径的同一批文件上（装备表、codegen），故登记为**同批清偿项**；单独清偿会造成同一文件二次改动。

| 缺陷 | 根因 | 处置 |
|---|---|---|
| **D1 装备双表漂移** | `EquipmentRegistry` 复制了一份硬编码装备表（同 id 不同数值，无守卫）；锻造走 Registry、掉落/价格走 Database | 随 §3.2 模板重写**一并删除数据面**，改为纯转发层；新增 `EquipmentSingleSourceGuardTest`（全量逐条一致 + 分类视图完全划分 + 按槽位/品阶/名称一致 + **源码扫描禁止再写模板字面量**） |
| **D9 codegen 非幂等** | 生成函数缺 `operator==`／`*TemplatesMutable()`／只读入口／KDoc 输出，而已提交头文件含这 51 行 ⇒ 重跑生成器会删掉 `data_inject.h` 依赖的注入入口 | 生成器**补全全部输出**使其幂等；新增 `TemplateCodegenIntegrityGuardTest` |
| **D10 生成器死代码** | 3 个提取函数 + 4 个正则零调用；文件头 KDoc 谎称"从 Kotlin Registry 源码提取" | 删死代码；重写 KDoc 为真实链路；同一守卫断言死符号不复活 |

**验收口径**：`node scripts/gen-templates.mjs` 后 `git diff` 必须只含**本方案有意变更**的文件；不得出现"删掉注入基础设施"的差异（写入 §6 门禁 G0）。

---

## 四、影响范围清单

> 格式：`文件路径 — 变更类型 — 变更说明`；标签：`C++` / `Kotlin` / `静态数据` / `存档` / `经济` / `iOS` / `隐私合规`。
> WP 是同一交付内的**并行切分**，不是分期发布（边界见 `rules/design-plan-review.md` 原则 3）；执行侧的批次编排见 `equipment-batches/IMPLEMENTATION-BATCHES.md`。

### WP0 存档编号规划与冻结守卫（轻量，非阻塞前置）

> **纠正说明**：初版方案把"补齐全部存档类 `@ProtoNumber`"列为最高优先前置，依据是"`Disciple`/`EquipmentSet` 无显式编号"。实测该依据**不成立**——`Disciple` 走 `DiscipleSerializer` 的扁平代理，装备段编号全部显式（§2.8）。故 WP0 收缩为轻量编排。

| 文件 | 变更 | 说明 |
|---|---|---|
| `DiscipleSerializer.kt` | 改 | `DiscipleSurrogate`：新增 `headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)`（按显示序）+ `innateDamageType(117)`；**复用** `weaponId(17)` → 武器部位；退役 `accessoryId(20)/armorId(18)/bootsId(19)`、`weaponNurture(24..27)`、`equipmentNurturingCompletion*`(98/99)、`pillNurtureSpeedBonus(47)` 就地注释 `reserved` |
| `SaveData.kt` | 改 | `equipmentStacks(53)` 删除 → 注释 `reserved` |
| `EquipmentProtoNumberFrozenTest.kt` | **新增** | 冻结表守卫：属性→编号 与冻结表一致；新增须登记、退役不得复活、`reserved` 不得被占用；错误消息带操作指引 |
| 隐式编号的 ~20 个子消息类 | **不改** | 独立债 I8 |

### WP1 领域模型与静态数据

| 文件 | 变更 | 说明 |
|---|---|---|
| `core/model/Items.kt` | 改 | `EquipmentSlot` 4→6 值（编号 10..15）；删 `EquipmentStack`；重写 `EquipmentInstance`（setId/part/growth/meta，9 构造参数） |
| `core/model/EquipStat.kt` | **新增** | `EquipStat`（8 维度）+ `EquipStatValue` |
| `core/model/EquipAffix.kt` | **新增** | `EquipAffixSet` / `EquipInstanceMeta` / `EquipGrowth` / `EquipLevelCurve` |
| `core/model/DiscipleComponents.kt` | 改 | `EquipmentSet`：4 id → 6 id（`headId/bodyId/handsId/feetId/weaponId/legsId`），删 4 个 nurture 字段；`PillEffects.pillNurtureSpeedBonus` 退役 |
| `core/model/Disciple.kt` | 改 | 删 `EquipmentNurtureData`；删 `equipmentNurturingCompletion*`；`ItemEffect` 3/8 退役 |
| `core/registry/EquipmentDatabase.kt` | **重写** | 12 条套装部件模板（含 §3.4.2 部位主词条池）+ 品阶展开 |
| `core/registry/EquipmentRegistry.kt` | **降级为纯转发层** | D1：数据面清零 |
| `core/registry/EquipmentSetDatabase.kt` | **新增** | 2 套 × 3 档效果（`EquipSchool`/`SetBonus`/`EquipmentSetDef`） |
| `core/registry/EquipMainStatPool.kt` | **新增** | 部位 → 主词条候选池的唯一实现 |
| `core/registry/EquipAffixPool.kt` | **新增** | 7 项池 + 权重 + 品阶档位值 + 不放回抽取 |
| `core/registry/ForgeRecipeDatabase.kt` | **重写** | 73 条 → 12 条 |
| `core/registry/ForgeRecipeRegistry.kt` | 改 | 改用新 `EquipmentDatabase` + `EquipmentFactory` |
| `core/engine/domain/EquipmentFactory.kt` | **新增** | `create(setId, part, rarity, rng)`：品阶门槛 + 抽主词条/副词条 + 生成实例（唯一产出入口） |
| `scripts/data/equipment_db_sample.json` | **重写** | 12 部件 × 6 品阶 = 72 条 + 2 套装定义 |
| `scripts/gen-templates.mjs` | 改 | 提取新中性源；产出 4 个 C++ 表 + 快照；**同批清偿 D9/D10**（补全 `operator==`/`*TemplatesMutable()`/只读入口/KDoc，删死正则，重写文件头） |
| `gamecore/data/equipment_db.h` / `equip_set_db.h` / `equip_main_stat_db.h` / `equip_affix_db.h` | 生成 | 禁手改 |
| `assets/data/game-data.json` | 生成 | `db.equipment` 段重生成 |
| `TemplateCodegenIntegrityGuardTest.kt` | **新增** | D9/D10 回归门禁 |

### WP2 存档与迁移

| 文件 | 变更 | 说明 |
|---|---|---|
| `local/GameDatabase.kt` | 改 | `DATABASE_VERSION` 递增（B1→60 / B2→61 / B3→62，按批取号）；实体表删除/重建；迁移注册 |
| `local/GameDatabaseMigrationsV<N>.kt`（**N 按 §5.1 规则取**） | **新增** | 依次为：属性单列化（4 列→3 列）；孕养丹列删除；`DROP equipment_stacks` + `DROP+CREATE equipment_instances` + `disciples` 8 列删/5 列增 + 清空旧行 |
| `local/EquipmentDaos.kt` | 改 | 删 `EquipmentStackDao`；`EquipmentInstanceDao` 索引/列更新 |
| `local/JsonConverters.kt` | 改 | `toEquipmentSlot` 回退改 `HEAD` + `Log.w`（D6）；新增 `EquipGrowth`/`EquipInstanceMeta`/`EquipAffixSet`/`EquipStatValue` 转换器 |
| `serialization/unified/SaveDataReconciler.kt` | 改 | 删堆叠重建分支，只保留功法 |
| `serialization/backwardcompat/OldSaveFormatDeserializer.kt` | 改 | 旧 `equipment` 字段一律丢弃，输出空 `equipmentInstances` |
| `integrity/rules/EquipmentRefRule.kt` / `EquipmentDedupeRule.kt` | **重写** | 六部位引用校验/去重 |
| `integrity/rules/EquipmentValueSanitizeRule.kt` | **新增** | 等级 1..30 / exp 范围 / subStats 恒 3 条去重 / subRolls 范围 / 词条枚举合法 |
| `integrity/rules/NurturePillRetirementRule.kt` | **新增** | R11 补偿规则（幂等标记同事务） |
| `integrity/rules/EntityCountBoundsRule.kt` | 改 | 装备阈值改实例条数 + **只告警不截断**（D7/S18） |
| `app/schemas/**/{60,61,62}.json` | 生成+提交 | Room schema |
| `RoomMigrationV{59→60,60→61,61→62}Test.kt` + `RoomMigrationTest` | **新增/改** | 迁移集成测试与全链升级 |

### WP3 Kotlin 引擎

| 文件 | 变更 | 说明 |
|---|---|---|
| `engine/EquipmentNurtureSystem.kt` / `service/EquipmentNurtureService.kt` | **删除** | 被 `EquipmentLevelSystem` / `EquipmentUpgradeService` 取代 |
| `engine/EquipmentLevelSystem.kt` | **新增** | 等级/经验/主词条成长/强化节点（Kotlin 回退臂） |
| `engine/service/EquipmentUpgradeService.kt` | **新增** | 升级/分解事务（`@GameService`） |
| `domain/disciple/EquipStatResolver.kt` | **新增** | 词条 + 套装效果 → `EquipBonus`（唯一结算入口） |
| `domain/disciple/DiscipleEquipmentService.kt` / `DiscipleEquipmentManager.kt` | **重写** | 删堆叠轨道、六部位、单轨实例、等级随实例 |
| `domain/disciple/DiscipleStatCalculator属性Ops3.kt` / `属性Ops4.kt` | 改 | 装备段改由 `EquipStatResolver` 供值；六部位列举；热点列直读改造；**属性单列化** |
| `domain/battle/BattleModels.kt` / `BattleSystem.kt` / `BattleSystem战斗Ops1.kt` / `HeavenlyTrialService.kt` / `EnemyGenerator.kt` / `AISectAttackManager.kt` / `BattleJsonCodec.kt` | 改 | 8 处构造点补 `critDamageBonus/physicalDamageBonus/magicDamageBonus`；类型判定改 `innateDamageType`/技能；JSON 编解码同步 |
| `util/BattleCalculator.kt` / `BattleCalculatorCombatOps.kt` | 改 | 暴击伤害 + 类型增伤/减伤分桶（清偿 D3）；攻防取单列 |
| `GameEngineManualOps.kt` | 改 | 新增 `upgradeEquipment` / `dismantleEquipment`（C++ 真相先行 + Kotlin 回退） |
| `domain/inventory/InventoryFacadeImpl*.kt` / `system/InventorySystem*.kt` | 改 | 产出改调 `EquipmentFactory`；删堆叠合并/分块/溢出分支 |
| `state/CaptiveGearUtils.kt` | 改 | 六部位落库 + 确定性 roll 词条 |
| 产出链 10 处（`AutoBuyService`/`MerchantItemConverter`/`DiplomacyService`/`MailAttachmentDistributeOps`/`RedeemCodeService`/`SecretRealm*`/`HeavenlyTrial*`/`MissionSystemRewardOps`/`EnemyGenerator`） | 改 | 全改 `EquipmentFactory` |
| `state/DiscipleTables*.kt` / `GameStateStore.kt` / `MutableGameState.kt` / `UnifiedGameState.kt` / `GameStateSnapshotProvider.kt` / `StackKeys.kt` | 改 | 六槽位列；删 `equipmentStacks`；删 nurture 列；删 `StackKeys.equipment` |
| 孕养丹应用链（`PillEffectApplier`/`AutoPillService`/`HpMpRecoveryService`/`DiscipleFacadeImpl战斗Ops2`/`CultivationCore`/`NumericSanitizeRule`） | 改 | R11：删判据与写入点 |
| `util/RngPartition.kt` | 改 | 新增 `EQUIPMENT(13)` |
| `nativebridge/ActionIds.kt` | 改 | 新增 `EQUIP_UPGRADE` / `EQUIP_DISMANTLE`（codegen 生成） |
| `nativebridge/StateSyncService.kt` / `GameViewMirrorCodec.kt` / `gameview/GameViewDiscipleRows.kt` | 改 | 镜像协议：六槽位 + 实例新字段；删 `equipmentStacks` |
| `core/engine/SectCombatPowerCalculator.kt` | 改 | 战力权重表按单列重标（决定 40% 占比口径） |
| `XianxiaApplication.kt` | 改 | DI 装配点更新 |

### WP4 C++ 引擎

| 文件 | 变更 | 说明 |
|---|---|---|
| `state/models.h` | 改 | `EquipmentInstance` 重定义；删孕养相关；顶层删 `equipmentStacks`；属性单列化；`innateDamageType` |
| `state/disciple_store.h` / `state/column_dirty.h` | 改 | 6 槽位列；删 nurture/pillNurture 列；脏列映射同步 |
| `system/equip_affix.h` / `equip_set_bonus.h` / `equip_main_stat.h` / `equipment_tx.h` | **新增** | 词条/套装/部位池/升级分解事务 |
| `system/disciple_tx.h` | 重写 | 单轨六部位穿卸 |
| `system/auto_gear.h` | 重写 | 自动装备新比较键 |
| `system/disciple_stats.h` | 改写 | `EquipBonus` 求和 + 乘区 + 单列 |
| `system/battle.h` / `battle_calculator.h` / `battle_json.h` | 改 | 三新字段 + 类型桶 + 单列攻防 |
| `system/ai_sect_ops.h` / `ai_sect_recruit.h` | 改 | AI 侧 (pieceId, rarity, level) |
| `system/inventory.h` | 改 | 删 `equipmentKey`；槽位计数去装备堆叠 |
| `system/nurture_constants.h` | **删除** | 熟练度常量迁 `manual_constants.h` |
| `data/recipe_db.h` | 改 | R11：删两类配方生成/分类/映射/类型清单 |
| `system/pill_system.h` / `disciple_tx.h` / `models.h` / `merchant_settlement.h` / `secret_realm_session.h` / `data/data_json.h` | 改 | R11：删判据与写入点 |
| `data/beast_config.h` / 角色模板 codegen | 改 | 单列 + `innateDamageType` |
| `system/production.h` / `merchant_settlement.h` / `secret_realm_session.h` / `mission_completion.h` / `exploration_tx.h` / `disciple_purchase.h` / `instance_buckets.h` / `death_handler.h` | 改 | 产出点与六部位 |
| `src/json_codec.cpp` / `src/gameview_encode.cpp` / `src/execute_dispatch.cpp` / `src/game_core.cpp` / `src/disciple_store.cpp` | 改 | 编解码 + 分发 + ActionId |
| `rng/rng_manager.h` | 改 | 新增 `kEquipment = 13` + 播种 |
| `include/gamecore/data/data_inject.h` / `data_json.h` / `data_store.h` | 改 | 新静态表注入 |
| `test/*.cpp` | 改/新增 | §6.3 清单 |

### WP5 UI

| 文件 | 变更 | 说明 |
|---|---|---|
| `components/detail/DetailEquipmentSection.kt` | 重写 | 六宫格 + 六部位选择弹窗 |
| `components/detail/EquipmentSetBonusPanel.kt` | **新增** | 套装 2/4/6 进度与效果 |
| `components/detail/EquipmentUpgradeDialog.kt` / `EquipmentDismantleDialog.kt` | **新增** | 升级 / 分解界面（升级界面首次打开含一次性说明气泡） |
| `components/detail/DetailCombatSection.kt` / `DetailBasicInfoSection.kt` / `DiscipleDetailScreen.kt` | 改 | 六部位签名（`pieces: Map<EquipmentSlot, EquipmentInstance>`）+ 面板四行→两行 + 固有属性标签 |
| `components/ItemDetailDialog.kt` / `ItemDetailEffects.kt` / `ItemDetailOtherEffects.kt` / `ItemWatchKeys.kt` | 改 | 删孕养进度；词条/套装展示 |
| `components/detail/ReplaceSelectionData.kt` | 改 | 六部位筛选、套装/等级/词条摘要 |
| `tabs/WarehouseTab.kt` / `tabs/WarehouseBulkSellDialog.kt` / `tabs/EquipmentSection.kt` | 改 | 去数量角标；实例网格 + 筛选 + 批量分解 |
| `dialogs/ForgeDialog.kt` / `MerchantDialog.kt` / `MerchantInventoryDialog.kt` / `MerchantListingDialog.kt` / `AutoBuyDialog.kt` / `SecretRealmBackpackDialog.kt` | 改 | 12 配方 + 套装部件展示 |
| `delegate/InventoryDelegate.kt` / `MerchantOpsDelegate.kt` / `DiscipleDelegateGearOps.kt` | 改 | 升级/分解转发 |
| `GameViewModel.kt` | 改 | 删 `equipmentStacks` 暴露；新增升级/分解入口 |
| `core/ui/components/EquipmentSprite.kt` | 改 | 套装部件图解析（按 setId+part） |
| `android/scripts/resource-registry.json` + `模拟宗门美术素材/装备/` | 改 | 12 张新图 + 旧图归档 |
| `ui/game/SaveLoadViewModelLoadOps.kt` + 登录/读档流程 | 改 | **存档版本高于客户端**时给出明确文案提示（替代笼统"存档损坏"） |

### WP6 测试、文档与发布

| 文件 | 变更 | 说明 |
|---|---|---|
| 217 个装备相关测试文件 | 改 | 逐文件对齐（含旧用例处置表） |
| `CHANGELOG.md` + `assets/changelog_entries.json` | 改 | **两个更新日志必须一起更新** |
| `docs/knowledge-base.md` | 改 | 经济基线表新增升级/分解/两笔补偿；子系统索引 |
| `docs/architecture.md` | 改 | 装备与属性体系描述；债 D/I 系列登记 |
| `docs/ui-read-surface.md` §2 | 改 | **先登记再实现** |
| `docs/adr/equipment-set-system.md` | **新增** | ADR（含"单列属性"与"删堆叠"两项决策） |
| `CODE_WIKI.md` | 改 | 装备模块入口 |
| `docs/design/gacha-batches/m0-economic-whitepaper.md` 一脉口径 | 改 | R11 经济影响审计（补偿总额 / 产出缺口 / 新汇） |

### WP7 需求盲区落地（§0.2 的代码面）

| 文件 | 变更 | 说明 | 标签 |
|---|---|---|---|
| `core/util/ItemSortUtils.kt` | 改 | **0.2-1**：`watchKey("equipment", ...)` 名称→**实例 id** | `Kotlin` |
| `core/model/GameData.kt` + `GameDataFieldModels.kt` | 改 | **0.2-1**：`watchedItemIds` 旧名称键清空 | `Kotlin` `存档` |
| `core/config/InventoryConfig.kt` | 改 | **0.2-2**：删 `equipment_stack=999`；装备实例**不设上限** | `Kotlin` `经济` |
| `engine/SectWarehouseManager.kt` | 改 | **0.2-2**：`itemId` → 实例 id | `Kotlin` |
| `engine/system/InventorySystemHasOps6.kt` | 改 | **0.2-2**：槽位计数去堆叠口径 | `Kotlin` |
| `data/integrity/rules/EntityCountBoundsRule.kt` | 改 | **0.2-2/D7**：装备**只告警不截断** | `Kotlin` `存档` |
| `engine/service/OverflowMailSender.kt` | 改 | **0.2-2**：删装备溢出分支 | `Kotlin` |
| `domain/battle/BattleModels.kt` + `BattleJsonCodec.kt` + `BattleSystem*` + C++ `battle.h`/`battle_json.h` | 改 | **0.2-12**：4 个具名装备字段 → 六部位表示（对拍基线重录） | `Kotlin` `C++` |
| 数值：§3.7 基数表 + §3.6 消耗表 | 改 | **0.2-3/0.2-6**：目标 40%±5% 与 1 个月产出；解整体系数 `k` | `静态数据` `经济` |
| 品阶产出分布 | 改 | **0.2-5**：受境界约束（单点在 `EquipmentFactory.create`） | `Kotlin` `静态数据` |
| `EquipmentSetDef` 乘区叠加规则 | 改 | **0.2-7**：同类百分比相加 + 总上限 | `静态数据` |
| AI 侧 | 改 | **0.2-9**：AI 口径 + 宗门战难度回归 | `C++` `Kotlin` |
| `EquipmentSprite*` + `resource-registry.json` | 改 | **0.2-10**：程序化占位兜底 | `Kotlin` |
| `EquipmentUpgradeDialog` 首次气泡 | 改 | **0.2-11**：最小 FTUE | `Kotlin` |
| `CloudPayloadSizeBenchTest` / `ui-read-surface.md` | 改 | **0.2-13**：体积与镜像载荷实测 + 先登记 | `Kotlin` `存档` |
| 死亡/逐出/俘虏三路径 | 改 | **0.2-14**：4 件 → 6 件物化同步 | `Kotlin` `C++` |

### WP8 属性机制重构（§15，与装备同批交付）

> 改动面最大的一批：弟子属性、战斗公式、存档、C++ 对拍、面板、妖兽、丹药、功法结算与战力计算。

| 文件 | 变更 | 说明 | 标签 |
|---|---|---|---|
| `core/model/DiscipleComponents.kt` / `Disciple.kt` | 改 | `CombatAttributes`：4 个 `base*` → `baseAttack/baseDefense`；4 variance → 2；`DiscipleStats` 四列 → 两列；`ItemEffect` 丹药 4 个 Add → `attackAdd/defenseAdd` | `Kotlin` `存档` |
| `core/model/CharacterTemplate.kt` + `scripts/gen-*.mjs` | 改 | 角色模板新增 `innateDamageType`（Q1） | `Kotlin` `C++` `静态数据` |
| `DiscipleSerializer.kt` | 改 | 60–73 段退役 4 个/新增 3 个（含 `innateDamageType(117)`） | `Kotlin` `存档` |
| `GameDatabase.kt` + 新迁移文件（`GameDatabaseMigrationsV<N>.kt`） | 改/新增 | `disciples` 4 列删 + 3 列增；**迁移按 Q7 取和 × k 回填** | `Kotlin` `存档` |
| `DiscipleStatCalculator*.kt` | 改 | 基础生成、装备/功法/丹药结算改单列；**功法/丹药双列在结算层相加**（Q2） | `Kotlin` |
| `BattleCalculator*.kt` + `DamageZones` | 改 | 单列攻防；`isPhysical` 改固有属性/技能；类型增伤/减伤分桶；`DamageZones.damageReduction` 拆两桶 | `Kotlin` |
| `SectCombatPowerCalculator.kt` | 改 | 战力权重表按单列重标 | `Kotlin` |
| `EnemyGenerator.kt` / `AISectAttackManager.kt` / `HeavenlyTrial*` | 改 | 构造点全部改单列 + 固有属性 | `Kotlin` |
| C++ `disciple.h` / `disciple_stats.h` / `battle*.h` / `state/models.h` / `disciple_store.h` / `column_dirty.h` / `json_codec.cpp` / `data/beast_config.h` | 改 | 单列 + 类型桶 + `innateDamageType` | `C++` |
| 全部 `Diff*Test`（Battle / BattleExecution / SectBattle / MissionSettlement…） | 改 | 攻防字段与伤害数值基线**一次性重录** | `Kotlin` `C++` |
| 弟子面板 | 改 | 四行 → 两行 + 固有属性标签 + 类型伤害加成 | `Kotlin` |
| `docs/knowledge-base.md` / `architecture.md` / `cpp-engine.md` | 改 | 属性与伤害公式描述更新 | — |

---

## 五、兼容性分析

### 5.1 Room 迁移（三批各自递增；映射为计划值）

```sql
-- MIGRATION_<N-1>_<N>（B1 属性单列化；DDL 逐字抄对应版本 schema JSON）
ALTER TABLE `disciples` ADD COLUMN `baseAttack` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `disciples` ADD COLUMN `baseDefense` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `disciples` ADD COLUMN `innateDamageType` TEXT NOT NULL DEFAULT 'PHYSICAL';
UPDATE `disciples` SET `baseAttack` = `basePhysicalAttack` + `baseMagicAttack`,
                       `baseDefense` = `basePhysicalDefense` + `baseMagicDefense`;
db.safeDropColumns("disciples", "basePhysicalAttack","baseMagicAttack",
                   "basePhysicalDefense","baseMagicDefense",
                   "physicalAttackVariance","magicAttackVariance",
                   "physicalDefenseVariance","magicDefenseVariance");

-- MIGRATION_<N>_<N+1>（B2 孕养丹退役）
db.safeDropColumns("disciples", "pillNurtureSpeedBonus");

-- MIGRATION_<N+1>_<N+2>（B3 装备体系）
DROP TABLE IF EXISTS `equipment_stacks`;
DROP TABLE IF EXISTS `equipment_instances`;   -- 重建为空表（旧装备全部作废）
CREATE TABLE IF NOT EXISTS `equipment_instances` (
  `id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL DEFAULT 0,
  `name` TEXT NOT NULL DEFAULT '', `setId` TEXT NOT NULL DEFAULT '',
  `part` TEXT NOT NULL DEFAULT 'HEAD', `growth` TEXT NOT NULL,
  `meta` TEXT NOT NULL, `ownerId` TEXT, `isEquipped` INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(`id`, `slot_id`)
);
-- 弟子：装备部位 4 → 6（旧 WEAPON/ARMOR/BOOTS/ACCESSORY → 新 HEAD/BODY/HANDS/FEET/WEAPON/LEGS）
-- 复用既有列：`weaponId` → 武器部位（旧档该列存旧体系 id，迁移期清空 ⇒ 复用无歧义）
-- 其余 5 个部位为新增列；旧 `accessoryId` 随"饰品位 → 腿部"的调整一并退役
ALTER TABLE `disciples` ADD COLUMN `headId` TEXT NOT NULL DEFAULT '';
ALTER TABLE `disciples` ADD COLUMN `bodyId` TEXT NOT NULL DEFAULT '';
ALTER TABLE `disciples` ADD COLUMN `handsId` TEXT NOT NULL DEFAULT '';
ALTER TABLE `disciples` ADD COLUMN `feetId` TEXT NOT NULL DEFAULT '';
ALTER TABLE `disciples` ADD COLUMN `legsId` TEXT NOT NULL DEFAULT '';
-- 清空六个部位列（含复用的 weaponId 与旧 id 列）
UPDATE `disciples` SET `weaponId`='', `headId`='', `bodyId`='', `handsId`='', `feetId`='', `legsId`='';
db.safeDropColumns("disciples",
  "accessoryId","armorId","bootsId","weaponNurture","armorNurture","bootsNurture","accessoryNurture",
  "equipmentNurturingCompletionMonth","equipmentNurturingCompletionPhase");
```

- 遵守 `rules/database-migration.md`：**禁 `ALTER TABLE DROP COLUMN`**，统一 `db.safeDropColumns`；新列带 `DEFAULT`；schema JSON 提交；每批迁移都有集成测试。
- `DROP TABLE + CREATE TABLE` 属 schema 重建（先例：`GameDatabaseMigrationsV39.kt`、`V21ToV30.kt`）。**旧装备数据作废是 R2 的既定结果**，不是迁移缺陷。
- **🔴 迁移取号规则（唯一口径）**：**不使用绝对版本号**——每批以合入时刻的 `GameDatabaseConfig.DATABASE_VERSION` **实际值 +1** 取号，类名/测试名随之（`GameDatabaseMigrationsV<N>.kt` / `RoomMigrationV<N-1>To<N>Test`）；跳过或延后的批顺延，**禁预占**。**当前实测 61** ⇒ 计划 `B1 = v62 / B2 = v63 / B3 = v64`（开工时须再复核一次实际值）。

### 5.2 迁移前后的旧数据处置

| 旧数据 | 处置 |
|---|---|
| `equipment_stacks` 全部行 | 丢弃；按 `basePrice` 折算补偿（§5.4） |
| `equipment_instances` 全部行 | 丢弃；按 `basePrice × (1 + 0.5 × 孕养等级/品阶满级)` 补偿 |
| 弟子 4 个槽位 id | 清空 |
| 弟子 4 个 nurture 数据 | 丢弃（孕养是时间累积，无玩家投入物） |
| 弟子储物袋内装备条目 | 折算补偿后移除条目 |
| 邮件附件 `type="equipment"` 未领取 | 折算补偿后清空该附件 |
| 秘境背包 / 世界战斗待领取装备 | 折算补偿 |
| AI 弟子装备 | 下线为"无装备"（AI 无资产概念，不补偿） |
| 弟子 4 个 `base*` 属性列 | **不丢弃**——按 §15.7 Q7 **取两列之和 × k** 迁移到 `baseAttack/baseDefense` |
| 孕养丹（物品/配方/临时效果） | 按 §5.7 处置 |

### 5.3 ProtoBuf 存档兼容

| 项 | 处置 |
|---|---|
| 编号占用实况（实测） | `DiscipleSurrogate` 已用 `1–6,9,10,17–21,23–28,30–49,51–75,77–87,89–91,94–109,111`；已 `reserved` `7,8,11–16,22,29,50,76,88,93,102,104,105,110`。**新增取 112–117** |
| `DiscipleSurrogate` 装备段 | **新增** `headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)` + `innateDamageType(117)`；**复用** `weaponId(17)` → 武器部位（旧值由迁移清空）；**退役** `accessoryId(20)/armorId(18)/bootsId(19)` + `weaponNurture(24..27)` + `equipmentNurturingCompletion*(98,99)` + `pillNurtureSpeedBonus(47)` ⇒ 就地 `reserved`（保留声明维持旧档可读，值由迁移清空） |
| `DiscipleSurrogate` 属性段 | 新增 `baseAttack/baseDefense/innateDamageType(117)`；退役 `basePhysicalAttack(69)/baseMagicAttack(70)/basePhysicalDefense(71)/baseMagicDefense(72)` 与 4 个 variance(62–66) 中的两个属性方差 ⇒ `reserved` |
| `SaveData.equipmentStacks`(53) | 删除字段，编号 **reserved** |
| `SaveData.equipmentInstances`(5) | 编号不变，类型换为新 `EquipmentInstance`；旧档该列表在迁移期清空 ⇒ 无需旧结构反序列化 |
| `SaveData.stacksSerialized`(55) | 保留（语义收窄为"功法堆叠已序列化"） |
| `EquipmentInstance` | 新增 `setId(60)/part(61)/growth(62)/meta(63)`；保留 `id(1)/slotId(100)/name(2)/ownerId(16)/isEquipped(11)`；退役 `slot(3)/rarity(4)/description(7)/critChance(10)/nurtureLevel(13)/nurtureProgress(14)/minRealm(15)/面板属性(50–56)` |
| 新消息类型 | `EquipGrowth`/`EquipAffixSet`/`EquipInstanceMeta`/`EquipStatValue` 全新，编号自 1 起 |
| `EquipmentNurtureData` 类型 | 保留定义并标 `@Deprecated`（`DiscipleSurrogate` 旧 nurture 字段仍引用以保证旧档可反序列化），不参与新逻辑 |
| `ItemEffect` 的 `nurtureSpeedPercent(3)`/`nurtureAdd(8)` | **退役**（R11）：`reserved` 禁复用；类型保留声明但**不再有读取点** |
| 退役编号登记（汇总） | `DiscipleSurrogate` **18/19/20**（armorId/bootsId/accessoryId）24–27/47/69–72/98/99（+ 属性方差 62–66 中两项）；`EquipmentInstance` 3/4/7/10/13/14/15/50–56；`ItemEffect` 3/8；`SaveData` 53；`EquipmentSlot` 枚举 0–3。**复用（非退役）**：`weaponId(17)` → 武器部位（旧值由迁移清空，故复用无歧义） |
| 隐式编号的 ~20 个子消息类 | 本方案不动；独立债 I8 |

### 5.4 旧装备折算补偿（**已拍板：默认口径**）

| 资产 | 补偿 |
|---|---|
| 装备堆叠（仓库） | 每件按 `EquipmentStack.basePrice` 的 **100%** 折算灵石 |
| 已装备/储物袋内实例 | 每件按 `basePrice × (1 + 0.5 × 已孕养等级/品阶满级)` 折算 |
| 未领取的装备邮件附件 | 同"堆叠"口径 |
| 秘境背包 / 战斗待领取 | 同"堆叠"口径 |
| 孕养类加成丹药（R11） | 见 §5.7（单独口径、单独幂等标记，不与本节混发） |

- 包裹 `withTrackingSource("equipment_legacy_compensation")`，来源名加入 `OverflowMailSender.SOURCE_DISPLAY_NAMES`；**凭据类语义** ⇒ 包 `withOverflowMailSuppressed`。
- 补偿邮件在迁移后**首次进入游戏**时由确定性逻辑生成（幂等：`GameData.legacyEquipmentCompensated` 布尔标记落库，与发放**同事务**）。
- 单档上限 **1 亿灵石**，超出按比例截断并记日志。

### 5.5 向后兼容与回滚

| 场景 | 行为 |
|---|---|
| 新存档在旧版本 App 打开 | 旧 App 不知道新列 → Room 降级校验失败 → 走"迁移前备份恢复"（`GameDatabase.kt:113-121` 既有机制）。**必须在更新日志与登录流程明确"不可回退"** |
| 迁移中断（杀进程） | 三层防御自动覆盖（迁移前备份 / 启动验证恢复 / 迁移链守卫） |
| 上线后发现问题 | **无开关可关**（深度数据重构）：兜底 = 迁移前备份 + 云存档 + 强制更新。登记明示债 **I1** |
| 跨设备（一台已更新一台未更新） | 未更新设备拒绝打开并按备份恢复，可能表现为"进度回退" ⇒ 登录流程加"存档版本高于客户端"的明确文案（替代笼统"存档损坏"） |
| JSON / `GameView` 快照 | 字段名与 kotlinx 一致；C++ 侧导出按"非空/非零才导出"，与 `encodeDefaults=false` 对称 |

### 5.6 隐私合规

**不涉及**：无新增 SDK、权限、网络请求、个人数据收集、数据共享、广告/分析模块变更 ⇒ **不需要**更新 `PrivacyConsentScreen.kt` 与 `docs/index.html`（`rules/design-plan-review.md` 原则 5 已核对）。

### 5.7 孕养类加成丹药退役补偿（R11 执行细则）

| 资产 | 处置 |
|---|---|
| 仓库/储物袋/邮件附件中已持有的孕养丹 | 按**丹药原价 100%** 折算灵石（价格取自模板；模板同时被删，故折算表由静态数据快照提供，不依赖运行时模板） |
| 弟子身上正在生效的孕养丹临时效果 | 直接清空（`pillNurtureSpeedBonus` 列迁移置 0），不补偿 |
| 未领取的含孕养丹奖励（邮件/兑换码） | 从奖励池移除该物品；已生成未领取的按上表折算 |
| 已解锁的孕养丹**配方** | 从 `unlockedRecipes` 移除对应 id（否则配方页出现无法炼制的死条目） |

- 补偿走邮件，包裹 `withTrackingSource("nurture_pill_retirement")`，来源名加入 `SOURCE_DISPLAY_NAMES`，**凭据类语义** ⇒ 包 `withOverflowMailSuppressed`。
- 幂等：`GameData.nurturePillsRetired` 布尔标记（Proto 预留段编号）与发放在**同一事务**内完成。
- 审计：补偿总额登记经济基线表；单档上限 **2000 万灵石**，超出按比例截断并记日志。

---

## 六、测试方案

### 6.1 新增/重写的单元测试（Kotlin）

| 测试类 | 模块 | 覆盖 | 关键用例 |
|---|---|---|---|
| `EquipAffixRollTest` | `:core:domain` | 7 项池与抽取 | 恒 3 条、互不重复、**权重分布 13/13/14/15/15/15/15**、档位值单调、池内不含速度/灵力 |
| `EquipMainStatPoolTest` | `:core:domain` | 部位池 | 逐部位断言取值 ∈ 该部位池（头 2/身 4/手 3/脚 5/腿 5/饰 3）；顺序稳定 |
| `EquipmentSetDatabaseTest` | `:core:domain` | 套装表 | 2 套 × 3 档；门槛不越级；2 件套类型伤害差异化 |
| `EquipmentSingleSourceGuardTest` | `:core:domain` | **D1 守卫** | Registry 与 Database 全量逐条一致、分类视图完全划分、**源码扫描禁止再写模板字面量** |
| `EquipmentSlotOrderGuardTest` | `:core:domain` | 六部位枚举 | `displayOrder` 覆盖全部且**与声明序一致**；`@ProtoNumber` 无重复 |
| `EquipmentLevelSystemTest` | `:core:engine` | 等级/经验 | Lv1 初始、30 封顶、30 级后不获经验、溢出清零、`expRequired` 单调、品阶倍率 |
| `EquipmentLevelPersistGuardTest` | `:core:engine` | **R5 根因守卫** | 穿→卸→穿往返后 `level/exp/subStats/subRolls` 逐位不变；仓库/储物袋两条来源均成立 |
| `EquipmentSetBonusTest` | `:core:engine` | 套装 2/4/6 | 0–6 件逐一断言；两套混穿按各自件数独立计；同套重复部位不重复计件 |
| `EquipStatResolverTest` | `:core:engine` | 加成汇合 | flat 求和、`ATTACK_PCT` 乘区口径、类型加成/暴击伤害分项、空装备/未知 setId/损坏词条 |
| `EquipmentUpgradeServiceTest` | `:core:engine` | 升级/分解 | 材料不足失败（不扣材料）、满级失败、`subRolls` 提升落在 3 的倍数、分解返还公式、`isLocked`/已装备拒分解 |
| `EquipmentPowerParityTest` | `:core:engine` | 数值与占比（S9/S16） | **装备贡献 ∈ 总战力 [35%,45%]**（含套装）；**按维度拆分**（速度/灵力单列） |
| `EquipmentEconomyCalibrationTest` | `:core:engine` | 经济校准（S16） | 一套满级消耗 ÷ 月产出 ∈ [0.75,1.25] |
| `EquipmentRarityGateTest` | `:core:engine` | 品阶门槛（S17） | Lv1 玩家经锻造/掉落/商店拿不到 T6 |
| `EquipmentNoCapGuardTest` | `:core:engine` | 无上限模式（S18） | 只告警不截断；溢出邮件装备分支零调用 |
| `EquipmentLegacyCompensationTest` / `NurturePillRetirementTest` | `:core:engine` | 补偿 | 折算公式、幂等、上限截断 |
| `EquipmentValueSanitizeRuleTest` | `:core:data` | 存档清洗 | 越界 level/exp、重复词条、`subRolls` 越界被修正且幂等 |
| `EquipmentRefRuleTest` / `EquipmentDedupeRuleTest` | `:core:data` | 完整性 | 六部位孤立/重复引用清除 |
| `EquipmentProtoNumberFrozenTest` | `:core:engine` | 编号冻结（S8） | 属性→编号 与冻结表一致；`reserved` 不得被占用 |
| `SingleColumnStatGuardTest` | `:core:engine` | 单列符号面（S19） | `physicalAttack`/`magicAttack`/`physicalDefense`/`magicDefense` 作为属性名归零 |
| `LegacyStatMigrationTest` | `:core:engine` | 旧值映射（S20） | 迁移前后总战力比 ∈ [0.98,1.02] |
| `InnateDamageTypeGuardTest` | `:core:engine` | 固有属性（S21） | 模板覆盖完整；按 `templateId` 回填幂等 |
| `TemplateCodegenIntegrityGuardTest` | `:core:engine` | **D9/D10 守卫** | 生成物含 `operator==`/`*Mutable()`/只读转发；生成器无死符号 |
| `EquipmentSpriteGuardTest` | `:app` | 资源 | 12 部件图全部注册、稀有度回退、无 PNG 直引 |
| `EquipmentStatHotPathBenchmark` | `:core:engine`（`test/bench`） | 每旬热点 | `getMaxHpMpColumn` 装备段 + `EquipBonus` 缓存命中；**不劣化 >10%** |

### 6.2 迁移与存档集成测试

| 测试 | 内容 |
|---|---|
| `RoomMigrationV<N-1>To<N>Test` | 属性 4 列 → 3 列；断言 `baseAttack = 旧物攻+旧法攻`、`innateDamageType` 按模板回填、其余数据零丢失 |
| `RoomMigrationV<N>To<N+1>Test` | 孕养丹列删除；断言列不存在 + 其它数据零丢失 |
| `RoomMigrationV<N+1>To<N+2>Test` | 上一版建库插种子（装备堆叠 3 / 实例 2（含孕养）/ 弟子 4 槽位 + 4 孕养 + 2 checkpoint / 储物袋装备条目 2）→ 跑迁移 → 断言 `equipment_stacks` 不存在、`equipment_instances` 空且结构正确、`disciples` 六槽位全空且旧列消失、其余表零丢失 |
| `RoomMigrationTest`（全链） | v2 → 最新版 + 迁移注册守卫 |
| `ArchivePayloadRoundTripTest` / `CloudPayloadSizeBenchTest` | 新实例字段往返与包体（词条列表放大体积，须复核 I10 阈值） |
| `SaveDataReconcilerTest` | 堆叠重建只剩功法 |
| `OldSaveFormatDeserializerTest` | 旧 `equipment` 字段丢弃不炸 |

### 6.3 C++ GTest（黄金序列）

| 测试 | 内容 |
|---|---|
| `equipment_db_test.cpp`（改） | 新静态表 72 行 + 套装表 + 部位主词条表 + 词条池 ↔ 快照 |
| `equipment_tx_test.cpp`（新） | 升级/分解事务校验链与写段；等级边界 1/29/30/31 |
| `equip_affix_test.cpp`（新） | 与 Kotlin 同种子逐位一致的副词条抽取（含强化节点） |
| `equip_main_stat_test.cpp`（新） | 与 Kotlin 同种子逐位一致的部位主词条抽取 |
| `equip_set_bonus_test.cpp`（新） | 0..6 件档位 |
| `disciple_tx_test.cpp`（重写） | 单轨六部位穿卸、旧装备入袋、等级保真 |
| `single_column_stat_test.cpp`（新） | 单列攻防 + 类型桶默认值等价性 |
| `phase_settlement_test.cpp` / `ai_sect_ops_test.cpp` / `test/bench/*`（改） | 自动装备、AI 装备、每旬热点 |
| `battle_*_test.cpp`（改） | 新三字段与伤害公式 |
| `recipe_db` 孕养丹删除（改） | R11：断言两类配方零产出 |

### 6.4 跨语言对拍（双守护之一，缺一即未完成）

| 测试 | 内容 |
|---|---|
| `DiffEquipmentGenerationTest`（新） | 同种子 `EquipmentFactory` vs C++：setId/part/rarity/level/主词条/3 副词条/档位逐位一致 |
| `DiffEquipmentUpgradeTest`（新） | 升级 30 级全序列 + 10 次强化节点 + 分解返还 |
| `DiffEquipmentSetBonusTest`（新） | 六部位组合 × 套装件数 → `EquipBonus` 逐位 |
| `DiffEquipmentStatTest`（新） | 面板结算（含 `ATTACK_PCT` 乘区与暴击伤害）逐位 |
| `DiffBattle*` / `DiffBattleExecution*` / `DiffSectBattle*`（改） | 单列攻防 + 类型桶基线**一次性重录**（B1） |
| `DiffInventoryTest` / `DiffStateSyncTest` / `DiffExecuteTest` / `DiffStateTest` / `DiffSurfaceAssertion` / `FakeGameStateStore`（改） | 删除 `equipmentStacks` 镜像面 |
| 其余 `Diff*Settlement*` / `DiffSecretRealmTest` / `DiffDeathHandlerTest` / `DiffAuthoritativeTickTest` / `MirrorProtoFeedEquivalenceTest` / `MirrorTypedDiscipleBagGuardTest`（改） | 装备 fixture 改新模型 |

### 6.5 对抗性审查要点（提交前逐条过）

| # | 对抗点 | 期望结论 |
|---|---|---|
| A1 | **Room 按枚举 name 落列**：只有 `WEAPON`（旧 0 / 新 14）新旧同名；已退役 `ARMOR/BOOTS/ACCESSORY` 也在旧行里（`ACCESSORY` 在新枚举中已不存在 ⇒ 解码走 `JsonConverters` 回退分支）⇒ 迁移若漏清行会被当作新部位读出（幽灵件） | 迁移**清空两张装备表 + 六个部位列**（不是仅改列）；`RoomMigrationV61ToV62Test` 断言旧行不存在 |
| A2 | 存档编号：`DiscipleSerializer` 代理是**唯一**进档面（`EquipmentSet` 自身不进档）——改错地方等于没改 | 已实测（§2.8/§5.3）；`EquipmentProtoNumberFrozenTest` 在册 |
| A3 | 百分比乘区插入位置会改变既有对拍基线 | 乘区**只作用于 (base + 装备 flat)**，功法/丹药加法序逐位不变；`DiffPhaseSettlementTest` 全绿 |
| A4 | 词条列表进实例 → 云存档体积放大 | `CloudPayloadSizeBenchTest` 复核；≈250 字节/件，纳入 I10 监控 |
| A5 | 30 级 + 10 次强化每次都要走 RNG；玩家连点是否破坏确定性 | 升级是离散事务（ActionId → C++ 单点推进 RNG），非每帧采样；引擎线程串行化 |
| A6 | 套装件数在"同一件被两弟子引用"时是否重复计 | `EquipmentDedupeRule` 保证独占；解析层只统计**该弟子**六槽位 |
| A7 | 分解已装备/已锁定装备 | 事务校验拒绝 + 测试覆盖 |
| A8 | 从仓库产出时词条 roll 用哪个 RNG 分区 | 一律 `RngPartition.EQUIPMENT(13)`；`RngSourceGuardTest` 拦截未登记分区 |
| A9 | 旧装备美术资源是否被 `resource-registry.json` 残留引用 | `SpriteCodegenSyncTest` / `SpriteSourceMappingGuardTest` 断言 12 项且无孤儿 |
| A10 | `EquipmentRegistry` 数据面删除后 `GameDataManager.equipment` 是否仍有消费者 | 全仓 grep 归零；`ForgeRecipeRegistry` 改用新表 |
| A11 | **R11 删除孕养丹后残留读取点**会把退役字段当有效值用 | 三面归零（Kotlin 写入/清理、C++ 判据/写入、静态配方清单）+ 迁移显式清零 + `NurturePillRetirementTest` |
| A12 | 主词条随机后同名同品阶装备差异进一步扩大 | 已无堆叠；`EquipmentLevelPersistGuardTest` 的"词条逐位保真"覆盖该差异 |
| A13 | **类型通道跨端若一侧分桶、另一侧合并**会静默错配 | `DiffBattle*` 逐位对拍；桶默认 0.0 时与旧公式逐位一致（S19） |
| A14 | 部位池/副词条池的**声明顺序**变化会改写同种子抽取序列 | 顺序稳定用例 + 跨端对拍 |

### 6.6 测试成本核算

| 检查项 | 结论 |
|---|---|
| 墙钟成本 | 新增约 26 个测试类；最大项为抽样类：`EquipAffixRollTest` 1 万次抽取 ≈ 0.2 s（纯计算，**不用 10 万**） |
| 上限约束 | 单个新测试均 < 2 s；三条迁移测试各含建库+迁移 ≈ 3–5 s，远低于 30 s 上限 |
| 确定性偏差 | 守卫类一律单次迭代；仅分布用统计抽样且固定种子 |
| 环境依赖 | `EquipmentProtoNumberFrozenTest` / `StaticDataSingleSourceGuardTest` / `TemplateCodegenIntegrityGuardTest` 依赖仓库根路径（`repoRoot()` + `assumeTrue` 既有模式） |
| 热点性能 | `EquipmentStatHotPathBenchmark` ≈ 1–2 s；门禁为"相对改造前基线不劣化 >10%" |
| 全量预算 | 现有全量 ~10 分钟；本次新增预计 +50 s |

---

## 七、风险评估与兜底

| # | 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|---|
| R1 | **存档装备面编解码落错位置** | 中 | 高 | 已实测落点（§2.8/§5.3）；`EquipmentProtoNumberFrozenTest` + `ArchivePayloadRoundTripTest` |
| R2 | 迁移缺列/少索引致 Room 校验失败 | 中 | 致命 | DDL 逐字抄对应版本 schema（B1→60 / B2→61 / B3→62）；全链迁移测试 + 注册守卫 |
| R3 | C++/Kotlin 词条抽取序列漂移 | 中 | 高 | 定点整数前缀和（禁 double 累加比较）；`DiffEquipmentGenerationTest` |
| R4 | 数值失衡 | 中 | 高 | `EquipmentPowerParityTest` 卡**占比 [35%,45%] 且按维度拆分**；`EquipmentEconomyCalibrationTest` |
| R5 | 实例无界增长 | 中 | 中 | **不设硬上限**：只告警不截断 + 排序筛选批量分解 + 不走溢出邮件；P95 超 1500 触发 I10 |
| R6 | 玩家资产损失投诉 | 中 | 中 | §5.4/§5.7 100% 折算补偿 + 上限 + 双更新日志公告 |
| R7 | 217 个测试文件改造遗漏 | 高 | 中 | 编译期签名变更天然暴露 + 全量 `testReleaseUnitTest` 门禁 + 旧用例处置表 |
| R8 | `nurture_constants.h` 删除影响熟练度常量消费者 | 低 | 中 | 先拆分常量（熟练度迁 `manual_constants.h`）再删文件 |
| R9 | 12 张精灵图缺失 | 低 | 中 | 程序化占位兜底（I6） |
| R10 | 死亡/逐出/没收路径物化遗漏 | 中 | 中 | 三路径测试全绿 + `StorageBagMaterializer` 六部位适配 |
| R11 | 镜像只读面未登记就改 C++ 协议 | 低 | 中 | `docs/ui-read-surface.md` §2 **先登记再实现**；`MirrorReadOnlyGuardTest` |
| R12 | R11 补偿漏发/重复/额度失控 | 中 | 中 | 同事务幂等 + 凭据类溢出语义 + 单档上限 + 经济基线登记 |
| R13 | **速度/灵力塌陷**（装备不再提供）⇒ 出手顺序与灵力池下降 | 高 | 中 | 数值对比**按维度拆分**；备选 B1（脚部池加回速度）/B2（提升基础速度成长）；上线前用战斗日志抽测复核 |
| R14 | 两笔一次性灵石补偿叠加造成短期通胀 | 中 | 中 | 两笔分别设上限（1 亿 / 2000 万）并**同版本合并审计**；超阈值改分月发放 |
| R15 | 主词条随机 ⇒ 价值方差变大，分解/重取循环放大经济波动 | 中 | 低 | 分布断言 + 分解返还 50%（低于重取期望成本）；如波动超预期切 §13-12 |
| R16 | **无保底 + 无上限的组合风险** | 中 | 中 | ① 理想件难获取 ⇒ 分解回收/强化节点/可切过滤池，触发即上 I9；② 实例无界 ⇒ 告警+管理入口，P95 超 1500 上 I10。**两项均不改架构，属可逆决策** |
| R17 | **40% 占比依赖 `SectCombatPowerCalculator` 权重表**；权重设偏会让占比被动变低 | 中 | 中 | 先固定总战力口径再反推基数；`EquipmentPowerParityTest` 直接断言占比区间，口径漂移会被捕获 |

---

## 八、未来场景推演（≥6 个月档）

| 维度 | 必答问题 | 结论 |
|---|---|---|
| **规模增长** | 同类内容 ×10（20 套 × 6 部位 = 120 部件）时成本是否线性？ | 线性。套装效果是数据行（每套 3 条），部件是数据行；无按套/按部位的条件分支代码。词条池扩容只改 `EquipAffixPool` 一行数据 + codegen |
| **生命周期** | 构建/重启/重建/清缓存全周期行为一致？ | 静态表走 codegen 且产物提交 git；无时间戳入 hash；`EquipmentProtoNumberFrozenTest` 保证重建后编号不漂 |
| **平台扩张** | iOS 端是否需重做？ | 引擎逻辑全在 C++ game-core（零 Android 依赖），iOS 直接复用；UI 是 Compose → iOS 需 SwiftUI 重写（与现有全部 UI 同口径，非本方案新增负担）。**无新增平台能力依赖** |
| **运营演进** | 6 个月内调数值/加套装是否要发版？ | 调数值改 `scripts/data/*.json` 后重新构建；**加套装需发版** ⇒ 登记 I2（RemoteConfig 化） |
| **兼容回退** | 上线后能否关闭/回退？ | **不能**（无开关）；兜底 = 迁移前备份 + 云存档 + 强制更新；登记 I1 |
| **词条长期演进** | 未来要加"洗练/重铸/定向"？ | 加成模型已把词条表示为 (stat, value, rolls) 列表，洗练只需替换 `subStats/subRolls` ⇒ **架构无需改动**；登记 I3 |
| **数值乘区扩展** | 未来要加"生命%""元素伤害"？ | `EquipStat` 加枚举值 + 结算单点加一臂；乘区口径设计为"每维度一元"，扩展线性 |
| **套装跨件混搭** | 未来要做"跨套装 2+4"混搭？ | 已支持：套装件数按 setId 独立统计，天然允许 4+2 混搭 |
| **属性模型再演进** | 若未来又回到双列？ | §15.8 末段保留回退口径；`EquipStat` 单列枚举需回退为分列枚举 + 词条池重定（改动集中在 2 张表 + 1 个枚举） |
| **数值结构性现象**（EQ-B4 实测登记，2026-09-30） | 占比带外的结构性出带如何处置？ | 两条**只登记不设断言**（`EquipmentPowerParityTest` 断言面 = 五个品阶入口阶段）：① **T1@炼气 54.6% 越带**——副词条单独已 35.9%，主词条杠杆不可达；若要求入带需动副词条档位表或境界基础属性（超"主词条基数 × k"授权面），作后续数值批的可选杠杆评估；② **深化期衰减**（渡劫 23.0% / 仙人 12.6%）——T6 封顶 × 境界基础属性继续成长的结构结果，出 T7+ 品阶时自然回带，与本章「规模增长」行同口径 |

---

## 九、技术债与偿还计划

| 债项 | 产生原因 | 偿还时机（可判断的触发条件） |
|---|---|---|
| **I1 装备/属性系统无运行时开关** | 深度数据重构，开关意味着两套存储语义并存 | 出现需要 A/B 的方案时；或第二次深度重构需要灰度时 |
| **I2 套装/词条表随包发版** | 静态表走 codegen + 提交 git（确定性前提） | 运营提出"不发版上新套装/调词条权重"时，接 RemoteConfig |
| **I3 无洗练/重铸/定向** | 首期只要求主词条+3 副词条 | 运营提出洗练需求时（加成模型已就绪，纯新增事务） |
| **I4 副词条池只 7 条、无百分比副词条（攻击力% 不进池）** | 避免多重百分比乘区带来的双端对拍风险 | 出现"战斗深度不足"反馈或第 3 套上线需要差异化时 |
| **I5 AI 弟子装备不存词条** | AI 载荷体量极大，存词条会显著放大云存档 | AI 宗门需要"逐件词条级对抗"时（按 (pieceId, rarity, seed) 确定性派生，零存储） |
| **I6 装备精灵图首期程序化占位** | 12 张部件图未产出 | 美术交付时（`rules/static-resources.md` 7 步流程已就绪） |
| **I7 不新增装备埋点/引导** | YAGNI：无当前运营消费者 | 运营需要装备升级漏斗数据时（`rules/data-analytics.md` 三处同步 + 守卫） |
| **I8 ~20 个存档子消息类零 `@ProtoNumber`** | 既有架构遗留，与本方案无关 | 需要修改其中任一类字段时——**先补显式编号（= 当前声明序，二进制零变化）再改** |
| **I9 无保底/无定向获取** | 拍板"不做保底，纯随机" | 上线 4 周内玩家反馈"理想件过难"或分解/持有比异常 ⇒ 引入"锻造定向指定部位"（套装+部位定向已由 12 配方锻造链天然承担，缺口=主词条定向/洗练）。**EQ-B4 量化基线（2026-09-30，登记入债）**：掉落链理想件（同套+同部位+理想主词条+T6）期望 2,400–6,000 件掉落/件（随部位池 2–5），六件理想套期望 **11,389 件掉落**（精确容斥）；锻造链定向后单件理想 = 4×\|主词条池\| 次（8–20 次）、理想套 **88 次**（含失败材料整耗，T6 成功率 25%）；月产出锚 940 万灵石（T6 阶段，见 §3.6 实施记录） |
| **I10 装备实例无硬上限** | 拍板"不设上限，仅告警" | 云存档体积 P95 超阈值，或单档实例 P95 超 **1500 件** ⇒ 引入软上限 + 一键分解 |

---

## 十、YAGNI 反向检查与全局交叉核对

### 10.1 YAGNI 反向检查（每个新抽象必须有当前生产消费者）

| 新抽象 | 当前生产消费者 | 结论 |
|---|---|---|
| `EquipStat` / `EquipStatValue` | 副词条（3 条/件 × 6 件）+ 套装效果（2 套 × 3 档） | ✅ 保留 |
| `EquipBonus`（结算值对象） | `applyEquipmentStats` / `getMaxHpMpColumn` + C++ 三处 | ✅ 保留 |
| `EquipStatResolver` | 上述两处 Kotlin 调用 | ✅ 保留 |
| `EquipmentFactory` | 10 条产出链（§3.8） | ✅ 保留 |
| `EquipAffixPool` / `EquipMainStatPool` | `EquipmentFactory` + 升级强化 | ✅ 保留 |
| `EquipmentSetDef` / `SetBonus` | `EquipStatResolver` | ✅ 保留 |
| `EquipGrowth` / `EquipAffixSet` / `EquipInstanceMeta` | `EquipmentInstance` 字段聚合（构造参数收敛） | ✅ 保留 |
| `Combatant.critDamageBonus` | 法术套 4 件套 + 接线 D3 的丹药字段 | ✅ 保留 |
| `Combatant.physicalDamageBonus` / `magicDamageBonus` | 两套的 2 件套与 6 件套 | ✅ 保留 |
| `RngPartition.EQUIPMENT(13)` | `EquipmentFactory` + 升级强化 | ✅ 保留 |
| `EquipmentValueSanitizeRule` | `SaveValidator.registerDefaults()` | ✅ 保留 |
| 4 条跨域守卫（枚举/编号/单源/codegen） | 自动化"加一值需同步 N 处" | ✅ 保留 |
| **被裁掉的臆造项** | — | 原 `SPEED`/`MP`/物法分列/`*_ATTACK_PCT` 双列维度全部裁掉（零生产者）；`EquipSchool` 保留（有 UI 消费者） |
| **移入债表** | 洗练（I3）、RemoteConfig（I2）、百分比副词条（I4）、AI 词条（I5）、保底定向（I9） | 均无当前消费者 |

### 10.2 `rules/` 交叉核对

| 规则文件 | 结论 |
|---|---|
| `rules/database-migration.md` | ✅ 三批各自递增 + Migration + `safeDropColumns` + schema JSON + 集成测试 + 备份三层防御 |
| `rules/cpp-priority.md` | ✅ §3.11 完整 C++ 方案与双守护；静态表走 codegen；不新增纯 Kotlin 引擎逻辑 |
| `rules/economy-design.md` | ✅ §3.14 源汇闭环；无新货币；`withTrackingSource` 登记；补偿额度审计 |
| `rules/code-quality.md` | ✅ 无 `!!`、无裸异常、无魔法数字（池/数值全为命名常量+数据行）、单文件 <2000 行 |
| `rules/static-resources.md` | ✅ 12 张新图走 7 步流程；旧图归档不删源 |
| `rules/design-plan-review.md` | ✅ 覆盖原则 1–6 与第一~八节 |
| `rules/pr-review-checklist.md` / `build-quality.md` / `testing.md` | ✅ 门禁与 mock 约定列入 §2 与 §6 |
| `rules/data-analytics.md` | ✅ 不新增埋点（I7） |
| `rules/commercialization.md` / `ad-cooldown.md` / `social-system.md` / `sdk-init-lifecycle.md` | ✅ 不涉及 |
| `rules/dialog-soft-input-guard.md` / `new-dialog-checklist.md` / `dialog-scrim-standard.md` | ✅ 新增 3 个对话框**无输入框**（升级固定 1 级/次） |
| `rules/code-comment.md` | ✅ 注释只述当前状态 |
| `rules/industry-benchmark.md` | ⚠️ 见 §11 的诚实声明 |
| `docs/ui-read-surface.md` §2 | ⚠️ **先登记再实现** |
| `docs/threading-contract.md` | ✅ 不新增线程；升级/分解走既有 GameEngine-Thread 事务 |
| `docs/cpp-engine.md` 结算层级 | ✅ 删除每旬孕养结算（减一层）；升级是离散事务，无新结算循环 |

---

## 十一、行业对标要点（附来源等级自评）

> **诚实声明**：`rules/industry-benchmark.md` 把行业对标定为 🟡 建议级，其第四节流程第 6 步为"用户确认后再执行"。本节给出**要点级对标**支撑关键取舍，**尚未达到**"≥20 条合格来源、≥12 条 S/A 级、逐条确认发布日期"的硬门槛；该门槛要求在正式对标报告中满足，建议实施前另行委托专项调研。

**三类范式（完整调研见 §15.1）**：

| 范式 | 代表 | 攻/防 | 类型差异承载 | 设计动机 | 本项目 |
|---|---|---|---|---|---|
| **A 双列攻防（MOBA）** | LoL、王者荣耀、WoW | 双列（AD/AP）+ 双抗 | 双抗 + 穿透 + 真实伤害 | **出装二选一**的取舍 | ❌ 无出装二选一 |
| **B 单列攻防 + 类型通道** | 原神、星铁、绝区零、崩坏 3、Dota 2、暗黑 4 | **单列** | 类型伤害加成 + 类型抗性（+韧性/异常） | 类型由**角色与技能**决定 | ✅ **本项目采用** |
| **C 无攻防成长（格斗）** | 街霸、罪恶装备、大乱斗 | **不存在** | 打击属性（决定能否格挡）+ 连段补正 | 公平对称、招式表驱动 | ❌ 目标相反；仅"连段补正"可借鉴 |

**装备体系对标要点**：

| 设计问题 | 头部做法 | 本项目采纳 | 来源 |
|---|---|---|---|
| 部位数与套装档位 | 原神 5 件（2+4 件套）；星铁 4+2；魔兽套装 2+4 | 6 部位 + 2/4/6 三档（用户指定 6 部位，故 6 = 全套） | [Genshin 圣遗物套装一览](https://game8.co/games/Genshin-Impact/archives/297493genshin%20artifacts)、[WoW 套装效果汇总](https://www.icy-veins.com/wow/news/what-does-your-spec-get-in-midnight-season-2-every-tier-set-bonus-is-in/)、[星铁遗器指南](https://game8.co/games/Honkai-Star-Rail/archives/579631) |
| 主词条 + 副词条 | 原神：固定主词条 + 4 副词条；副词条权重池且不重复 | 主词条按部位池随机（R9）+ 3 副词条（7 项权重池不放回） | [原神词缀强化机制](https://a.9game.cn/yuanshen/6676051.html)、[草词条强化规则](https://www.9game.cn/yuanshen/10568284.html) |
| 强化等级与"换装不降级" | 原神/星铁强化等级**跟随物品**，卸下不重置 | 等级 1–30 随实例走（根治 D2） | [原神词缀强化机制](https://a.9game.cn/yuanshen/6676051.html)、[星铁变量骰子与词条重置](https://news.17173.com/content/01152025/173910220.shtml) |
| 每 N 级强化副词条 | 原神每 +4 级随机强化一条 | **每 3 级**强化一条（30 级共 10 次） | [原神圣遗物副词条强化规则](https://www.9game.cn/yuanshen/10568284.html) |
| 装备不堆叠 | 原神/星铁/暗黑 4 均为独立实例 | **移除堆叠**（用户指定，且为词条玩法必要条件） | [Diablo IV 官方补丁说明](https://news.blizzard.com/es-mx/article/24244466/notas-del-parche-de-diablo-iv-2-5) |
| 主词条保底 | 暗黑 4 官方加入"保证一条主属性词缀" | 主词条**按部位池必出且必定存在**（比"保证一条"更强） | [Blizzard 官方 Diablo IV 补丁说明](https://news.blizzard.com/pt-br/article/24287406/notas-de-patch-de-diablo-iv) |
| 数值乘区 | GDC「Math for Game Programmers」：显式区分加法区与乘法区 | §3.4 明确"装备 flat 加法区 + 装备百分比乘区（只放大装备自身）+ 功法/丹药加法区" | [GDC Vault: Math for Game Programmers](https://gdcvault.com/play/1024674/Math-for-Game-Programmers-Dark)（S 级） |

**取舍依据声明**：关键取舍（6 部位 = 全套、3 副词条、每 3 级强化、等级跟物品、不堆叠、单列属性）**均由用户需求直接指定**；对标用于确认这些选择与头部一致，并确定"主词条池随机 + 副词条权重池不放回 + 每 N 级强化"三条实现口径。**未采纳**：① 原神式 4 副词条与 +20 上限（用户明确 3 副词条 + 30 级）；② 洗练/定向（首期无需求，I3）；③ 元素伤害区（本项目只有物理/法术两路）。

---

## 十二、实施顺序（工作包依赖）与验收

> **分批作业规程见** `docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（6 个批次 B0–B5 的写入面/验收判据/旧用例处置/回滚/报告模板，以及跨批串行约束）。本章的工作包（WP）是**方案侧分工视图**，批次文档是**执行侧编排视图**，两者共同构成实施依据。

```
WP0 存档编号规划（轻量，可与 WP1 并行）─┐
WP1 领域模型与静态数据 ────────────────┼─┬─► WP2 存档与迁移（三批递增）
WP8 属性机制重构（与装备同批交付）─────┤ ├─► WP3 Kotlin 引擎
                                      ├─► WP4 C++ 引擎（与 WP3 同步，逐位对拍）
                                      └─► WP5 UI（依赖 WP1 + WP3/WP8 接口签名）
                                                          └─► WP6 测试/文档/双更新日志 ─► WP7 盲区落地
关键路径：WP0/WP1/WP8 → {WP3,WP4} → WP6
同批交付：WP8（属性机制重构）与 WP1–WP6 **合并为一次交付**（Q8 拍板）
```

| 门禁 | 命令 | 时机 |
|---|---|---|
| G0 | `node scripts/gen-templates.mjs` 后 `git diff` **只含有意变更**（不得出现注入基础设施被删） | WP1/B3 后 |
| G1 | `cd android && ./gradlew.bat compileReleaseKotlin` | 每批后 |
| G2 | 三批迁移测试（每批 `RoomMigrationV<N-1>To<N>Test`） | WP2/B1/B2/B3 后 |
| G3 | `:core:engine:testReleaseUnitTest --tests "*Diff*" --max-workers=1` | WP3/WP4 后 |
| G4 | `./gradlew.bat testReleaseUnitTest --max-workers=1` | WP6 |
| G5 | `./gradlew.bat lintRelease detekt` | WP6（baseline 只缩不增） |
| G6 | 桌面 `cmake --build . ; ctest`（含新增 5 个 GTest 类） | WP4/B1 后 |
| G7 | `node scripts/check-agent-instructions.mjs` + `check-jni-count.mjs` + `gen-game-data.mjs --check` | WP1/WP6 |

---

## 十三、盲区自查与完善建议（末章）

> 立场切换为**审查者/对抗者**。条目三要素 = 盲点 + 影响 + 建议；实质影响方案主体的建议已回写正文。

**13-1 需求理解**
- *盲点*："两件装备套装"存在两种读法；若真实意图是"每套只要 2 件（3 套 × 2 件）"，则 6 件套档位永不触发，两个 `damageBonus` 字段失去消费者。
- *建议（已闭环）*：**用户已拍板读法①**（物理套 + 法术套，各 6 部位，2/4/6 全给）⇒ 字段有消费者，风险消除（§0.1）。

**13-2 边界与极端**
- *盲点*：Lv30 的第 10 次强化与"满级后 exp 清零"存在时序歧义。
- *建议（已回写）*：§3.6 明确"强化节点在**升级动作完成时**判定 `newLevel % 3 == 0`"；Lv30 后无任何强化；测试固定该口径。
- *盲点*：`subRolls` 越界是否放大属性到爆炸值？截断口径必须双端一致。
- *建议（已回写）*：§3.6 明确 `coerceIn`；§6.1 加幂等断言。

**13-3 系统耦合**
- *盲点*：R11 删除孕养丹连带三处非直觉耦合：已解锁**配方**未回滚会留死条目；**奖励池**残留会发出无效物品；玩家**已购未用**的丹药直接消失。
- *建议（已回写）*：§5.7 四条处置 + §3.15 全链删除点。
- *盲点*：初版方案曾把孕养丹**改语义保留**（`nurtureAdd` → 装备经验），属"保留资产"路线；R11 明确**删除**，差异在"是否补偿"——已在 §5.7 补上补偿闭环。
- *盲点*：`GuideTaskRegistry` id=4「锻造装备 3 次」仍可完成，但"装备升级/主词条抽取/套装激活"无引导。
- *建议*：首期最小气泡 + I7（完整引导）。

**13-4 假设有效性**

| 假设 | 验证方式 | 不成立时的备选 |
|---|---|---|
| `Disciple` 无显式 Proto 编号 | **已实测否证**：走 `DiscipleSerializer` 扁平代理，91 字段全显式 | —（无此假设） |
| 存档隐式编号在 ~20 个子消息类中真实存在 | 已实测（零 `@ProtoNumber`）；本方案不触碰 | 登记债 I8；若未来要改这些类，先补编号 |
| `EquipmentSlot` 以 name 落 Room 列 | 已实测 `JsonConverters.kt:31-36` | — |
| 云存档体积可接受 | `CloudPayloadSizeBenchTest` | 超阈值则词条改紧凑编码（stat→byte，rolls→byte），并触发 I10 |
| `scripts/data/*_sample.json` 是中性源 | 已实测 `gen-templates.mjs:218-247` | 若改回"从 Kotlin 源码提取"，改造点相应回到 Kotlin Registry |

**13-5 数据与兼容**
- *盲点*：**没有为旧版本 App 打开新存档提供降级路径**（版本只增）。跨设备可能表现为"进度回退"。
- *建议（已回写）*：§5.5 列入发布说明；登录流程加"存档版本高于客户端"的明确文案。
- *盲点*：补偿邮件幂等标记与发邮件必须同事务。
- *建议（已回写）*：§5.4/§5.7 明确同事务 + 幂等测试。

**13-6 非功能属性**
- *盲点*：**性能**。六件套 + 词条使面板结算从"4 次查表求和"升到"6 件 + 3 词条 ×6 + 套装解析"，而 `getMaxHpMpColumn` 是**每旬热点**。
- *建议（已回写）*：`EquipBonus` 解析结果按实例内容做值语义缓存；§6.6 增加每旬热点基准（不劣化 >10%）。
- *盲点*：**内存**。无上限 = 实例数决定内存 ⇒ 由 I10 监控兜底。
- *盲点*：行业对标**硬性指标未达**（≥20 条来源、≥12 S/A、逐条日期）。
- *建议*：作为实施前可选交付，委托专项调研（不改架构，只校准数值与防坑机制）。

**13-7 生命周期**
- 与 §8 逐项复核一致。补充：`gen-templates.mjs` 的源码正则提取死代码（D10）已列入删除清单，避免下次误以为 Kotlin 源码仍是源。

**13-8 流程盲区**
- *盲点*：217 个测试文件改造是最大**执行**风险；但任何遗漏都会在编译期（签名变更）或测试期暴露，门禁可覆盖。
- *盲点*：`LongParameterList` —— `EquipmentInstance` 朴素写法 15 个参数会违规。
- *建议（已回写 §3.5）*：聚合为 `EquipGrowth` + `EquipInstanceMeta` ⇒ 9 参数达标（detekt `constructorThreshold: 10`）。
- *盲点*：**未跟踪文档会被并行实施流的 `git clean` 清除**（本方案 v1.x 已实际发生一次，无 git 历史可恢复）。
- *建议（已回写）*：方案与批次文档**必须入库提交**，不得长期停留在未跟踪状态；每批开工前用 `git status` 确认文档在库。

**13-10 枚举 name 在新旧体系间重合（随部位集反复调整）**
- *盲点*：当前新枚举 = `HEAD/BODY/HANDS/FEET/WEAPON/LEGS`，与旧枚举的**同名项只有 `WEAPON`**（旧 0 / 新 14）。旧行里的 `"WEAPON"` 能被新代码**成功解码**，只是含义已变且数值字段全部退役。已退役的 `ARMOR/BOOTS/ACCESSORY` 在旧行里也存在，其中 `ACCESSORY` **在新枚举中已不存在** ⇒ 解码走 `JsonConverters` 回退分支（`?: HEAD` + `Log.w`）。
- *影响*：迁移若只改列不清行，旧装备行会以"数值全 0 的幽灵武器"形态复活；`ACCESSORY` 行则被静默回退成 `HEAD`。
- *建议（已回写）*：迁移**必须清空两张装备表与六个部位列**（不是仅改列）；本批迁移测试断言旧行不存在；§6.5 A1 列为对抗点；`EquipmentProtoNumberFrozenTest` 同时冻结**枚举 name → 编号**映射，禁止把 `WEAPON` 的编号"回收"成 0。
- *决策轨迹（留档，避免后来者误读）*：部位集在本轮之间经历了「头身手脚腿饰（无武器）→ 头身手脚武饰（腿部→武器）→ **头身手脚武腿（移除饰品、腿部回归）**」三次调整。冲突面也随之变化：一处（`ACCESSORY`）→ 两处（`WEAPON`+`ACCESSORY`）→ **一处（`WEAPON`）**。无论取哪一版，**兜底手段都不变**：迁移清空旧行 + 枚举 name→编号冻结。

**13-11 主词条"逻辑词条"的固化口径** —— **❌ 已作废**
- 原盲点：R9 的"攻击力/防御力"若按双列理解会与 R10 的"物攻/法攻/物防/法防"冲突。
- **最终解法（比原两方案更彻底）**：属性**单列化**（§15）——不存在物攻/法攻两个属性，主词条池里的"攻击力/防御力"直接就是单列属性，冲突自然消失。

**13-12 全局副词条池的"废词条"风险**
- *盲点*：副词条为**全局池** ⇒ 物理套会抽出"法术伤害加成"、法术套会抽出"物理伤害加成"（权重 15/100 = 15%）⇒ **单件至少含 1 条对流派无用词条的概率 ≈ 38.92%**。
- *影响*：强化收益方差增大、挫败感（首期无洗练）。
- *建议（已采纳按用户口径）*：保持全局池 + 分解回收 + 每 3 级强化缓解；**备选（未采纳）**：按流派过滤池（废词条率降为 0），切换只改 1 张表 + 1 个 C++ 常量数组，登记为 I9 缓解手段。

**13-13 装备不再提供"速度"与"灵力"的数值面影响**
- *盲点*：旧体系下饰品/靴子是**速度与灵力**的主要来源（旧 T6 饰品 = 灵力 30,150 / 速度 4,575；旧 T6 靴 = 速度 4,575）。新口径下装备不提供这两项 ⇒ **出手顺序与灵力池显著下降**，直接影响战斗节奏。
- *建议（已回写）*：① S14 写成显式标准；② `EquipmentPowerParityTest` **必须按维度拆分**断言，否则"总战力持平"会掩盖速度/灵力塌陷；③ 备选 B1（脚部主词条池加回"速度"）/B2（提升基础速度成长）——当前**不预置**（避免未授权数值改动），等拆分对比结果再定。

**13-14 自查结论**
- 逐维度均给出结论，无"加强关注"式空话。
- **自查中否证了一条重大误判**：初版 D0 断言"`Disciple`/`EquipmentSet` 无显式 `@ProtoNumber` ⇒ 必须先补编号冻结"，并把 WP0 列为最高优先阻塞前置。复核 `DiscipleSerializer.kt` 后确认其走扁平代理、91 字段全显式，`EquipmentSet` 根本不进档 ⇒ 已修正（D0 → D0′，降级为独立债 I8；WP0 收缩为轻量编排）。
- **第二次自查纠正**：`MAX_EQUIPMENT_PURCHASES` 被误判为缺陷（D8），实为月度购买次数限流，**已更正为"非缺陷"**。
- **决策轨迹留档**：属性模型曾出现"双列 → 单列 →（一度）双列 → **最终单列**"；**以 §15 的单列为最终口径**。
- 未采纳但已说明理由：① 洗练/重铸（I3）；② 副词条池扩为 8 项含攻击力%（I4）；③ 正式 20 条对标报告（建议另行委托）；④ 顺手补齐 ~20 个隐式子消息类编号（独立债 I8）；⑤ §13-12 按流派过滤 / §13-13 速度补偿（已按用户口径定默认，备选与切换成本写明）。
- **第三次教训（本轮）**：方案文档曾以**未跟踪文件**长期存在，被并行实施流的 `git clean` 清除且**无法从 git 恢复** ⇒ 现已入库；后续任何方案/批次文档都不得停留在未跟踪状态。

---

## 十四、交付状态

> **交付状态更新（2026-09-30，EQ-B5 收口）**：本节初稿时点（2026-09-28）交付性质为「纯文档（零代码实施）」；
> 同日经用户派发启动批次化实施，2026-09-28~30 由 `feat/equipment-set` worktree 分六批（EQ-B0–B5）
> **全部实施完毕并验收**——状态见 §14.2，终态全景对照见 `docs/architecture.md`「属性与装备体系」节。
> 本文件正文 §三/§五/§六/§八/§九/§十五 为**设计与推导记录**，描述与实现的差异以各批报告
> `docs/design/equipment-batches/reports/` 的实施记录为准（已知的两处显式偏差：热点缓存改恒等键、
> 属性迁移 k=1，均在报告与 ADR 登记在案）。

### 14.1 需求覆盖矩阵（文档级）

| 需求 | 章节 | 状态 |
|---|---|---|
| R1 六部位（头/身/手/脚/**武**/饰，顺序照此） | §3.1 / §3.5 / WP1 / WP5 | 📄 已定稿 |
| R2 删除现有所有装备 | §3.15 / §5.2 / §5.4 | 📄 已定稿 |
| R3 套装 + 2/4/6 件套效果 | §3.3 / §3.7 / §3.10 | 📄 已定稿 |
| R4 孕养 → 升级（1–30） | §3.6 / WP3 / WP4 | 📄 已定稿 |
| R5 装卸不改等级 | §3.5 单点存储 / `EquipmentLevelPersistGuardTest` | 📄 已定稿（含根因 D2） |
| R6 移除堆叠 | §3.15 / WP2 / WP3 | 📄 已定稿 |
| R7 主词条 + 3 副词条 | §3.4 / §3.7 | 📄 已定稿 |
| R8 物理套 + 法术套（各 6 部位） | §3.7 | 📄 已定稿 |
| R9 主词条按部位池随机 | §3.4.2 + §3.7 | 📄 已定稿 |
| R10 副词条池 7 项与权重 13/13/14/15/15/15/15 | §3.4.3 + §3.7 | 📄 已定稿 |
| R11 删除孕养类加成丹药（含补偿） | §3.15 / §5.7 / §3.14 | 📄 已定稿 |
| R12 属性机制重构（单列） | §15 全章 | 📄 已定稿 |
| D1/D9/D10 缺陷处置 | §2.12 / §3.16 / WP1 | 📄 处置已定稿 |
| D3 暴击伤害死字段 | §2.12 / §3.10 | 📄 处置已定稿 |
| D8 误判更正 / D0 判定作废 | §2.12 | 📄 已更正 |
| §0.2 盲区清单 14+1 项 | §0.2 + WP7 + §14.4 | 📄 甲组已拍板，乙/丙组有默认口径 |

### 14.2 实施工作包（✅ 全部完成，2026-09-30 EQ-B5 收口更新）

> 交付状态变更记录：本节原为「纯文档交付、全部 ⬜ 待实施」（2026-09-28 方案定稿时点）；2026-09-28~30 经
> `feat/equipment-set` worktree 分六批（EQ-B0–B5）全部实施完毕，状态如下。各批验收明细见
> `docs/design/equipment-batches/reports/report-B0..B5.md`；门禁终态 = 六模块 JVM 7652/0/0 · ctest 1521/1521 ·
> detekt 零违规 · G0 零差异。需求 R1–R12 / 缺陷 D1–D10 / 债 I1–I10 / 风险 R12–R17 的**终态全景对照**
> 见 `docs/architecture.md`「属性与装备体系」节（活文档）。

| 工作包 | 状态 | 说明 |
|---|---|---|
| WP0 存档编号规划与冻结守卫 | ✅ EQ-B0 | `EquipmentProtoNumberFrozenTest`（5 用例）；编号分配表 E1 已冻结 |
| WP1 领域模型与静态数据 | ✅ EQ-B3 | 12 部件 × 6 品阶 72 条目 + 两套装 + 双词条池；D1/D9/D10 闭合 |
| WP2 存档与迁移 | ✅ EQ-B1/B2/B3 | Room v62（属性单列）/ v63（孕养丹退役）/ v64（装备实例轨）三批递增 |
| WP3 Kotlin 引擎 | ✅ EQ-B3/B4 | 升级/分解/解析/穿卸/产出链；恒等键热点缓存（B4） |
| WP4 C++ 引擎 | ✅ EQ-B3/B4 | `equipment_tx.h`/`equipment_factory.h` + 四张 equip 表 codegen；双端对拍 |
| WP5 UI | ✅ EQ-B3 | 六宫格/套装/升级分解对话框/仓库筛选/锻造 12 配方/精灵图占位 |
| WP6 测试/文档/双更新日志 | ✅ EQ-B4/B5 | B4 数值四测试类；B5 双 changelog + ADR + 活文档收口 |
| WP7 盲区落地 | ✅ EQ-B3/B4 | §0.2 甲组 6 项全落地（占比/保底/品阶/满级/上限/含套装） |
| WP8 属性机制重构 | ✅ EQ-B1 | §15 单列 + 类型通道；S19/S20/S21 |

### 14.3 可选口径与已关闭项（全部已拍板）

| 编号 | 选项 | 结论 | 备选（已关闭） |
|---|---|---|---|
| **§3.1** | **六部位构成与显示序** | ✅ **已拍板（2026-09-29）**：头 / 身 / 手 / 脚 / **武** / **腿**（**移除饰品位**、腿部回归；3×2 宫格：上行 头·身·手，下行 脚·武·腿） | 武器与腿部位置互换；改 `EquipmentSlot.displayOrder` 一行即可，架构与存档不受影响 |
| **§3.4.2** | **武器（第 5 位）与腿部（第 6 位）的主词条池与系数** | ✅ **已拍板（2026-09-29）**：武器 = 攻击力/暴击率/暴击伤害（1.15 输出向）；腿部 = 防御力/攻击力/暴击率/暴击伤害/血量（0.95，R9 原池）；手部保持 R9 原样 | ① 手部转防御向（防御力/血量，0.95）+ 武器输出向（分工最清晰）；② 腿部改纯防御向（防御力/血量）；③ 武器与腿部同用原腿部池（冗余）。切换只改 §3.4.2 表 + `EquipMainStatPool` 数据行 + C++ 对偶表 |
| §13-11 | 主词条"攻击力/防御力"如何落物/法 | ❌ **已作废**（单列后即属性本身） | — |
| §13-12 | 副词条池是否按流派过滤 | ✅ 全局池（不按流派过滤） | 按流派过滤（I9 缓解手段之一） |
| §13-13 | 速度/灵力塌陷是否补偿 | ✅ 先不补偿，上线前看拆分对比 | B1 脚部池加回速度 / B2 提升基础速度成长 |
| §15 | 属性机制重构 | ✅ 采纳（单列 + 类型通道），与装备**同批交付** | 回退双列口径见 §15.8 末段 |
| §15.7 Q11 | 7 项池权重 | ✅ 13/13/14/15/15/15/15（合计 100 = 直接概率） | 数值评审可按占比目标微调 |

### 14.4 已拍板清单（§0.2 甲组 6 项 + §15 共 9 项）

**§0.2 甲组（2026-09-28）**：

| # | 问题 | 用户拍板 | 与建议的差异 / 代价 |
|---|---|---|---|
| 1 | 装备占弟子总战力 | ✅ **40% ± 5%**（含套装） | 与建议一致 |
| 2 | 保底/定向机制 | ✅ **不做保底，纯随机** | ⚠️ 与建议不同；缓解 = 分解回收 50% + 每 3 级强化 + 可切过滤池；债 **I9** |
| 3 | 品阶产出是否受境界约束 | ✅ **是** | 与建议一致 |
| 4 | 一套满级目标耗时 | ✅ **1 个月左右**（容差 [0.75,1.25]） | ⚠️ 比建议（2–3 周）更长；因不做保底，产出速率同时承担延长长线职责 |
| 5 | 装备实例仓库上限 | ✅ **不设上限，只告警** | ⚠️ 与建议不同；必须配套"告警不截断 + 排序筛选批量分解 + 不走溢出邮件"；债 **I10** |
| 6 | 40% 是否含套装效果 | ✅ **含在内** | 与建议一致 |

**§15 属性机制重构（9 项）**：见 §15.7（Q1–Q4、Q7–Q11）。

**由拍板值直接产生的 4 项硬约束**：① `EquipmentPowerParityTest` 断言改**占比 [35%,45%]**；② §3.6 加**校准目标 = 月产出**与系数 `k`；③ §3.8 加**品阶受境界约束**（单点在 `EquipmentFactory.create`）；④ **无上限模式**（只告警 + 删装备溢出邮件分支 + 仓库可管理）。

### 14.5 与另一并行实施流的协调（**开工前置，未解决**）

工作树中实测存在另一实施流（法务/监牢下线、自动存档下线；已改 `version.properties`、`CHANGELOG.md`、`models.h`、存档管线），并已实测**抢占 Gradle 构建产物**（`core/domain/.../classes.jar` 被占用导致测试失败），且其 `git clean` 曾清除本方案的未跟踪文档。本方案与它在 `models.h`、`CHANGELOG.md`、`docs/knowledge-base.md`、`docs/architecture.md`、存档管线上**写入面重叠** ⇒ 开工前必须明确串行化或写入面划分。

---

## 十五、属性机制重构：统一基础攻防 + 技能决定伤害类型

> **来源**：用户在拍板主词条口径时提出"重构角色属性机制：新增基础攻击力与基础防御力，由普通攻击/技能决定伤害属于物理或法术"，并要求先调研主流做法。**最终拍板采用单列**（决策轨迹见 §13-14）。
> **性质**：比装备重构更大（触及弟子属性生成、战斗公式、AI、妖兽、试炼、宗门战、面板、存档、C++ 对拍、以及本方案**全部词条池**），故单独成章。

### 15.1 主流游戏怎么做（含 MOBA 与格斗类专项）

#### 15.1.1 抽卡/动作 RPG 与 MMO

| 产品 | 攻击/防御维度 | 伤害类型由什么决定 | 类型差异承载 | 来源 |
|---|---|---|---|---|
| **原神** | **单列** ATK / DEF / HP | **技能自带**（普攻/重击为物理，元素战技/爆发为元素） | ① 伤害加成区（物理/元素）② 抗性区 ③ 防御区 | [HoYoWiki 伤害计算公式（官方）](https://wiki.hoyolab.com/m/genshin/entry/5654?lang=pt-pt) |
| **崩坏：星穹铁道** | **单列** ATK / DEF / HP / 速度 | **技能自带属性**（物理/火/冰/雷/风/量子/虚数） | ① 属性增伤 ② 抗性 ③ 韧性 ④ 防御区 | [星铁伤害乘区汇总](http://360game.360.cn/article/content?id=6821baf57b0a655d6eae5182)、[星铁伤害与速度公式](https://news.17173.com/content/08122024/160903756.shtml) |
| **绝区零** | **单列** ATK + 冲击力/异常精通 | **角色属性固定**（物理/火/冰/电/以太） | ① 属性伤害加成 ② 抗性 ③ **异常积蓄**（异常条） | [绝区零异常学·底层机制](https://news.17173.com/content/11052024/114808835.shtml) |
| **崩坏 3** | **单列** ATK / DEF | **角色/技能属性**（物理/冰火雷/量子虚数） | ① 元素伤害加成 ② 敌方克制/抗性 | [崩坏 3 伤害机制与攻击类别](https://a.9game.cn/bhxy3/6783547.html) |
| **明日方舟** | **单列** ATK / DEF | **攻击方式**（物理/法术/真实） | ① 物理走**减法**（保底 5%）② 法术走**乘法**（×(1−法抗)） | [明日方舟干员属性说明](https://a.9game.cn/news/3231620.html) |
| **暗黑破坏神 4** | 武器伤害 + 统一乘区 | **技能自带**（物理/火焰/冰霜…） | ① 类型伤害加成 ② 易伤 ③ 暴击 | [暗黑 4 伤害算法与乘区解析](https://a.9game.cn/ahphs4/11600256.html) |
| **魔兽世界** | **分离**（AP / SP） | 技能自带学派 | 学派加成 + 抗性 | [WoW 6.0 官方补丁说明](https://www.wowhead.com/classic/news/patch-notes-for-warlords-of-draenor-6-0-ptr-243107?page=2) |

#### 15.1.2 MOBA 类

| 产品 | 攻击维度 | 防御维度 | 类型由什么决定 | 类型差异承载 | 来源 |
|---|---|---|---|---|---|
| **英雄联盟** | **双列：AD / AP** | **双列：护甲 / 魔法抗性** | 技能各自带 AD/AP 系数、混合、**真实伤害** | ① 护甲只减物理、魔抗只减魔法（`减伤 = 100/(100+抗性)`）② 穿透（固定→百分比）③ 真实伤害绕过全部 | [LoL Wiki 伤害](https://wiki.leagueoflegends.com/en-us/Damage)、[LoL Wiki 魔法抗性](https://wiki.leagueoflegends.com/en-us/Magic_resistance)、[腾讯官方《数值篇》](https://lol.qq.com/news/detail.shtml?docid=4750627352862087689)（A 级官方） |
| **王者荣耀** | **双列：物理攻击 / 法术攻击** | **双列：物理防御 / 法术防御** | 技能各自带 AD/AP 加成；部分**真实伤害** | ① 物防/法防分别减伤 ② 物理/法术穿透 ③ 真实伤害无视双抗 ④ 物理/法术吸血分流 | [王者荣耀伤害计算](https://www.9game.cn/wzry/11018807.html)（C 级补充）、[为什么设计里会有"法强"](https://www.vgover.com/news/169419) |
| **Dota 2（反例）** | **单列：攻击力**（力量/敏捷/智力只决定成长） | 分开：护甲（减物理）/ 魔抗（默认 25%，减魔法） | **伤害类型三分**：物理 / 魔法 / **纯粹(Pure)** | ① 护甲只减物理 ② 魔抗只减魔法 ③ 纯粹绕过两者 ④ 法术输出靠"法术增幅"装备 | [Liquipedia Dota 2：Damage Types](https://liquipedia.net/dota2/Damage_Types) |

**MOBA 关键结论**：LoL 与王者把攻击力拆两列，**不是战斗公式需要，而是"出装二选一"的载体**（玩家必须在买 AD 装还是 AP 装之间取舍）。**Dota 2 是反证**：没有出装二选一，攻击力只有一列，类型差异全交给"伤害类型 × 抗性"。

#### 15.1.3 格斗类

| 产品 | 攻击力属性 | 防御力属性 | 伤害怎么算 | "类型"指什么 | 来源 |
|---|---|---|---|---|---|
| **街霸 6 / 街霸 IV** | **不存在**（招式表固定值） | **不存在**（HP 固定；仅"格挡"削减 chip damage） | **招式固定伤害 × 连段补正（damage scaling）**：连段越长后续每段递减 | 打击属性（上/中/下段、投技、飞行道具）——决定**能否被格挡** | [街霸 6 伤害补正机制](https://gamemad.com/guide/246578)、[街霸 IV 连续技伤害修正](http://article.pchome.net/content-884964.html) |
| **罪恶装备 Strive** | 不存在 | **角色固有防御修正**（defense modifier） | 招式固定伤害 × 连段补正 × 角色防御修正 | 同上 | [Guilty Gear Strive: Damage Scaling](https://www.thegamer.com/guilty-gear-strive-damage-scaling-explained/) |
| **任天堂明星大乱斗** | 不存在 | **weight（体重）**：不影响伤害，只影响击飞距离 | 招式固定伤害，以**百分比累计**而非扣血 | 无物理/法术之分 | [大乱斗特别版入门指南](https://m.163.com/dy/article/E2J4K5DS0526DBKT.html) |

**格斗类关键结论**：格斗游戏**根本没有攻防两个成长属性**——伤害是招式表固定值，平衡靠 ① **连段补正** ② **角色固有防御修正/体重** ③ 打击属性（决定能否格挡）。这与养成型游戏目标相反（格斗要对称公平，养成要数值成长）⇒ **不能照搬**，但可借鉴：**多段技能用连段补正**、**角色固有防御修正与类型抗性是两条独立通道**。

#### 15.1.4 三类范式总览与本项目定位

| 范式 | 代表 | 攻/防 | 类型承载 | 设计动机 | 本项目适用性 |
|---|---|---|---|---|---|
| **A 双列攻防** | LoL、王者荣耀、WoW | 双列 + 双抗 | 双抗 + 穿透 + 真实伤害 | **出装二选一** | ❌ 无出装二选一 |
| **B 单列攻防 + 类型通道** | 原神、星铁、绝区零、崩坏 3、Dota 2、暗黑 4 | **单列** | 类型伤害加成 + 类型抗性 | 类型由**角色与技能**决定 | ✅ **本项目采用** |
| **C 无攻防成长** | 街霸、罪恶装备、大乱斗 | **不存在** | 打击属性 + 连段补正 | 公平对称 | ❌ 目标相反 |

**结论**：本项目"抽卡获得弟子、功法决定技能类型、装备统一为套装"的结构**没有"出装二选一"这层决策**，现状的双列攻防缺少设计动机 ⇒ **调研支持单列**（范式 B），与用户拍板一致。

### 15.2 目标模型

```
属性面（改造后）
  统一：attack（基础攻击力）、defense（基础防御力）、maxHp、maxMp、速度、暴击率、暴击伤害
  类型通道：
    physicalDamageBonus / magicDamageBonus                 // 类型伤害加成（攻方）
    physicalDamageReduction / magicDamageReduction         // 类型减伤分桶（守方，改造现有减伤区）

isPhysical = 普攻 ? 弟子.innateDamageType == PHYSICAL : 技能.damageType == PHYSICAL
attack     = 攻方.attack                       // 单列
reduction  = 守方.defense / (守方.defense + DEFENSE_CONSTANT)   // 单列，与类型无关
类型增伤   = isPhysical ? 攻方.physicalDamageBonus : 攻方.magicDamageBonus
类型减伤   = isPhysical ? 守方.physicalDamageReduction : 守方.magicDamageReduction

伤害 = attack × 技能倍率 × (1 − reduction) × 暴击区
     × (1 + 增伤区 + 类型增伤) × (1 − 减伤区 − 类型减伤)
     × 境界压制因子 × 命中次数
```

**举例（同 attack/defense，只差类型）**：

| | 弟子甲（普攻=物理，物理套 6 件 +20% 物伤） | 弟子乙（普攻=法术，法术套 6 件 +20% 法伤） |
|---|---|---|
| attack / defense | 1000 / 守方 500 | 同左 |
| 减伤（单列） | 500/1500 = 33.3% | 同左 |
| 类型增伤 | +20% | +20% |
| 妖兽类型减伤 | 物理减伤 10% | 法术减伤 0% |
| **结果** | 1000 × 0.667 × 1.20 × 0.90 = **720** | 1000 × 0.667 × 1.20 × 1.00 = **800** |

⇒ 物法差异全部来自"**谁决定类型 + 类型增伤 + 类型减伤**"三条通道；旧的"物防/法防分别取值"被"类型减伤桶"取代。

- **保留**现有 `DEFENSE_CONSTANT` 除法减伤、境界压制因子、暴击区、增伤/减伤区（`DamageZones` 结构不动）。
- **改动点 3 处**：① 攻/防取单列；② 新增类型增伤/减伤两个乘区（默认 0.0 ⇒ 未配置时与旧公式逐位一致）；③ 攻/法 buff 分桶改为类型增伤语义（需逐位重录对拍基线）。
- **不引入**减法型（明日方舟）——本项目减伤是除法型，改减法会使高防单位失衡。

### 15.3 普攻的伤害类型判定（**已拍板：每个角色固定一种属性，由角色模板给出**）

- 新增**弟子固有伤害属性** `Disciple.innateDamageType`，来源是**角色模板**：`CharacterTemplate` 新增 `innateDamageType` 字段（与立绘/天赋同级的角色固有属性）；弟子生成时**从模板继承、创建后永不改变**；旧档按 `templateId` 回填（**幂等**，不随机）；模板缺失兜底按灵根派生（金/土→物理，水/木/火→法术）并记日志。
- **普攻**用 `innateDamageType`；**技能**用 `skill.damageType`（技能自带，现状保留）——两者可不一致（物理弟子也可能学到法术技能），这是流派搭配乐趣的来源。
- 妖兽/敌人同样新增该字段（按种类固定，如"火系妖兽=法术"）。
- 弟子详情面板展示该标签（"物理"/"法术"）；套装面板对"固有属性与套装流派不匹配"给非阻断提示。
- 存档/列：`DiscipleSurrogate` 新增 1 字段（建议 117）+ Room 1 列 + C++ 1 列；`CharacterTemplate` 是静态数据，走 codegen。

### 15.4 对弟子/敌人属性的影响面

| 面 | 现状 | 改造后 |
|---|---|---|
| `Disciple` 战斗属性 | `basePhysicalAttack/baseMagicAttack/basePhysicalDefense/baseMagicDefense` + 4 variance | `baseAttack/baseDefense` + **2 variance** |
| `DiscipleStats` | 四列 | `attack/defense` |
| 弟子生成（方差/天赋） | 物法双列独立随机 | 单列随机；"物法倾向"由**固有属性 + 技能类型 + 套装倾向**表达 |
| 功法 `ManualStats` | 双列 | **保留双列，结算层相加**（Q2） |
| 丹药 `ItemEffect` | 4 个 `*Add` | `attackAdd/defenseAdd`（旧 4 字段退役 reserved；旧值相加） |
| 妖兽/敌人 | 双列 | 单列 + 类型抗性 |
| 面板 UI | 物攻/法攻/物防/法防四行 | 攻击力/防御力两行 + 固有属性标签 + 类型伤害加成 |
| 存档 | `DiscipleSurrogate` 60–73 | 退役 4 个 + 新增 3 个 + Room 列同步 |
| C++ | `disciple.h`/`disciple_stats.h`/`battle*.h`/`beast_config.h` | 同构改造 + 双守护 |
| 战力计算 | 权重表按 4 属性 | 权重表重标（直接决定 40% 占比口径，R17） |

### 15.5 与装备方案的三处冲突（**已全部解决**）

| # | 冲突 | 解决 |
|---|---|---|
| **C1** | 主词条"攻击力/防御力"不再需要按流派固化 | 单列后 `攻击力/防御力` **就是单列属性本身**，固化步骤取消（§13-11 关闭） |
| **C2** | 副词条池的物攻/法攻/物防/法防与统一模型冲突 | 重定为 **7 项**：攻击力、防御力、血量、暴击率、暴击伤害、物理伤害加成、法术伤害加成（§3.4.3 / §15.6） |
| **C3** | 套装流派区分被削弱 | **2 件套差异化**：物理套 = 物理伤害 +10%，法术套 = 法术伤害 +10%；4 件套暴击率 vs 暴击伤害；6 件套类型伤害 +20% ⇒ 穿满一套 = 该流派 **+30% 类型伤害** |

### 15.6 统一模型下的词条池（**已定稿**）

**主词条池**：见 §3.4.2（形态与 R9 原话一致；`攻击力/防御力` 直接是单列属性）。

**副词条池（7 项，权重 = 概率 %）**：

| 词条 | 权重 | 词条 | 权重 |
|---|---|---|---|
| 攻击力 | 13 | 暴击率 | 15 |
| 防御力 | 13 | 暴击伤害 | 15 |
| 血量 | 14 | 物理伤害加成 | 15 |
| — | — | 法术伤害加成 | 15 |
| **合计** | **100** | | |

- 档位值：前三项与暴击率/暴击伤害沿用 §3.7 原表；新增两项取 `0.004/0.006/0.008/0.012/0.016/0.020`（与暴击伤害同档）。
- 抽取口径不变（按权重不放回抽 3 条，声明序参与前缀和比较，禁止重排）。
- **结构提示**：比例类合计 60%、flat 类 40% ⇒ 偏乘区型，需与"装备占战力 40%"一起复核；若乘区过强，优先下调两条类型伤害加成权重。

#### 15.6.1 类型抗性的落地（**已拍板：改造现有减伤区分桶**）

- **不新增独立字段**，而是改造现有 `DamageZones` 的减伤区，使其按类型分桶：`damageReduction` → `physicalDamageReduction` / `magicDamageReduction`（各默认 0.0 ⇒ 未配置时与旧公式**逐位一致**）；攻击方同理拆 `physicalDamageBonus` / `magicDamageBonus`（§3.10 已为套装新增，正好复用）。
- **好处**：不动属性面板、不加新列、不扩存档；**代价**：减伤区从 1 个乘区变 2 个分桶，`DiffBattle*` 基线需重录（本来也要重录）。

### 15.7 拍板结果（2026-09-28）

| # | 问题 | ✅ 用户拍板 | 落地含义 |
|---|---|---|---|
| **Q1** | 普攻伤害类型怎么定 | **每个角色的普攻固定一种属性**；来源**由角色模板固定** | 新增 `Disciple.innateDamageType`（模板继承、创建后不变、旧档按 templateId 幂等回填）；§15.3 完整设计 |
| **Q2** | 功法是否统一为单列 | **保留双列，仅结算层合并** | 150+ 功法数据与 codegen **一字不改**；结算层把物攻/法攻都计为 `attack`、物防/法防都计为 `defense` ⇒ 改动面最小，保留"物法双修"差异 |
| **Q3** | 副词条池 | **7 项**（攻击力/防御力/血量/暴击率/暴击伤害/物理伤害加成/法术伤害加成） | §15.6 / §3.4.3 / §3.7 已定稿 |
| **Q4** | 类型抗性 | **改造现有减伤区分桶**（不新增字段） | §15.6.1 |
| **Q7** | 旧双列 → 单列映射 | **取两列之和，再整体重标基数** | `attack = 旧物攻+旧法攻`、`defense = 旧物防+旧法防`（信息不丢），乘统一系数 `k` 使总战力与 40% 占比目标不变；功法/丹药/妖兽同口径。⇒ **纯法弟子不会因合并而变弱** |
| **Q8** | 交付关系 | **合并为同一次交付** | 两项强耦合（词条池/占比/套装区分/战斗公式），合并避免同一批文件改两次 |
| **Q9** | 两套 2 件套差异化 | **差异化**：物理套 2 件 = 物理伤害 +10%，法术套 = 法术伤害 +10% | §3.7（穿满一套 = 该流派 +30% 类型伤害） |
| **Q10** | 是否保留"攻击力%" | **保留，仅套装/功法/丹药使用**（不进副词条池） | `EquipStat.ATTACK_PCT(6)`；副词条池仍 7 项 |
| **Q11** | 7 项池权重与档位值 | **用户给定权重 13/13/14/15/15/15/15（合计 100 = 直接概率）**；档位值用 §15.6 建议 | §3.4.3 / §3.7 已定稿 |

> **决策轨迹留档**：属性模型曾出现"双列 → 单列 →（一度）双列 → **最终单列**"；**以本章为准：采用单列**。

### 15.8 风险评估

| # | 风险 | 兜底 |
|---|---|---|
| A1 | **全量战斗平衡重算**：合并攻/防会改变每个弟子、妖兽、AI、试炼、宗门战强度 | **拍板口径（Q7）**：`attack = 旧物攻+旧法攻`、`defense = 旧物防+旧法防`，乘统一系数 `k` 使总战力与"装备占比 40%"目标不变；功法/丹药/妖兽同口径相加 ⇒ 一次做完、不做二次改动；`EquipmentPowerParityTest` 与全部 `Diff*Test` 全绿为门禁 |
| A2 | **对拍面几乎全红**（`battle_json`/`diff` 涉及攻防字段） | 基线**一次性重录** + `DiffBattle*`/`DiffSectBattle*`/`DiffBattleExecution*` 全绿为门禁 |
| A3 | 存档列/编号退役与新增 | 沿用既有纪律：退役写 `reserved`、新增取未占用段、迁移删列 + 集成测试 |
| A4 | 改动面与装备重构叠加（互相耦合） | **合并为同一次交付**（Q8）；若必须分开，则装备池表先冻结为空实现，等属性重构定稿后再落数据 |
| A5 | 与外部并行实施流的 `models.h`/`disciple.h` 写入面重叠 | 见 §14.5，开工前必须先串行化 |

**回退口径（决策已定，采用单列；以下仅作历史留档）**：若未来回退到双列，则 §3.4.2 恢复"逻辑词条按流派固化"、副词条池恢复"物攻/法攻/物防/法防 + 暴击率/暴击伤害/血量"7 项分裂维度，`EquipStat` 与 §3.10 乘区表相应回退；装备方案其余部分不受影响。

---

## 附：本方案对用户需求的交付方式

| 用户原话要求 | 方案章节 | 交付方式 |
|---|---|---|
| 调查当前装备系统 | 第二章（12 小节，逐行读实 + 规模量化） | ✅ 已完成 |
| 改为六部位（头/身/手/脚/**武器**/饰） | §3.1 / §3.5 / WP1 / WP5 | 实施蓝图 |
| 删除现有所有装备 | §3.15 / §5.2 / §5.4 | 实施蓝图 |
| 套装 + 2/4/6 件套效果 | §3.3 / §3.7 / §3.10 | 实施蓝图 |
| 孕养 → 升级（1–30） | §3.6 / WP3 / WP4 | 实施蓝图 |
| 装卸不改等级 | §3.5 单点存储 / `EquipmentLevelPersistGuardTest` | 实施蓝图（含根因 D2） |
| 移除堆叠 | §3.15 / WP2 / WP3 | 实施蓝图 |
| 主词条按部位池随机 + 3 副词条（7 项池，权重 13/13/14/15/15/15/15） | §3.4 / §3.7 | 实施蓝图 |
| 物理套 + 法术套 | §3.7 | 实施蓝图 |
| 删除孕养类加成丹药（含补偿） | §3.15 / §5.7 / §3.14 | 实施蓝图 |
| 属性机制重构（单列 + 类型通道） | §15 全章（含 MOBA/格斗/米哈游系调研） | 实施蓝图 |
| 发现的其他缺陷一并解决 | §2.12 / §3.16（D1/D3/D9/D10）+ I8 登记 | 实施蓝图 |
| 分批实施文档 | `equipment-batches/IMPLEMENTATION-BATCHES.md` | ✅ 已完成 |
| **给出计划方案** | **本文件全文** | **✅ 已交付（纯文档，零代码改动）** |

**待拍板项：无**（§0.2 甲组 6 项 + §15 的 9 项已全部闭环，见 §14.4 / §15.7）。

**下一步**：先按 §14.5 协调与外部并行实施流的写入面，再按 `equipment-batches/IMPLEMENTATION-BATCHES.md` 从 **B0** 开工。


