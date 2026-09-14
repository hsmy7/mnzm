# Batch-04：协程取消传播专项（~90 处 suspend 上下文 catch(Exception) 前置 CancellationException 分支）

| 项 | 内容 |
|---|---|
| 批次 | 04 ｜ 并行组 B（与 batch-05 文件正交；避开 01/02/03 所有权文件） |
| 模块 | 全仓（app / core:data / core:domain / core:engine / feature:game） |
| 性质 | **行为变更批**（协程取消语义修复）——与 detekt 结构批性质不同，每处需行为论证 |
| 来源 | handover §2.23 登记待办：「474 处中约 90 处 suspend/launch 上下文的 `catch (e: Exception)` 未前置 CancellationException 分支（协程取消被兜底吞）——取消传播专项批统一处理（每处需行为论证）」 |
| 预分配 | handover 小节 §2.33 |

## 1. 问题

suspend/launch 上下文里 `catch (e: Exception)` 会把 `CancellationException` 一起吞掉，
破坏结构化并发取消传播：父作用域取消（弹窗关闭/画面退出/引擎重启）后协程不退出，
继续跑完剩余逻辑甚至再挂起，造成僵尸任务与状态错乱。§2.24.3 已落首个族先例
（三个 SaveLoad Delegate 14/6/4 处 + StorageEngine 拆 IOException 分支），本批清偿剩余面。

## 2. 目标集定位（实施第一步：重新实跑裁决）

§2.23 的 ~90 处是 2026-09-08 时点估算，且其后多批重构移动过代码。**开工先重新枚举**：

```bash
# 候选生成：全部 @Suppress("TooGenericExceptionCaught") 点（app≈109/data≈160/domain≈7/engine≈133/game≈136，2026-09-10 实测）
# 逐个判定所在函数是否 suspend / lambda 是否运行于协程（launch/async/runBlocking/withContext 体）
# 且 catch 体未前置 CancellationException 分支 → 入本批清单
grep -rn '@Suppress("[^)]*TooGenericExceptionCaught' --include='*.kt' \
  android/app/src/main android/core/*/src/main android/feature/*/src/main
```

**排除**（README §3.2 所有权约束）：batch-01 的 engine 34 文件、batch-02 的 game 7 文件、
batch-03 的 DiscipleTables、batch-05 的 GameStateRepository——这些文件被结构批拆分/重构中，
本批跳过并登记（结构批完成后补做，避免同文件双改）。

## 3. 修复模式（逐处判定，三选一）

| 形态 | 修法 | 论证要点 |
|---|---|---|
| 常规（默认） | **两段式 catch**：`catch (e: CancellationException) { throw e }` 前置于 `catch (e: Exception)` | 取消必须穿透；不改其余行为。注意：新增分支是独立 catch 子句（`e is CancellationException` 的 if 检查会触 InstanceOfCheckForException） |
| 原子段（存档写盘/WAL 提交/DB 事务） | 关键段包 `withContext(NonCancellable) { ... }`，段外照常传播 | 该段**必须完成**才能保证一致性（失败回滚语义依赖）——论证段内无无限等待 |
| 刻意吞取消（极少数，如后台守护任务自身生命周期管理） | 附理由 @Suppress 并与既有注解**合并单注解** | 需给出"该协程不属于结构化取消树"的证据 |

**每处必须留一行论证注释**（取消穿透/NonCancellable 原子段/刻意吞并的理由）——本批
验收看的是论证质量不是数量。

## 4. 纪律

- 行为变更批：新增 CancellationException 分支会改变"弹窗关闭瞬间该协程的行为"——
  每处的测试面 = 该路径既有单测全绿 + 无新增 flaky（被取消路径原先"跑完"现在"中断"，
  若测试依赖跑完则测试本身在测僵尸行为，修正测试并登记）。
- 不收窄泛型 catch 本身（异常源不可枚举，§2.23 口径不变）；本批只前置取消分支。
- @Suppress 合并纪律：同目标多注解只生效其一/编译错——新增抑制并入既有注解。
- 测试自身协程（runTest 等）中的 catch 同样在列，但修法以保持测试意图为准。

## 5. 验收

1. 清单内每处：三形态之一 + 论证注释；登记表（文件:行 → 形态 → 理由）进 §2.33。
2. §4 模板：①②④（五模块全量回归——本批触碰面分散，data/engine/game 全量必跑）③⑤。
3. 既有套件零新增失败零新增 flaky；`:core:data` 707 / `:feature:game` 868 基线全绿。
4. handover §2.33 + CHANGELOG；跳过文件（结构批所有权）的待补清单登记进 §2.33。

## 6. 本批触碰文件声明

- 全仓 suspend 上下文 catch 点（排除 §2 所列结构批所有权文件）+ 相关测试。
- **不触碰** detekt-baseline/guard（新增分支若触新违规——如 CCM +1——实修或附理由
  合并注解，禁止装回）。
