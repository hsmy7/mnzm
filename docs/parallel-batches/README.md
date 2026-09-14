# C++ 迁移剩余工作·并行批次总览与协作协议

| 项 | 内容 |
|---|---|
| 文档性质 | **协调文档**（非实施批）：剩余工作全集 → 批次映射、依赖图、多批并行的共享文件协议、统一验证与纪律。各实施批见同目录 `batch-01` ~ `batch-10` |
| 依据 | [cpp-migration-handover-m0.md](../cpp-migration-handover-m0.md) §4 遗留待办 + §5 下轮建议（截至 2026-09-09 M3 第十批）+ [ui-read-surface.md](../ui-read-surface.md) §4.1 逐域写者审计 + 仓库实测（2026-09-10，基于 commit `ab9b4fa`） |
| 基线事实 | detekt 拆分任务队列残量实测 **44 条**（guard：core/engine=34、feature/game=8、core/domain=2，app/data/ui=0）。handover §2.29 记"59→45"为笔误，以 guard 分模块计数为准 |
| **收口状态（2026-09-11）** | 10 批**全部交付并已合流**为单一可编译树：分支 `integration/parallel-batches`（基于 batch/02）→ **已合入 `main`**（合并提交 `c125f52`，合并树与集成提交 `b7f6788` 逐字节相同），随后**分支已清理**：`batch/*` 与 `integration/*` 全部删除，仅 `main` 保留；`batch/05/06/09` 的提交链以归档 tag `archive/batch-05-dirty-ledger` / `archive/batch-06-sink-building` / `archive/batch-09-sink-diplomacy` 保留可达。详见 handover §2.40 与 §4.1。**教训（本文件协议补强）**：各批在自己的分支交付后无人合并，且 `a45692b` 带走组 C 在途 Kotlin 改动而遗漏其引用的 C++ 产物（road_tx.h/diplomacy_tx.h + 两 GTest 未入库）→ 最全分支的 native/桌面 GTest 无法干净编译。**新增协议条款**：①`gen-action-ids.mjs`/`action_ids.h`/`ActionIds.kt`/`execute_dispatch.cpp`/`test/CMakeLists.txt` 是**原子变更集**，必须同批提交；②批次分支完成后**必须**并入集成分支并跑全量门禁（detekt 六模块 + 六模块主源/测试源编译 + 引擎全量对拍 + 桌面 CTest + NDK + lintRelease）才算收口；③合并时冲突不得取"theirs"整体覆盖（旧基线的批会把后续批次的结构重构整体回退），须逐项补差；④收口后立即清理分支，非祖先提交一律先打 `archive/*` tag 再删。 |

---

## 1. 剩余工作全集 → 批次映射

| # | 批次文档 | 模块 | 规模 | 依赖 | 可否立即并行 |
|---|---|---|---|---|---|
| 01 | [batch-01-detekt-split-engine.md](batch-01-detekt-split-engine.md) 拆分队列·engine 域管理者族 | core:engine | 34 条（26 TMF + 8 LC） | 无 | ✅（组 A） |
| 02 | [batch-02-detekt-split-game-viewmodels.md](batch-02-detekt-split-game-viewmodels.md) 拆分队列·game ViewModel 族 | feature:game | 8 条（7 TMF + 1 LC） | 无 | ✅（组 A） |
| 03 | [batch-03-detekt-split-domain-discipletables.md](batch-03-detekt-split-domain-discipletables.md) 拆分队列·DiscipleTables | core:domain | 2 条（高风险深耦合） | 无 | ✅（组 A） |
| 04 | [batch-04-coroutine-cancellation.md](batch-04-coroutine-cancellation.md) 协程取消传播专项 | 全仓 | ~90 处（行为论证） | 无（软性：避开 01/02 所有权文件，登记后补） | ✅（组 B） |
| 05 | [batch-05-repo-dirty-ledger-removal.md](batch-05-repo-dirty-ledger-removal.md) GameStateRepository dirty 记账摘除 | core:data | 小批 | 无 | ✅（组 B） |
| 06 | [batch-06-sink-building-transactions.md](batch-06-sink-building-transactions.md) 建筑放置/迁移/升级/拆除事务下沉 | engine+cpp | WS-2 规模 | 无 | ✅（组 C） |
| 07 | [batch-07-sink-road-transactions.md](batch-07-sink-road-transactions.md) 道路事务下沉 | engine+cpp | 中批 | 无 | ✅（组 C） |
| 08 | [batch-08-sink-disciple-gear-manuals.md](batch-08-sink-disciple-gear-manuals.md) 弟子管理第一子批（装备/功法/任命） | engine+cpp | WS-2 规模 | 无 | ✅（组 C） |
| 09 | [batch-09-sink-diplomacy-favor-vassal.md](batch-09-sink-diplomacy-favor-vassal.md) 外交/好感/附庸族下沉 | engine+cpp | WS-2 规模 | 无 | ✅（组 C） |
| 10 | [batch-10-device-verification.md](batch-10-device-verification.md) 真机验证批（含 RNG 断言升级收口） | 真机 | 大类 | 需真机；代码面仅 RNG 断言小改 | ✅（组 D，与全部代码批正交） |

**组内完全并行、组间也完全并行**；组 A 内三批按模块隔离（各自模块独立的 `detekt-baseline.xml` 与 guard 行）。组 C 四批的共享冲突面集中在 3 个文件，协议见 §3.3。

### 不生成为实施批的工作（勿遗漏）

| 类别 | 项 | 处置 |
|---|---|---|
| 依赖终局 | 反向同步通道按域全关（§4.3 关闭动作：停捕获 + 信封摘段 + 体积归零验收） | **前置 = 组 C 全部域批 + §5 后续波次全部下沉**。所有域下沉后单开终局批（见 §6 波次表末行） |
| 待拍板 | WS-4 NPC 移动系统 | 需用户先提供 NPC 玩法设计文档（数量上限/生成规则/与弟子系统关系/可行走语义——树/边界是否阻塞）。E3 组件族与寻路三要素地基已就绪（handover §2.14/§2.19） |
| 待拍板 | P1-5 月结配对结构级优化 | M×F 循环形状 = RNG 消费序，降复杂度须双端同步改算法 = 行为基线变化，需用户拍板（handover §4.1） |
| 待拍板 | 地图跨版本冻结协议批 | 地形现不入存档/镜像协议（确定性再生零成本）；若需冻结语义须拍板补协议（handover §2.19 偏差登记） |
| 待拍板 | `TimeSystem.onPhaseTick` / `GameSettingsData.autoSave` 删除 | 已拍板保留（对拍基准 / 序列化设置字段），维持现状，需用户再次拍板才动（handover §4.2） |
| 立项 | WS-1 阶段 3 数据导向存储（列级 delta/二进制通道 + dirty_tracker 列级写屏障） | 计划 v2 阶段 3，约 145+ 列写点回归风险，需单独立项而非派工（handover §4.1） |

---

## 2. 依赖图

```
batch-01 (engine detekt) ──┐
batch-02 (game detekt) ────┤  组 A：互不相交（不同模块 baseline/guard 行）
batch-03 (domain detekt) ──┘
        │ 唯一软依赖：batch-04 跳过 01/02/03 所有权文件（登记后补，见 §3.2）
batch-04 (取消传播) ─────── 组 B：与 05 文件正交
batch-05 (dirty 摘除) ─────┘
batch-06 (建筑下沉) ───────┐
batch-07 (道路下沉) ────────┤  组 C：共享面仅 3 文件（协议见 §3.3）
batch-08 (弟子装备功法) ────┤
batch-09 (外交好感附庸) ────┘
batch-10 (真机验证) ─────── 组 D：不碰生产代码（除 RNG 断言升级收尾），与全部批正交
        │
        ▼ （远期：组 C + §6 后续波次全部域下沉完成后）
反向通道终局关闭批（停捕获 + 信封摘段 + 归零验收）
```

注意：**batch-02 与组 C 都可能触碰 feature/game**。协议：下沉批（06-09）接线一律在
Facade/Service/Delegate 层完成，**不改 `GameViewModel.kt` 本体**（S7 先例：门面层 native
分支，ViewModel 零改动）；若确需改，只动 delegate 文件并在 PR 声明与 batch-02 的合并
顺序（batch-02 先合并更优）。

---

## 3. 并行协作协议（每批开工前必读）

### 3.1 分支与提交

- 每批一分支：`batch/<NN>-<slug>`（如 `batch/06-sink-building`）。
- **严格按本批触碰文件清单暂存，禁止整仓 `git add`**——仓库曾发生并行会话互相覆盖
  （handover §2.26/§2.29 两次实际冲突，MainGameScreen 编辑回退、app 编译被并行符号卡死）。
- Windows 共享 Gradle daemon 的 classes.jar 文件锁：`gradlew --stop` + 杀残留
  `KotlinCompileDaemon` + 删受损 intermediates 后重试（handover §2.27 机制发现②）。
- 批前 `git status` 快照在多批并行时**不可信**：每文件保存后以编译 + 定向 diff 复核
  （§2.26 机制发现④）。

### 3.2 文件所有权（跨批互斥）

| 文件/目录 | 拥有批 | 其他批规则 |
|---|---|---|
| `android/core/engine/detekt-baseline.xml` + guard `core/engine=` 行 | 01 | 04/06-09 若在 engine 产生新违规**必须实修**（13.2 禁止装回/禁增） |
| `android/feature/game/detekt-baseline.xml` + guard `feature/game=` 行 | 02 | 同上 |
| `android/core/domain/detekt-baseline.xml` + guard `core/domain=` 行 | 03 | 同上 |
| batch-01/02/03 的 34+8+2 条 baseline 目标文件本体 | 各 detekt 批 | 04 的取消传播点若落在这些文件：**跳过并在批内登记**，由对应 detekt 批完成后补做（结构拆分会移动这些代码） |
| `core/data .../GameStateRepository.kt` | 05 | 04 跳过该文件（05 摘除 dirty 机制时的回滚语义重构优先） |
| `GameViewModel.kt` | 02 | 06-09 禁改（见 §2 末段）；04 跳过并登记 |
| `GameCoreBridge.cpp/.kt` | 10（RNG 断言升级） | 06-09 走 `nativeExecute` 转发**不得**新增 JNI 导出（S6/S7 先例）；确需新导出时在 PR 提前声明与 10 协调 |
| `StateSyncService.kt` / `GameStateStoreImpl`（捕获面） | 无人（终局批才动） | 06-09 只加 native 分支，**不动反向捕获/信封**——关闭动作属终局批 |

补充两条软协议：① batch-04 与组 C（06-09）若撞上同一文件（下沉接线点恰含旧
suspend catch）：**后到者 rebase 重放**，双方 PR 均按文件清单暂存可自动合并的照常，
不可自动解的找协调人；② 组 C 各批之间除 §3.3 三文件外撞上同一 Kotlin 文件时同样
后到者 rebase。

### 3.3 组 C 共享文件协议（下沉四批）

| 共享文件 | 协议 |
|---|---|
| `scripts/gen-action-ids.mjs`（ACTION_CATALOG 单一事实源） | 只在目录中**追加自己 ID 段**的条目（互不重叠，见 §3.4），改后运行 `node scripts/gen-action-ids.mjs` 重新生成两份产物（`action_ids.h` + `ActionIds.kt`）一并提交。追加式改动 git 合并可自动解 |
| `android/app/src/main/cpp/gamecore/src/execute_dispatch.cpp` | 每批新增**独立** `handleXxx` 域函数 + 中央 switch（~L1396 起）**各自一行** `case`。禁止改其他域的 handler |
| `android/app/src/main/cpp/gamecore/include/gamecore/state/models.h` | 原则上不改（建筑/道路/弟子/外交状态字段已齐备：`placedBuildings`/`roads`/弟子行/镜像段）。确需新增字段 = 协议漂移，PR 提前声明（对拍键集红线） |

### 3.4 ActionId 段预分配（当前 MAX_ID=1444）

| 批 | 段 | 用途 |
|---|---|---|
| （预留） | 1445–1449 | 紧急修复 |
| batch-06 | **1450–1469** | 建筑域 |
| batch-07 | **1470–1479** | 道路域 |
| batch-08 | **1480–1499** | 弟子装备/功法/任命 |
| batch-09 | **1500–1519** | 外交/好感/附庸 |
| 后续波次 | 1520+ | 见 §6，开批时再分配 |

### 3.5 handover 章节号预分配

各批完成后在 handover §2 追加**自己预分配的**小节（避免并行追加冲突；编号空洞无妨）：
01→§2.30，02→§2.31，03→§2.32，04→§2.33，05→§2.34，06→§2.35，07→§2.36，
08→§2.37，09→§2.38，10→§2.39。同时在 §4.1 勾销自己的登记项（只删/划线自己的行）。

`task_plan.md` / `findings.md` / `progress.md` / `CODE_WIKI.md` 为共享规划记忆文件，
**各批不直接编辑**——机制发现以"findings 候选"写入 PR 描述，由收口人统一合并
（这些文件是追加式历史，并行编辑冲突率高）。

---

## 4. 统一验证命令模板（各批按触碰面裁剪）

```bash
# 工作目录：android/（gradle 根）
# ① detekt 六模块（触碰 Kotlin 的批次）
./gradlew :app:detekt :core:data:detekt :core:domain:detekt :core:engine:detekt :feature:game:detekt :core:ui:detekt
# ② 编译（主源 + 测试源——改名/拆分批必须跑测试源编译，主源不查测试源）
./gradlew :app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin compileReleaseUnitTestKotlin
# ③ 引擎全量单测（对拍验收）——JNI 路径必须绝对路径，且 --rerun-tasks（系统属性非任务输入，否则假绿）
./gradlew :core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>
# ④ 模块回归
./gradlew :core:data:testReleaseUnitTest --max-workers=1
./gradlew :feature:game:testReleaseUnitTest --max-workers=1
./gradlew :app:testReleaseUnitTest --tests "...core.state.*" --tests "...core.repository.*"
# ⑤ 提交门
./gradlew :app:lintRelease
./gradlew :app:externalNativeBuildRelease        # 触碰 C++ 的批次
# ⑥ 桌面 C++ 单测（触碰 C++ 的批次；ctest 以 build/desktop-test/ 为准，build/ 是旧套件）
#    先重建 desktop-jni：pwsh -File scripts/build-desktop-jni.ps1（llvm-mingw 工具链）
```

已知坑（沿用 handover findings，勿重踩）：`-D` 单横线不是 `--D`；相对 JNI 路径 →
UnsatisfiedLinkError 假失败；detekt 全量重跑必须**全规则看报告**（行文本变化会使其他
规则既有条目失配复活）；多 `@Suppress` 注解同目标只生效其一/编译错——必须并入既有注解；
detekt 报告行号随编辑漂移，脚本以新鲜报告为输入且幂等。

---

## 5. 统一纪律（所有批次必须遵守）

1. **RNG 红线**：任何重构/下沉不得改变 RNG 抽取集与顺序（对拍逐位一致是最终裁决）。
   触碰结算/事务循环形状的改动必须论证抽取序不变；结构级变更须双端同步（需拍板）。
2. **行为零变更证明**（detekt 批/重构批）：拆分移动的代码与源**逐字节 diff 校验**；
   同包顶层扩展/文件级私有函数是零调用点变化的落位惯用法（§2.28/§2.29 先例）。
3. **13.2 禁止装回**：本批触碰面不得新增任何 baseline 条目；新违规实修或附理由
   `@Suppress`（与既有注解合并单注解多参数）。
4. **guard 只缩不增**：`android/detekt-baseline-count.guard` 只许改自己模块行且数值只减。
5. **次生违规根治**：以"本批触碰文件零新增违规"为清偿标准，全规则重跑裁决。
6. **诚实回退**：超出"行为零变更"安全边界时 git 还原 + 装回登记，不带病拆分
   （DiscipleTables 先例，§2.29）。
7. **提交前跑 §4 ⑤**；PR 描述附验证输出摘要与 findings 候选。

---

## 6. 后续波次登记表（本轮未开批，供下一轮继续派工）

> **➡️ 下一轮派工文档已就绪：[../parallel-batches-w2/README.md](../parallel-batches-w2/README.md)**
> （W2-b ~ W3：批次 11–21 + 非并行项 [non-parallel-work.md](../parallel-batches-w2/non-parallel-work.md)；
> 含 ActionId 段 1530–1729、handover §2.42–§2.53 预分配、文件所有权矩阵）。
> 下表为本轮（batch-01~10）的原始波次登记，**已被 W2 文档细化取代**，保留作历史留痕。

来源 ui-read-surface §4.1（域→写者→下沉批次清单）+ handover §4.1/§5：

| 波次候选 | 域 | 备注 |
|---|---|---|
| W2 | ~~库存残余：商人买卖/上架、出售族残余、开袋、充公~~ **出售/上架/材料消耗族已下沉（2026-09-11，W2-a，handover §2.41）**：`inventory_tx.h`（ActionId 1520–1525）六入口单类出售 + 批量出售 + 商人收购 + 上架/撤下 + 按名称品阶材料消耗；**剩余**：商人购买（容量预测+模板转换）、开袋（EXPLORATION RNG + `Random.Default` 模板抽取）、充公（BagItemReconstructor+装备实例回仓+溢出抑制） | add/remove/sort/consolidate/lock 五族已 C++ 转发 |
| W2 | 巡逻/探索：assign/remove/swap/autoAssignPatrolAtomic、worldLevels 战斗结算 | patrol 槽已在镜像域 |
| W2 | 招募/派遣/俘虏残余：回退路径、列表刷新/老化、lifeEvents 补写、startMission、奖励发放、俘虏装备物化 | 手动+一键主路径已 C++ |
| W2 | 生产 UI 面残余：自动续班入口、镜像槽维护 | 月结视图已窗口对齐兜底 |
| W3 | 弟子管理后续子批：婚姻/收徒/逐出/状态同步/仓库驻守/玉符/checkpoint | batch-08 之后按子域续拆 |
| W3 | 月年边界编排域：processMonthYearChange 边界效果、洞府探索、天劫、兑换码、宗门升级、政策开关、设置项、guide、邮件附件 | 逐批，每批 1-2 个子域 |
| W3 | 秘境平台段评估（start 换岗/到期守卫/回退路径） | S6 会话域已下沉 |
| W4 | aiSectDisciples 段月变真相源切换（S-15/S-16）→ 段反转或关闭 | 依赖战斗阵亡/吞并回退事务下沉 |
| 终局 | **反向通道关闭批**：逐域停捕获 + 信封摘段 + 反向信封体积归零（可观测验收） | **前置 = 以上全部域下沉**；提前关闭任何域 = 数据丢失缺陷（ui-read-surface §4.3） |

---

## 7. 各批完成后动作（收口清单）

1. 验证全绿（§4 裁剪面）+ guard 只缩更新（若适用）。
2. handover 追加自己预分配的 §2.x 小节（做了什么/为什么/验证结果）+ §4.1 勾销。
3. `CHANGELOG.md` 追加条目（沿用既有格式）。
4. findings 候选写入 PR 描述（收口人合并进 findings.md）。
5. 触碰 C++ 协议面的批次：核对 `docs/cpp-engine.md` / `CODE_WIKI.md` 是否需要同步
   （交收口人）。
6. 开批前重读 handover 最新增量（其他批可能已清偿重叠项）；合并冲突按 §3 协议处理，
   冲突无法自解时找协调人拍板。
