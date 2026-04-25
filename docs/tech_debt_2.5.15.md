# 技术债务报告 v2.5.15

## 生成日期
2026-04-25

---

## 背景

本次报告汇总了 v2.5.13 → v2.5.15 期间代码复查专家提出的、**尚未实施**的改进建议。这些建议涉及架构优化、线程安全改进和代码质量提升，当前版本已通过编译验证，功能正确，以下问题不影响现有功能，但建议在未来版本中逐步处理。

---

## 一、equipEquipment TOCTOU 风险（优先级：高）

### 问题描述

[DiscipleService.equipEquipment](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/DiscipleService.kt#L629) 方法的验证逻辑在 `stateStore.update {}` 事务外执行：

```kotlin
fun equipEquipment(discipleId: String, equipmentId: String): Boolean {
    val disciple = currentDisciples.find { it.id == discipleId } ?: return false
    // ... 境界检查、已装备检查等验证 ...
    
    scope.launch {
        stateStore.update {
            // 实际装备操作（异步执行）
        }
    }
    return true
}
```

验证时读取的 `currentDisciples` 是 StateFlow 的快照，到 `scope.launch { stateStore.update {} }` 实际执行之间，状态可能已被其他操作修改。可能导致：
- 弟子境界已降低，但装备仍被成功穿戴（境界不足）
- 装备已被其他弟子穿戴，但当前操作仍成功执行
- 弟子已被删除，但操作仍尝试执行

### 当前缓解措施

- 事务内重新查找 disciple 和 equipment，基础安全性有保障
- `unequipEquipmentLogic` 返回值已检查，卸装失败时中止流程

### 建议修复方案

将 `equipEquipment` 改为 suspend 函数，验证和实际执行都在 `stateStore.update` 事务内完成：

```kotlin
suspend fun equipEquipment(discipleId: String, equipmentId: String): Boolean {
    return stateStore.update {
        val disciple = disciples.find { it.id == discipleId } ?: return@update false
        // ... 验证逻辑 ...
        // ... 实际装备操作 ...
        true
    }
}
```

**影响范围**：所有调用 `equipEquipment` 的代码（GameEngine、UI 层等）都需要改为 suspend 调用。

---

## 二、共用方法泛型化（优先级：中）

### 问题描述

[BagUtils.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/BagUtils.kt) 中 `addEquipmentInstanceToDiscipleBag` 和 `addManualInstanceToDiscipleBag` 的核心逻辑高度重复：

1. 查找已有栈（条件：name/rarity/type 匹配 + id in bagStackIds + id != excludeStackId + quantity < maxStackSize）
2. 合并或新建栈
3. 删除实例
4. 创建 StorageBagItem
5. 更新弟子储物袋

仅数据类型不同（EquipmentInstance vs ManualInstance, EquipmentStack vs ManualStack）。

### 当前状态

两个方法已提取到同一文件中，逻辑一致，但仍有约 60 行重复代码。

### 建议修复方案

方案 A：提取泛型方法（推荐）

```kotlin
private inline fun <reified S : ItemStack, reified I : ItemInstance> MutableGameState.addInstanceToDiscipleBag(
    stacks: List<S>,
    instances: List<I>,
    // ... 其他参数
): AddToBagResult {
    // 泛型实现
}
```

方案 B：使用策略模式/接口抽象

为 Equipment 和 Manual 定义统一接口（`StackableItem`, `InstanceItem`），将差异部分参数化。

**难点**：Kotlin 泛型与当前数据类结构（EquipmentStack/ManualStack 无共同基类）的适配需要一定重构成本。

---

## 三、其他次要建议

| 建议 | 优先级 | 说明 |
|------|--------|------|
| `increaseItemQuantity` 后的 `.map` 在新建栈场景冗余 | 低 | 新建栈时 `storageItem` 已包含正确 forget 日期，`.map` 再次设置是冗余的。仅合并场景需要更新已有条目。当前不影响正确性，仅轻微性能浪费。 |
| storageBagItems 访问路径统一 | 低 | 代码中混用 `disciple.equipment.storageBagItems` 和 `disciple.storageBagItems`（后者委托到前者）。建议统一使用 `disciple.equipment.storageBagItems` 以明确数据来源。 |
| `equipmentBagStackIds` 遍历性能 | 低 | 每次调用遍历弟子储物袋。当前储物袋规模较小，影响可忽略。未来规模增大时可考虑缓存。 |

---

## 四、后续行动计划

| 优先级 | 事项 | 建议版本 |
|--------|------|----------|
| 高 | `equipEquipment` 改为 suspend 函数，消除 TOCTOU 风险 | v2.5.16 |
| 中 | `unequipEquipment` 同步路径也改为 suspend 函数，统一异步语义 | v2.5.16 |
| 中 | BagUtils 泛型化，消除两个共用方法之间的重复 | v2.6.0 |
| 低 | 统一 storageBagItems 访问路径 | 任意维护版本 |
| 低 | 优化 `.map` 冗余操作 | 任意维护版本 |

---

## 五、相关代码链接

- [DiscipleService.kt - equipEquipment](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/DiscipleService.kt#L629)
- [DiscipleService.kt - unequipEquipment](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/DiscipleService.kt#L729)
- [BagUtils.kt - addEquipmentInstanceToDiscipleBag](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/BagUtils.kt#L14)
- [BagUtils.kt - addManualInstanceToDiscipleBag](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/BagUtils.kt#L68)
- [GameEngine.kt - equipItem](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L1382)
