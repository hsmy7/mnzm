# 修复探查队伍路线移动问题 Spec

## Why
探查队伍在世界地图上没有按照规划的路线移动，导致队伍显示位置不正确，影响玩家对探查进度的判断。

## What Changes
- 修复 `WorldMapScreen.kt` 中 `SupportTeamMarker` 的坐标计算逻辑，使其与 `ScoutTeamMarker` 保持一致
- 修复 `GameEngine.kt` 中 `SupportTeam` 创建时的坐标系统不一致问题
- 确保探查队伍的路线移动逻辑正确执行

## Impact
- Affected specs: 世界地图显示、探查系统
- Affected code: 
  - `WorldMapScreen.kt` - 队伍标记显示
  - `GameEngine.kt` - 支援队伍创建

## ADDED Requirements

### Requirement: 统一坐标系统
系统应确保所有地图坐标使用统一的坐标系统：
- `WorldSect.x/y` 使用像素坐标（范围 0-4000, 0-3500）
- `ExplorationTeam.currentX/currentY` 使用像素坐标
- `SupportTeam.currentX/currentY` 应使用像素坐标
- 地图显示时统一将像素坐标转换为归一化坐标

#### Scenario: 探查队伍按路线移动
- **WHEN** 玩家派遣探查队伍前往目标宗门
- **THEN** 队伍应按照 BFS 计算的路线逐段移动
- **AND** 在世界地图上正确显示队伍当前位置

#### Scenario: 支援队伍正确显示位置
- **WHEN** AI 宗门派遣支援队伍前往玩家宗门
- **THEN** 队伍应在世界地图上正确显示起点和终点位置
- **AND** 队伍应正确显示移动进度

## MODIFIED Requirements

### Requirement: SupportTeamMarker 坐标计算
`SupportTeamMarker` 组件应正确处理像素坐标：
- 将 `team.currentX/currentY` 视为像素坐标
- 将 `team.targetX/targetY` 视为像素坐标
- 使用与 `ScoutTeamMarker` 相同的坐标转换逻辑

### Requirement: SupportTeam 创建时坐标一致性
创建 `SupportTeam` 时应使用统一的坐标系统：
- `currentX/currentY` 使用源宗门的像素坐标
- `targetX/targetY` 使用目标宗门的像素坐标（而非硬编码的归一化坐标）

## REMOVED Requirements
无
