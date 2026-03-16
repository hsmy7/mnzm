# Tasks

- [x] Task 1: 修改天工峰炼器速度加成计算逻辑
  - [x] SubTask 1.1: 修改 `calculateElderAndDisciplesBonus()` 方法中 "forge" 分支的速度加成计算
    - 长老速度加成：从 `(artifactRefiningDiff * 0.01)` 改为 `(artifactRefiningDiff / 5.0) * 0.01`
    - 亲传弟子速度加成：从 `(artifactRefiningDiff / 5.0) * 0.01` 改为 `(artifactRefiningDiff / 10.0) * 0.01`
  - [x] SubTask 1.2: 修改 `calculateWorkDurationWithAllDisciples()` 方法，为 forge 添加独立分支
    - 添加 "forge" 分支，使用 `calculateElderAndDisciplesBonus("forge")` 计算速度加成
    - 原来走 else 分支使用旧公式，现在独立出来使用新公式

# Task Dependencies
- 无依赖
