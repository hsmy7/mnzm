# 探索队伍血条实时同步修复 - 产品需求文档

## Overview
- **Summary**: 修复探索队伍界面中弟子槽位上方的血条没有实时同步的问题，确保血条能够实时反映弟子的当前生命值状态。
- **Purpose**: 解决弟子生命值变化后血条不更新的问题，提升用户体验和游戏状态的准确性。
- **Target Users**: 游戏玩家，特别是使用探索队伍功能的玩家。

## Goals
- 确保探索队伍界面中的血条能够实时反映弟子的当前生命值
- 修复战斗后弟子生命值更新但血条不同步的问题
- 保持血条显示逻辑的简洁性和可靠性

## Non-Goals (Out of Scope)
- 不修改血条的视觉设计和样式
- 不改变弟子生命值的计算逻辑
- 不影响其他UI组件的功能

## Background & Context
- 目前血条显示逻辑在 `SectMainScreen.kt` 中的 `TeamMemberSlot` 组件中实现
- 血条通过 `disciple.statusData["currentHp"]` 获取当前生命值
- 战斗结束后，弟子的生命值会更新，但血条没有实时同步这些变化
- 游戏使用 Compose UI 框架和 StateFlow 进行状态管理

## Functional Requirements
- **FR-1**: 确保战斗后弟子的 `statusData["currentHp"]` 被正确更新
- **FR-2**: 血条组件能够实时读取并显示最新的生命值数据
- **FR-3**: 确保血条在弟子生命值变化时立即更新

## Non-Functional Requirements
- **NFR-1**: 血条更新性能良好，不影响游戏流畅度
- **NFR-2**: 代码修改最小化，不引入新的bug
- **NFR-3**: 保持现有代码风格和架构

## Constraints
- **Technical**: 使用 Kotlin 语言和 Compose UI 框架
- **Dependencies**: 依赖现有的游戏引擎和战斗系统

## Assumptions
- 弟子的生命值数据存储在 `statusData` 映射中
- 战斗结果包含每个弟子的最终生命值
- 血条组件能够正确读取 `statusData["currentHp"]` 的值

## Acceptance Criteria

### AC-1: 战斗后血条更新
- **Given**: 探索队伍中的弟子参与战斗
- **When**: 战斗结束，弟子生命值发生变化
- **Then**: 探索队伍界面中弟子槽位上方的血条应立即更新为当前生命值
- **Verification**: `human-judgment`
- **Notes**: 血条应反映战斗后的实际生命值状态

### AC-2: 实时同步验证
- **Given**: 弟子生命值在任何情况下发生变化
- **When**: 界面处于活跃状态
- **Then**: 血条应实时反映最新的生命值状态
- **Verification**: `human-judgment`
- **Notes**: 确保血条与实际生命值数据保持同步

## Open Questions
- [ ] 是否需要考虑其他可能导致生命值变化的场景？
- [ ] 血条更新是否需要添加动画效果？