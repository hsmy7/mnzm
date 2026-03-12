# 弟子属性浮动百分比精度优化 Spec

## Why
当前游戏中弟子属性相关的百分比显示（如灵根修炼加成、悟性修炼速度加成等）使用 `.toInt()` 转换为整数，丢失了小数精度。例如 1.5 倍显示为 "150%" 而不是 "150.0%"。用户要求精确到 0.1%，即显示小数点后一位。

## What Changes
- 修改弟子属性百分比显示精度，从整数改为保留1位小数（0.1%精度）
- 需要修改的显示位置：
  - 灵根修炼加成百分比
  - 悟性修炼速度加成百分比
  - 功法/装备/丹药等属性加成百分比显示

## Impact
- Affected specs: 弟子属性显示系统
- Affected code: 
  - `DiscipleDetailScreen.kt` - 弟子详情界面
  - `GameScreen.kt` - 弟子列表界面
  - `MainGameScreen.kt` - 装备/道具详情显示
  - `GameEngine.kt` - 事件消息中的百分比显示

## ADDED Requirements
### Requirement: 弟子属性百分比显示精度
弟子界面显示的所有百分比数值（灵根加成、悟性加成、装备加成等）应精确到0.1%，即显示一位小数。

#### Scenario: 灵根修炼加成显示
- **WHEN** 弟子详情界面显示灵根修炼加成时
- **THEN** 显示如 "300.0%"（双灵根）、"400.0%"（单灵根）等精确到小数点后一位

#### Scenario: 悟性修炼速度显示
- **WHEN** 弟子详情界面显示悟性修炼速度加成时
- **THEN** 显示如 "120.0%"（悟性70时）、"100.0%"（悟性50时）等精确到小数点后一位

## MODIFIED Requirements
### Requirement: 百分比格式化统一
所有百分比显示统一使用 `String.format("%.1f%%", value * 100)` 或类似方式保留一位小数。

## REMOVED Requirements
### Requirement: 旧的整数百分比显示
移除所有使用 `.toInt()` 的百分比显示方式。

