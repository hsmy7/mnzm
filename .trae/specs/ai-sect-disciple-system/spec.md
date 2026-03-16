# AI宗门弟子系统规格

## Why
当前AI宗门的弟子系统仅存储境界与数量的映射（`Map<Int, Int>`），缺乏与玩家宗门一致的弟子管理机制。需要为AI宗门实现完整的弟子系统，包括每年招募弟子、自动修炼/突破、战斗属性浮动、天赋、功法和装备系统，以增强游戏的深度和一致性。

## What Changes
- 新增 `AISectDisciple` 数据类，存储AI宗门弟子的完整信息
- 修改 `WorldSect` 数据类，存储AI宗门弟子列表
- 新增 `AISectDiscipleManager` 管理类，处理AI宗门弟子的招募、修炼、突破等逻辑
- 修改年度事件处理，添加AI宗门弟子招募逻辑
- 修改月度事件处理，添加AI宗门弟子修炼和突破逻辑
- 修改 `AICaveTeamGenerator`，使用真实AI弟子数据生成战斗队伍

## Impact
- Affected specs: AI宗门系统、战斗系统、侦查系统
- Affected code: 
  - `GameData.kt` - WorldSect数据结构
  - `GameEngine.kt` - 年度/月度事件处理
  - `AICaveTeamGenerator.kt` - AI战斗队伍生成
  - 新增 `AISectDisciple.kt` - AI弟子数据模型
  - 新增 `AISectDiscipleManager.kt` - AI弟子管理逻辑

## ADDED Requirements

### Requirement: AI宗门弟子数据模型
系统应为AI宗门弟子提供与玩家弟子一致的数据模型。

#### Scenario: AI弟子属性存储
- **WHEN** AI宗门创建弟子时
- **THEN** 弟子应具有：ID、姓名、境界、境界层数、修为、灵根类型、年龄、寿命、战斗属性浮动值、天赋列表、功法列表（1-5本）、装备列表（1-4种）、悟性

### Requirement: AI宗门弟子年度招募
系统应每年为AI宗门自动招募新弟子。

#### Scenario: 年度招募触发
- **WHEN** 游戏进入新一年的第一个月
- **THEN** 所有AI宗门应自动招募新弟子

#### Scenario: 招募数量计算
- **WHEN** AI宗门招募弟子时
- **THEN** 招募数量统一为1-10人

#### Scenario: 新弟子境界分配
- **WHEN** AI宗门招募新弟子时
- **THEN** 新弟子境界应为炼气期（境界9），灵根随机生成

### Requirement: AI宗门弟子天赋系统
AI弟子应具有与玩家弟子一致的天赋系统。

#### Scenario: 天赋生成
- **WHEN** AI弟子创建时
- **THEN** 随机生成1-3个天赋（可能包含负面天赋）

#### Scenario: 天赋效果应用
- **WHEN** 计算AI弟子属性时
- **THEN** 天赋效果应正确应用到修炼速度、战斗属性、突破概率等

### Requirement: AI宗门弟子功法系统
AI弟子应具有功法，且功法品质不超过境界限制。

#### Scenario: 功法数量
- **WHEN** AI弟子创建时
- **THEN** 随机获得1-5本功法

#### Scenario: 功法品质限制
- **WHEN** AI弟子获得功法时
- **THEN** 功法品质不超过境界允许的最高品质：练气期白品，筑基期绿品，金丹期蓝品，元婴期紫品，化神期金品，炼虚及以上橙品/红品

### Requirement: AI宗门弟子装备系统
AI弟子应具有装备，且装备品质不超过境界限制。

#### Scenario: 装备数量
- **WHEN** AI弟子创建时
- **THEN** 随机获得1-4种装备（武器、防具、鞋子、饰品中随机选择）

#### Scenario: 装备品质限制
- **WHEN** AI弟子获得装备时
- **THEN** 装备品质不超过境界允许的最高品质（与功法相同）

### Requirement: AI宗门弟子自动修炼
系统应每月为AI宗门弟子自动修炼，修炼速度计算与玩家弟子一致。

#### Scenario: 月度修炼触发
- **WHEN** 游戏进入新月时
- **THEN** 所有AI宗门弟子自动修炼，修为增加

#### Scenario: 修炼速度计算公式
- **WHEN** 计算AI弟子修炼速度时
- **THEN** 修炼速度 = 基础速度(5.0) × 灵根加成 × 悟性加成 × 功法加成 × 天赋加成

#### Scenario: 灵根加成计算
- **WHEN** 计算灵根加成时
- **THEN** 单灵根4.0倍，双灵根3.0倍，三灵根1.5倍，四灵根1.0倍，五灵根0.8倍

#### Scenario: 悟性加成计算
- **WHEN** 计算悟性加成时
- **THEN** 悟性高于50时每点+4%，低于50时每点-2%

#### Scenario: 功法加成计算
- **WHEN** 计算功法加成时
- **THEN** 功法修炼速度加成 = Σ(功法修炼速度百分比 × 熟练度加成)

#### Scenario: 天赋加成计算
- **WHEN** 计算天赋加成时
- **THEN** 天赋修炼速度加成 = Σ(天赋修炼速度效果值)

#### Scenario: 月度修为增加
- **WHEN** AI弟子月度修炼时
- **THEN** 修为增加 = 修炼速度 × 每秒tick数(5) × 每月秒数(30)

### Requirement: AI宗门弟子自动突破
系统应在AI弟子修为满时自动尝试突破。

#### Scenario: 突破条件检查
- **WHEN** AI弟子修为达到当前境界上限
- **THEN** 系统应自动尝试突破

#### Scenario: 突破成功
- **WHEN** AI弟子突破成功
- **THEN** 境界提升一层或进入下一大境界

#### Scenario: 突破失败
- **WHEN** AI弟子突破失败
- **THEN** 修为清零，境界不变，突破失败次数+1

### Requirement: AI宗门弟子战斗属性浮动
AI弟子应具有与玩家弟子一致的战斗属性浮动机制。

#### Scenario: 属性浮动生成
- **WHEN** AI弟子创建时
- **THEN** 随机生成-30%到+30%的战斗属性浮动值

#### Scenario: 属性浮动应用
- **WHEN** 计算AI弟子战斗属性时
- **THEN** 最终属性 = 基础属性 × (1 + 浮动百分比)

### Requirement: AI宗门弟子年龄与寿命
AI弟子应具有年龄增长和寿命机制。

#### Scenario: 年龄增长
- **WHEN** 游戏进入新年
- **THEN** 所有AI弟子年龄+1

#### Scenario: 寿命耗尽
- **WHEN** AI弟子年龄超过寿命
- **THEN** 弟子死亡，从宗门弟子列表移除

### Requirement: AI宗门弟子侦查信息同步
侦查AI宗门时，应返回真实的弟子信息。

#### Scenario: 侦查信息返回
- **WHEN** 玩家侦查AI宗门
- **THEN** 返回该宗门所有弟子的境界分布和数量

### Requirement: AI战斗队伍生成优化
使用真实AI弟子数据生成战斗队伍。

#### Scenario: 洞府探索队伍生成
- **WHEN** 生成AI洞府探索队伍时
- **THEN** 从宗门真实弟子中选择，使用弟子的真实属性、天赋、装备和功法

## MODIFIED Requirements

### Requirement: WorldSect数据结构扩展
WorldSect数据类需要存储完整的弟子列表。

**修改内容**：
- 新增 `aiDisciples: List<AISectDisciple>` 字段存储AI宗门弟子
- 保留 `disciples: Map<Int, Int>` 字段用于快速统计境界分布（从aiDisciples计算得出）

### Requirement: 年度事件处理扩展
年度事件处理需要添加AI宗门弟子招募逻辑。

**修改内容**：
- 在每年一月调用 `AISectDiscipleManager.recruitDisciplesForAllSects()`

### Requirement: 月度事件处理扩展
月度事件处理需要添加AI宗门弟子修炼逻辑。

**修改内容**：
- 每月调用 `AISectDiscipleManager.processMonthlyCultivation()`

## REMOVED Requirements
无移除的需求。
