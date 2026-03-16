# 统一界面颜色规范 Spec

## Why
游戏界面颜色不统一，需要建立统一的颜色规范，使所有界面使用一致的背景色（F8F5F2）、边框色（DCD6D0）和按钮颜色（米色），提升视觉一致性和用户体验。

## What Changes
- 统一界面背景色为 `#F8F5F2`
- 统一边框颜色为 `#DCD6D0`
- 统一按钮颜色为米色（保持现有 `#F5DEB3`）
- 更新 `GameColors` 中的颜色常量
- 更新所有界面文件中的硬编码颜色

## Impact
- Affected specs: 所有游戏界面
- Affected code:
  - `Color.kt` - 更新颜色常量
  - `GameButton.kt` - 按钮样式
  - `MainGameScreen.kt` - 主界面
  - `GameScreen.kt` - 游戏界面
  - `InventoryScreen.kt` - 仓库界面
  - `AlchemyScreen.kt` - 炼丹界面
  - `ForgeScreen.kt` - 锻造界面
  - `HerbGardenScreen.kt` - 药园界面
  - `SpiritMineScreen.kt` - 灵矿界面
  - `LibraryScreen.kt` - 藏经阁界面
  - `RecruitScreen.kt` - 招募界面
  - `WorldMapScreen.kt` - 世界地图界面
  - `DiscipleDetailScreen.kt` - 弟子详情界面
  - `ItemCard.kt` - 道具卡片组件
  - 其他所有使用背景色和边框色的界面文件

## ADDED Requirements

### Requirement: 统一界面背景色
系统应确保所有游戏界面使用统一的背景色 `#F8F5F2`。

#### Scenario: 界面背景使用统一颜色
- **WHEN** 用户打开任意游戏界面
- **THEN** 界面背景应为 `#F8F5F2`

#### Scenario: 对话框背景使用统一颜色
- **WHEN** 用户打开任意对话框
- **THEN** 对话框背景应为 `#F8F5F2`

#### Scenario: 卡片背景使用统一颜色
- **WHEN** 用户查看任意卡片组件
- **THEN** 卡片背景应为 `#F8F5F2`

### Requirement: 统一边框颜色
系统应确保所有边框使用统一的颜色 `#DCD6D0`。

#### Scenario: 按钮边框使用统一颜色
- **WHEN** 用户查看任意按钮
- **THEN** 按钮边框应为 `#DCD6D0`

#### Scenario: 卡片边框使用统一颜色
- **WHEN** 用户查看任意卡片
- **THEN** 卡片边框应为 `#DCD6D0`

#### Scenario: 输入框边框使用统一颜色
- **WHEN** 用户查看任意输入框
- **THEN** 输入框边框应为 `#DCD6D0`

#### Scenario: 分隔线使用统一颜色
- **WHEN** 用户查看界面中的分隔线
- **THEN** 分隔线应为 `#DCD6D0`

### Requirement: 统一按钮颜色
系统应确保所有按钮使用米色背景 `#F5DEB3`。

#### Scenario: 按钮使用米色背景
- **WHEN** 用户查看任意按钮
- **THEN** 按钮背景应为米色 `#F5DEB3`

#### Scenario: 按钮使用统一边框
- **WHEN** 用户查看任意按钮
- **THEN** 按钮边框应为 `#DCD6D0`

### Requirement: 颜色常量更新
系统应在 `GameColors` 中定义统一的颜色常量。

#### Scenario: 背景色常量定义
- **WHEN** 代码引用背景色
- **THEN** 应使用 `GameColors.PageBackground`（`#F8F5F2`）

#### Scenario: 边框色常量定义
- **WHEN** 代码引用边框色
- **THEN** 应使用 `GameColors.BorderColor`（`#DCD6D0`）

#### Scenario: 按钮背景色常量定义
- **WHEN** 代码引用按钮背景色
- **THEN** 应使用 `GameColors.ButtonBackground`（`#F5DEB3`）

## MODIFIED Requirements
无

## REMOVED Requirements
无
