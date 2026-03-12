# 灵矿场槽位扩展至六个 Spec

## Why
当前灵矿场只有 3 个槽位，限制了玩家同时派遣弟子进行灵石开采的能力。增加槽位数量可以提升游戏体验，让玩家能够更充分地利用弟子资源。

## What Changes
- 将灵矿场槽位数量从 3 个增加到 6 个
- 调整 UI 布局以适应 6 个槽位的显示

## Impact
- Affected specs: 无
- Affected code: 
  - `SpiritMineScreen.kt` - UI 槽位数量和布局
  - 无需修改 `GameData.kt` 或 `GameEngine.kt`，因为它们使用动态列表，已支持任意数量的槽位

## ADDED Requirements
### Requirement: 灵矿场槽位数量
系统应提供 6 个灵矿场槽位供玩家派遣弟子。

#### Scenario: 显示 6 个槽位
- **WHEN** 玩家打开灵矿场界面
- **THEN** 系统显示 6 个槽位

#### Scenario: 分配弟子到任意槽位
- **WHEN** 玩家选择任意一个空槽位并选择弟子
- **THEN** 该弟子被分配到对应槽位并开始产出灵石

#### Scenario: UI 布局适应 6 个槽位
- **WHEN** 显示 6 个槽位时
- **THEN** 布局采用 2 行 3 列的方式排列，保持界面整洁
