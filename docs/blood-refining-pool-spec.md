# 血炼池 — 产品执行文档

> 版本：v1.0 | 日期：2026-06-03 | 作者：世界顶级产品经理视角

---

## 一、功能概述

新增建筑"血炼池"——消耗妖兽精血材料淬炼弟子肉身，永久提升战斗属性。每品阶材料每弟子限一次。

**核心循环**：建血炼池 → 放入妖血材料 → 选空闲弟子 → 消耗灵石开始洗炼 → 弟子进入"血炼中"状态持续 N 个月 → 完成后弟子获得随机属性加成。

---

## 二、属性提升规则

### 血种→属性映射（随机二选一，各50%）

| 材料血种 | 属性A | 属性B | 影响字段 |
|----------|-------|-------|---------|
| 蛇血（snakeBlood） | 速度 | 气血 | `combat.baseSpeed` / `combat.baseHp` |
| 虎血（tigerBlood） | 物攻 | 法攻 | `combat.basePhysicalAttack` / `combat.baseMagicAttack` |
| 龟血（turtleBlood） | 物防 | 法防 | `combat.basePhysicalDefense` / `combat.baseMagicDefense` |

### 品阶→提升幅度 & 消耗时间

| 品阶 | Tier | 前缀 | 提升幅度 | 洗炼时间 |
|------|------|------|---------|---------|
| 凡品 | 1 | 凡 | 1% | 1 个月 |
| 灵品 | 2 | 灵 | 3% | 3 个月 |
| 宝品 | 3 | 宝 | 6% | 6 个月 |
| 玄品 | 4 | 玄 | 12% | 12 个月 |
| 地品 | 5 | 地 | 20% | 20 个月 |
| 天品 | 6 | 天 | 30% | 30 个月 |

> 提升公式：`baseStat = baseStat + (baseStat * percentage).toInt()`，最少提升 1 点。
> 洗炼期间弟子状态变为"血炼中"，无法进行其他操作。时间到后自动完成，属性生效。

### 限制

- 每个弟子对每个**具体材料 ID**（如 `tigerBlood0`、`snakeBlood3`）只能洗炼一次
- 总计 3 种血 × 6 品阶 = 最多 18 次洗炼/弟子

---

## 三、建筑属性

| 属性 | 值 |
|------|-----|
| ID | `blood_refining_pool` |
| 名称 | 血炼池 |
| 费用 | 50,000 灵石 |
| 占地面积 | 2×2（4 格） |
| 建造上限 | 无限制 |
| 精灵图 | `blood_refining_pool.png`（已存在于 drawable-nodpi） |
| BuildingType | 新增 `BLOOD_REFINING_POOL` |

---

## 四、洗炼消耗

| 消耗项 | 数量 |
|--------|------|
| 妖血材料 | 200 个（同一种材料，如 200 个凡虎血） |
| 灵石 | 1,000,000 |

---

## 五、界面设计（半屏弹窗）

### 空闲态（全部居中）

```
┌─────────────────────────────────────┐
│                                     │
│           血  炼  池                │  ← 居中标题
│                                     │
│       ┌─────────────────┐          │
│       │    [精灵图]       │          │  ← 材料区域（居中）
│       │     "材料"        │          │
│       └─────────────────┘          │
│                                     │
│       消耗 100 万灵石               │  ← 红色小字（居中）
│                                     │
│       ┌─────────────────┐          │
│       │    [精灵图]       │          │  ← 弟子区域（居中）
│       │    "空闲弟子"     │          │
│       └─────────────────┘          │
│                                     │
│             12月                     │  ← 时间（居中）
│                                     │
│       ┌─────────────────┐          │
│       │     洗    炼     │          │  ← 按钮（居中）
│       └─────────────────┘          │
│                                     │
└─────────────────────────────────────┘
```

### 已选材料+弟子态（全部居中）

```
┌─────────────────────────────────────┐
│                                     │
│           血  炼  池                │
│                                     │
│       ┌─────────────────┐          │
│       │  🩸 凡虎血  x200  │          │
│       └─────────────────┘          │
│                                     │
│       消耗 100 万灵石               │
│                                     │
│       ┌─────────────────┐          │
│       │  🧑 张三  空闲中   │          │
│       └─────────────────┘          │
│                                     │
│             12月                     │
│                                     │
│       ┌─────────────────┐          │
│       │     洗    炼     │          │
│       └─────────────────┘          │
│                                     │
└─────────────────────────────────────┘
```

### 选择材料弹窗（按血种分组 + 高品阶在前，无分组标题）

```
┌─────────────────────────────────────┐
│         选择妖兽精血                │
│                                     │
│  ┌──────┐ ┌──────┐ ┌──────┐       │
│  │天虎血│ │地虎血│ │玄虎血│ ...     │  ← 虎血（tier 6→1）
│  │x300  │ │x250  │ │x210  │       │
│  └──────┘ └──────┘ └──────┘       │
│  ┌──────┐ ┌──────┐               │
│  │宝虎血│ │灵虎血│ ...             │
│  │x200  │ │x200  │               │
│  └──────┘ └──────┘               │
│                                     │
│  ┌──────┐ ┌──────┐ ┌──────┐       │
│  │天蛇血│ │地蛇血│ │玄蛇血│ ...     │  ← 蛇血（tier 6→1）
│  │x500  │ │x400  │ │x200  │       │
│  └──────┘ └──────┘ └──────┘       │
│                                     │
│  ┌──────┐ ┌──────┐                │
│  │天龟血│ │地龟血│ ...             │  ← 龟血（tier 6→1）
│  │x100  │ │x200  │               │  ← 不足200置灰
│  └──────┘ └──────┘                │
│                                     │
└─────────────────────────────────────┘
```

### 进行中态（全部居中）

```
┌─────────────────────────────────────┐
│                                     │
│           血  炼  池                │
│                                     │
│       ┌─────────────────┐          │
│       │  🩸 凡虎血  x200  │          │
│       └─────────────────┘          │
│                                     │
│       消耗 100 万灵石               │
│                                     │
│       ┌─────────────────┐          │
│       │  🧑 张三  血炼中   │          │
│       └─────────────────┘          │
│                                     │
│             8月                      │
│                                     │
│       ┌─────────────────┐          │
│       │  血炼中...（灰显） │          │
│       └─────────────────┘          │
│                                     │
└─────────────────────────────────────┘
```

### 交互流程

1. **材料槽位**：空闲时显示血炼池精灵图 + "材料"文字。放入材料后以**物品卡片形式**展示（材料精灵图 + 名称 + 数量）。点击弹出材料选择界面（仅显示仓库中 category=blood 的材料：蛇血/虎血/龟血），每个材料以 `UnifiedItemCard` 展示。材料卡片复用 `EquipmentSprite` 的精灵图映射。

   **选择界面排序规则**：
   - 按血种分组显示：虎血 → 蛇血 → 龟血，每组一个区域标题
   - 每组内按品阶降序：天品 → 地品 → 玄品 → 宝品 → 灵品 → 凡品（tier 6→1）
   - 仅显示仓库数量 ≥200 的材料；不足 200 的置灰不可选
   
   选择材料后，"XX月"时间自动更新为该品阶对应的耗时。
2. **弟子槽位**：空闲时显示默认头像 + "空闲弟子"文字。点击弹出弟子选择界面，过滤条件：①存活 ②状态=空闲中。**额外过滤**：如果当前已选材料已被某弟子洗炼过，该弟子在列表中置灰不可选。
3. **洗炼按钮**：
   - 灵石不足 → 灰色不可点击
   - 材料不足 200 → 灰色不可点击（由材料槽位保证）
   - 未选弟子 → 灰色不可点击
   - 全部满足 → 可点击，开始洗炼
4. **红色小字**：位于材料槽位与弟子槽位之间（弟子槽位上方），槽位外部。文字"消耗 100 万灵石"，红色（`Color(0xFFCC0000)`），字号 13sp。
5. **时间显示**：位于弟子槽位下方、按钮上方，槽位外部。空闲/选择态显示总时长如"12月"，进行中态显示剩余时间如"8月"（仅数字+月，不显示进度分数）。黑色加粗，字号 14sp。
6. **洗炼执行**：点击洗炼后：①扣除 100 万灵石 ②扣除 200 材料 ③随机选择属性（50/50） ④弟子状态变为"血炼中" ⑤记录洗炼进度（开始时间、总月数、选中属性、加成比例） ⑥按钮变为"血炼中..."灰显。
7. **自动完成**：每月结算时检查洗炼进度。到期后：①计算并应用属性加成 ②将材料ID追加到 bloodRefinements[discipleId] ③弟子状态恢复为空闲 ④弹 Toast 通知结果 ⑤清除 activeBloodRefinements 条目。

---

## 六、实现清单

### 6.1 建筑注册（4 处）

| # | 文件 | 改动 |
|---|------|------|
| 1 | `buildings.json` | 新增 `blood_refining_pool` 条目 |
| 2 | `BuildingRegistry.kt` | 新增 `BLOOD_REFINING_POOL` 枚举值 |
| 3 | `BuildingNames.kt` | 新增映射 `"blood_refining_pool" to "血炼池"` |
| 4 | `ProductionSlot.kt` | `BuildingType` 枚举新增 `BLOOD_REFINING_POOL` |

### 6.2 建筑精灵图

已就绪：`res/drawable-nodpi/blood_refining_pool.png`

### 6.3 数据层（5 处）

| # | 文件 | 改动 |
|---|------|------|
| 5 | `GameData.kt` | 新增字段 `var bloodRefinements: Map<String, List<String>> = emptyMap()`（discipleId → 已完成的材料ID列表）。新增字段 `var activeBloodRefinements: Map<String, BloodRefinementProgress> = emptyMap()`（buildingInstanceId → 进行中的洗炼）。均加 `@SettlementStrategy(SHADOW)`。**需 DB Migration**（2 个 safe add column） |
| 6 | `DiscipleStatCalculator.kt` | 新增 `applyBloodRefinementBonuses(disciple, bloodRefinements)` — 遍历已完成材料，计算并累加属性加成。在 `getBaseStats()` 末尾调用 |
| 7 | `BeastMaterialDatabase.kt` | 新增辅助方法：`fun getBloodMaterials(): List<BeastMaterial>`、`fun getTierPercentage(tier: Int): Double`、`fun getTierDuration(tier: Int): Int` |
| 8 | `SettlementCoordinator.kt` | 月度结算中新增血炼进度检查：遍历 activeBloodRefinements，到期则完成洗炼 |
| 9 | `ui/game/BloodRefiningViewModel.kt` | 新建。管理槽位状态、验证、洗炼启动 |

**BloodRefinementProgress 数据结构**：

```kotlin
@Serializable
data class BloodRefinementProgress(
    val discipleId: String,
    val discipleName: String,
    val materialId: String,
    val materialName: String,
    val startYear: Int,
    val startMonth: Int,
    val durationMonths: Int,
    val selectedStat: String,    // "speed"/"hp"/"physicalAttack"/"magicAttack"/"physicalDefense"/"magicDefense"
    val bonusPercent: Double
)
```

**每月结算检查逻辑**（在 SettlementCoordinator 月度阶段末尾）：

```kotlin
// 检查血炼进度
val completedRefinements = mutableListOf<String>()  // buildingInstanceId
for ((buildingId, progress) in state.gameData.activeBloodRefinements) {
    if (TimeProgressUtil.isTimeElapsed(progress.startYear, progress.startMonth, progress.durationMonths, currentYear, currentMonth)) {
        // 完成洗炼
        val d = state.disciples.find { it.id == progress.discipleId } ?: continue
        val bonus = (d.combat.getBaseStatValue(progress.selectedStat) * progress.bonusPercent).toInt().coerceAtLeast(1)
        val newCombat = d.combat.applyStatBonus(progress.selectedStat, bonus)
        val newDisciple = d.copy(
            combat = newCombat,
            status = DiscipleStatus.IDLE,
            statusData = ""
        )
        state.disciples = state.disciples.map { if (it.id == d.id) newDisciple else it }
        
        // 记录已完成
        val currentRefinements = state.gameData.bloodRefinements.toMutableMap()
        currentRefinements[d.id] = (currentRefinements[d.id] ?: emptyList()) + progress.materialId
        state.gameData = state.gameData.copy(bloodRefinements = currentRefinements)
        
        completedRefinements.add(buildingId)
    }
}
// 清除已完成的
if (completedRefinements.isNotEmpty()) {
    val remaining = state.gameData.activeBloodRefinements.toMutableMap()
    completedRefinements.forEach { remaining.remove(it) }
    state.gameData = state.gameData.copy(activeBloodRefinements = remaining)
}
```

**ViewModel 核心逻辑**：

```kotlin
fun startRefine() {
    stateStore.update {
        // 1. 验证灵石、材料、弟子
        // 2. 扣除灵石 + 材料
        // 3. 随机选择属性
        // 4. 弟子状态 → 血炼中
        val d = disciples.find { it.id == selectedDiscipleId }!!
        disciples = disciples.map { if (it.id == d.id) d.copy(status = DiscipleStatus.BUSY, statusData = "血炼中") else it }
        // 5. 记录进度
        val progress = BloodRefinementProgress(
            discipleId = d.id, discipleName = d.name,
            materialId = selectedMaterial.id, materialName = selectedMaterial.name,
            startYear = gameData.gameYear, startMonth = gameData.gameMonth,
            durationMonths = selectedMaterial.tierDuration,
            selectedStat = selectedStat, bonusPercent = selectedMaterial.tierPercent
        )
        gameData = gameData.copy(
            activeBloodRefinements = gameData.activeBloodRefinements + (buildingInstanceId to progress)
        )
    }
}
```

### 6.5 UI 层（1 处新建 + 1 处修改）

| # | 文件 | 改动 |
|---|------|------|
| 9 | `ui/game/dialogs/BloodRefiningPoolDialog.kt` | 新建。半屏弹窗，包含标题、材料槽位（ItemCard）、弟子槽位（DiscipleSlotWithActions）、红色提示文字、洗炼按钮 |
| 10 | `ui/game/GameViewModel.kt` | 新增打开血炼池弹窗的方法 + `DialogType.BLOOD_REFINING_POOL` |

### 6.6 血种→属性映射表（在 ViewModel 或 Database 中定义）

```kotlin
data class BloodRefineRule(
    val bloodType: String,       // "snake", "tiger", "turtle"
    val statA: String,           // "speed" / "physicalAttack" / "physicalDefense"
    val statB: String,           // "hp" / "magicAttack" / "magicDefense"
    val statAField: (CombatAttributes) -> Int,  // 读取函数
    val statBField: (CombatAttributes) -> Int,
    // ...
)

val BLOOD_RULES = mapOf(
    "snake" to BloodRefineRule("snake", "速度", "气血", ...),
    "tiger" to BloodRefineRule("tiger", "物攻", "法攻", ...),
    "turtle" to BloodRefineRule("turtle", "物防", "法防", ...),
)

val TIER_PERCENTAGES = mapOf(
    1 to 0.01, 2 to 0.03, 3 to 0.06,
    4 to 0.12, 5 to 0.20, 6 to 0.30
)
```

---

## 七、界面组件复用清单

| 组件 | 来源 | 用途 |
|------|------|------|
| `UnifiedGameDialog` | `ui/components/UnifiedGameDialog.kt` | 半屏弹窗容器 |
| `UnifiedItemCard` | `ui/components/UnifiedItemCard.kt` | 材料展示卡片 |
| `UnifiedDiscipleSlot` | `ui/components/UnifiedDiscipleSlot.kt` | 弟子槽位展示 |
| `DiscipleSelectorDialog` | `ui/game/dialogs/shared/DiscipleSelectorDialog.kt` | 弟子选择弹窗 |
| `GameButton` | `ui/components/GameButton.kt` | 洗炼按钮 |
| `GameColors` | `ui/theme/` | 按钮颜色、红色提示文字 |
| `ButtonSizes` | `ui/theme/` | 72×38dp 标准按钮 |

---

## 八、DB Migration 说明

**GameData.kt 新增 2 个字段**：
```kotlin
@ColumnInfo(defaultValue = "{}")
var bloodRefinements: Map<String, List<String>> = emptyMap()

@ColumnInfo(defaultValue = "{}")
var activeBloodRefinements: Map<String, BloodRefinementProgress> = emptyMap()
```

- 均为 **safe add column**，不需要 `safeDropColumns`
- Migration 1：`ALTER TABLE game_data ADD COLUMN bloodRefinements TEXT NOT NULL DEFAULT '{}'`
- Migration 2：`ALTER TABLE game_data ADD COLUMN activeBloodRefinements TEXT NOT NULL DEFAULT '{}'`
- Protobuf 序列化：两个 Map 类型均需在 `ProtobufConverters.kt` 添加转换器
- `BloodRefinementProgress` 数据类需标注 `@Serializable`
- 当 `activeBloodRefinements` 中某 building 完成时，由月度结算自动清理条目

---

## 九、验收清单

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 1 | 建造血炼池，费用 50000 | 建筑出现在地图上，灵石扣除 |
| 2 | 点击血炼池 | 弹出半屏界面 |
| 3 | 材料槽位为空时 | 显示血炼池精灵图 + "材料" |
| 4 | 点击材料槽位 | 弹出材料选择，仅显示蛇血/虎血/龟血 |
| 5 | 选择凡虎血 200 个 | 材料槽位显示虎血图标 + "凡虎血 x200" |
| 6 | 弟子槽位为空时 | 显示默认头像 + "空闲弟子" |
| 7 | 点击弟子槽位 | 弹出弟子列表，仅显示空闲存活弟子 |
| 8 | 灵石不足 100 万 | 洗炼按钮灰色不可点击 |
| 9 | 灵石充足，点击洗炼 | 扣除灵石+材料，弟子状态变为"血炼中"，按钮灰显 |
| 10 | 洗炼进行中 | 显示"剩余 X / Y 月"进度，弟子不可操作 |
| 11 | 凡品蛇血 1 个月到期 | 速度或气血 +1%（随机），弟子恢复空闲 |
| 12 | 天品虎血 30 个月到期 | 物攻或法攻 +30%（随机） |
| 13 | 已洗炼过的材料+弟子组合 | 该弟子在选择列表中不可选 |
| 14 | 材料不足 200 | 无法放入槽位（选择时过滤） |
| 15 | 洗炼完成通知 | 弹 Toast："弟子[名]使用凡虎血洗炼完成，物攻+5" |
| 16 | 时间显示更新 | 选凡品材料显示"1月"，选天品显示"30月" |
| 17 | 红色小字+时间 | 始终位于两个槽位区域下方、按钮上方 |

---

## 十、风险与建议

| 风险 | 缓解 |
|------|------|
| 加成叠加后属性膨胀 | 每品阶限一次，理论最大 18 次 × 30% = 已有限制 |
| 洗炼结果通知不醒目 | 使用红色文字 + Toast 提示具体数值 |
| 材料 200 个消耗大 | 可后续加"批量洗炼"功能 |
| DB Migration 兼容性 | safe add column，不删不改现有列 |
