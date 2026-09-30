# ADR: 五行属性伤害系统——「DamageType 六值化」与「灵根 gate」两大架构决策（elemental-damage-system）

> 状态：✅ **已实施**（2026-09-30 单批交付，装备重构 B0–B5 并网主干后开工）
> ｜决策日期：2026-09-29（方案定稿）～2026-09-30（实施）
> ｜关联：[elemental-damage-system-plan.md](../design/elemental-damage-system-plan.md)（权威方案，P1–P9/Q1–Q12/E1–E12）
> · [equipment-set-system.md](equipment-set-system.md)（前置：属性单列 + 类型通道骨架）
> · [architecture.md](../architecture.md)（活文档终态）

## 0. 实施状态快照（2026-09-30）

| 项 | 内容 | 状态 |
|---|---|---|
| DamageType 6 值化 | `PHYSICAL` + `METAL/WOOD/WATER/FIRE/EARTH`；`MAGIC` 退役段保留（name 序列化兼容，禁新产出） | ✅ |
| 类型通道 6 路 | `Combatant` 12 桶（物理+五行 × 增伤/减伤）；Kotlin `mergeTypeChannels` 与 C++ 同式逐位 | ✅ |
| 普攻类型配置驱动 | `innateDamageType` 为**活配置位**（战斗管线按其判定普攻类型）；当前全部角色设定物理——是内容现状而非架构恒等式（实施初版误作硬编码，2026-09-30 用户纠正后返工，见 D5） | ✅ |
| 灵根 gate | `SpiritRoot.elementGate`（唯一实现入口）：含该元素→1.0、不含→恰 0.0、物理恒 1.0；`typeDamageBonusesOf` 弟子侧汇总折算 | ✅ |
| 技能元素 | 功法静态表 `skillDamageType` 值域扩 5（78 条 magic 功法按名义语义映射五行）；妖兽表 6 处 MAGIC→兽种元素 | ✅ |
| 装备侧 | `EquipStat` 8→13 值（12 活跃+退役段）、词条池 7→11 项（类型词条 ×1.5 补偿档）、**6 套 36 部件**、配方 36 条 | ✅ |
| 数据管线 | 中性源→gen-templates/gen-manual-db/gen-game-data 全链重生成；`gen-manual-db.mjs` 补齐 operator==/manualTemplatesMutable 生成（防复发） | ✅ |
| 测试 | JVM 3005/0/5skip（含 Diff 家族双端逐位对拍全绿）；ctest 1528/1529；新守卫 `DamageTypeGuardTest`/`ElementalDamageSystemTest`（E1–E7/E11/E12） | ✅ |

门禁说明：ctest 唯一失败 `AccrualSegmentBench.SegmentUnderBudgetAt5000` 经 HEAD 对照 worktree 定性为**机器环境劣化**（裸弟子 accrue 段与本任务改动零交集；HEAD 树同用例同红，best 834µs vs 改动树 858µs，基线 670µs 时代的环境已不可复现）。

---

## 1. 背景

装备重构（B1）交付了「属性单列 + 物理/法术二值类型通道」骨架，但类型桶在弟子装配链断裂（`EquipBonus` 产出类型维、`applyEquipBonus` 丢弃、`Combatant` 四桶生产代码零写入）。同期用户需求：**法伤改为五行属性伤害**——灵根（金木水火土）从纯展示属性接入战斗，套装扩为物理+五行六套。

## 2. 决策

### D1：DamageType 六活跃值 + MAGIC 退役段（不删值）

方案原文假设 DamageType 走 ProtoNumber 编号制、MAGIC 编号 reserved。**实测修正**：`DamageType`/`EquipStat`/`ManualSkill.damageType` 全部按 kotlinx **枚举 name 字符串**序列化——删枚举值会使存量档 `valueOf("MAGIC")` 抛异常读档失败。故：

- 枚举保留 `MAGIC`/`MAGIC_DAMAGE_PCT` 退役段（存档兼容解析），活跃集由 `DamageType.ACTIVE`（恰 6 值）/`EquipAffixPool`（11 项不含退役段）钉死；
- `DamageTypeGuardTest` 守卫：退役段存在于枚举、不进活跃集、fromElement 不映射；
- `plusStat`/`plusEquipStat` 对退役段词条**静默不并入任何通道**（旧档残留词条失效而非崩溃）。

### D2：灵根 gate 为构造期开关（1/0），战斗期零开销

gate（方案 §3.3：灵根含该元素→加成全额，不含→0）落在**弟子侧汇总点**（`DiscipleStatCalculator.typeDamageBonusesOf`）：装备词条原始值 → 按弟子灵根集合折算 → Combatant 桶存**gate 后生效值**。战斗公式（`mergeTypeChannels`）不感知 gate。物理通道恒 gate=1.0（普攻人人物理，E11）。

三条弟子 Combatant 装配线（BattleSystem/AISectAttackManager/CaveExplorationSystem）统一走该旁路；妖兽/散修/试炼敌人**不经 gate**（敌人按元素配装口径，试炼敌人类型加成全额生效）。

### D3：对拍无"基线重录"——活体双臂比对

方案 E1 预设对拍基线需一次性重录。实测修正：`Diff*Test` 家族是**双臂活对比**（Kotlin 本地算 vs C++ 经 DiffRngBridge JSON op 回传），无 golden 文件。两端同批改后协议面逐键对齐（`BattleJsonCodec` ↔ `battle_json.h` 12 桶）即绿——本次 Diff 家族全绿验证了该机制。

### D5：普攻类型是角色配置数据，不是架构恒等式（2026-09-30 架构修正）

实施初版把方案 §3.2 的"普攻恒为 PHYSICAL"落成了**硬编码**：战斗管线直接写死 `PHYSICAL`、`innateDamageType` 标记"退役不读取"、C++/Kotlin 测试断言 innateDamageType 无效。**用户纠正**：当前所有角色普攻是物理是**版本内容设定**，不代表未来角色普攻都是物理。返工为数据驱动：

- 战斗管线（Kotlin `computeDamagePipeline`/`tryDodge`/`tryInstantKill`/`calculateDamage` 与 C++ 对偶）普攻臂**恢复读取 `attacker.innateDamageType`**；
- 三条弟子装配线恢复 `innateDamageType = resolvedInnateDamageType()` 传参（模板显式值优先，非法值兜底物理）；
- `InnateDamageType.derive` 旧"首灵根物法二分派生"随 MAGIC 退役（兜底物理）；C++ `auto_gear.h` 对偶同步；
- **四个角色模板数据修正**：suqing/linxuetang/xuhe/zhaoyan 原配 `MAGIC`（MAGIC 时代内容），按当前设定改为 `PHYSICAL`——净行为与上一交付态（恒物理）逐位一致；
- `ElementalDamageSystemTest` E2 拆为「当前全物理内容数据守卫」+「普攻类型跟随 innateDamageType 配置」两用例（C++ `single_column_stat_test` 同语义）；
- 妖兽/人形敌人构造点维持默认 PHYSICAL（字段活语义，未来可按兽种配置）。

**净行为变化 = 0**（相对上一交付态），恢复的是"普攻类型可配置"的架构能力。

### D4：功法元素映射为内容决策，随实施落表

78 条 `skillDamageType="magic"` 功法按名义语义映射五行（雷属金/庚金雷法、腐蚀毒属木、因果诅咒系属木、护体系水/木、寂灭太初崩坏属土），保证五系攻击功法全覆盖（ER4 底线：每元素 ≥1 系功法）。映射表显式落在中性源（`scripts/data/manual_db_sample.json` + `assets/data/manuals.json` 双改，守卫逐字节一致）。

## 3. 后果

**正面**：灵根从背景设定变为配装约束（双灵根角色可吃两系加成=策略点）；六套同构骨架（3 模板 × 6 实例）数据量最小；五行相克/元素反应架构已预留（类型按元素索引，插矩阵只需加一层查表，债 I-E1/I-E3）。

**代价与登记**：
- 装备贡献占比带重锚：低品阶入口中位 2–3.6pp 下移（词条池权重 15+15→7+30、4 件套统一暴击率骨架的直接后果），`EquipmentPowerParityTest` 带按 B4 方法论重锚 [0.30,0.45]（E12 校准授权，测试 KDoc 登记实测表）；
- `gen-manual-db.mjs` 曾落后于手工增强的 `manual_db.h`（缺 operator==/Mutable 容器生成）——本批补齐生成器正根，G0 幂等恢复；
- 预存问题顺手修复：三个 bench 文件 B3 后从未同步（ctest 门禁不含 bench 目标）——`armorIds→bodyIds`/`slot→part` 适配 + 列长==弟子行数不变量修复（TimingPerPhase SEGFAULT 根因）；
- 债表：I-E1 相克 / I-E2 灵根字符串 / I-E3 元素反应 / I-E4 gate 线性折算退路 / I-E6 innateDamageType 冗余（方案 §九 原文有效）。
