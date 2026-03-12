# 探索队伍界面血条实时同步修复 - 产品需求文档

## Overview
- **Summary**: 修复探索队伍界面中弟子槽位上方的血条没有实时同步的问题，确保弟子的生命值变化能够实时反映在血条上。
- **Purpose**: 解决用户在查看探索队伍状态时无法实时了解弟子生命值的问题，提高游戏体验的连贯性和准确性。
- **Target Users**: 游戏玩家，特别是使用探索队伍功能的用户。

## Goals
- 实现探索队伍界面中弟子血条的实时同步更新
- 确保弟子生命值变化时血条能够立即反映最新状态
- 保持血条显示逻辑的正确性和一致性

## Non-Goals (Out of Scope)
- 不修改血条的视觉设计和样式
- 不修改弟子生命值的计算逻辑
- 不影响其他界面的血条显示

## Background & Context
- 探索队伍界面中的弟子槽位显示了弟子的基本信息和血条
- 血条当前通过 `disciple.statusData["currentHp"]` 获取生命值数据
- 当弟子生命值发生变化时，血条没有实时更新，导致用户看到的是过时的生命值状态

## Functional Requirements
- **FR-1**: 探索队伍界面中的弟子血条应实时反映弟子的当前生命值
- **FR-2**: 当弟子生命值发生变化时，血条应立即更新
- **FR-3**: 血条的显示逻辑应保持正确，包括死亡状态的处理

## Non-Functional Requirements
- **NFR-1**: 血条更新应具有实时性，延迟不超过1秒
- **NFR-2**: 血条更新不应影响界面的整体性能
- **NFR-3**: 修复应保持代码的可维护性和可读性

## Constraints
- **Technical**: 基于现有的Compose UI框架和StateFlow数据流机制
- **Dependencies**: 依赖于GameEngine中的弟子状态更新机制

## Assumptions
- 弟子的生命值数据存储在 `disciple.statusData["currentHp"]` 中
- GameEngine负责更新弟子的生命值状态
- StateFlow机制用于通知UI数据变化

## Acceptance Criteria

### AC-1: 血条实时更新
- **Given**: 探索队伍中的弟子生命值发生变化
- **When**: 用户查看探索队伍界面
- **Then**: 弟子槽位上方的血条应显示最新的生命值状态
- **Verification**: `programmatic` - 通过模拟生命值变化并检查血条显示是否更新

### AC-2: 死亡状态正确显示
- **Given**: 探索队伍中的弟子生命值降为0
- **When**: 用户查看探索队伍界面
- **Then**: 弟子槽位应显示"死亡"状态，血条应不显示
- **Verification**: `programmatic` - 通过将弟子生命值设为0并检查显示状态

### AC-3: 血条计算逻辑正确
- **Given**: 弟子生命值在不同范围内变化
- **When**: 血条显示时
- **Then**: 血条长度应正确反映生命值百分比，颜色应根据生命值范围变化
- **Verification**: `programmatic` - 通过设置不同生命值值并检查血条显示

## Open Questions
- [ ] 弟子生命值变化时，GameEngine是否正确更新了 `disciple.statusData["currentHp"]`？
- [ ] `disciple` 对象在状态变化时是否被正确更新，以便StateFlow能够通知UI？
- [ ] 是否存在其他因素导致血条没有实时更新？