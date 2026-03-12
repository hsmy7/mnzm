# 修复弟子列表无境界弟子排序问题 Spec

## Why
弟子界面的弟子列表中，无境界弟子（realmLayer == 0 或 age < 5）没有显示在最下方，而是显示在最上方。这是因为排序逻辑错误：使用 `sortedByDescending` 时，无境界弟子被赋予 `Int.MAX_VALUE`，导致它们排在最前面而不是最后面。

## What Changes
- 修复 `DisciplesTab` 中的排序逻辑，将无境界弟子的排序值从 `Int.MAX_VALUE` 改为 `-1`，使其排在列表最下方

## Impact
- Affected code: `MainGameScreen.kt` 中的 `DisciplesTab` 函数

## ADDED Requirements

### Requirement: 无境界弟子显示在列表最下方
系统应在弟子界面的弟子列表中，将无境界弟子显示在最下方。

#### Scenario: 无境界弟子排在列表末尾
- **WHEN** 用户查看弟子界面的弟子列表时
- **THEN** 有境界弟子应按境界从高到低排序显示
- **AND** 无境界弟子（realmLayer == 0 或 age < 5）应显示在列表最下方

#### Scenario: 境界筛选后无境界弟子仍显示在末尾
- **WHEN** 用户选择特定境界筛选时
- **THEN** 无境界弟子不应出现在筛选结果中（因为它们没有对应境界）
- **AND** 筛选结果只显示对应境界的弟子

## MODIFIED Requirements
无

## REMOVED Requirements
无
