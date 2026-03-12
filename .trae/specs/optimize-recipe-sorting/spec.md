# 优化选择装备和选择丹药界面排序与移除详情确认按钮 Spec

## Why
选择装备界面中装备详情对话框存在确定按钮，增加了用户操作步骤。同时，选择装备和选择丹药界面的配方排序不够智能，用户需要花费时间查找可炼制的配方。

## What Changes
- 移除 `ForgeScreen.kt` 中 `EquipmentDetailDialog` 的确定按钮
- 优化 `EquipmentSelectionDialog` 中配方的排序逻辑
- 优化 `PillSelectionDialog` 中配方的排序逻辑

## Impact
- Affected specs: 炼器室、炼丹房
- Affected code: `ForgeScreen.kt`、`AlchemyScreen.kt`

## ADDED Requirements

### Requirement: 移除装备详情对话框确定按钮
系统应在装备详情对话框中移除确定按钮，用户点击对话框外部即可关闭。

#### Scenario: 装备详情无确定按钮
- **WHEN** 用户在选择装备界面点击查看按钮
- **THEN** 显示的详情对话框应无确定按钮，用户可点击外部关闭

### Requirement: 选择装备界面配方智能排序
系统应根据配方是否可锻造和品阶进行智能排序。

#### Scenario: 可锻造配方排在上方
- **WHEN** 用户打开选择装备界面
- **THEN** 有足够材料的配方应排在上方
- **AND** 没有足够材料的配方应排在下方

#### Scenario: 可锻造配方按品阶排序
- **WHEN** 用户打开选择装备界面
- **THEN** 可锻造的配方应按品阶从高到低排序
- **AND** 不可锻造的配方保持原有顺序

### Requirement: 选择丹药界面配方智能排序
系统应根据配方是否可炼制和品阶进行智能排序。

#### Scenario: 可炼制配方排在上方
- **WHEN** 用户打开选择丹药界面
- **THEN** 有足够材料的配方应排在上方
- **AND** 没有足够材料的配方应排在下方

#### Scenario: 可炼制配方按品阶排序
- **WHEN** 用户打开选择丹药界面
- **THEN** 可炼制的配方应按品阶从高到低排序
- **AND** 不可炼制的配方保持原有顺序

## MODIFIED Requirements
无

## REMOVED Requirements
无
