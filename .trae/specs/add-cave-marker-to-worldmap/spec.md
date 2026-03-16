# 世界地图洞府显示功能 Spec

## Why
洞府系统已存在（CultivatorCave、CaveGenerator、CaveExplorationSystem），但世界地图界面未显示洞府位置，玩家无法直观查看和交互洞府，需要将洞府标记添加到世界地图上以实现完整的洞府功能。

## What Changes
- 在 `WorldMapScreen` 中添加洞府标记显示组件 `CaveMarker`
- 在 `WorldMapDialog` 中传入洞府数据并转换为地图标记
- 添加洞府点击交互，显示洞府详情对话框
- 洞府标记需要区分不同状态（可探索、探索中、已探索、已消失）

## Impact
- Affected specs: 世界地图系统、洞府探索系统
- Affected code:
  - `WorldMapScreen.kt` - 添加洞府标记显示
  - `MainGameScreen.kt` - WorldMapDialog 传入洞府数据，添加洞府详情对话框
  - `GameViewModel.kt` - 可能需要添加洞府交互相关状态

## ADDED Requirements

### Requirement: 洞府地图标记显示
系统应当在世界地图上显示所有洞府的位置标记。

#### Scenario: 显示可用洞府
- **WHEN** 玩家打开世界地图
- **AND** 存在状态为 AVAILABLE 的洞府
- **THEN** 洞府应当以特定图标/颜色显示在地图对应位置

#### Scenario: 显示探索中洞府
- **WHEN** 洞府状态为 EXPLORING
- **THEN** 洞府标记应当显示"探索中"状态标识

#### Scenario: 显示已探索洞府
- **WHEN** 洞府状态为 EXPLORED
- **THEN** 洞府标记应当显示"已探索"状态标识

#### Scenario: 隐藏已消失洞府
- **WHEN** 洞府状态为 EXPIRED
- **THEN** 洞府标记不应当显示在地图上

### Requirement: 洞府点击交互
系统应当支持玩家点击洞府标记查看详情。

#### Scenario: 点击可用洞府
- **WHEN** 玩家点击状态为 AVAILABLE 的洞府标记
- **THEN** 显示洞府详情对话框，包含洞府名称、境界、剩余时间等信息
- **AND** 提供"派遣队伍探索"按钮

#### Scenario: 点击探索中洞府
- **WHEN** 玩家点击状态为 EXPLORING 的洞府标记
- **THEN** 显示洞府详情对话框，显示探索进度

#### Scenario: 点击已探索洞府
- **WHEN** 玩家点击状态为 EXPLORED 的洞府标记
- **THEN** 显示洞府详情对话框，显示探索结果或奖励信息

### Requirement: 洞府标记样式
洞府标记应当具有独特的视觉样式，与宗门标记区分。

#### Scenario: 洞府标记外观
- **WHEN** 洞府显示在地图上
- **THEN** 使用与宗门不同的颜色/图标
- **AND** 显示洞府境界信息
