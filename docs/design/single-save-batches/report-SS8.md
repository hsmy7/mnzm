# report-SS8 · 登录门槛 + 隐私政策双入口

> 批次真源：[`TASKBOOK-SS8.md`](TASKBOOK-SS8.md)；协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4/§7 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 分支 `feat/single-save-SS8`（基线 = main `617179adf`，含 SS0–SS7 并网结果）。

## 1. 做了什么

### ① 登录门槛状态机化（验收①，切片 SS8-a）

| 文件 | 变更 |
|---|---|
| `login/LoginFlowState.kt` | `Idle` 更名 **`RequireLogin`**（显式门槛态：未登录不可进游戏，唯一不携带会话的状态，唯一出口 = `LoginRequested`）；`ColdStart` 事件扩为三参 `(loggedIn, complianceVerified, unionId)`——把 MainActivity 侧的 `if (isLoggedIn)` 判定收编进状态机，门槛判定面单点化；"四件套"注释过时修正为五件套 |
| `login/LoginFlowStateMachine.kt` | 全局状态更名迁移；`onColdStart` 判定面重写为四分支（详见下表）；`clearToIdle` → `clearToLoginGate`（登出五件套 + 回门槛态的统一收敛点）；类 KDoc 补门槛/B1/会话残缺语义。零一次性布尔——门槛语义全部由状态承载（`rules/sdk-init-lifecycle.md` 原则 5 合规） |
| `ui/MainActivity.kt` | 三处接线（见下） |

**ColdStart 判定面（状态机内单点，四分支互斥）：**

| loggedIn | complianceVerified | unionId | 去向 | 语义 |
|---|---|---|---|---|
| true | true | 非空 | `Verified` + EnterGame | **B1 离线宽限①**：已登录过（unionId 缓存即凭证）离线照常进入 |
| true | 任意 | **空** | `RequireLogin` + 五件套 | 会话残缺（有登录标记缺账号键）：清会话回门槛态 |
| true | false | 非空 | `VerificationFailed` + 实名认证界面 | 已登录未验证：手动重试 |
| **false** | 任意 | 任意 | `RequireLogin` + 仅 ShowLoginScreen | **B1 离线宽限②**：从未登录停留登录页（无会话可清，不触发登出五件套） |

**MainActivity 三处接线：**

1. `onLoadingComplete`：冷启动路由统一经状态机——已登录分支保留 `safeRunAfterSdkInit` + `awaitTapTapSdkReady` 前置后发 `ColdStart`；**未登录分支不再直调 `showMainScreen()`**，同样发 `ColdStart(loggedIn=false, …)` 由状态机出登录页（门槛判定单点化）。
2. `enterGameAuto` 无 unionId 兜底：原直调 `showMainScreen()`（**状态机停在 `Verified` 与 UI 脱节——此后用户再登录时 `LoginRequested`/`LoginSuccess` 全被状态机按不匹配事件忽略，登录成功也进不了游戏的死锁缺陷**），改为经状态机 `LogoutRequested` 清残缺会话回门槛态。这是本批的**根因修复**：SS2 防御兜底只改了 UI 没改状态机，本批把该路径收敛为状态机事件。
3. `initTapTapLoginSdk` 超时降级分支：删除直调 `enterGameAuto()`（SS2 前的旧降级语义——未登录自动进游戏，违反 Q5；且与 onLoadingComplete 的 ColdStart 链并发双跑存在双启动窗口）。超时后仅释放守卫 + `tapTapReady=false`；已登录玩家由 ColdStart 链照常路由（离线宽限①不受影响），未登录玩家停留登录页（登录按钮按 `tapTapReady` 拦截提示）。

**禁一次性布尔复核**：本批零新增布尔标记；门槛/残缺/宽限全部以状态与事件参数表达，状态机自身可复位（登出即回 `RequireLogin`）。

### ② B1 离线宽限（验收②，切片 SS8-b）

- 判定面 = `SessionManager` 既有会话缓存（`isLoggedIn` / `complianceVerified` / `unionId`）**只读消费，零新增持久化键**（D-2 合规）。
- 两分支均有状态机测试锁定（见 §3）。
- 离线继续时云上传**零特殊处理**（D-3）：`UploadQueue` 退避/熔断既有语义天然吸收离线（`NETWORK` 属指数退避重试族 `RETRYABLE_ERRORS`）；补一条 `NETWORK` 失败形状的队列测试（既有 Q3=令牌失效/Q4=并发未直接覆盖网络不可达）——**生产代码零改动**。

### ③ 隐私政策双入口同步（验收③，切片 SS8-c）

`PrivacyConsentScreen.kt` 与 `docs/index.html` 同步更新（更新日期 2026-08-13 → **2026-10-02**），更新点 = 单存档改造相关条款（D-4：以当前行为为准，通俗无术语）：

| 条款 | 更新内容 |
|---|---|
| 1.1 账户信息 | 补"全部功能需要登录后使用；存档按账号独立隔离存放，云端备份按账号归属" |
| 三、信息使用目的 | 新增"云存档备份"条目（加密上传一份备份，用于恢复进度和换设备继续游戏） |
| 四、信息存储与保护 | 修正过时表述"仅保存在您的设备本地"（SS7 云灾备后已不准确）→ 按账号隔离 + 云端一份加密备份；存储位置补"云端备份存储在境内的 TapTap 云服务" |
| 五、信息共享 | 新增 TapTap 云存档条目（存档数据经 TapTap 云存储备份） |
| 六、数据保留期限 | 本地档/云端备份/版本升级旧档（**删档重置告知**：格式不再兼容时更新说明提前告知 + 升级时清除含云端旧备份）分行明示 |
| 七、您的权利 | 删除途径按现状改写：游戏内"重新开始"（新档覆盖，云端备份随之更新）、退出登录（清本机登录信息不清云端）、卸载（清全部本地，云端可重装恢复） |
| 九、隐私政策更新 | 重大变更清单补"数据保存方式发生重大变化（如新增云存档备份、存档按账号隔离保存）" |

文案事实基线（已核实）：玩家可达的删除途径 = 游戏内重新开始（`SaveLoadViewModelRestartOps`：预存→重置引擎→落新档）；`SaveWipeCoordinator` 删档重置为 Debug 构建专属开发入口（W12，玩家不可达）；云端备份登出/卸载均不清除（重装同账号可恢复）——文案与行为逐条对应，无营销性承诺。

### ④ 文档同步

- `docs/login-flow-state-machine.md`（状态转移表唯一真相源）：顶部补 2026-10-02 SS8 更新注记；状态/事件定义、转移表（ColdStart 四分支 + RequireLogin 全行）、关键机制（门槛/B1/会话残缺三条）、测试用例表同步为现状；历史章节（背景/影响范围/实施步骤）保留原文（沿用 2026-10-01 主界面退役批先例）。
- `rules/sdk-init-lifecycle.md`：派工册附带修正项（`:50` 已退役行）复核确认**已由 SS2 消费**（现指向 `GameActivity.onLogout`，无需重复修）；本批另行修正两处主界面退役后未跟新的过时表述（原则 5 与真机冒烟清单的"进模式选择"→"自动进入游戏"），原则 5 补"界面切换不得绕过状态机"契约，冒烟清单补 B1 两分支真机项。

## 2. 验证（门禁实跑）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin` | BUILD SUCCESSFUL |
| JVM 三模块（:app / :core:data / :feature:game，`--max-workers=1` + `--rerun-tasks` + 主树桌面 JNI 桥） | BUILD SUCCESSFUL，**2692 tests / 0 failures**（app 1021 + core:data 703 + feature:game 968），195 tasks 全执行 |
| 五守卫定向（LoginFlowStateMachineTest / SafeRunAfterSdkInitTest / ComplianceManagerSelfHealTest / SdkInitGuardTest / TapDBManagerInitGuardTest） | `--tests` 定向 `--rerun-tasks` 复跑 BUILD SUCCESSFUL |
| `:app:detekt` `:feature:game:detekt` | BUILD SUCCESSFUL（途中 2 处新违规——六节文本行超长 + 九节合并后 `FullPolicySharingAndRightsSections` 达 LongMethod 阈值——均**实修**换行/合并段落，未入 baseline） |
| `node scripts/check-agent-instructions.mjs` | EXIT=0（485 条引用全绿，规则①–⑤全过） |
| C++（ctest / JNI 重建） | 不适用——本批零 C++ 改动（任务书"不做"项） |

## 3. B1「已登录过」边界态定义（任务书登记项 2）

**判定面**：`SessionManager.unionId` 非空 = 已登录过。三键一致性由 `SessionManager` 写入侧保证（`saveLoginSession` / `saveComplianceVerified` / `clearSession` 均为同一 `SharedPreferences.Editor` 原子批写）。

**wipe 后半态**（数据空间在而 unionId 缓存被清）：发生场景 = 加密存储降级重建（Bugly #3107 型：AndroidKeyStore 主密钥损坏 → `SessionManager` prefs 重建，unionId 与 isLoggedIn 同时丢失，而 `filesDir/accounts/` 下数据空间目录仍在磁盘）。

**行为定义：按未登录处理**——`ColdStart(loggedIn=false, …)` → `RequireLogin` 停留登录页；同账号重新登录后 `AccountSpaceManager.activate(unionId)` 按 `AccountKey.derive(unionId)` 幂等重新激活同一空间（`activate` 为 `mkdirs()` + 写 `.current`，目录已存在时无副作用），进度原样恢复。测试锁定：`门槛 - 会话残缺即使合规已验证也不进游戏（wipe 后半态）`——**即使 `complianceVerified=true` 残留，unionId 为空也绝不 EnterGame**。

**变体**（isLoggedIn=true 而 unionId 空，如写入中断的非原子残留——理论上被原子批写排除，防御性覆盖）：`ColdStart` 分支二清会话（登出五件套）回门槛态，与既有用例 `冷启动缺 unionId - 清会话回登录界面` 同型。

## 4. 三条启动路径回归证据（任务书登记项 3）

| 路径 | 链路 | 回归证据 |
|---|---|---|
| **冷启动（从未登录）** | 隐私同意 → 加载页 → `ColdStart(loggedIn=false)` → `RequireLogin` + 登录页 | 新增用例 `门槛 - 冷启动未登录停留登录页且不触发登出（B1 离线宽限②）`：断言 effects 仅 `showLogin`，无 `clearLogout`/`enterGame`；另有 `门槛 - 冷启动未登录时合规与unionId参数不改变门槛判定` 锁定 loggedIn 为先决条件 |
| **杀进程重进（已登录已验证）** | 加载页 → SDK 兜底初始化 → `awaitTapTapSdkReady` → `ColdStart(loggedIn=true, true, u)` → `Verified` → `enterGameAuto` | 既有用例 `冷启动已认证 - 自动进入游戏`（补 B1① 注释）：断言 effects 仅 `enterGame`——**零网络/SDK 事件参与**，离线照常；既有用例 `冷启动未认证 - 显示实名认证界面` 覆盖重进未验证路径 |
| **登出重启** | 游戏内/合规弹窗/实名认证界面/防沉迷退出 → `LogoutRequested` → `clearToLoginGate`（五件套）→ 进程重启 → `ColdStart(loggedIn=false)` → 登录页 | 既有用例 `登出 - 各状态 LogoutRequested 统一清会话回登录界面`（三状态段）全绿；新增 `门槛不可绕过 - 已进入游戏后回归门槛态则再次进入必须重新走完整登录链` 锁定 Verified→RequireLogin 后无法凭旧验证状态放行 |

`LoginFlowStateMachineTest` 既有 13 用例全部保留（仅状态名与 ColdStart 构造签名机械迁移，断言语义零放宽）+ 新增 6 用例（门槛/B1/会话残缺），JVM `:app` 模块 1021 tests / 0 failures 实跑佐证。

## 5. 隐私双入口一致性核对表（SS8-c 自检项）

| 核对项 | PrivacyConsentScreen.kt | docs/index.html | 一致 |
|---|---|---|---|
| 更新/生效日期 | 2026年10月2日 | 2026年10月2日 | ✅ |
| 1.1 登录门槛 + 账号隔离 + 云备份归属括注 | 摘要卡 1.1 + 完整 1.1 | 1.1 表格用途列 | ✅（完整版详述、摘要卡精简，要点一致） |
| 三、云存档备份条目 | 摘要卡三节 + 完整三节 | 三节列表 | ✅ |
| 四、账号隔离 + 云备份 + 境内 TapTap 云 | 摘要卡四节 + 完整四节 | 4.1 + 4.4 | ✅ |
| 五、TapTap 云存档共享条目 | 完整五节 | 五节 | ✅（摘要卡无五节，既有结构） |
| 六、本地/云端/版本升级删档告知分行 | 完整六节 | 六节表格 | ✅ |
| 七、删除途径三分句 | 完整七节 | 七节 | ✅ |
| 九、重大变更补数据保存方式 | 完整九节 | 九节 | ✅ |
| 外链（TapTap/MMKV/五广告/友盟/政策页 URL） | 未动 | 未动 | ✅ |

## 6. 未完成 / 登记

1. **真机 pending-device**（任务书 §5）：B1 两分支（飞行模式杀进程重进/首次安装飞行模式）+ 首次登录强制联网——已写入 `rules/sdk-init-lifecycle.md` 真机冒烟清单，需真机验证。
2. **SS10 登记**：玩家可见变更（强制登录 + 隐私政策更新）并入 4.2.00 唯一条目；隐私政策更新如需渠道侧合规审核，登记给发布流程。
3. **途中发现（已随批修复）**：`enterGameAuto` 会话残缺兜底的状态机死锁缺陷（详见 §1① 接线 2）与 `initTapTapLoginSdk` 超时直调的绕过状态机 + 双启动窗口（接线 3）——均属 SS2 基座的遗留防御兜底，本批根因修复。
4. **行为变化说明（供验收知悉）**：会话残缺兜底路径由"仅切登录界面（状态机卡 Verified）"变为"清残缺会话 + 进程重启回登录页"——与既有 `ColdStart` 缺 unionId 路径行为对齐（该路径本就走登出五件套 + 重启），残缺会话必须重新登录，语义一致化。

## 7. 风险

| # | 风险 | 定性 |
|---|---|---|
| 1 | `initTapTapLoginSdk` 超时直调删除后，已登录玩家进游戏需等 `awaitTapTapSdkReady`（最多 5s）——**该等待在删除前就存在于 onLoadingComplete 主链**（超时分支与主链并发），删除仅消除重复进入，不新增等待 | 已核实（代码链路追踪） |
| 2 | 未登录玩家离线时点登录：TapTap SDK 未就绪被登录按钮 `tapTapReady` 拦截 Toast 提示，停留登录页——符合 B1②，体验为"无法登录"而非"明确告知需联网"（Toast 文案沿用既有"正在初始化"） | 已核实；文案优化空间登记 SS10 |
| 3 | 会话残缺（isLoggedIn=true 且 unionId 空）实际可达性：三键原子批写下理论不可达，仅加密存储降级（此时 isLoggedIn 同丢，落入 loggedIn=false 分支）——分支二为纵深防御 | 已核实（SessionManager 写入面逐方法核对） |
| 4 | 隐私政策"版本升级清旧档"条款的触发时点（4.2.00 升级）早于政策生效时点（同版本）——条款描述的是既有事实（SS0 已实施删档重置），非新增承诺 | 已核实（SS0 交付范围） |
| 5 | `enterGameAuto` 兜底改经 `LogoutRequested` 后，该路径会触发 `restartToLoginScreen` 进程重启——残缺会话为罕见边界（风险 3），玩家感知为"重新登录"，可接受 | 推测（真机待验证，已列入 pending-device 冒烟面） |
