# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 写入守卫架构债务（⏸️ 暂不修复）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)，6 项低风险守卫设计限制记录在案：
1. `store` 底层存储绕过守卫
2. `requireWrite` / `onWrite` 为 `@JvmField var`
3. `writeGuardEnabled` 全局开关
4. `ids` public MutableList
5. `deathRecords` public MutableList
6. 影子结算死代码（已移除守卫兼容代码）

## 邮件/兑换码 RNG 未接入分区 PRNG（⏸️ 低优先级）

`RedeemCodeManager.kt` 顶层使用 `DeterministicRng.fromSeed(System.nanoTime())` 作为随机数源，
以下路径未走 `GameRngManager` 分区 PRNG：

- `generateDiscipient()` — 弟子属性/灵根/天赋随机生成
- `generateRandomEquipment()` — 装备随机生成（间接调用 `EquipmentDatabase.generateRandom`）

影响范围：
- 运营补偿邮件中通过 `MailAttachment(type="disciple")` 发放弟子时，弟子属性随机使用顶层 RNG
- 兑换码系统中所有随机物品/弟子的生成

建议：迁移至 `GameRngManager.getRng(RngPartition.SYSTEM)`，与邮件系统随机操作统一。
当前风险低（邮件/兑换码操作不涉及战斗或突破，RNG 不一致不影响核心玩法）。
