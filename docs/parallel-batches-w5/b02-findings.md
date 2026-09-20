# B02 途中发现登记——解析口径双份与两处范围外观察

> 日期：2026-09-18。来源：批次 B02（R1.2 + R1.3，去物化 + dense 索引）施工完成报告
> "途中发现"第 3、4 条的展开与复核。关联提交：`96636ec95`（R1.2）/ `d4e25dac1`、
> `dd2b4e0e9`、`f7e9b3251`（R1.3 两步 + 补遗）/ `c656dce3f`（文档三件套）。
> 性质：均为**观察登记**，不是 B02 交付的阻塞项；逐项处置建议见文末汇总表。

---

## 发现 3 —— 整数解析逻辑双份实现（已用守卫测试锁定一致）

### 现象

B02 R1.3 第一步给弟子存储表（`DiscipleStore`）新增"数字 id 列"时，需要一份
"字符串 → 整数"的解析逻辑。这套逻辑仓库里原本就有一份——
`settle_util::toIntOrNull`（`gamecore/system/settlement_detail.h`，Kotlin
`String.toIntOrNull` 的逐字等价实现）。本次没有直接复用它，而是在存储层复制了一份
**同实现**的 `DiscipleStore::parseNumericId`（`gamecore/src/disciple_store.cpp`）。

### 为什么复制而不是复用

仓库分层规则（CLAUDE.md §2.1）："依赖方向不可反转"——`state` 层（弟子存储所在）
只能被上层引用，**不能反向引用** `system` 层（解析工具所在）。若在存储层 include
`settlement_detail.h`，依赖方向即被打破。因此单点复制，并在两处注释中互相指认。

### 风险与已布置的对策

复制的经典风险是"改了一份忘了另一份，两边口径悄悄分叉"（表现为同一弟子 id 在
不同读取路径下被判定为有效/无效不一致）。已布置对策：

- **守卫测试** `DiscipleStoreTest.ParseParityWithSettleUtilToIntOrNull`
  （`disciple_store_test.cpp`，随 `d4e25dac1` 落库）：以边界样本
  （空串 / 非数字 / 数字后缀 / 前导空白 / 尾随空白 / 显式正号 / 负号 / 前导零 /
  int32 上界 / 溢出大数）同时喂两份实现，断言"有效性与数值"逐样本一致。
  任何人改动其中一份，该测试立即红，强制同步。

### 附注：64 位平台溢出行为差异（既有特性，非本批引入）

事实链：解析底层 `std::stol` 解析到 C++ `long`，其宽度**随平台变化**
（Windows 32 位 / Linux、安卓 arm64 64 位）。对超出 int32 表示范围的大数串
（如 `"99999999999"`）：

| 平台 | `std::stol` 行为 | 最终结果 | Kotlin `toIntOrNull` |
|---|---|---|---|
| Windows（桌面 GTest） | 32 位 long → 溢出抛异常 | "无效"（与 Kotlin 一致） | 无效 |
| Linux / 安卓 arm64（真机 .so） | 64 位 long → 解析成功 | `static_cast<int32_t>` 截断回卷（C++20 语义确定） | **无效** → 两端不一致 |

要点：

1. **这是 `settle_util::toIntOrNull` 的既有行为**，`parseNumericId` 刻意保持
   一致（两端一致性守卫的口径基准就是它），不是本批新引入的分叉。
2. **实际影响评估：当前不可达**。弟子 id 为自增小整数（Kotlin 侧生成口径），
   存量与可预见增量都远小于 2^31；没有任何路径会产生超界 id 串。
3. **若需根治**（登记备查，不建议立即做）：在两处解析中把 `stol` 结果先做
   `long → int32` 范围检查、超界返回"无效"即可与 Kotlin 对齐；属**行为变更**
   （Linux/真机臂语义收口），须按对拍纪律单独走批，并在 arm64 对拍 CI
   （R0.2 建立的 Test Lab 通道）配好后补超界样本用例。
   **触发条件**：id 生成机制变更为可能超 int32 的口径，或 arm64 对拍 CI
   纳入超界样本之前——二者满足其一时再处理。

---

## 发现 4 —— 范围外观察两处（4b 为对施工报告口径的更正）

### 4a 战斗组装域保留 map 版 `finalStats`（语义必须，非漏改）

**现象**：任务完成结算（`mission_completion.h` 的 `discipleToCombatant` /
`createBeastBattle`）与宗门防御战（`sect_defense_battle.h`）、AI 宗门模拟
（`ai_sect_ops.h`）组装战斗体时，仍走传参映射版
`stats::finalStats(d, equipmentMap, manualMap, ...)`。

**不动它们的两个理由**：

1. **不在本批热路径**。本批改造对象是"每旬结算"——全宗门弟子每旬都要走的
   高频链路；上述三处是"打一场仗 / 完成一个任务才执行一次"的低频域。
2. **输入形态不兼容，语义上必须保留**。桶式存储（`instance_buckets.h`）的
   寻址键是"弟子在宗门弟子表（DiscipleStore）中的行号"，且指向宗门装备/功法
   实例表（`state.equipmentInstances`/`state.manualInstances`）；而战斗组装的
   输入常是**临时副本**——典型如 AI 宗门弟子的装备是现场模拟生成、不存在宗门
   实例表中（`ai_sect_ops.h` 自建 per-disciple 模拟装备映射），根本没有行号
   可寻。硬套桶结构等于要求"临时道具必须先进宗门库存表才能参战"，属语义变更，
   违反本批"只改形状不改结果"红线。

**结论**：`finalStats` 的映射签名是该域的正确形态，保留；本批删除的是
`getMaxHpMp` / `calculateCultivationPerPhaseColumn` 的映射版（全部调用方
已确认可迁移完毕），与 `finalStats` 无关。

### 4b `applyEquipmentUpdates` 复核更正：单遍 O(E)，无性能问题（更正施工报告）

**更正**：B02 完成报告原文写"applyEquipmentUpdates 提交仍逐 update 全表扫描
（每旬一次，低频）"——**该描述不准确，特此更正**。实测实现
（`phase_settlement.h:532`）：

```cpp
inline void applyEquipmentUpdates(
        GameState& state, const std::map<std::string, EquipmentInstance>& updates) {
    if (updates.empty()) return;
    for (auto& eq : state.equipmentInstances) {      // 单遍历装备表
        const auto it = updates.find(eq.id);          // 每实例查一次更新映射
        if (it != updates.end()) eq = it->second;
    }
}
```

即**遍历装备表一次**（O(E)，每实例一次 map 查找），不是"每条更新扫全表"
（O(U×E)）。每旬结算仅执行一次、E 为装备实例量级，**当前实现已经合理，
无优化必要**。原文将"以表为主循环、按实例查更新"误述为"以更新为主循环、
逐条扫表"，方向说反了。此条登记以更正为准，不产生任何待办。

---

## 处置建议汇总

| # | 事项 | 是否需要行动 | 建议归属 / 触发条件 |
|---|---|---|---|
| 3 | 解析逻辑双份 | 无需立即行动（守卫测试已锁口径） | 持续由 `ParseParityWithSettleUtilToIntOrNull` 守卫；若未来两份实现任一需要修改，先过该测试 |
| 3 附注 | 64 位平台溢出语义与 Kotlin 不一致 | 否（当前不可达） | 触发条件二选一：id 生成口径可能超 int32；或 arm64 对拍 CI 配好后补超界样本——届时按对拍纪律单独走批根治（`stol` 超界 → 无效） |
| 4a | 战斗组装域 map 版 `finalStats` | 否（语义必须保留） | 无待办；若未来战斗域也要求实例寻址统一，需先解决"临时模拟装备无行号"的前置问题（属 R4 战斗域批次范畴） |
| 4b | `applyEquipmentUpdates` 扫描方式 | 否（更正：已是单遍 O(E)） | 无待办；本条仅为更正施工报告口径 |
