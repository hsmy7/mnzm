# 修复小境界突破使用突破丹药问题 - 产品需求文档

## Why
用户反馈弟子在小境界突破时也会消耗突破丹药，但根据游戏设计逻辑，突破丹药应该只在大境界突破时使用，小境界突破不应该消耗突破丹药。

## What Changes
- 修改 `autoUseBreakthroughPills` 方法，添加大境界突破判断逻辑
- 只有在大境界突破时才自动使用突破丹药
- 小境界突破时不消耗突破丹药

## Impact
- Affected specs: 弟子修炼系统、丹药使用系统
- Affected code: `GameEngine.kt` 中的 `autoUseBreakthroughPills` 方法

## ADDED Requirements

### Requirement: 突破丹药使用限制
系统应当只在弟子进行大境界突破时自动使用突破丹药，小境界突破时不使用。

#### Scenario: 小境界突破不使用丹药
- **WHEN** 弟子进行小境界突破（realmLayer < maxLayers）
- **THEN** 系统不自动使用突破丹药
- **AND** 突破丹药保留在储物袋中

#### Scenario: 大境界突破使用丹药
- **WHEN** 弟子进行大境界突破（realmLayer >= maxLayers）
- **AND** 弟子储物袋中有匹配当前境界的突破丹药
- **THEN** 系统自动使用突破丹药
- **AND** 突破概率获得加成

## MODIFIED Requirements

### Requirement: 突破丹药自动使用逻辑
原有逻辑：
- 检查丹药目标境界是否与弟子当前境界匹配

修改后逻辑：
- 检查是否为大境界突破（`TribulationSystem.isBigBreakthrough`）
- 检查丹药目标境界是否与弟子当前境界匹配
- 只有大境界突破时才使用突破丹药
