# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 待完成项

### 1. 广告回调透传链（原 #8/#14）

**问题描述：** `GameViewModel` 上的 `onWatchAdBreakthroughBonus` / `onWatchAdMerchantRefresh` 仍使用 `var` 回调属性，未注入 `RewardVideoAdManager` 单例。新增广告类型仍需在 ViewModel 加字段，非类型安全。

**当前改进（2026-07-20）：**
- 透传层从 5 层降至 2 层（GameActivity → ViewModel → 消费端）
- `RewardVideoAdManager` 已是 Kotlin `object` 单例

**修复方向：**
- 移除 ViewModel 的 `var` 回调属性
- 消费端（`DetailCultivationSection`、`MerchantDialog`）改为直接调用 `RewardVideoAdManager`
- 新增广告类型无需改 ViewModel

**难度：** 低

**优先级：** ⏸️ 低（当前实现可接受）

---

### 2. `lastTheftMonths` / `lastTheftMonth` 写而不再读

**问题描述：** 移除单弟子偷盗冷却后，`DiscipleComponents.kt` 中的 `lastTheftMonth` 和 `DiscipleTables` 中的 `lastTheftMonths` 组件表仍在保存/加载，但不再用于冷却判定。冷却逻辑已由 `annualTheftCount` 年上限完全替代。`executeSuccessfulTheft` 两版本已无对此字段的写入。

**影响范围：** `DiscipleComponents.kt:188`、`DiscipleTables.kt:202/685/819`

**修复方向：**
- 从 `DiscipleComponents` 中移除 `lastTheftMonth` 字段
- 从 `DiscipleTables` 中移除 `lastTheftMonths` 组件表及 save/load 行
- 删除测试中设置 `lastTheftMonths` 的行

**难度：** 低

**注意：** `DiscipleComponents` 属组件表列，删除会改变序列化格式。需在不破坏旧存档读兼容的前提下移除，或在下次 Migration 时一并处理。

---

## 写入守卫架构债务（⏸️ 暂不修复）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)，6 项低风险守卫设计限制记录在案：
1. `store` 底层存储绕过守卫
2. `requireWrite` / `onWrite` 为 `@JvmField var`
3. `writeGuardEnabled` 全局开关
4. `ids` public MutableList
5. `deathRecords` public MutableList
6. 影子结算死代码（已移除守卫兼容代码）
