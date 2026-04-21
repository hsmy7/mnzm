# 物品堆叠系统问题报告

> 审查日期：2026-04-22
> 当前版本：2.3.09
> 审查范围：InventorySystem、StackableItemUtils、DiscipleService、OptimizedWarehouseManager、SectWarehouseManager、InventoryConfig

---

## 一、游戏设定基准

| 物品类型 | 设定 maxStack | 代码当前值 | 是否一致 |
|----------|-------------|-----------|---------|
| 装备 (equipment_stack) | 999 | 99 | ❌ |
| 功法 (manual_stack) | 999 | 99 | ❌ |
| 丹药 (pill) | 999 | 999 | ✅ |
| 材料 (material) | 9999 | 9999 | ✅ |
| 草药 (herb) | 9999 | 999 | ❌ |
| 种子 (seed) | 9999 | 99 | ❌ |

---

## 二、问题清单

### 🔴 P0 - 严重问题

#### P0-1: InventoryConfig 堆叠上限与游戏设定不符

- **文件**: `InventoryConfig.kt` (第 24-32 行)
- **现状**: equipment_stack=99, manual_stack=99, herb=999, seed=99
- **期望**: equipment_stack=999, manual_stack=999, herb=9999, seed=9999
- **影响**: 装备/功法最多只能堆叠 99 个，草药最多 999，种子最多 99，远低于设定值，玩家正常游戏会频繁达到上限

#### P0-2: StackableItemUtils 合并不检查 maxStack 上限

- **文件**: `StateFlowListUtils.kt` (第 301、334、370 行)
- **现状**: `addStackable` / `addStackableSuspend` / `addStackableBatch` 合并时直接 `getQuantity(existing) + getQuantity(item)`，无上限截断
- **期望**: 合并后数量应 `coerceAtMost(maxStack)`
- **影响**: 通过 StackableItemUtils 添加的物品数量可无限叠加，绕过堆叠上限

#### P0-3: DiscipleService 硬编码 maxStack=999，与配置不一致

- **文件**: `DiscipleService.kt` (第 557、575、594、731 行)
- **现状**: 逐出弟子归还装备/功法时使用 `.coerceAtMost(999)` 硬编码
- **期望**: 应使用 `InventoryConfig.getMaxStackSize()` 获取配置值
- **影响**:
  - 当前装备/功法 maxStack=99，DiscipleService 允许到 999，与 InventorySystem 行为不一致
  - 即使修复 P0-1 后 maxStack 变为 999，硬编码仍会在未来配置变更时产生不一致

#### P0-4: 超出 maxStack 的溢出物品被静默丢弃

- **文件**: `InventorySystem.kt` (第 231 行等所有 addXxx 方法)
- **现状**: `(existing.quantity + item.quantity).coerceAtMost(maxStack)` 直接截断，返回 `AddResult.SUCCESS`
- **期望**: 应区分完全成功和部分成功，或至少返回溢出数量供调用方处理
- **影响**: 玩家添加物品时，超出上限的部分凭空消失且无任何提示。例如已有 990 颗丹药再添加 20 颗，11 颗丢失

---

### 🟡 P1 - 中等问题

#### P1-1: canAddXxx 未考虑堆叠已达上限

- **文件**: `InventorySystem.kt` (第 170-210 行)
- **现状**: `canAddEquipment` 等方法只检查是否存在同名物品可合并，不检查已有堆叠是否已达 maxStack
- **影响**: `canAddEquipment` 返回 true 但实际添加时数量被截断，UI 层可能显示可添加但实际无法完整添加

#### P1-2: OptimizedWarehouseManager 合并不检查 maxStack 上限

- **文件**: `OptimizedWarehouseManager.kt` (第 296、327 行)
- **现状**: `addItem` / `addItemsBatch` 合并时 `existing.quantity + item.quantity` 无上限
- **影响**: 宗门仓库物品数量可无限叠加

#### P1-3: SectWarehouseManager legacy 路径合也不检查 maxStack

- **文件**: `SectWarehouseManager.kt` (第 212 行)
- **现状**: `addItemToWarehouseLegacy` 同样无上限检查
- **影响**: 与 P1-2 相同

#### P1-4: removeXxx 与 removeXxxByName 边界处理不一致

- **文件**: `InventorySystem.kt`
- **现状**:
  - `removeXxx` (按 ID): `newQty < 0` 保留原物品 + 警告, `newQty == 0` 删除
  - `removeXxxByName`: `newQty <= 0` 直接删除（前置 quantity 检查降低了风险，但逻辑不一致）
- **影响**: 如果前置检查逻辑变更，removeXxxByName 可能静默删除数量不足的堆叠

#### P1-5: addSeedSync 存在竞态条件

- **文件**: `InventorySystem.kt` (第 1273-1303 行)
- **现状**: 无事务时先读快照判断容量，再在 `stateStore.update` 中重新查找添加，两次操作间状态可能变化
- **影响**: 并发场景下容量判断可能不准确

---

### 🟢 P2 - 轻微问题

#### P2-1: StackableItemUtils 与 InventorySystem 大量重复逻辑

- **文件**: `StateFlowListUtils.kt` vs `InventorySystem.kt`
- **现状**: InventorySystem 为 6 种物品类型各手写了 add/remove/update 逻辑，未复用 StackableItemUtils
- **影响**: 逻辑修改需同步多处，容易产生行为不一致（P0-2 正是此问题导致）

#### P2-2: 各类型合并匹配字段粒度差异大，缺乏文档

- **现状**:
  - EquipmentStack: name + rarity + slot
  - ManualStack: name + rarity + type
  - Pill: name + rarity + category + grade
  - Material: name + rarity + category
  - Herb: name + rarity + category
  - Seed: name + rarity + growTime
- **影响**: 不同类型匹配粒度差异大，新开发者难以理解设计意图

#### P2-3: 测试覆盖不完整

- **文件**: `InventorySystemTest.kt`
- **缺失测试**:
  - maxStack 上限截断测试
  - 溢出物品丢失测试
  - Herb/Seed 合并测试
  - `returnEquipmentToStack` / `returnManualToStack` 测试
  - `canAddXxx` 堆叠已满时的行为测试
  - StackableItemUtils 的合并测试

---

## 三、问题关联分析

```
P0-1 (配置错误)
  ├─→ P0-3 (DiscipleService 硬编码 999，当前配置为 99，行为矛盾)
  ├─→ P1-1 (canAddXxx 基于错误的上限判断)
  └─→ P0-4 (溢出丢失在错误上限下更易触发)

P0-2 (StackableItemUtils 无上限)
  └─→ P2-1 (重复逻辑导致行为不一致)

P1-2 + P1-3 (仓库无上限)
  └─→ 仓库物品数量不受控
```

---

## 四、修复优先级建议

| 优先级 | 问题编号 | 修复内容 |
|--------|---------|---------|
| 1 | P0-1 | 修正 InventoryConfig 堆叠上限为游戏设定值 |
| 2 | P0-3 | DiscipleService 改用 InventoryConfig 获取 maxStack |
| 3 | P0-2 | StackableItemUtils 增加 maxStack 参数和上限检查 |
| 4 | P0-4 | InventorySystem 溢出时返回部分成功结果或溢出数量 |
| 5 | P1-1 | canAddXxx 增加堆叠上限检查 |
| 6 | P1-2/P1-3 | 仓库合并增加 maxStack 上限 |
| 7 | P1-4 | 统一 remove 边界处理逻辑 |
| 8 | P1-5 | addSeedSync 竞态修复 |
| 9 | P2-1~P2-3 | 代码重构和测试补充 |

---

## 五、涉及文件清单

| 文件 | 涉及问题 |
|------|---------|
| `core/config/InventoryConfig.kt` | P0-1 |
| `core/util/StateFlowListUtils.kt` | P0-2 |
| `core/engine/system/InventorySystem.kt` | P0-4, P1-1, P1-4, P1-5 |
| `core/engine/service/DiscipleService.kt` | P0-3 |
| `core/warehouse/OptimizedWarehouseManager.kt` | P1-2 |
| `core/engine/SectWarehouseManager.kt` | P1-3 |
| `core/engine/system/InventorySystemTest.kt` | P2-3 |
