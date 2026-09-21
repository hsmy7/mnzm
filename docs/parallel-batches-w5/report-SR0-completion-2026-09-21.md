# SR-0 前置侦察批 · 完成报告

> 日期：2026-09-21（2026-09-22 凌晨收尾）
> 实施会话：ZCode 子会话（看护 23:55 派发，每会话只做 SR-0 一批）
> 侦察报告（正本）= `docs/sr0-recon-report-2026-09-21.md`；施工卡 = `docs/parallel-batches-w5/batch-SR0.md`

## 0. 总判定

**Go**——五个子项全部交付，No-Go 条件（payload 无法压红线内且分 key 不可行）未触发；
IN5 红线建议 **2MB**（最坏实测 0.29MB 的 ~7× 余量）；SR-1 起可按依赖链推进。

## 1. 逐项结论与证据

| 任务 | 结论 | 证据（commit / 实测） |
|---|---|---|
| T5 嵌套事务收口 | **并入同一事务，审计 §15 存疑 1 解除**（审计已补注） | `ce6945f84`；`RoomNestedTransactionSemanticsTest` 4/4 绿（真实 `GameDatabase`，合并/外滚传染/内败穿透/吞内层仅回滚内层）+ room-runtime 2.7.0 `ConnectionPoolImpl` SAVEPOINT 源码证据 + 同线程探针 `arch_disk_io_*` |
| T1 payload 尺寸实测 | **Go**；尺寸-游戏年曲线 + 裁剪决策 + IN5 红线 2MB 建议 | `945d608d9`；`CloudPayloadSizeBenchTest` 3/3 绿：1年 32.8KB → 50年 264KB → 极限档 286KB（压缩率 ~0.10）；单弟子 351B/单日志 437B；battleLogs 1000 条=209KB 线性（维持现役封顶）；heavy 分 key 无必要 |
| T2 TapTap v4 限额 | **Go**：100 档/100MB 总量、10MB 单档、限频 1/min 共享冷却（保守口径）、同档禁并发 | `5c2add385`；官方 v4 文档（developer.taptap.cn tap-cloudsave）+ 本地 AAR 4.10.5 API 面佐证；文档口径冲突（60/min vs 1/min）如实登记 |
| T3 云多档语义 | **代码层可行**（`archiveName=slot_N`）；单档硬编码 7 处施工面清单交 SR-2/SR-3；**6 项待真机如实登记** | `08f3949e7`；`CloudSaveApi`(:678-689) 全名参数化 + AAR javap 签名核验逐项匹配（create/update/delete/list/getData + 回调族） |
| T4 双设备冲突剧本 | SR-2 仲裁单测清单 **11 例 verdict 纯函数 + 10 例队列状态机**；双设备剧本矩阵 S1-S10 | `f4ed9674f`；状态模型 (L,C,W) 四态 verdict，零时钟判据守 IN2；UPLOAD_PENDING≠CONFLICT 为关键正确性 |

## 2. 勘察发现（超出任务书预期的实事）

1. **`aiSectDisciples` 带 `@Transient`，不进云档 payload**——29 宗 × 1000 弟子的最坏担心不成立；
   但换设备云读档时 AI 宗弟子按 ~初始态重生成（现役语义）→ **SR-3 前需用户拍板**：
   接受重生成 vs heavy 域并入云档（现实态 ~0.5MB 可行、1000/宗 满编 ~10MB 需配裁剪）。
2. **`terrainTiles`（16384 瓦片）在云档内**，实测仅 ≈3KB——RLE 优化无必要（触发条件未满足）。
3. TapTap 限频文档两处口径矛盾（功能介绍 60 次/分钟 vs 开发指南 1 次/分钟共享冷却）——
   UploadQueue 按保守口径设计、参数化待真机放宽。
4. Room 2.7.0 KMP 重写后嵌套事务语义为连接级 SAVEPOINT；"吞内层异常=只回滚内层写"
   与旧 room-ktx（≤2.6）行为不同——后续批写代码不得依赖吞内层异常做"部分提交"。

## 3. 交付物清单

- 施工卡 `batch-SR0.md` + 台账 SR-0 行（in_progress→delivered）
- 侦察报告 `docs/sr0-recon-report-2026-09-21.md`（§1-§6 全部落稿）
- 台架测试 2 文件**保留于测试目录**（`CloudPayloadSizeBenchTest` = IN5 CI 断言种子；
  `RoomNestedTransactionSemanticsTest` = Room 升级守卫），零产品代码改动
- 审计报告 §15 存疑 1 补注（原表未动）
- 完成报告（本文件）

## 4. 提交清单（7 笔，每子项独立、不夹带）

| commit | 内容 |
|---|---|
| `d096a05ef` | 施工卡立卡 + 台账 in_progress + 监控日志 |
| `ce6945f84` | T5 嵌套事务（测试 + 审计补注 + 报告 §5） |
| `945d608d9` | T1 payload 实测（台架 + 报告 §1） |
| `5c2add385` | T2 TapTap 限额复核（报告 §2） |
| `08f3949e7` | T3 云多档语义勘察（报告 §3） |
| `f4ed9674f` | T4 冲突剧本设计（报告 §4） |
| 本笔 | §6 结论汇总 + 台账 delivered + 完成报告 |

## 5. 门禁与纪律自检

- **零产品代码改动**：全部提交面 = `docs/**` + `android/core/data/src/test/**`（2 新测试文件）；
  生产源码、schema、proto、CI 均零触及（`git diff --stat` 全程可核）。
- **测试实跑**：两台架定向跑绿（4/4 + 3/3，XML 计数与时间戳在本报告与各 commit 说明）；
  本批为侦察批，无组合门义务（派发指令口径），测试目录新增文件不影响既有基线计数
  （组合门下轮实跑时计 7861+7 例，属测试资产增加非产品行为变化）。
- **诚实登记**：T1 样本为合成生产形状（未采真实档）；T3 六项待真机；
  T2 口径冲突；均已在报告对应节明示。
- **未登记 `accepted`**（归用户/验收轮）。

## 6. 遗留与建议

1. IN5 红线 2MB 固化进 CI 的时机：建议 SR-2（SaveBackend 批）开工时一并收紧断言。
2. SR-3 真机项（T3 六项 + 剧本 S1-S10）建议提前排真机/沙盒窗口（方案 §6 已登记基建欠账）。
3. AI 宗弟子保真拍板（§6.3-1）建议在 SR-1 完成后、SR-3 设计前由用户裁决。
