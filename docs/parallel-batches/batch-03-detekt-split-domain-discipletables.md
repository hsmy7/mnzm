# Batch-03：detekt 拆分队列·core:domain DiscipleTables + GameConfigTest（2 条，高风险）

| 项 | 内容 |
|---|---|
| 批次 | 03 ｜ 并行组 A（与 batch-01/02 互不相交） |
| 模块 | `core:domain`（baseline 2 条 LargeClass） |
| 性质 | 真实结构性重构，**行为零变更**——本批是全队列**已知最难单体**，曾整批回退 |
| 前置 | 无硬前置；**强烈建议实施者先做拆分设计再动手**（见 §2 回退教训） |
| 预分配 | handover 小节 §2.32；guard `core/domain=` 行（当前 2，只缩） |

## 1. 目标清单（`android/core/domain/detekt-baseline.xml` 实测）

| 条目 | 说明 |
|---|---|
| `LargeClass:DiscipleTables.kt$DiscipleTables` | 1786 行。**弟子镜像列协议下界 + 组装家族深耦合** |
| `LargeClass:GameConfigTest.kt$GameConfigTest` | 配置一致性测试大类——按配置域拆多个测试类即可（低风险，先做热身） |

## 2. DiscipleTables 回退教训（§2.29 原文要点，必读）

2026-09-09 M3 第十批曾执行至半后**主动回退**：组装家族（assemble 族）与
`txAssembled` 事务缓存 / `columnGroupByIndex` 脏组位图 / 双指针归并助手**深耦合**，
文件级扩展化需要重排缓存失效不变量——超出"行为零变更"单批安全边界。

**本批正确姿势**：

1. **设计先行**：先画出 assemble 家族 × 事务缓存 × 脏组位图 × 归并助手的依赖图
   （读代码产出，不改代码），设计"缓存失效不变量保持"的拆分边界——候选方向：
   - 按"列族"拆 mixin/扩展（基础属性列 / 修炼列 / 战斗列 / 装备功法列 / 生命周期列），
     组装家族按列族聚合到各自文件，`txAssembled` 缓存失效点保持单一入口；
   - 或先提取**纯函数层**（归并助手、脏组位图运算、id 解析——零状态，可安全下放
     文件级），把有状态面收敛后再评估剩余体量。
2. **分两步走**：第一步纯函数层提取（低风险，可独立验收）；第二步有状态域拆分
   （设计评审通过后执行）。
3. **再次诚实回退是合法结局**：若设计后仍判定超出安全边界，产出 = 设计文档 +
   纯函数层清偿 + 条目继续装回登记（guard 不缩或只缩 GameConfigTest 一条），在
   §2.32 说明原因——**不带病拆分**。

## 3. 相关红线

- DiscipleTables 是**镜像列协议**（upsertMirrorRow / isAlive SparseArray / 双指针归并 /
  非数字 id 抛错防御——DiffDirtyDisciplesTest 守卫，§2.9）：任何拆分不得改变
  upsert 语义与 `id in _ids` 探测路径。
- 行级应用是 WS-1 性能验收载体（O(k) 行级镜像应用）——拆分后
  DiffDirtyDisciplesTest / DiscipleTablesMirrorUpsertTest 必须全绿。
- batch-04（取消传播）与本文件正交（DiscipleTables 非 suspend 域）；若发现 suspend
  catch 点按 README §3.2 让 04 跳过。

## 4. 验收

1. `GameConfigTest` 拆分归零 + DiscipleTables 按设计结论处置（清偿或登记回退）；
   触碰面 0 违规（六模块全规则重跑）。
2. §4 模板：①②③（对拍——DiscipleTables 变更必须经 45 Diff 类逐位一致证明）④⑤。
3. `:core:domain` DiscipleTablesMirrorUpsertTest + 引擎 DiffDirty 家族全绿。
4. handover §2.32（含拆分设计或回退结论）+ §4.1 勾销 + CHANGELOG。

## 5. 本批触碰文件声明

- `android/core/domain/**`（DiscipleTables.kt + 新拆分文件 + GameConfigTest 拆分 + 测试适配）。
- `android/core/domain/detekt-baseline.xml` + guard `core/domain=` 行。
- 不触碰：其他模块、C++、镜像协议字段面（`models.h` 对拍键集）。
