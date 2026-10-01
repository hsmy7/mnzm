# TASKBOOK-SS9 · 玉符账本（C++ 真源）

> **本文件是 SS9 的派工真源**（开工时补卡，对齐 `DISPATCH-ledger.md` §5 在册理由）。上位方案：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) **§2.4**（玉符：不可变流水账 + 余额派生，全景取证修正版）。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：SS1 已合入（槽维度已删，`580fab8fb`）；**G 批 `models.h` / `jade_tx.h` 无在途改动（已核实 2026-10-01）**；重录窗口归属已确认 = **§4.2 安排①，正式重录在本批完成时执行**（SS1 已让位）。
> 行号证据：2026-10-01 17:5x 主树实测（SS1 合并后）。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 把玉符从「绝对值覆盖写 + 三份真相」改成**不可变流水账 + 余额派生**：任意时点余额 = 期初 + 流水合计，不存在绕过流水的余额写入，「余额回涨」类缺陷在结构上不可能发生。账本真源在 **C++**（玉符落账已在 C++ 事务族，Kotlin 单侧账本会被直接绕过）。 |
| 验收① | `GameState.jadeLedger`（C++）为 **append-only** 条目数组；全仓（C++ + Kotlin）**零**绕过账本的 `jadeSymbols` 独立赋值路径（派生缓存的唯一写法 = 账本求和/同事务双写） |
| 验收② | **六事务全部改造**为「append 条目 + 派生缓存校验」：`purchaseMerchantRefreshTx`（`jade_tx.h:289`）、`purchaseBreakthroughBonusTx`（`:322`）、`settleJadeGrantsTx`（`:418`）、`jadeDayResetTx`（`:481`）、`jadeCheckpointTx`（`:530`）、`grantJadeFromAdTx`（`:565`）；扣费/发放不再 `+=`/`-=` 余额，而是落条目 |
| 验收③ | **派生缓存同事务双写**：条目内冗余 `balance_after`；`jadeSymbols` = 账本求和缓存；两者不一致时**以账本为准**并计数上报（计数通道现状用 Log + 既有埋点；`StorageMetrics` getter 补全归 SS3，本批登记） |
| 验收④ | **性能护栏**：扣费/发放路径余额检查保持 O(1)（读缓存，不遍历账本）；不得复活 `cpp-migration-handover-m0.md:303` 的「tick 滞后窗口双花」——bench 面无退化（触碰 `jade_tx.h` 的既有 bench 判据照跑） |
| 验收⑤ | **期初开账（v2 适配）**：SS0 删档后无老档 ⇒ 「老档 OPENING_BALANCE」适配为**新档账本初始化**——新档创建（C++ 开局路径）即写一条 `reason = OPENING_BALANCE` 期初条目（`balance_after` = 期初余额，余额不变）；此后任意时点 派生余额 == 期初 + 流水合计（守卫断言） |
| 验收⑥ | **协议字段**：Kotlin `GameData.jadeLedger` 新 `@ProtoNumber` 字段（**240+ 段取号**，保留段禁复用，过 `ProtoNumberUniquenessTest`/`ProtoNumberCoverageTest`）；`json_codec.cpp` `GC_TO/GC_FROM` 双向编解码；`GameDataFieldPatch.coveredFields` 登记新字段（镜像投影合法面）；`jadeSymbols`/`jadeSymbolsToday`/`jadeDayAnchorMs`/`jadeAccumMs` 现有编号**保留不动** |
| 验收⑦ | **守卫扩面**：`JadeSymbolConsumptionGuardTest` 两个正则（现 `core/engine/src/test/.../architecture/JadeSymbolConsumptionGuardTest.kt:37-40` 只认 `jadeSymbols`）**扩到账本字段名**（`jadeLedger`），重划白名单分界——镜像写 `GameDataFieldPatch` vs 玩法写 `JadeSymbolService`；`GameDataFieldPatchGuardTest` 绿 |
| 验收⑧ | **对拍双守护齐备**：桌面 GTest `jade_tx_test.cpp`（现 17 例）/ `jade_runtime_tx_test.cpp`（现 18 例）逐值断言从「绝对值语义」改写为「append + 派生」语义（**同步改写，不留旧语义断言**）+ JUnit `Diff*Test` 跨语言对拍全绿 |
| 验收⑨ | **UI 读数收敛为一**：UI 层玉符读数统一只读镜像 `GameData.jadeSymbols`（派生缓存）；`JadeSymbolService` 运行时 `@Volatile` 计数与快照同步读数不再对外暴露（服务内私有化/退役，逐点 enumerated 进报告） |
| 验收⑩ | **正式重录窗口（本批执行）**：SS1 已占形状变更位，本批完成时执行**全局唯一一次**对拍基线权威重录（覆盖 SS1 去槽 + SS9 账本两次形状变更）；重录产物与脚本入批；**此后任何批次禁止再重录** |
| **不做** | 不改白名单发奖语义（**B2：免广告特权 = 产品有意设计**，账本仅如实记录来源 `GRANT_AD`，白名单无上限语义保持）；不引入付费通道；玉符不接 `withTrackingSource`/`OverflowMailSender`（既有设计：不占仓库、无品阶）；`jadeSymbolsToday`/`jadeDayAnchorMs`/`jadeAccumMs` 保持独立语义（墙钟日闸）**不并入账本** |

---

## 2. 实测现状（2026-10-01 主树）

- **三份真相**：① C++ `state.gameData.jadeSymbols`（`models.h:1226-1229` 四字段，AUTHORITATIVE，差额直接改）② Kotlin 镜像 `GameData.kt:288-303`（`@ProtoNumber(220~223)`，经 `GameDataFieldPatch:175-178` 写入）③ Kotlin 运行时 `@Volatile totalCount`（`JadeSymbolService.kt:137`）。对齐靠 5 个重锚点，`syncBalanceFromSnapshot` 读镜像快照为**全链最脆一环**。
- **六事务**（`include/gamecore/system/jade_tx.h`，SS1 后行号）：见验收②表；`jade_tx.h:24-37` 自陈「绝对值覆盖写模型」为 🔴 前置条件。
- **历史缺陷佐证**：`CHANGELOG.md:4293`（玉符 20→3 冷启动竞态）、`:4519`（旧循环 finally 覆盖新档）——本批结构性消灭。
- **经济面（源 2 汇 2）**：S1 在线时长（`GameConfig.Jade.INTERVAL_MS` 10min / `DAILY_CAP 20`）、S2 激励视频（`AdsDelegate.kt:35` `JADE_AD_REWARD = 3`，白名单直发不播广告）；C1 突破率加成、C2 商人刷新。**0 IAP**——账本价值 = IAP 上线前把账立起来。
- **`jadeLedger` 全仓零命中**（grep 已核）——全新字段面。

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **账本真源在 C++**，`state.jadeLedger` append-only；Kotlin 仅派生只读 + 镜像投影 | 玉符落账已在 C++ 事务族；Kotlin 单侧账本会被 C++ 直接绕过（方案 §2.4 取证修正） |
| **D-2** | **条目 + 派生缓存同事务双写**，条目冗余 `balance_after`；不一致以账本为准并计数 | 保住六事务 O(1) 余额检查；防「每扣费遍历账本」O(n) 退化与双花复活 |
| **D-3** | **期初开账 = 新档初始化写 `OPENING_BALANCE` 条目**（v2 删档适配；原「老档迁移期初」语义随 SS0 删档失效） | 账本完整性要求：派生余额 == 期初 + 流水合计 需要期初锚点 |
| **D-4** | **重录窗口在本批执行**（SS1+SS9 两次形状变更共用；§4.2 安排①） | 全局唯一权威重录；本批是窗口执行批，重录后基线冻结 |
| **D-5** | **白名单玉符 = 特权不是缺陷**：`GRANT_AD` 如实记录来源（可区分白名单直发与真实观看），语义零改动 | 用户 2026-10-01 拍板 B2 |
| **D-6** | **`reason` 枚举登记 `docs/knowledge-base.md` 经济基线表**，不对接 `OverflowMailSender.SOURCE_DISPLAY_NAMES` | 玉符不走 `withTrackingSource` 是既有设计（knowledge-base:742） |
| **D-7** | **今日计数/日锚/周期累计不并入账本** | 独立墙钟日闸语义，入账本反增复杂度 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **T-SS9a** C++ 账本模型 | `models.h`（`jadeLedger` 条目结构 + `jadeSymbols` 降级派生缓存注记）、`json_codec.cpp`（双向编解码） | 四个现有玉符字段的 proto 位置语义 | 编解码双向对称；导出形状变更登记（重录窗口内） |
| **T-SS9b** 六事务改造 | `jade_tx.h`（六事务 append 化 + 派生双写） | 白名单发奖语义、其他事务族 | 六事务逐个改造；O(1) 余额检查保持 |
| **T-SS9c** C++ 初始化 | 新档开局路径（`OPENING_BALANCE` 期初条目） | 其他开局语义 | 新档首条目 = 期初；派生 == 期初 |
| **K-SS9a** Kotlin 镜像 | `GameData.kt`（`jadeLedger` 新字段 240+ 段）、`GameDataFieldPatch`（coveredFields）、编解码/镜像链 | 四个现有字段编号 | `ProtoNumberUniquenessTest`/`CoverageTest` 绿；`GameDataFieldPatchGuardTest` 绿 |
| **K-SS9b** 服务收敛 | `JadeSymbolService`（读数改缓存派生、`@Volatile` 计数私有化/退役）、UI 读数收敛 | 玩法语义（扣减/发放入口签名尽量少动） | UI 三读数归一；守卫正则扩面后全仓零违规 |
| **c-SS9** 测试改写 | `jade_tx_test.cpp`/`jade_runtime_tx_test.cpp`（语义改写）、JUnit `Diff*Test`、`JadeSymbolConsumptionGuardTest`（正则扩面）、经济基线表 | 旧语义断言保留 | GTest 35 例全部新语义；对拍全绿 |
| **主线程** | 重录产物与脚本、`report-SS9.md`、门禁复跑 | — | 重录一次、基线冻结 |

**共享面**：`models.h` 刚被 SS1 改过——本批开工前 `git log -1 -- models.h` 复核无他批在途；**与 G 批对表**（已核 2026-10-01：G 批收官，`models.h` 无在途）。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
# 桌面 C++（触碰 C++ 必跑；先构建再 ctest）
cd app\src\main\cpp\gamecore\build\desktop-test ; cmake --build . ; ctest   # 基线 1532/1532
# 桌面 JNI 重建（仓库根）
pwsh -File scripts/build-desktop-jni.ps1
# JVM 五模块（串行）
.\gradlew.bat :core:domain:testReleaseUnitTest :core:engine:testReleaseUnitTest :core:data:testReleaseUnitTest :feature:game:testReleaseUnitTest :app:testReleaseUnitTest `
  --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" --rerun-tasks --console=plain
.\gradlew.bat :core:domain:detekt :core:engine:detekt :core:data:detekt :feature:game:detekt :app:detekt --console=plain
# 生成物与计数
node scripts/gen-action-ids.mjs ; git diff --exit-code   # 若有新 ActionId（预期不需要：期初开账走 C++ 开局路径）
node scripts/check-jni-count.mjs
node scripts/check-agent-instructions.mjs
```

**提交前**：`git status` 只留本批改动；构建副产物 `git checkout --` 还原；一次性脚本清零。

---

## 6. 登记项

1. **跨批登记（SS3）**：账本↔派生缓存不一致计数的 `StorageMetrics` getter/上报面归 SS3（本批用 Log + 既有埋点过渡）。
2. **跨批登记（SS10）**：玉符账本的玩家可见 changelog 并入 4.2.00 唯一条目。
3. **风险（必须写进报告）**：`jadeSymbols` 从「可覆盖写」降级为「派生缓存」——任何残留的覆盖写路径都会被守卫拦截，实施时须以编译错误/守卫红为遗漏清单逐个收口（编译器背书法）。
4. **风险**：重录窗口在本批执行——重录前必须 SS1+SS9 两侧形状变更都已合入工作树，**一次重录覆盖两次变更**；重录产物 hash 写进报告。
5. **经济影响**：账本使玉符来源/去向全程可审计——IAP 上线前的账面基建；本期经济数值零变化（源汇不变）。

---

## 7. 一句话给执行者

**把玉符的「三份真相 + 绝对值覆盖写」改成 C++ 端 append-only 流水账 + 派生缓存同事务双写：六事务全部落条目、新档写期初条目、Kotlin 只读镜像 + 守卫正则扩面、两侧 GTest/JUnit 对拍同步改写，并在本批完成时执行全局唯一一次对拍基线重录（覆盖 SS1+SS9 两次形状变更）。**
