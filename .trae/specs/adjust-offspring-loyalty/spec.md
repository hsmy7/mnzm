# 子嗣初始忠诚度随机化调整 Spec

## Why
子嗣作为宗门弟子血脉传承，其初始忠诚度应高于普通招募弟子，体现亲情纽带关系。当前子嗣初始忠诚度偏低（30-60），与设定不符。

## What Changes
- 将普通子嗣（同宗门父母）初始忠诚度从30-60改为70-100
- 将跨宗门子嗣初始忠诚度从60-89改为70-100

## Impact
- Affected code: `GameEngine.kt` 中的 `createChild` 和 `createCrossSectChild` 函数

## MODIFIED Requirements
### Requirement: 子嗣初始忠诚度
子嗣创建时，系统应随机生成70到100之间的初始忠诚度值。

#### Scenario: 创建普通子嗣
- **WHEN** 同宗门弟子夫妇生育子嗣
- **THEN** 子嗣初始忠诚度为随机70-100

#### Scenario: 创建跨宗门子嗣
- **WHEN** 跨宗门道侣生育子嗣
- **THEN** 子嗣初始忠诚度为随机70-100
