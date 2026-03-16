# 灵矿场执事板块 Spec

## Why
当前灵矿场只有灵矿槽位用于派遣弟子进行灵石开采，没有长老和亲传弟子系统。增加执事板块可以让玩家更充分地利用弟子资源，同时通过道德属性影响产量加成，增加游戏的策略性。

## What Changes
- 在灵矿场界面增加"灵矿执事"板块，包含2个执事槽位
- 执事只能由内门弟子担任
- 执事提供产量加成：以道德50为基准，每高5点增加2%产量，每低5点减少2%产量
- 在GameData.kt的ElderSlots中新增spiritMineDeaconDisciples字段存储执事数据
- 在SpiritMineScreen.kt中添加执事板块UI
- 在GameViewModel.kt中添加执事任命/卸任方法
- 在GameEngine.kt中计算执事的产量加成

## Impact
- Affected specs: 无
- Affected code:
  - `GameData.kt` - ElderSlots数据类新增spiritMineDeaconDisciples字段
  - `SpiritMineScreen.kt` - 新增执事板块UI组件
  - `GameViewModel.kt` - 新增执事管理方法
  - `GameEngine.kt` - 产量计算逻辑更新

## ADDED Requirements

### Requirement: 灵矿执事槽位
系统应在灵矿场界面提供2个灵矿执事槽位供玩家任命内门弟子。

#### Scenario: 显示执事槽位
- **WHEN** 玩家打开灵矿场界面
- **THEN** 系统在灵矿槽位下方显示"灵矿执事"板块，包含2个执事槽位

#### Scenario: 任命执事
- **WHEN** 玩家点击空的执事槽位
- **THEN** 系统显示内门弟子选择对话框，仅显示可用的内门弟子

#### Scenario: 执事卸任
- **WHEN** 玩家点击已任命执事的"卸任"按钮
- **THEN** 该弟子从执事位置移除，状态变为空闲

### Requirement: 执事产量加成
系统应根据执事的道德属性计算产量加成。

#### Scenario: 道德高于基准
- **GIVEN** 执事道德值为60（高于基准50）
- **WHEN** 计算灵矿产量
- **THEN** 该执事提供 (60-50)/5 * 2% = 4% 的产量加成

#### Scenario: 道德低于基准
- **GIVEN** 执事道德值为40（低于基准50）
- **WHEN** 计算灵矿产量
- **THEN** 该执事提供 (40-50)/5 * 2% = -4% 的产量惩罚

#### Scenario: 道德等于基准
- **GIVEN** 执事道德值为50
- **WHEN** 计算灵矿产量
- **THEN** 该执事不提供任何产量加成或惩罚

### Requirement: 执事弟子限制
系统应限制只有内门弟子才能担任执事。

#### Scenario: 内门弟子可选
- **WHEN** 玩家打开执事选择对话框
- **THEN** 只显示内门弟子类型的弟子

#### Scenario: 弟子唯一性
- **WHEN** 弟子已在其他职位（长老、亲传弟子、其他执事等）
- **THEN** 该弟子不在执事选择列表中显示

### Requirement: 执事UI设计
执事板块UI应与现有建筑风格保持一致。

#### Scenario: 执事槽位样式
- **WHEN** 显示执事槽位
- **THEN** 槽位样式与亲传弟子槽位一致，使用55dp大小的圆角矩形框

#### Scenario: 执事信息显示
- **WHEN** 执事槽位已任命弟子
- **THEN** 显示弟子姓名、境界和道德属性
