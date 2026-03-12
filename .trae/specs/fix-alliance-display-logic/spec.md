# 修复盟友显示逻辑 Spec

## Why

当前盟友显示逻辑存在错误：只检查宗门是否有 allianceId，而没有检查该 alliance 是否包含玩家。这导致盟友的盟友也会显示为玩家的盟友，只要和一个宗门结盟就能实现与所有宗门结盟的效果。

## What Changes

- 修复 UI 中盟友判断逻辑，确保只有与玩家直接结盟的宗门才显示为盟友
- 修改 MainGameScreen.kt 中的 isAlly 判断

## Impact

- Affected specs: 宗门结盟系统
- Affected code: MainGameScreen.kt

## ADDED Requirements

### Requirement: 盟友显示准确性

系统应准确判断并显示与玩家结盟的宗门。

#### Scenario: 直接盟友显示
- **GIVEN** 玩家与宗门A直接结盟
- **WHEN** 玩家查看宗门A
- **THEN** 宗门A显示为"盟友"

#### Scenario: 非直接盟友不显示
- **GIVEN** 玩家与宗门A结盟，宗门A与宗门B结盟（玩家与B无结盟关系）
- **WHEN** 玩家查看宗门B
- **THEN** 宗门B不显示为"盟友"

#### Scenario: AI间结盟不影响玩家
- **GIVEN** 宗门A与宗门B结盟（玩家与A、B均无结盟关系）
- **WHEN** 玩家查看宗门A或宗门B
- **THEN** 宗门A和宗门B都不显示为玩家的"盟友"

## MODIFIED Requirements

无

## REMOVED Requirements

无

---

## 技术方案

### 问题分析

当前代码中的问题：
```kotlin
// 错误：只检查是否有 allianceId
val isAlly = sect.allianceId != null
```

这会导致任何有结盟关系的宗门都被显示为玩家的盟友。

### 修复方案

使用 GameViewModel 中已有的 `isAlly(sectId)` 方法，该方法正确检查了 alliance 是否包含 "player"：

```kotlin
// GameEngine.kt 中的正确实现
fun isAlly(sectId: String): Boolean {
    val data = _gameData.value
    val sect = data.worldMapSects.find { it.id == sectId } ?: return false
    return sect.allianceId != null && data.alliances.any { it.sectIds.contains("player") && it.sectIds.contains(sectId) }
}
```

### 修改点

1. `MainGameScreen.kt` 第 4538 行附近 - SectListItem 组件
2. `MainGameScreen.kt` 第 4716 行附近 - SectTradeDialog 组件

需要将 `sect.allianceId != null` 替换为调用 `viewModel.isAlly(sect.id)` 或传递正确的 isAlly 参数。
