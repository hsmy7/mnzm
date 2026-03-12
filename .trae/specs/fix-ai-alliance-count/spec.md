# 修复单个AI宗门与多个宗门结盟的问题

## Why

在 `processAIAlliances` 方法中，检查AI宗门结盟数量时使用的是原始的 `data.alliances`，而不是包含新添加结盟的列表。这导致单个AI宗门可以在同一年度结算中与多个宗门结盟，违反了"每个AI宗门最多同时拥有2个盟友"的限制。

## What Changes

- 修改 `processAIAlliances` 方法中的结盟数量检查逻辑，在计算结盟数量时同时考虑原始数据和新添加的结盟

## Impact

- Affected specs: 宗门结盟系统
- Affected code: GameEngine.kt

## ADDED Requirements

### Requirement: AI结盟数量实时检查

系统应在AI结盟过程中实时检查结盟数量，确保单个AI宗门最多同时拥有2个盟友。

#### Scenario: AI宗门结盟数量限制
- **GIVEN** AI宗门A已有1个盟友
- **WHEN** 年度结算时AI宗门A尝试与其他宗门结盟
- **THEN** AI宗门A只能再结盟1次（达到上限2个）
- **AND** 后续尝试结盟时应被跳过

#### Scenario: 结盟数量检查包含新添加的结盟
- **GIVEN** AI宗门A与AI宗门B刚刚结盟（在同一次年度结算中）
- **WHEN** 检查AI宗门A是否可以与AI宗门C结盟
- **THEN** 系统应计算A的结盟数量 = 原有结盟数 + 新添加的结盟数
- **AND** 如果总数已达上限，则跳过结盟

## MODIFIED Requirements

无

## REMOVED Requirements

无

---

## 技术分析

### 问题根因

```kotlin
// 当前代码（有问题）
val allianceCount1 = data.alliances.count { it.sectIds.contains(sect1.id) }
if (allianceCount1 >= 2) return@forEach
```

问题：`data.alliances` 是原始数据，不包含在当前循环中已添加的新结盟。

### 修复方案

```kotlin
// 修复后的代码
val allianceCount1 = data.alliances.count { it.sectIds.contains(sect1.id) } +
                     newAlliances.count { it.sectIds.contains(sect1.id) }
if (allianceCount1 >= 2) return@forEach
```

同样需要修复 `allianceCount2` 的计算。
