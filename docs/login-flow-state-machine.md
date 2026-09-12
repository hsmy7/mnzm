# 登录/防沉迷验证流程状态机重构方案

> 2026-08 制定。目标：根除 4.00.98 以来反复出现的"登录后卡在登录界面 / 弹出实名认证界面"问题。
> 本方案为最终态，执行者照单实施，不留"后续优化"尾巴。

## 一、背景与目标

### 需求要点

4.00.98 修复"广告 SDK 重复初始化"后，部分玩家反馈：**登录游戏后有概率卡在登录界面进不去，或弹出实名认证界面**。
此后 4 轮修复（初始化时机收敛 `8844f31b` / 登出清会话 `585d4e23` / 初始化解耦 `383f3a4c` / RESUMED 延迟启动 + 进程内恢复 `57919e8a`）均未根除。

上一轮排查已定位 4 个相互叠加的结构性根因，全部集中在"登录成功 → 防沉迷验证 → 进模式选择"这段流程的状态管理上：

| 根因 | 本质 | 引入版本 | 后果 |
|------|------|---------|------|
| A | 合规回调注册与 TapTap 登录 SDK 初始化时序竞态（冷启动路径注册早于 SDK 就绪，失败无重试） | 4.00.98 | SDK 回调监听器永久未注册 → 验证回调丢失 → 卡死/循环弹实名认证 |
| B | `complianceCheckDeferredStarted` 一次性标记永不复位 | 4.01.02 | "退出认证/切换账号后再登录"防沉迷验证被永久跳过，无任何提示 |
| C | `startComplianceCheck` 无 SDK 就绪前置检查 | 4.00.98 加剧 | 冷启动点"开始认证"时 `TapTapKit.context` 未就绪 → startup 静默失败 |
| D | 恢复流程依赖反射复位 SDK `isRunning`，只手动重试 | 4.01.02 | 反射失败则永久卡实名认证界面 |

**共同病灶**：登录/防沉迷验证的状态（验证是否在飞、是否已触发、回调是否注册、SDK 是否就绪）分散在 MainActivity 的多个独立布尔字段/一次性标记中，生命周期互不约束，互相踩踏；回调注册、SDK 就绪、验证启动三者的时序没有统一契约。

### 成功标准

1. ✅ 根因 A/B/C/D 全部消除——不再存在"卡登录界面 / 弹实名认证界面进不去"路径
2. ✅ 登录流程状态收敛为单一状态机（纯 Kotlin、零 Android 依赖、全转移矩阵单测覆盖）
3. ✅ 回调注册与验证启动原子绑定（startup 前自愈补注册）
4. ✅ SDK 调用前必须就绪（发送方保证 + 防御检查双保险）
5. ✅ 登出统一四件套收敛为单一实现（消除 4 处登出不一致）
6. ✅ 途中发现的 4 个预存问题一并解决
7. ✅ 既有测试全绿（SafeRunAfterSdkInitTest / SdkInitGuardTest / TapDBManagerInitGuardTest / ComplianceCallbackHostTest）

## 二、技术方案

### 架构变化总览

**新组件**：`LoginFlowStateMachine`（app 模块 `com.xianxia.sect.login` 包）——纯 Kotlin 状态机，
State/Event/SideEffect 全部 sealed class，宿主（MainActivity）经 `LoginFlowHost` 接口执行副作用。
模式完全对齐项目既有 ADR（docs/adr/lifecycle-state-machine-refactor.md）与行业标杆 Tinder StateMachine DSL。

**删除**：MainActivity 中 `complianceCheckInFlight` / `complianceCheckDeferredStarted` / `complianceTimeoutJob`
三个手工状态字段，以及 `startComplianceCheckWhenResumed` / `startComplianceCheck` / `scheduleComplianceTimeoutHint` /
`recoverComplianceStuckState` / `handleUserExit` 五个方法（职责全部并入状态机）。

### 关键类/接口

```kotlin
// ====== 1. 状态（六态） ======
sealed interface LoginFlowState {
    data object Idle : LoginFlowState              // 未登录：显示登录界面
    data object LoggingIn : LoginFlowState         // TapTap 授权页展示中
    data object VerifyPending : LoginFlowState     // 登录成功：等 Activity 稳定 RESUMED
    data object Verifying : LoginFlowState         // 防沉迷验证已启动：等 SDK 回调
    data object Verified : LoginFlowState          // 验证成功：进模式选择
    data object VerificationFailed : LoginFlowState // 验证失败/超时：实名认证界面可重试
}

// ====== 2. 事件（12 种） ======
sealed interface LoginFlowEvent {
    data object LoginRequested : LoginFlowEvent
    data class LoginSuccess(val unionId: String) : LoginFlowEvent
    data class LoginFailure(val message: String) : LoginFlowEvent
    data object LoginTimeout : LoginFlowEvent
    data object ActivityResumed : LoginFlowEvent        // repeatOnLifecycle(RESUMED) 每次进入触发
    data object VerificationSuccess : LoginFlowEvent    // CODE_LOGIN_SUCCESS
    data object VerificationExited : LoginFlowEvent     // CODE_EXITED / 9002 / 1001
    data object VerificationNetworkError : LoginFlowEvent
    data object VerificationTimeout : LoginFlowEvent    // 30s 无回调
    data object RetryVerification : LoginFlowEvent      // 实名认证界面点"开始认证"（发送方先保证 SDK 就绪）
    data object LogoutRequested : LoginFlowEvent
    data class ColdStart(val complianceVerified: Boolean, val unionId: String?) : LoginFlowEvent
}

// ====== 3. 副作用（11 种） ======
sealed interface LoginFlowSideEffect {
    data class StartComplianceVerification(val unionId: String) : LoginFlowSideEffect
    data class ShowComplianceVerificationScreen(val unionId: String) : LoginFlowSideEffect
    data object ShowModeSelection : LoginFlowSideEffect
    data object ShowLoginScreen : LoginFlowSideEffect
    data object ClearSessionAndLogout : LoginFlowSideEffect   // 登出统一四件套唯一实现
    data class ShowToast(val message: String) : LoginFlowSideEffect   // 网络异常/验证超时/登录超时提示
    data object RecoverSdkRunningState : LoginFlowSideEffect // exit() + 反射复位 isRunning
    data object ScheduleVerificationTimeout : LoginFlowSideEffect
    data object CancelVerificationTimeout : LoginFlowSideEffect
    data object ScheduleLoginTimeout : LoginFlowSideEffect
    data object CancelLoginTimeout : LoginFlowSideEffect
}

// ====== 4. 状态机 ======
class LoginFlowStateMachine(private val host: LoginFlowHost) {
    interface LoginFlowHost { /* 12 个副作用方法 + onLog */ }
    var state: LoginFlowState = Idle; private set
    fun onEvent(event: LoginFlowEvent)   // 状态转移 + 副作用回调，全同步主线程
}
```

### 状态转移表（唯一真相源）

| 当前状态 | 事件 | 新状态 | 副作用 |
|---------|------|--------|--------|
| Idle | LoginRequested | LoggingIn | ScheduleLoginTimeout |
| Idle | ColdStart(true, _) | Verified | ShowModeSelection |
| Idle | ColdStart(false, null) | Idle | ClearSessionAndLogout, ShowLoginScreen |
| Idle | ColdStart(false, u) | VerificationFailed | ShowComplianceVerificationScreen(u) |
| LoggingIn | LoginSuccess(u) | VerifyPending | CancelLoginTimeout（等 RESUMED，resumedReady 已置位则立即转移） |
| LoggingIn | LoginFailure(m) | Idle | CancelLoginTimeout |
| LoggingIn | LoginTimeout | Idle | CancelLoginTimeout |
| VerifyPending | ActivityResumed | Verifying | StartComplianceVerification(u), ScheduleVerificationTimeout |
| VerifyPending | LogoutRequested | Idle | ClearSessionAndLogout, ShowLoginScreen |
| Verifying | VerificationSuccess | Verified | CancelVerificationTimeout, ShowModeSelection |
| Verifying | VerificationExited | Idle | CancelVerificationTimeout, ClearSessionAndLogout, ShowLoginScreen |
| Verifying | VerificationNetworkError | VerificationFailed | CancelVerificationTimeout, ShowToast(网络异常), ShowComplianceVerificationScreen(u) |
| Verifying | VerificationTimeout | VerificationFailed | CancelVerificationTimeout, RecoverSdkRunningState, ShowToast(无响应), ShowComplianceVerificationScreen(u) |
| Verifying | LogoutRequested | Idle | CancelVerificationTimeout, ClearSessionAndLogout, ShowLoginScreen |
| VerificationFailed | RetryVerification | VerifyPending | 等待 ActivityResumed（resumedReady 已置位则立即转移） |
| VerificationFailed | LogoutRequested | Idle | ClearSessionAndLogout, ShowLoginScreen |
| Verified | LogoutRequested | Idle | ClearSessionAndLogout, ShowLoginScreen |
| 其他 | 不匹配事件 | 不变 | 记日志（幂等防重入） |

**关键机制**：
- **resumedReady 标志**：`ActivityResumed` 事件（任意状态）置位；进入 VerifyPending 时若已置位立即启动验证。
  登录成功路径：等 repeatOnLifecycle 回调；重试路径：用户在实名认证界面（Activity 已 RESUMED，标志早已置位）立即启动。
  **替代原 `complianceCheckDeferredStarted` 且可复位**（每次会话重置）——根因 B 根治。
- **单飞语义**：状态机只在 VerifyPending→Verifying 转移时启动一次验证，重复事件 no-op——替代原 `complianceCheckInFlight`，无"永不复位"缺陷。
- **SDK 就绪契约**：LoginSuccess（登录前置 isReady）/ ColdStart（onLoadingComplete 已 await）/ RetryVerification（按钮回调先 await）
  三个事件发送方均保证 SDK 就绪；`StartComplianceVerification` 副作用处理时再做防御性 `isReady()` 检查。
- **回调注册原子绑定**：`StartComplianceVerification` 副作用处理 = `ComplianceManager.ensureCallbackRegistered(host.callback)`（自愈补注册）+ `startup`——根因 A 根治。

### 数据流

```
事件发送方（MainActivity / 回调）                     状态机                     副作用执行（LoginFlowHost）
─────────────────────────────────────────────────────────────────────────────────────────────
EnterGameButton 点击 ──LoginRequested──▶ ┌──────────┐ ──ScheduleLoginTimeout──▶ 60s 登录超时 job
TapTap onSuccess ──LoginSuccess(u)─────▶ │          │ ──CancelLoginTimeout───▶ 取消
repeatOnLifecycle ──ActivityResumed────▶ │ LoginFlow │ ──StartComplianceVerification(u)──▶ ensureCallbackRegistered + startup
ComplianceCallback ──VerificationSuccess▶ │  State   │ ──ScheduleVerificationTimeout──▶ 30s 超时 job
超时 job ──VerificationTimeout─────────▶ │  Machine  │ ──RecoverSdkRunningState──▶ exit() + 反射复位 isRunning
"开始认证"按钮 ──RetryVerification──────▶ │          │ ──ShowComplianceVerificationScreen(u)──▶ setContent 实名认证界面
登出入口 ──LogoutRequested─────────────▶ │          │ ──ClearSessionAndLogout──▶ 清会话+logout+停统计+解绑回调+ShowLoginScreen
冷启动 ──ColdStart(v,u)────────────────▶ └──────────┘ ──ShowModeSelection──▶ setContent 模式选择
```

### MainActivity 侧改造点

1. `onLoadingComplete` 冷启动分支：`initTapTapLoginSdk()` 后 `awaitTapTapSdkReady()`，再发 `ColdStart` 事件
2. `EnterGameButton.onSuccess`：全部 UI 操作包 `runOnUiThread`（线程加固），发 `LoginSuccess` 事件
3. `repeatOnLifecycle(RESUMED)`：每帧进入 RESUMED 发 `ActivityResumed` 事件
4. `complianceWindowPort` 回调：转发为 VerificationSuccess / VerificationExited / VerificationNetworkError 事件
5. 登出入口统一：模式选择 / 合规弹窗 / 实名认证界面 / 防沉迷退出 → 全部发 `LogoutRequested`（不再各写四件套）
6. 副作用处理器实现 `LoginFlowHost`（12 方法）

### ComplianceManager 重构

```kotlin
object ComplianceManager {
    // 测试注入点（internal，JVM 测试可替换，遵循"9.4 优先 Fake"）
    internal var sdkCallbackRegistrar: (TapTapComplianceCallback) -> Unit = { listener ->
        TapTapCompliance.registerComplianceCallback(listener)
    }

    /** 幂等自愈注册：callback 总更新；SDK listener 仅在未注册时（重）注册 */
    fun ensureCallbackRegistered(callback: ComplianceCallback) {
        this.callback = callback
        if (!isCallbackRegistered) {
            try {
                sdkCallbackRegistrar(TapTapComplianceListener())
                isCallbackRegistered = true
                Log.d(TAG, "合规认证回调已注册")
            } catch (e: Exception) {
                Log.e(TAG, "注册合规认证回调失败（下次 startup 前自愈重试）: ${e.message}", e)
            }
        }
    }
    // registerCallback 保留为 ensureCallbackRegistered 的别名（兼容既有调用）
}
```

`resetSdkRunningState` 反射字段名尝试多候选（`isRunning` / `mIsRunning` / `running`），失败日志带字段名与异常。

## 三、影响范围清单

| 文件 | 变更类型 | 变更说明 |
|------|---------|---------|
| `android/app/src/main/java/com/xianxia/sect/login/LoginFlowState.kt` | ✨ 新增 | 状态/事件/副作用 sealed class |
| `android/app/src/main/java/com/xianxia/sect/login/LoginFlowStateMachine.kt` | ✨ 新增 | 纯状态机（零 Android 依赖） |
| `android/app/src/main/java/com/xianxia/sect/taptap/ComplianceManager.kt` | ✏️ 重构 | ensureCallbackRegistered 自愈 + 可注入 registrar + resetSdkRunningState 多候选字段 |
| `android/app/src/main/java/com/xianxia/sect/ui/MainActivity.kt` | ✏️ 重构 | 接入状态机；删除手工状态字段与 5 个方法；登出统一四件套；onSuccess 线程加固；移除 MainScreen.onLoginSuccess 死参数 |
| `android/app/src/test/java/com/xianxia/sect/login/LoginFlowStateMachineTest.kt` | ✨ 新增 | 全转移矩阵（含根因 B 回归守卫） |
| `android/app/src/test/java/com/xianxia/sect/taptap/ComplianceManagerSelfHealTest.kt` | ✨ 新增 | ensureCallbackRegistered 幂等/失败重试 |
| `rules/sdk-init-lifecycle.md` | ✏️ 修改 | 检查清单补"SDK 调用前必须就绪"与"一次性标记必须可复位/收敛状态机" |
| `CHANGELOG.md` + `changelog_entries.json` | ✏️ 修改 | 双 changelog 追加 |
| `docs/login-flow-state-machine.md` | ✏️ 修改 | 本方案文档落库 |
| `task_plan.md` / `findings.md` / `progress.md` | 🗑️ 清理 | 一次性工作文件，任务完成后删除 |

**经济影响**：无货币/奖励发放变更。
**iOS 影响**：状态机纯 Kotlin（sealed class + 同步回调），iOS 侧 KMP 可复用同一状态机定义（对照 lifecycle-state-machine-refactor.md 的跨平台结论）。

## 四、兼容性分析

- **Migration**：无 Entity/ProtoBuf/Room 变更（DATABASE_VERSION 不变）
- **序列化**：无变更（登录态/合规标记仍在 SessionManager EncryptedSharedPreferences，字段不变）
- **存档**：无变更
- **行为变化**（失败路径更健壮，成功路径逐位一致）：
  - 登出不再依赖 `recreate()` 重置状态（状态机自身复位），模式选择界面 onLogout 改为 `showMainScreen()`——UI 表现等价（重新 setContent）
  - 网络错误不再回登录界面（保留会话，实名认证界面可重试）
  - 登录超时（60s 无回调）自动回登录界面并提示（原为永久转圈）

## 五、测试方案

### 单元测试（JVM，纯逻辑）

`LoginFlowStateMachineTest`（FakeHost 记录副作用调用序列）：

| 用例 | 验证点 |
|------|--------|
| 正常登录路径 | LoginRequested→LoginSuccess→ActivityResumed→Verifying→VerificationSuccess→Verified（副作用序列断言） |
| **根因 B 守卫：退出认证后再登录** | Verifying+VerificationExited→Idle→再次 LoginRequested→LoginSuccess→ActivityResumed→Verifying（验证可重新启动） |
| **根因 B 守卫：切换账号后再登录** | VerificationFailed+LogoutRequested→Idle→再登录→正常启动验证 |
| 冷启动已验证 | ColdStart(true)→Verified（ShowModeSelection） |
| 冷启动未验证 | ColdStart(false,u)→VerificationFailed（ShowComplianceVerificationScreen） |
| 冷启动缺 unionId | ColdStart(false,null)→Idle（ClearSessionAndLogout） |
| 超时恢复 | VerificationTimeout→VerificationFailed（副作用含 RecoverSdkRunningState+ShowVerificationTimeoutHint+界面） |
| 重试立即启动 | VerificationFailed+RetryVerification→VerifyPending→（resumedReady 已置位）→Verifying |
| 网络错误保留会话 | VerificationNetworkError→VerificationFailed（无 ClearSessionAndLogout） |
| 登录失败/超时 | LoginFailure/LoginTimeout→Idle |
| 幂等防重入 | Verifying 中重复 VerificationSuccess / 任意状态收到不匹配事件 → no-op |
| 登出全出口 | Verified/Verifying/VerifyPending/VerificationFailed + LogoutRequested → Idle + ClearSessionAndLogout |

`ComplianceManagerSelfHealTest`（注入 fake registrar）：注册成功 / 注册抛异常后再次 ensure 重试成功 / callback 更新但 listener 不重复注册 / 幂等。

### 对抗性审查要点

| 角色 | 审查维度 | 关注点 |
|------|---------|--------|
| 状态破坏者 | 快速连续操作 | 登录成功→立刻退出→再登录→再退出（状态机转移序列正确） |
| 状态破坏者 | 中断恢复 | Verifying 中杀进程→冷启动（ColdStart 未验证→VerificationFailed→重试→Verifying） |
| 时序狂魔 | 事件乱序 | ActivityResumed 先于 LoginSuccess 到达（resumedReady 先置位，进入 VerifyPending 立即转移） |
| 逆向工程师 | 绕过路径 | 直接调 startup（不经状态机）→ 防御检查 + ensureCallbackRegistered 仍保证回调可达 |

### 真机冒烟清单（与 rules/sdk-init-lifecycle.md 同步更新）

1. 登录 → 进模式选择（正常）
2. 登录 → SDK 实名认证页点退出 → 再登录 → 正常进模式选择（**原卡死路径**）
3. 杀进程重进（已登录已认证）→ 直接进模式选择
4. 杀进程重进（已登录未认证）→ 实名认证界面 → 开始认证 → 进模式选择
5. 实名认证界面点切换账号 → 登录界面 → 再登录 → 正常（**原卡死路径**）
6. 登录后立即切后台再回前台 → 验证不重复触发（单飞）

## 六、风险评估与兜底

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 状态机事件发送遗漏（某路径未接） | 中 | 高 | 全转移矩阵测试 + 状态机不匹配事件记日志（日志可观测） |
| repeatOnLifecycle 行为差异 | 低 | 中 | resumedReady 标志设计已覆盖（登录成功时已 RESUMED 立即转移） |
| Compose setContent 切换与新流程组合 | 低 | 中 | 副作用处理器集中实现，行为与现状一致 |
| 既有测试受 MainScreen 参数移除影响 | 低 | 低 | onLoginSuccess 为死参数（grep 确认无使用），无测试引用 |

**兜底**：若状态机接入后真机异常，所有状态转移均有日志（`LoginFlowStateMachine: Idle --LoginSuccess--> VerifyPending`），
对照转移表可 5 分钟内定位；极端情况可回滚到 4.01.04 提交（改动集中在登录链路，无存档/数据影响）。

## 七、未来场景推演（≥6 个月）

| 场景 | 推演 | 本方案准备 |
|------|------|-----------|
| iOS 端接入 | 登录/防沉迷流程需双端一致 | 状态机纯 Kotlin 可直接 KMP 共享；Activity 生命周期事件由 iOS 对等（sceneDidBecomeActive）映射 |
| 新增第三方登录渠道（微信/手机号） | 登录入口多样化 | 状态机以事件驱动，新增渠道仅需发 LoginSuccess(unionId)，零改动 |
| 合规政策变更（防沉迷规则调整） | 回调码/流程变化 | 状态机事件与回调码解耦（VerificationSuccess 等语义事件），改 ComplianceManager 映射即可 |
| 运营活动要求强制实名二次验证 | 验证时机变化 | 状态机暴露 ResetVerification 语义（LogoutRequested 或新增事件）可复用 |
| 多账号同时登录（切号） | 会话切换频繁 | 登出统一四件套收敛，切号路径 = LogoutRequested → 再登录，天然安全 |

## 八、技术债与偿还计划

| 决策 | 偿还触发条件 | 说明 |
|------|-------------|------|
| 状态机放 app 模块而非 core:domain | 未来有第二宿主（iOS/其他 Activity）需要复用 | 迁移成本低（纯 Kotlin 无依赖，移动包即可） |
| `ensureSdkServicesInitialized` 保持"登录成功/冷启动"两调用点 | 无——当前收敛已是最优 | 广告/统计/合规回调与登录无因果解耦，契约由 SafeRunAfterSdkInitTest 守护 |
| TapTap SDK 反射复位 isRunning | SDK 版本升级后确认字段名 | 多候选字段 + 失败日志可观测；SDK 升级时运行一次冒烟即可 |
| 登录超时 60s 为防御性硬编码 | 无（常量命名，非魔法数字） | 若 TapTap SDK 提供登录超时回调则替换 |

## 九、行业参考来源清单

> 核心模式对标（FSM 设计）复用项目既有 ADR（docs/adr/lifecycle-state-machine-refactor.md）已验证的 22 条来源，
> 本方案额外补充登录/SDK 初始化专项来源。来源等级遵循 CLAUDE.md 设计方案规则。

| # | 等级 | 来源 | 核心摘要 | URL | 日期 |
|---|------|------|---------|-----|------|
| 1 | S | TapTap 防沉迷开发指南（官方） | registerComplianceCallback 须先于 startup 注册；startup(activity, userIdentifier) 标准用法 | https://developer.taptap.cn/docs/en/sdk/anti-addiction/guide/ | 2025 |
| 2 | S | TapTap 防沉迷 FAQ（官方） | 防沉迷接入常见问题：回调不触发排查、个人开发者接入要求 | https://developer.taptap.cn/docs/v3/sdk/anti-addiction/faq/ | 2025 |
| 3 | S | Google Android — App Startup Time | Cold/Warm/Hot Start 三态；TTID/TTFD 指标；启动关键路径 | https://developer.android.com/topic/performance/vitals/launch-time | 2025 |
| 4 | S | Google AndroidX App Startup | 启动期初始化组件化管理（ContentProvider 合并、懒加载） | https://developer.android.com/topic/libraries/app-startup | 2025 |
| 5 | S | Robert Nystrom — Game Programming Patterns | State Pattern：每状态一类的 Enter/Exit/Tick，状态转移封装 | https://gameprogrammingpatterns.com/state.html | 2014 |
| 6 | S | Apple — Managing Your App's Life Cycle | iOS 5 状态 + Scene Phase；生命周期状态机权威模型 | https://developer.apple.com/documentation/uikit/managing-your-app-s-life-cycle | 2025 |
| 7 | S | Unreal Engine 5 — Game Mode & Game State | 内置 Match State FSM（循环状态机行业范式） | https://dev.epicgames.com/documentation/unreal-engine/game-mode-and-game-state-in-unreal-engine | 2025 |
| 8 | S | Bezditnyi & Chebanyuk 2024 — 三引擎通用生命周期模型 | Bootstrap → GameLoop → Dispose 三阶段 + State | https://ceur-ws.org/Vol-3806/S_46_Bezditnyi_Chebanyuk.pdf | 2024 |
| 9 | A | Tinder — StateMachine (Kotlin/Swift DSL) | sealed class State/Event/SideEffect 类型安全 FSM，生产验证 | https://github.com/Tinder/StateMachine | 2023 |
| 10 | A | Scaling out Tinder Android Payment Flow using State Machine | 支付流程状态机实战（事件驱动副作用，状态集中管理） | https://devblogs.sh/posts/scaling-out-tinder-android-payment-flow-using-state-machine | 2024 |
| 11 | A | Supercell — The Engine Behind Every Supercell Game | 按需初始化、无多余服务启动（SDK 惰性初始化对标） | https://supercell.com/en/news/game-engine-called-titan/ | 2024 |
| 12 | A | NVIDIA — Android Lifecycle Recommendations for Games | Activity 生命周期与渲染/初始化时序建议 | https://developer.nvidia.com/docs | 2023 |
| 13 | A | codecentric — iOS App State Machine | 7 状态拆分 + State Pattern + UIApplicationDelegate 映射 | https://www.codecentric.de/en/knowledge-hub/blog/handling-ios-app-states-state-machine | 2024 |
| 14 | A | Unity Modular Game Template — FSM-Driven Loading | Bootstrap → Loading → GameHub → Gameplay（初始化分阶段） | https://github.com/NintendaDev/modular-unity-game-template | 2025 |
| 15 | B | ProAndroidDev — Model View State Machine (MVS) | 纯 FSM 替代 MVVM boolean flags；副作用一致性 | https://proandroiddev.com/model-view-state-machine-mvs-7dc371275b60 | 2024 |
| 16 | B | Thoughtbot — Finite State Machines + Android + Kotlin | Kotlin sealed class FSM + side effect 抽象 | https://thoughtbot.com/blog/finite-state-machines-android-kotlin-good-times | 2024 |
| 17 | B | 异步初始化框架设计：拓扑排序干掉启动串行瓶颈 | 初始化任务依赖图（SDK 就绪依赖建模） | https://www.e-com-net.com/article/2046502610566963200.htm | 2025 |
| 18 | B | BetterGenshinImpact — State Machine Framework | StateMachineBase + retry/timeout 机制（超时重试模式） | https://deepwiki.com/babalae/better-genshin-impact/3.9-state-machine-framework-and-stygian-onslaught | 2025 |
| 19 | B | lite-states — <1KB FSM Library | strict transitions + onEnter/onLeave hooks | https://www.npmjs.com/package/lite-states | 2024 |
| 20 | B | NWPU GameDev — Hierarchical FSM with Stack | 状态栈 + 持久层分离 | https://deepwiki.com/konakona418/nwpu-gamedev-2025/7-game-state-system | 2025 |

**等级分布**：S 级 8 条 / A 级 6 条 / B 级 6 条，S+A 共 14 条（≥12 达标）。

## 十、实施步骤（顺序执行）

```
P1 新增 LoginFlowState.kt + LoginFlowStateMachine.kt（纯状态机）
P2 重构 ComplianceManager.kt（ensureCallbackRegistered + 可注入 registrar + resetSdkRunningState 多候选）
P3 重构 MainActivity.kt（接入状态机，删除手工状态，登出统一，线程加固，移除死参数）
P4 新增 LoginFlowStateMachineTest + ComplianceManagerSelfHealTest
P5 compileReleaseKotlin + testReleaseUnitTest --max-workers=1 + lintRelease + detekt
P6 更新 rules/sdk-init-lifecycle.md + CHANGELOG.md + changelog_entries.json
P7 删除 task_plan.md/findings.md/progress.md 一次性文件，一次性提交
```
