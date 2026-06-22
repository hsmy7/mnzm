# 修复物品详情界面的中文描述与信息展示

## Why

物品详情弹窗中存在三类问题：

1. 部分技能/效果元数据（如 buff 类型、作用目标）未做中文化映射，界面直接显示英文原文（例如 `shield`、`damage_link`、`enemy`）。
2. 材料、灵草、种子的 `description` 字段未在详情效果列表中展示，导致描述信息缺失。
3. 功法技能描述不完整，缺少作用目标、是否全体、固定治疗、护盾、伤害链接/分摊、行动提前等字段。
4. 一次性丹药的“持续/一次性”判定不准确，部分应立即生效的丹药仍显示“持续 X 旬”。

## What Changes

- 在 `ItemDetailEffects.kt` 中补充所有功法 buff 类型字符串到中文的映射，包括 `damage_link`、`damage_share`、`shield`、`damage_reduction`、`damage_boost`、`turn_advance` 等，并同步扩展 `parseManualStackBuffs` 对这些类型的解析。
- 增加作用目标中文映射，覆盖 `self` / `ally` / `enemy` / `team`，并在所有功法技能展示处统一输出。
- 统一并补全功法技能详情展示：增加作用目标、是否全体、固定治疗、护盾、行动提前、伤害分摊、伤害链接等字段；统一 `ItemDetailDialog.kt`、`ItemDetailEffects.kt`、`ItemDetailOtherEffects.kt`、`LearnedManualDetailDialog.kt` 中的技能描述逻辑。
- 修正一次性丹药判定：凡具有立即生效的固定数值增加、治疗、复活、清除负面、延寿等效果的丹药均标记为一次性，且不再显示持续时间。
- 在材料、灵草、种子的详情效果列表中追加原始 `description` 字段。
- 增加物品关联配方/产物信息：
  - 种子详情显示成熟后产出的草药名称。
  - 草药详情显示可用其炼制的丹药列表。
  - 丹药详情显示炼制所需草药及数量。
  - 装备详情显示锻造所需材料及数量。
  - 材料详情显示可用其锻造的装备列表。

## Impact

- 受影响的能力：物品详情展示、功法技能展示、丹药信息展示、材料/草药/种子/装备关联信息展示。
- 受影响的代码：`ItemDetailDialog.kt`、`ItemDetailEffects.kt`、`ItemDetailOtherEffects.kt`、`LearnedManualDetailDialog.kt`；引用 `PillRecipeDatabase`、`ForgeRecipeDatabase`、`HerbDatabase` 获取关联信息。
- 无破坏性变更。

## ADDED Requirements

### Requirement: 功法 buff 类型中文化

#### Scenario: 成功显示中文

- **WHEN** 物品详情展示包含 `skillBuffType="shield"` 的功法
- **THEN** 界面显示“护盾”而非 `"shield"`

### Requirement: 功法技能信息完整

#### Scenario: 成功展示完整技能信息

- **WHEN** 玩家查看任意功法详情
- **THEN** 技能区域显示技能名、描述、作用目标、伤害类型/倍率（攻击）、治疗（辅助）、护盾/伤害链接/分摊/行动提前（若有）、buff/debuff（含持续回合）、冷却、灵耗

### Requirement: 材料/灵草/种子显示描述

#### Scenario: 成功显示描述

- **WHEN** 玩家查看材料/灵草/种子详情
- **THEN** 效果列表中在类型/数量之后显示配置的中文 `description`

### Requirement: 一次性丹药不显示持续时间

#### Scenario: 成功标记为一次性

- **WHEN** 丹药效果为立即增加固定修为/熟练度/孕养度、恢复生命/灵力、复活、清除负面、延寿或永久基础属性
- **THEN** 详情显示“（一次性效果）”且不显示“持续 X 旬”

### Requirement: 种子显示成熟产物

#### Scenario: 成功显示成熟草药

- **WHEN** 玩家查看种子详情
- **THEN** 详情显示“成熟后：{草药名称}”

### Requirement: 草药显示可炼制丹药

#### Scenario: 成功显示丹药列表

- **WHEN** 玩家查看草药详情
- **THEN** 详情显示“可用于炼制：{丹药1}×数量、{丹药2}×数量…”

### Requirement: 丹药显示炼制所需草药

#### Scenario: 成功显示草药配方

- **WHEN** 玩家查看丹药详情
- **THEN** 详情显示“炼制材料：{草药1}×数量、{草药2}×数量…”

### Requirement: 装备显示锻造所需材料

#### Scenario: 成功显示锻造材料

- **WHEN** 玩家查看装备详情
- **THEN** 详情显示“锻造材料：{材料1}×数量、{材料2}×数量…”

### Requirement: 材料显示可用于锻造的装备

#### Scenario: 成功显示装备列表

- **WHEN** 玩家查看材料详情
- **THEN** 详情显示“可用于锻造：{装备1}×数量、{装备2}×数量…”

## MODIFIED Requirements

无

## REMOVED Requirements

无
