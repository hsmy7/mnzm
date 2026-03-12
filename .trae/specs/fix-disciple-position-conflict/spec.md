# 修复弟子职位冲突问题 Spec

## Why
弟子被任命为长老后，仍然可以出现在其他长老或亲传弟子槽位的选择弟子列表中，导致一名弟子能同时担任多个职位（多名长老和亲传弟子），这是严重的逻辑错误。

## What Changes
- 修复 `ElderDiscipleSelectionDialog` 组件，过滤掉已担任长老或亲传弟子的弟子
- 修复 `DirectDiscipleSelectionDialog` 组件，过滤掉已担任长老或亲传弟子的弟子
- 在 `GameViewModel.assignElder` 函数中添加验证逻辑，防止任命已在其他职位的弟子

## Impact
- Affected specs: 弟子管理系统、宗门管理系统
- Affected code: 
  - `MainGameScreen.kt` - ElderDiscipleSelectionDialog 和 DirectDiscipleSelectionDialog 组件
  - `GameViewModel.kt` - assignElder 函数

## ADDED Requirements

### Requirement: 弟子职位唯一性校验
系统应当确保一名弟子在同一时间只能担任一个职位（长老或亲传弟子）。

#### Scenario: 已任长老的弟子不应出现在其他长老选择列表中
- **GIVEN** 弟子A已被任命为灵植长老
- **WHEN** 用户打开炼丹长老的选择弟子对话框
- **THEN** 弟子A不应出现在可选弟子列表中

#### Scenario: 已任长老的弟子不应出现在亲传弟子选择列表中
- **GIVEN** 弟子A已被任命为灵植长老
- **WHEN** 用户打开任意长老的亲传弟子选择对话框
- **THEN** 弟子A不应出现在可选弟子列表中

#### Scenario: 已任亲传弟子的弟子不应出现在长老选择列表中
- **GIVEN** 弟子A已是某长老的亲传弟子
- **WHEN** 用户打开任意长老的选择弟子对话框
- **THEN** 弟子A不应出现在可选弟子列表中

#### Scenario: 已任亲传弟子的弟子不应出现在其他亲传弟子选择列表中
- **GIVEN** 弟子A已是灵植长老的亲传弟子
- **WHEN** 用户打开炼丹长老的亲传弟子选择对话框
- **THEN** 弟子A不应出现在可选弟子列表中

#### Scenario: 任命长老时进行后端验证
- **GIVEN** 弟子A已是某长老的亲传弟子
- **WHEN** 用户尝试通过其他方式（如API直接调用）将弟子A任命为长老
- **THEN** 系统应拒绝该操作并返回错误信息

## MODIFIED Requirements

### Requirement: 长老选择对话框过滤逻辑
原有的过滤条件 `it.status == DiscipleStatus.IDLE` 需要扩展，增加对已任职弟子的排除。

**修改前**:
```kotlin
disciples.filter { it.realmLayer > 0 && it.age >= 5 && it.status == DiscipleStatus.IDLE }
```

**修改后**:
```kotlin
disciples.filter { 
    it.realmLayer > 0 && 
    it.age >= 5 && 
    it.status == DiscipleStatus.IDLE &&
    !isDiscipleInAnyPosition(it.id, elderSlots)  // 新增：排除已任职的弟子
}
```

### Requirement: 亲传弟子选择对话框过滤逻辑
原有的过滤条件需要扩展，增加对已任职弟子的排除。

**修改前**:
```kotlin
disciples.filter { it.realmLayer > 0 && it.age >= 5 && it.status == DiscipleStatus.IDLE }
```

**修改后**:
```kotlin
disciples.filter { 
    it.realmLayer > 0 && 
    it.age >= 5 && 
    it.status == DiscipleStatus.IDLE &&
    !isDiscipleInAnyPosition(it.id, elderSlots)  // 新增：排除已任职的弟子
}
```
