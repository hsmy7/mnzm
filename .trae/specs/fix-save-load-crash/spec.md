# 道具堆叠修复 Spec

## Why

当前道具系统存在不一致的添加逻辑：部分代码使用 `addXxxToWarehouse` 方法正确堆叠，但部分代码直接添加道具到列表（如 `_pills.value = _pills.value + result.pills`），绕过了堆叠逻辑，导致出现多个相同道具卡片。

## What Changes

* 统一所有道具添加逻辑，确保都经过堆叠方法

* 修改 GameEngine.kt 中直接添加道具的代码，改为调用 `addXxxToWarehouse` 方法

* 移除丹药 `cannotStack` 检查，所有丹药均可堆叠

## Impact

* Affected code:

  * `GameEngine.kt` - 修改多处直接添加道具的代码

## ADDED Requirements

### Requirement: 统一道具添加逻辑

系统 SHALL 确保所有道具添加都经过堆叠逻辑，避免创建多个相同道具卡片。

#### Scenario: 任务奖励道具堆叠

* **GIVEN** 玩家有 10 个某丹药

* **WHEN** 任务奖励 5 个同类型丹药

* **THEN** 玩家拥有 1 个道具实例，数量为 15

* **AND** 不出现多个相同道具卡片

#### Scenario: 商店购买道具堆叠

* **GIVEN** 玩家有 50 个某材料

* **WHEN** 从商店购买 30 个同类型材料

* **THEN** 玩家拥有 1 个道具实例，数量为 80

#### Scenario: 探索获得道具堆叠

* **GIVEN** 玩家有 0 个某灵药

* **WHEN** 探索获得 10 个该灵药，然后又获得 5 个

* **THEN** 玩家拥有 1 个道具实例，数量为 15

### Requirement: 所有丹药可堆叠

系统 SHALL 允许所有丹药堆叠，不再区分可堆叠和不可堆叠丹药。

#### Scenario: 丹药堆叠

* **GIVEN** 玩家有 1 个某丹药

* **WHEN** 玩家获得同类型丹药

* **THEN** 丹药数量累加，不创建新实例

