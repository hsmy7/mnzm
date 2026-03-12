# 修复探查队伍贝塞尔曲线移动问题 Spec

## Why
探查队伍在世界地图上移动时走的是直线，但地图路线是用贝塞尔曲线绘制的，导致队伍显示位置与路线不匹配，视觉上队伍"脱离"了路线。

## What Changes
- 修改 `GameEngine.kt` 中 `processScoutTeamMovement()` 函数，使队伍位置沿着贝塞尔曲线移动
- 修改 `WorldMapScreen.kt` 中 `ScoutTeamMarker` 的位置计算，使用与路线绘制相同的贝塞尔曲线算法
- 确保控制点计算逻辑与路线绘制保持一致

## Impact
- Affected specs: 世界地图显示、探查系统
- Affected code: 
  - `GameEngine.kt` - 探查队伍移动逻辑
  - `WorldMapScreen.kt` - 队伍标记显示

## ADDED Requirements

### Requirement: 探查队伍沿贝塞尔曲线移动
系统应确保探查队伍沿着与路线绘制相同的贝塞尔曲线移动：
- 使用相同的控制点计算公式
- 控制点偏移量由路径两端宗门ID的hashCode确定
- 曲线强度为距离的20%乘以随机偏移系数

#### Scenario: 队伍沿曲线移动
- **WHEN** 探查队伍在地图上移动
- **THEN** 队伍位置应在贝塞尔曲线上
- **AND** 队伍显示位置与路线重合

#### Scenario: 控制点计算一致性
- **WHEN** 计算贝塞尔曲线控制点
- **THEN** 使用公式：`controlPoint = midPoint + normalDirection * curveStrength`
- **AND** `curveStrength = distance * 0.2f * randomOffset`
- **AND** `randomOffset = ((fromId.hashCode() + toId.hashCode()) % 100 - 50) / 100f`

## MODIFIED Requirements

### Requirement: processScoutTeamMovement 贝塞尔曲线位置计算
`processScoutTeamMovement()` 函数应计算贝塞尔曲线上的位置：
- 获取起点和终点宗门的坐标
- 计算贝塞尔曲线控制点
- 使用二次贝塞尔公式：`P(t) = (1-t)²P₀ + 2(1-t)tP₁ + t²P₂`
- 根据移动进度 `moveProgress` 计算当前位置

### Requirement: ScoutTeamMarker 贝塞尔曲线位置计算
`ScoutTeamMarker` 组件应使用贝塞尔曲线计算显示位置：
- 获取起点宗门（玩家宗门）和终点宗门（目标宗门）的坐标
- 计算与路线绘制相同的控制点
- 根据队伍的 `moveProgress` 计算贝塞尔曲线上的位置

## REMOVED Requirements
无
