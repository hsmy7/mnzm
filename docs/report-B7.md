# B7 交付报告——离线语义（实时结算线，结算改造 2026-09-27 §10 B7）

> 派发件：`docs/realtime-watch/batch-B7.md`；方案：`docs/realtime-settlement-plan-2026-09-27.md`
> §1.4（离线口径，已拍板不改口）/ §2.3（离线行）/ §10 B7 行 / §5.4（对抗性审查第 3 条）。

## 一、交付内容

### 1.1 折算口径（Kotlin，core/domain）

`GameConfig.Time.offlineGameMs(offlineWallMs)`——离线收益折算唯一口径：

- 离线时段 = `lastSaveTime`（现实墙钟基 `System.currentTimeMillis`）→ 本次读档墙钟差；
- ≤12h 全额（1x 速率：1 现实 ms → 1 游戏 ms，**与在线速度档解耦**——防"2x 挂机最优"）；
- 12h–24h 段按 50%（整数分子制 1/2）；
- 24h 硬顶，超出不再累积；注入总量上限 = 12h + 12h/2 = **18h 游戏时间**（64_800_000 ms）；
- 结果 **floor 到旬**（`GAME_MS_PER_PHASE=2000` 整数倍）——保持 GameData 权威轴旬网格
  对齐（INV-1 投影互逆 `calendarToGameMs∘projectCalendar` 恒等的前提）；floor 损失
  <1 旬（<2 游戏秒），对 12h 量级可忽略；
- 负输入/零 → 0（时钟回拨防御）。

常量族：`OFFLINE_FULL_RATE_WINDOW_MS=12h` / `OFFLINE_HARD_CAP_WINDOW_MS=24h` /
`OFFLINE_REDUCED_RATE_NUMERATOR=1` / `OFFLINE_REDUCED_RATE_DENOMINATOR=2`。

### 1.2 C++ 注入入口（game-core）

`GameCore::injectOfflineGameMs(int64_t offlineGameMs) -> int64_t`（`game_core.h/cpp`），
编排序（同 `accrue` 的调用点纪律）：

1. **L1 连续积分**：`accrueContinuous(state, ecs, X, recoveryCarry_)`——HP/MP 恢复/修炼/
   熟练度/孕养，整数分子制对大 Δt 与分帧逐位等价（B4 轨）；
2. **三轴同步跳变**：PhaseClock 纳秒真相轴 `advanceGameMs`（新入口，`elapsedGameNs_ += X×1e6`，
   不动旬内累积器/墙钟基准）+ SettlementEngine 已积分轴 `advanceGameMs`（新入口，shadow
   对拍一致性）+ GameData 旬投影 `elapsedGameMs += X` 并以 `projectCalendar` **set 日历投影**
   （X 整旬 ⇒ 与逐旬 `advancePhase` 逐位等价）；`lastSettleGameMs = 新轴`（月结幂等基准推进，
   保持 `lastSettle ≤ elapsed` 不变量）；
3. **L3 月度连续积分**：`accrueMonthlyContinuous(state, X, monthlyCarry_)`——政策月费/道德/
   丹药衰减 + 灵矿毫秒差分（span = 新旬投影 − `spiritMineLastSettledGameMs` = X，全额结算，
   差分基准随函数内部推进到新轴）。

**语义边界（方案 §2.3 离线行）**：

- **判定轨 0 次 / 月年离散事件 0 次**——突破/丹药 roll/AI 行动/任务刷新/偷盗等需要玩家在场
  或消费 RNG 的判定不在离线期间发生（离线收益 = 资源连续积分）；RNG 全分区零消耗；
- **与 `realtimeAccrual` 灰度旗标正交**——注入直调积分函数族，不经 `accrue` 的窗口循环，
  旗标翻转（双臂切换）不影响离线语义；
- 防御：非正输入零副作用；非整旬输入 floor 到旬网格；未初始化返回当前轴值零副作用；
- 权限：`consumePendingMemoryTrim()` 与 accrue 同点消费（结算边界纪律）。

### 1.3 注入路径（两段式；线程契约表四已登记）

- **staging**（`GameEngineCoreOfflineOps.stageOfflineProgress`）：
  `BootSequenceController.bootCore` **Step 6.5**（Step 6.4 之后、Step 7 启动循环之前——
  状态已导入、循环未启动）调用。boot 是新档/读档/重启/云档的**统一入口**，以
  `lastSaveTime > 0` 分岔（新档 `createNewGame` 后 lastSaveTime=0 自然零注入）。
  折算结果写入 `GameEngineCore.pendingOfflineGameMs`（现实时长存 `pendingOfflineWallMs`）。
- **consume**（`GameEngineCoreOfflineOps.consumePendingOfflineProgress`）：
  `ensureAuthoritativeNative` **统一成功出口尾部**（初始化首帧/逐旬快路径/重读档后下一次
  ensure 全覆盖；引擎线程串行）。消费即清零（幂等——native 初始化重试不重复注入）。
  分流：native 就绪 → `GameCoreBridge.nativeInjectOfflineGameMs(X)` + `applyDirtyFromNative`
  镜像同步（失败全量兜底）+ `accruedElapsedGameMs` 差分基准重锚（防注入被当帧增量重复积分）；
  native 不可用 → 回退臂 `GameTimeClock.addOfflineGameMs`（单引擎终态下仅测试/降级触达）。
  **时序约束**：消费点在 `importToNative`（注入不被导入覆盖）与 `nativeLoopStart`
  （`PhaseClock.start()` 清轴不清掉注入推进）之后——由 ensure 内部调用序结构保证。
- **线程契约**：`docs/threading-contract.md` 表四新增「离线收益」行（**先登记再实现**：
  staging/consume 实际同在引擎线程，`@Volatile` 为安全发布兜底；`nativeInjectOfflineGameMs`
  带 `jniRequireEngineThread` 守卫；报告流 StateFlow 引擎线程发布 → UI 收集）。

### 1.4 注入端口（方案 §2.4 指定面）

- `GameTimeClock.addOfflineGameMs(gameMs)`（`GameTimeClock.kt`）——回退臂端口：折算后的
  离线游戏毫秒一次性加进当旬累积器，后续 `tick` 按追补上限消化（超限丢弃余量 = 既有
  "追补超限丢弃"语义延续）；AUTHORITATIVE 生产路径不消费本端口（由 OfflineOps 分流 native）。
- JNI：`nativeInjectOfflineGameMs(Long): Long`（`GameCoreBridge.kt` + `GameCoreBridge.cpp`）。
  **jni-count 87→88 豁免理由**：折算口径在 Kotlin（UI 复用 + 产品口径单一来源），引擎侧
  三轴推进/积分结算/日历投影**无 Kotlin 等价实现面**——离线注入是 C++ 权威语义的自然产物，
  不可在 Kotlin 侧复刻（否则违反 C++ AUTHORITATIVE 真相源架构）。

### 1.5 UI 回归提示（feature/game）

- 数据通路：`OfflineReturnReport(offlineWallMs)`（仅展示面，不含注入数值）→
  `GameEngineCore.offlineReturnReport: StateFlow`（引擎线程发布）→ `GameEngine` 转发 →
  `GameViewModel.offlineReturnReport` → 主界面收集。
- 面板：`StandardPromptDialog`（自带 60% 遮罩，符合 `rules/dialog-scrim-standard.md`）——
  标题「云游归来」；文案 = 离开时长（`formatOfflineDuration` 通俗格式化：片刻/分钟/
  小时/天，`OfflineReturnFormatter.kt`）+ "离开期间，宗门弟子仍在自行修炼与劳作" +
  上限规则通俗说明（"离开约半天后收益会逐渐减少，更长的离开不再累积额外收益"——
  **不泄数值细节**，与游戏内 changelog 同口径）；确认/点外关闭均 ack 清空（防重复弹出）。

### 1.6 口径同步面

- `GameData.lastSaveTime`（Kotlin）+ `models.h lastSaveTime`（C++）注释改写：
  「仅用于存档列表显示，不用于离线时间差计算（游戏无离线进度机制）」→「显示 + 离线收益
  时段计量起点（B7）」——B7 落地后注释与代码一致（原注释明确"不用于离线时间差"是 D1
  现状描述，方案 §3.3 行已标注该字段"已是毫秒，无需改造"）。
- 字段本体零改动（无 Migration、无 ProtoBuf 变更——存档 schema 不动，`@ProtoNumber(32)` 原值）。

## 二、验收标准对照（§10 B7 行）

| 验收标准 | 结果 |
|---|---|
| **离线边界 7 档断言绿** | ✅ `OfflineProgressPolicyTest` 10 例：0 / 短时段（1h、30min、亚旬 floor） / 12h 整 / 12–24h 线性段（13h、18h） / 24h 硬顶 / 超 24h（48h、72h、7 天） / 边界毫秒级 ±1（12h±1、24h±1）；附负数回拨、旬网格对齐不变量（0–48h 逐小时）、单调性 |
| **经济总量对拍在容差内** | ✅ `OfflineInjectionTest.InjectOnceEqualsFrameByFrameAccrual`：一次性注入 30 游戏月 vs 连续臂 200ms 帧粒度分帧 accrue 同总量——灵石总量（政策月扣+灵矿产出）**逐位一致**（整数分子制，优于 §5.2 的 1e-9 相对误差口径）；日历终态一致 |

## 三、门禁实测（原数字）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin` | BUILD SUCCESSFUL（1m 10s） |
| ctest 全量（llvm-mingw PATH） | **1473/1473 全绿**（基线 1465 + 本批新增 8；49.5s） |
| 六模块 JVM 全量（`--max-workers=1`，含 feature:game） | BUILD SUCCESSFUL（3m 30s） |
| engine Diff 门（`-Dgamecore.jni.path=` 指本 worktree 桥） | Diff 家族定向复跑 BUILD SUCCESSFUL（36s）；六模块全量带参亦含全部 Diff 用例 |
| `build-desktop-jni.ps1` | 成功（C++ 变更后重跑，`.so` 重建——Diff 门依赖新鲜） |
| `lintRelease` + `detekt` | 六模块 0 错误（app 36 警告/core:data 5/core:domain 1/core:ui 1/feature:game 52 均为存量基线警告，本批零新增）；detekt 六模块 0 违规（第四轮终验 BUILD SUCCESSFUL 4m 38s；途中修复 4 项自引违规，见 §5） |
| `check-jni-count.mjs` | **88/88**（基线同步 +1，豁免理由见 §1.4 与 `jni-count.baseline.json` _doc） |
| `check-agent-instructions.mjs` | 全绿（路由闭包 42 篇/444 引用/7 AGENTS.md 完整） |

**门禁途中假红归因（诚实登记）**：

1. 首轮六模块全量 BUILD FAILED——`DiffBridgeGateTest` IN8 出厂门红：未带
   `-Dgamecore.jni.path`。**归因 = 跑法问题非代码问题**：IN8 守卫要求全量跑也必须带参
   （防 45 个 Diff*Test 静默 skip）。补正后带参全量绿。**建议看护将派发件门禁命令第 2 条
   补写该参数**（§9 留言区已同步）。
2. 首轮 `lintRelease detekt` BUILD FAILED——`:core:domain:detekt` `MayBeConst`（本批新增
   测试文件 companion `val H` 未 const）。修复为 `const val` 后重跑，暴露 core:engine 侧
   另 3 项本批自引违规（同轮次顺藤修完，共 4 项）：`TooGenericExceptionCaught`
   （OfflineOps consume 的降级契约 catch Exception——补 `@Suppress` 与设计注释，沿
   `GameEngineCoreAuthoritativeOps` 降级契约先例）、`InvalidPackageDeclaration`
   （`GameTimeClockOfflineInjectTest` 误放 `engine/` 根目录而包声明为 `engine.system`
   ——移动到 `engine/system/`）、`MaxLineLength`（接线测试 1 行超 120——折行重写）。
   第三轮全量绿（详见 §5 第 1 条）。

## 四、测试清单（本批新增 28 例）

| 文件 | 例数 | 覆盖 |
|---|---|---|
| `gamecore/test/offline_injection_test.cpp`（C++，已注册 CMakeLists） | 8 | 三轴增量一致/日历 set≡逐旬 advancePhase/注入≡分帧积分对拍/零 RNG（突破 1·系统 3·AI 镜像 9 三分区）/灵矿差分闭式/政策月扣闭式/边界 0·负·不足一旬·floor/未初始化零副作用 |
| `core/domain/.../OfflineProgressPolicyTest.kt` | 10 | 折算 7 档 + 负数 + 旬网格不变量 + 单调性 |
| `core/engine/.../system/GameTimeClockOfflineInjectTest.kt` | 4 | 回退臂端口消化/超上限丢弃/零副作用/暂停恢复 |
| `core/engine/.../GameEngineCoreOfflineOpsTest.kt` | 6 | staging 折算与硬顶/新档零注入/consume 回退臂/幂等/报告发布/ack |

## 五、途中发现与处理

1. **detekt 自引违规 4 项（三轮修复）**：① `MayBeConst`（OfflineProgressPolicyTest companion
   `val H` → `const val`）；② `TooGenericExceptionCaught`（OfflineOps consume 降级契约
   catch——补 `@Suppress` 与设计注释，沿 GameEngineCoreAuthoritativeOps 先例）；
   ③ `InvalidPackageDeclaration`（GameTimeClockOfflineInjectTest 文件误放 `engine/` 根——
   移至 `engine/system/`）；④ `MaxLineLength`（接线测试超长行折行）。第三轮 detekt 全量绿。
2. **C++ 测试首版两处断言错误（自引，ctest 首轮 2/8 红）**：① `ThreeAxesAndCalendarJumpTogether`
   用 `advancePhases(5)` 建非零起点后断言三轴**绝对值**相等——`advancePhases` 只推进 GameData
   旬投影轴、不推进 PhaseClock/SettlementEngine 会话轴（会话轴由墙钟帧驱动），前置偏移后绝对值
   必然不等；修正为断言「注入**增量**三轴一致」。② `SpiritMineDifferentialClosedForm` fixture
   漏配执事（deacon）导致月产率 204 而非 224（乘区构成差）；补齐执事 fixture。两处修正后
   ctest 8/8 绿、全量 1473/1473。**该过程暴露的三轴关系已写入 §1.2 与测试注释**（会话轴 vs
   全局旬投影轴存在 <1 旬粒度偏差，属既有架构事实，注入保持增量一致性不破坏）。
3. **IN8 门禁跑法补正建议**（非本批代码问题）：见 §3 假红归因 1，留言区同步看护。
4. **预存事实（非本批引入，登记不修）**：`SettlementEngine.settlement_.elapsedGameMs_` 在离散臂
   生产路径恒 0（离散臂旬推进不经 `advanceByGameMs`/`foldAndWindows`）——本批注入向其加 X 后
   与离散臂"未积分"基态存在语义差；因注入直调积分函数族且与 `realtimeAccrual` 正交、消费面仅
   shadow/对拍与连续臂 `foldAndWindows` 差分（`accruedElapsedGameMs` 已重锚），不产生行为影响。
   若未来离散臂也消费该轴，需在双臂切换点一并重锚（B6 已有"切换点强制重算"档口）。

## 六、真机 pending-device（不阻塞本批验收）

- **D8（方案 §9.1 第 12 条）**：`START_STICKY` 后台重建是否无条件重启循环（与"后台纯暂停"
  口径冲突，离线计时起点可靠性依赖该口径）——`GameForegroundService.kt:144,113-121`；
  待真机验证批统一登记（B7 门禁不含真机项，按派发件口径）。

## 七、边界决定遵守情况

- **连续收割（收获判定挪出月界）与年俸连续化**：未实施、未顺手改动（派发件补充要点明确
  "无批次承载、方案未采纳"）✓
- 离线口径 12h/50%/24h 按已拍板值实现，未改口 ✓
- 本批未动 `NativeEngineFlag.realtimeAccrual` 默认值（仍 false，双臂行为不变）✓
- 存档 schema 零变更（无 Entity/ProtoBuf/Migration 改动）✓
- 单笔收官提交；版本号不自增 ✓
