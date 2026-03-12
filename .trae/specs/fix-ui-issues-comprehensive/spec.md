# 综合UI问题修复 Spec

## Why
本次修复整合了多个UI相关的问题，包括仓库一键出售按钮不显示、招募弟子界面文字提示错误、种子/草药详情界面信息缺失、功法效果描述不全、修炼值显示位置不居中以及弟子灵根颜色显示等问题。

## What Changes
1. **仓库一键出售按钮**：修复仓库界面不显示一键出售按钮的问题
2. **招募弟子界面文字**：将"每年刷新"改为"每三年刷新"
3. **种子详情界面**：添加长成后是什么草药的描述
4. **草药详情界面**：添加可用于制作什么丹药的描述
5. **功法效果描述**：修复功法效果描述不全的问题，对比数据库中的完整效果
6. **修炼值显示位置**：将每秒修炼值与当前修为/最大修为居中显示，保持与境界处于同一行
7. **弟子信息界面灵根颜色**：根据灵根数量变换颜色

## Impact
- Affected specs: 仓库、招募弟子、种子详情、草药详情、功法详情、弟子信息
- Affected code:
  - `MainGameScreen.kt` - WarehouseTab（添加一键出售按钮）
  - `RecruitScreen.kt` - 修改文字提示
  - `HerbGardenScreen.kt` - SeedDetailDialog（添加草药信息）
  - `MainGameScreen.kt` - WarehouseItemDetailDialog（草药详情添加丹药用途）
  - `DiscipleDetailScreen.kt` - ManualDetailDialog（修复效果描述）
  - `RealtimeCultivationComponents.kt` - 修复显示位置
  - `DiscipleDetailScreen.kt` - BasicInfoSection（灵根颜色）

## ADDED Requirements

### Requirement: 仓库一键出售按钮
仓库界面应该显示一键出售按钮，允许玩家批量出售物品。

#### Scenario: 仓库显示一键出售按钮
- **WHEN** 用户打开仓库界面时
- **THEN** 应该显示"一键出售"按钮
- **AND** 点击按钮后弹出出售选项对话框
- **AND** 可以选择要出售的稀有度和物品类型

### Requirement: 种子详情显示长成后的草药
种子详情界面应该显示该种子长成后是什么草药。

#### Scenario: 种子详情显示草药信息
- **WHEN** 用户查看种子详情时
- **THEN** 应该显示"长成后：XXX"的信息
- **AND** 使用HerbDatabase.getHerbFromSeed获取对应草药信息

### Requirement: 草药详情显示可制作丹药
草药详情界面应该显示该草药可用于制作什么丹药。

#### Scenario: 草药详情显示丹药用途
- **WHEN** 用户查看草药详情时
- **THEN** 应该显示"可用于制作："的丹药列表
- **AND** 使用PillRecipeDatabase.getRecipesByHerb获取相关丹药

### Requirement: 功法效果描述完整
功法详情界面应该显示完整的功法效果描述。

#### Scenario: 功法详情显示完整效果
- **WHEN** 用户查看功法详情时
- **THEN** 应该显示完整的属性加成效果
- **AND** 与ManualDatabase中的描述保持一致

### Requirement: 修炼值显示居中
每秒修炼值与当前修为/最大修为应该居中显示，与境界处于同一行。

#### Scenario: 修炼值显示位置正确
- **WHEN** 用户查看弟子修炼信息时
- **THEN** 每秒修炼值与修为进度应该居中显示
- **AND** 与境界名称保持在同一行

### Requirement: 弟子灵根颜色根据数量变换
弟子信息界面的灵根颜色应该根据灵根数量显示不同颜色。

#### Scenario: 灵根颜色正确显示
- **WHEN** 用户查看弟子信息时
- **THEN** 灵根颜色应该根据灵根数量显示
- **AND** 单灵根红色、双灵根橙色、三灵根紫色、四灵根绿色、五灵根灰色

## MODIFIED Requirements

### Requirement: 招募弟子刷新提示
招募弟子界面的无招募弟子文字提示从每年改为每三年。

#### Scenario: 招募提示正确显示
- **WHEN** 招募列表为空时
- **THEN** 显示"暂无可招募弟子\n请等待每三年刷新"

## REMOVED Requirements
无
