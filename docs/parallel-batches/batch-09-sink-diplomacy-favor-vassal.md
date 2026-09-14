# Batch-09：UI 操作面下沉·外交/好感/附庸族（C++ 唯一真相）

| 项 | 内容 |
|---|---|
| 批次 | 09 ｜ 并行组 C（与 06/07/08 共享面仅 3 文件，协议见 [README](README.md) §3.3/§3.4） |
| 模块 | C++（gamecore 新域头 + execute_dispatch）+ Kotlin 门面（core:engine 外交域） |
| 性质 | WS-2 规模下沉批：diplomacy/vassal/favor 全 Kotlin 直改域 |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.1：「外交/好感/附庸——diplomacy/vassal/favor 全部 Kotlin 直改 → 外交族批次」 |
| 预分配 | handover §2.38；**ActionId 段 1500–1519** |

## 1. 范围

**In-scope（第一子批，状态事务为主）**：

- 外交动作族：宣战/停战/和平/赠礼（好感与关系状态机写入）
- 好感域：送礼好感增长（`FavorDomain` 纯函数公式已在 Kotlin——§2.28 拆分产物，
  本批 C++ 等价移植）/ 好感衰减（月结域既有逻辑核对归属）
- 附庸交互：纳贡/施压/附庸关系建立与解除（SectBattleStats 战绩统计读面已存在，§2.26 先例）

**Out-of-scope（登记 §2.38，归后续）**：外交聊天内容生成（若含模板 roll，属 RNG 面
另行论证）、附庸吞并/战斗联动（与 aiSectDisciples 段月变真相源切换批耦合，README §6 W4）。

## 2. 地基与前置核查

- **C++ 状态**：diplomacy/favor/vassal 段是否已在 `gameData` 镜像协议——**第一步
  核实**（若某段为 @Transient 顶层段或未导出，需先补协议字段 = 触碰 `models.h` +
  json_codec，按 README §3.3 提前声明并只加本域字段；若段根本不在快照协议，评估
  按月结消费面最小镜像）。
- **纯函数先例**：`FavorDomain`（送礼增长/交易价格/拒绝概率/偏好修正/衰减值）Kotlin
  侧已是文件级纯函数——C++ 侧逐字移植（乘区与钳制逐位一致，对拍可锁）。
- **Kotlin 门面域文件**：`DiplomacyService` / `VassalService`（core:engine
  domain/diplomacy）+ feature/game `DiplomacyFlows` 等（§2.28 拆分产物）——接线落
  Service 层，不动 feature/game 文件。

## 3. 实施步骤

1. **写者审计**：逐操作调用链 + stateStore.update 触碰字段 + **RNG 标注**。外交域
   预期 RNG 面：赠礼拒绝概率 roll / 聊天/事件模板抽取——**凡 roll 点逐个登记抽取
   分区与序**（BATTLE/SYSTEM 分区纪律）；含 roll 的操作若抽取序与 Kotlin 回退臂需
   逐位一致，对拍场景必须覆盖。
2. **C++ 域头**（`gamecore/include/gamecore/system/diplomacy_tx.h`）：
   - `favor_domain.h`（纯函数：好感公式族逐字移植，float 运算序逐位对齐——Kotlin
     侧乘加顺序不得重排，参照 terrain.h 位级移植三要点）
   - 事务族 `diplomacyTransaction`（宣战/停战/赠礼含拒绝 roll）/ `vassalTransaction`
     （纳贡/附庸建立解除）：校验链（关系状态机合法迁移/资源/好感门槛——逐字对齐
     Kotlin）→ diplomacy/favor/vassal 段写 → 信封（含 roll 结果供回退臂一致性论证）。
3. **协议**：`DIPLOMACY_TX=1500 / FAVOR_GIFT=1501 / VASSAL_TX=1502`（1500–1519 段内
   追加，细分到操作）→ 重新生成两份产物；`execute_dispatch.cpp` 独立
   `handleDiplomacyTx` + 中央一行。
4. **Kotlin 门面**：`DiplomacyService`/`VassalService` native 分支（AUTHORITATIVE
   门控 + 镜像回读 diplomacy/favor/vassal 段 + 失败信封回退 Kotlin 原路径——回退臂
   roll 用同一分区，双臂行为逐位一致由对拍锁）。
5. **测试**：GTest `diplomacy_tx_test.cpp`（状态机合法/非法迁移/好感公式黄金值
   （手算对拍 FavorDomain）/赠礼拒绝 roll 分区审计/附庸战绩读面一致性/失败零写入）；
   Diff 对拍（推荐：赠礼后好感值 + 月结外交效果双端逐位）；Kotlin flag 门控单测。

## 4. 红线

- **RNG 红线（本组 C 最高风险域）**：任何 roll 的分区与抽取序不得改变；C++ 侧 roll
  用 NativeBackedRng 同源分区（SYSTEM/BATTLE 既有分区语义），对拍场景锁终态
  rngStates。纯公式（好感增长）零 roll 论证进头注释。
- favor 衰减若在月结（C++ 已下沉月结域）——本批只动 UI 操作面写入，不触碰月结衰减
  循环形状。
- `models.h` 协议新增字段须双侧（json_codec 导出 + 键集对拍更新）——协议漂移是
  对拍假绿高发面（autoSaveIntervalMonths 教训，§2.8）。

## 5. 验收

1. In-scope 操作 AUTHORITATIVE 稳态写者归 C++（回退保留）；审计表（含 RNG 逐点
   结论与协议段核查结论）进 §2.38。
2. §4 模板全跑（⑥③必跑；协议字段有新增时对拍键集断言同步更新）。
3. handover §2.38 + ui-read-surface §4.1 外交行标注 + CHANGELOG。

## 6. 本批触碰文件声明

- 新：`diplomacy_tx.h`（含 favor 公式族）/ `diplomacy_tx_test.cpp`。
- 改：`gen-action-ids.mjs` + 生成物 / `execute_dispatch.cpp`（独立 handler + 一行）/
  `DiplomacyService.kt` / `VassalService.kt`（native 分支）/ `models.h` + `json_codec`
  （仅当第一步核查确认需补协议段，提前声明）/ 相关测试。
- 不触碰：`GameViewModel.kt`、feature/game `Diplomacy*` UI 文件（batch-02 所有权相邻，
  原则不动）、`GameCoreBridge.*`、`StateSyncService.kt`、其他域 handler。
