# 仓库标签页道具品阶排序修复 Spec

## Why
MainGameScreen 中的 WarehouseTab 组件显示的道具没有按品阶排序，用户希望高品阶的道具显示在上方，便于查看和管理高价值物品。

## What Changes
- 在 WarehouseTab 组件中对道具按品阶降序排序
- 排序范围包括：装备、功法、丹药、材料、灵药、种子

## Impact
- Affected code: `MainGameScreen.kt` 中的 `WarehouseTab` 组件

## ADDED Requirements
### Requirement: 仓库标签页道具品阶排序
系统 SHALL 在仓库标签页中按品阶降序显示道具，高品阶道具显示在上方。

#### Scenario: 查看仓库标签页道具
- **WHEN** 用户切换到仓库标签页
- **THEN** 各类道具应按品阶从高到低排序显示
- **AND** 品阶相同时按名称排序

## Implementation Details
品阶（rarity）定义：
- 1 = 凡品
- 2 = 灵品
- 3 = 宝品
- 4 = 玄品
- 5 = 地品
- 6 = 天品

排序规则：
1. 首先按 rarity 降序（天品 > 地品 > 玄品 > ... > 凡品）
2. 品阶相同时按名称升序

## Related
- `InventoryDialog` 组件已实现相同的排序逻辑，可参考实现
- 现有规格文档 `fix-inventory-rarity-sort` 已修复 `InventoryDialog` 的排序问题
